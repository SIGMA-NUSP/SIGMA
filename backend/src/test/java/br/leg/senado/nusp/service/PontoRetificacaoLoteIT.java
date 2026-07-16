package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT da ATOMICIDADE do lote de retificação (F39), contra Oracle real: ou os N dias entram, ou
 * NENHUM entra. É a única prova possível aqui — quem desfaz os dias já gravados é o rollback da
 * transação do container, não o service.
 *
 * <p><b>Por que {@code @SpringBootTest} e não o {@code @OracleIT} (slice {@code @DataJpaTest}) do
 * resto da FASE C:</b> no slice, a transação do TESTE envolve a chamada e nada é commitado nem
 * revertido de verdade — o teste "passaria" mesmo com a transação do service removida, que é
 * exatamente a mutação que ele precisa matar. Aqui o service é chamado FORA de transação: o
 * {@code @Transactional} dele abre, commita ou faz rollback pra valer, e a leitura seguinte (em
 * outra transação) vê o que o banco realmente guardou. Sem rollback automático → limpeza manual.
 *
 * <p>O prazo de retificação (publicação + 5 dias) é medido com {@code LocalDate.now()} pelo service,
 * então o lote é semeado como publicado AGORA e o período dele é fixo (junho/2026) — os dias
 * retificados caem dentro do período em qualquer dia de execução.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoRetificacaoLoteIT {

    private static final LocalDate INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate FIM = LocalDate.of(2026, 6, 30);
    private static final LocalDate DIA_1 = LocalDate.of(2026, 6, 15);
    private static final LocalDate DIA_2 = LocalDate.of(2026, 6, 16);
    private static final LocalDate DIA_3 = LocalDate.of(2026, 6, 17);

    @Autowired
    private RetificacaoService service;

    /**
     * Espião do repositório — só para reproduzir a CORRIDA da UK (o {@code exists} que não vê a linha
     * que já existe). Fora desse caso, todos os métodos são os reais: o IT continua batendo no Oracle.
     */
    @MockitoSpyBean
    private PontoRetificacaoRepository retificacaoRepo;

    /** O contexto completo liga @EnableScheduling; estes dois fazem I/O de rede a cada poucos segundos. */
    @MockitoBean
    private AgendaLegislativaService agendaLegislativaService;

    @MockitoBean
    private CessaoSheetService cessaoSheetService;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;

    /** Tudo que o teste semeou — a limpeza manual (sem rollback) apaga por estes ids. */
    private final List<String> adminIds = new ArrayList<>();
    private final List<String> pessoaIds = new ArrayList<>();
    private final List<String> loteIds = new ArrayList<>();

    private Operador operador;
    private PontoLotePagina pagina;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            Administrador admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            operador = CenarioFactory.novoOperador(em);
            pessoaIds.add(operador.getId());
            PontoLote lote = CenarioFactory.novoLotePontoPublicado(em, "MENSAL", INICIO, FIM, admin);
            loteIds.add(lote.getId());
            pagina = CenarioFactory.novaPaginaLote(em, lote, 1, operador.getId(), "OPERADOR");
        });
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM PNT_RETIFICACAO WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_LOTE WHERE ID IN :lotes");          // ON DELETE CASCADE leva as páginas
            executar("DELETE FROM PES_OPERADOR WHERE ID IN :pessoas");
            executar("DELETE FROM PES_ADMINISTRADOR WHERE ID IN :admins");
        });
    }

    private void executar(String sql) {
        var query = em.createNativeQuery(sql);
        if (sql.contains(":admins")) query.setParameter("admins", adminIds);
        if (sql.contains(":pessoas")) query.setParameter("pessoas", pessoaIds);
        if (sql.contains(":lotes")) query.setParameter("lotes", loteIds);
        query.executeUpdate();
    }

    // ══ Corpo do lote ═════════════════════════════════════════════

    private static Map<String, Object> dia(LocalDate data, String ent1, String sai1) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", data.toString());
        item.put("ent1", ent1);
        item.put("sai1", sai1);
        return item;
    }

    @SafeVarargs
    private static Map<String, Object> lote(Map<String, Object>... dias) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("dias", List.of(dias));
        return corpo;
    }

    // ══ Leitura do que o BANCO guardou (transação nova — não o cache da chamada) ══

    /** Dias retificados da pessoa, em ordem — lidos numa transação à parte, depois do commit/rollback. */
    private List<String> diasNoBanco() {
        return tx.execute(status -> {
            @SuppressWarnings("unchecked")
            List<Object> datas = em.createNativeQuery(
                            "SELECT TO_CHAR(DATA, 'YYYY-MM-DD') FROM PNT_RETIFICACAO "
                                    + "WHERE PESSOA_ID = :pessoa ORDER BY DATA")
                    .setParameter("pessoa", operador.getId())
                    .getResultList();
            return datas.stream().map(String::valueOf).toList();
        });
    }

    /** A observação como o BANCO a guardou (não o objeto em memória) — VARCHAR2(2000) em bytes. */
    private String observacaoNoBanco(LocalDate data) {
        return tx.execute(status -> (String) em.createNativeQuery(
                        "SELECT OBSERVACOES FROM PNT_RETIFICACAO WHERE PESSOA_ID = :pessoa AND DATA = :data")
                .setParameter("pessoa", operador.getId())
                .setParameter("data", data)
                .getSingleResult());
    }

    private void semearRetificacaoDe(LocalDate data) {
        tx.executeWithoutResult(status ->
                service.criarRetificacoes(pagina.getId(), operador.getId(), lote(dia(data, "07:00", "13:00"))));
    }

    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("corrige F39 — lote válido de 3 dias: os 3 estão no banco depois do commit")
    void loteValidoGravaTodosOsDias() {
        service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                dia(DIA_1, "08:00", "12:00"),
                dia(DIA_2, "09:00", "15:00"),
                dia(DIA_3, "08:00", "12:00")));

        assertEquals(List.of("2026-06-15", "2026-06-16", "2026-06-17"), diasNoBanco());
    }

    @Test
    @DisplayName("corrige F39 — um dia do lote viola a UK: NENHUM dia persiste (rollback do lote) e a recusa nomeia o dia")
    void diaJaRetificadoDerrubaOLoteInteiro() {
        semearRetificacaoDe(DIA_2);   // o dia do meio já foi retificado numa submissão anterior

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                        dia(DIA_1, "08:00", "12:00"),     // válido — antes do erro
                        dia(DIA_2, "09:00", "15:00"),     // ✗ já retificado
                        dia(DIA_3, "08:00", "12:00"))));  // válido — depois do erro

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("O dia 16/06/2026 já foi retificado.", ex.getMessage());
        // o 1º dia chegou a ser INSERIDO antes da recusa — o rollback da transação o levou junto:
        // no banco só sobrou o dia semeado, e nem o 15 nem o 17 entraram.
        assertEquals(List.of("2026-06-16"), diasNoBanco(),
                "o lote é tudo-ou-nada: o dia gravado antes do erro não pode sobreviver");
    }

    @Test
    @DisplayName("corrige F39 — recusa no ÚLTIMO dia (fora do período) também derruba os dois primeiros")
    void erroNoUltimoDiaDerrubaOsAnteriores() {
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                        dia(DIA_1, "08:00", "12:00"),
                        dia(DIA_2, "09:00", "15:00"),
                        dia(LocalDate.of(2026, 7, 1), "08:00", "12:00"))));   // ✗ fora do período da folha

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().startsWith("O dia 01/07/2026"), ex.getMessage());
        assertTrue(diasNoBanco().isEmpty(),
                "os dias válidos que vieram ANTES do erro não podem ter sobrado no banco: " + diasNoBanco());
    }

    @Test
    @DisplayName("corrige F39 — horário inválido no 2º dia: nada persiste e a mensagem diz o dia e o valor")
    void horarioInvalidoDerrubaOLote() {
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                        dia(DIA_1, "08:00", "12:00"),
                        dia(DIA_2, "25:00", "15:00"))));   // ✗ hora fora da faixa

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("16/06/2026") && ex.getMessage().contains("25:00"), ex.getMessage());
        assertTrue(diasNoBanco().isEmpty(), "nenhum dia pode ter sido gravado: " + diasNoBanco());
    }

    // ══ C11 — as validações de conteúdo contra o banco REAL (F31/F33) ═════════════

    @Test
    @DisplayName("corrige F33 — observação de 301 caracteres: 400 nomeando o CAMPO e o dia, e NENHUM dia do lote persiste")
    void observacaoLongaDerrubaOLoteComMensagemHonesta() {
        // Sem a guarda, o texto ia cru ao Oracle: ORA-12899 → DataIntegrityViolationException → o
        // catch (escrito para a UK) respondia "O dia … já foi retificado.", e o usuário ia embora
        // convencido de que gravou — sem repetir, com o prazo de 5 dias correndo.
        Map<String, Object> longo = dia(DIA_2, "09:00", "15:00");
        longo.put("observacoes", "ç".repeat(301));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                        dia(DIA_1, "08:00", "12:00"),   // válido — veio antes
                        longo)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("observação") && ex.getMessage().contains("16/06/2026")
                && ex.getMessage().contains("300 caracteres"), ex.getMessage());
        assertTrue(!ex.getMessage().contains("já foi retificado"),
                "a recusa por tamanho não pode se disfarçar de dia duplicado: " + ex.getMessage());
        assertTrue(diasNoBanco().isEmpty(), "o lote é tudo-ou-nada: " + diasNoBanco());
    }

    @Test
    @DisplayName("corrige F33 — o texto que ANTES estourava a coluna (1.200 caracteres = 2.400 bytes) é barrado pela guarda e NÃO chega ao Oracle")
    void observacaoQueEstouravaAColunaNaoChegaMaisAoBanco() {
        // O caso ORIGINAL do F33: acima de ~1.100 caracteres acentuados o texto estoura VARCHAR2(2000)
        // BYTES. ⚠️ Medido aqui (mutação M2 do C11): o ORA-12899 NÃO vira DataIntegrityViolationException
        // — o Hibernate o traduz para GenericJDBCException → JpaSystemException, que o catch da UK nunca
        // viu. Ou seja, a faceta "responde 'já foi retificado'" do §5 não era alcançável por esta via: o
        // desfecho real era um 500 mudo, com a retificação perdida e o prazo de 5 dias correndo. É esse
        // 500 que a guarda troca por um 400 que diz o que fazer.
        Map<String, Object> item = dia(DIA_1, "08:00", "12:00");
        item.put("observacoes", "ç".repeat(1200));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(), lote(item)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("300 caracteres"), ex.getMessage());
        assertTrue(diasNoBanco().isEmpty());
    }

    @Test
    @DisplayName("corrige F33 — 300 caracteres ACENTUADOS (600 bytes) gravam de verdade: o teto em caracteres cabe no budget em BYTES da coluna")
    void observacaoNoLimiteCabeNaColuna() {
        // O teto é em CARACTERES (o que o usuário conta e o que o maxlength do HTML conta), mas a
        // coluna é VARCHAR2(2000) em BYTES. Este caso prova, no Oracle real, que 300 não estoura —
        // é o que autoriza a mensagem "máximo de 300 caracteres" a ser verdadeira.
        String obs = "ç".repeat(300);
        Map<String, Object> item = dia(DIA_1, "08:00", "12:00");
        item.put("observacoes", obs);

        service.criarRetificacoes(pagina.getId(), operador.getId(), lote(item));

        assertEquals(List.of("2026-06-15"), diasNoBanco());
        assertEquals(obs, observacaoNoBanco(DIA_1), "a observação tem de voltar do banco INTEIRA");
    }

    @Test
    @DisplayName("corrige F33 — a corrida na UK REAL do Oracle continua virando 'já foi retificado' (o catch acha a constraint na cadeia de causas)")
    void corridaNaUkRealContinuaSendoRecusaAmigavel() {
        semearRetificacaoDe(DIA_1);
        // A corrida: a submissão rival commitou DEPOIS do nosso exists, que por isso não viu a linha.
        // Espiar o exists reproduz essa janela de milissegundos de forma determinística — o INSERT
        // então bate na UK de verdade, e é o catch (não o check prévio) que responde.
        doReturn(false).when(retificacaoRepo)
                .existsByPessoaIdAndPessoaTipoAndData(anyString(), anyString(), any());

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(),
                        lote(dia(DIA_1, "08:00", "12:00"))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("O dia 15/06/2026 já foi retificado.", ex.getMessage(),
                "o estreitamento do catch (C11) não pode ter deixado a UK real de fora");
    }

    @Test
    @DisplayName("corrige F31 — dia sem nenhum horário: 400 e nada persiste (a retificação vazia não nasce mais)")
    void diaSemHorariosNaoPersiste() {
        Map<String, Object> vazio = new LinkedHashMap<>();
        vazio.put("data", DIA_2.toString());
        vazio.put("observacoes", "estava em reunião externa");

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                        dia(DIA_1, "08:00", "12:00"),
                        vazio)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("16/06/2026"), ex.getMessage());
        assertTrue(diasNoBanco().isEmpty(), "nem o dia válido do lote pode sobrar: " + diasNoBanco());
    }

    @Test
    @DisplayName("corrige F39 — depois de um lote recusado, reenviar o lote corrigido grava tudo (a UK não ficou 'suja')")
    void reenvioAposRecusaGravaTudo() {
        assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                        dia(DIA_1, "08:00", "12:00"),
                        dia(DIA_2, "99:99", "15:00"))));   // ✗ recusado, nada gravou

        service.criarRetificacoes(pagina.getId(), operador.getId(), lote(
                dia(DIA_1, "08:00", "12:00"),
                dia(DIA_2, "09:00", "15:00")));            // ✓ mesmo dia 15, agora corrigido

        assertEquals(List.of("2026-06-15", "2026-06-16"), diasNoBanco(),
                "o usuário conserta o dia recusado e reenvia — a 2ª tentativa não pode bater na UK do 1º dia");
    }
}
