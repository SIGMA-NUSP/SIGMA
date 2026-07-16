package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT do F32 (facetas EXIBIÇÃO e PRAZO) contra Oracle real — o cenário que o unitário não alcança:
 * a MESMA pessoa com DUAS folhas publicadas cobrindo o mesmo dia. Folhas SEMANAIS são cumulativas
 * por decisão (01–05, 01–12, 01–19…): a sobreposição de período é feature, não bug.
 *
 * <p><b>Exibição:</b> a listagem lê pela chave da UK (pessoa+tipo+dia) recortada pelo PERÍODO da
 * folha consultada — não por PAGINA_ID. Antes, o dia retificado pela folha A vinha LIVRE na folha B,
 * e o envio levava 400 "já foi retificado" sem retificação nenhuma na tela; sem edição nem exclusão
 * na v1 (Q1), o dia ficava congelado sem explicação.
 *
 * <p><b>Prazo:</b> a janela de 5 dias é DA FOLHA (âncora: PUBLICADO_EM do lote da página usada).
 * Publicar uma folha nova reabre a janela SÓ pela folha nova e SÓ para os dias ainda não
 * retificados; os já retificados seguem travados pela UK, e a folha antiga continua regida pela SUA
 * janela — vencida é vencida, mesmo para os dias livres dela. É comportamento DECIDIDO, e estes
 * testes o travam.
 *
 * <p>Idioma do harness = {@link PontoRetificacaoLoteIT}: {@code @SpringBootTest} (não o slice
 * {@code @OracleIT}) para que o {@code @Transactional} do service commite de verdade, e limpeza
 * manual. O período das folhas é fixo (junho/2026) e a publicação é sempre relativa a HOJE — os
 * dias retificados caem dentro do período em qualquer dia de execução.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoRetificacaoCrossFolhaIT {

    /** Folha A — SEMANAL 01–12/jun. */
    private static final LocalDate A_INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate A_FIM = LocalDate.of(2026, 6, 12);
    /** Folha B — SEMANAL 01–19/jun: cobre A inteira e vai além (cumulativa). */
    private static final LocalDate B_INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate B_FIM = LocalDate.of(2026, 6, 19);

    private static final LocalDate DIA_05 = LocalDate.of(2026, 6, 5);    // retificado VIA A
    private static final LocalDate DIA_06 = LocalDate.of(2026, 6, 6);    // livre, dentro de A e de B
    private static final LocalDate DIA_07 = LocalDate.of(2026, 6, 7);    // livre, dentro de A e de B
    private static final LocalDate DIA_15 = LocalDate.of(2026, 6, 15);   // dentro de B, FORA de A

    @Autowired
    private RetificacaoService service;

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
    private PontoLote loteA;
    private PontoLotePagina paginaA;
    private PontoLotePagina paginaB;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            Administrador admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            operador = CenarioFactory.novoOperador(em);
            pessoaIds.add(operador.getId());

            loteA = CenarioFactory.novoLotePontoPublicado(em, "SEMANAL", A_INICIO, A_FIM, admin);
            loteIds.add(loteA.getId());
            paginaA = CenarioFactory.novaPaginaLote(em, loteA, 1, operador.getId(), "OPERADOR");

            PontoLote loteB = CenarioFactory.novoLotePontoPublicado(em, "SEMANAL", B_INICIO, B_FIM, admin);
            loteIds.add(loteB.getId());
            paginaB = CenarioFactory.novaPaginaLote(em, loteB, 1, operador.getId(), "OPERADOR");
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

    // ══ Corpo do lote / chamadas ao service ═══════════════════════

    private static Map<String, Object> dia(LocalDate data, String ent1, String sai1) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", data.toString());
        item.put("ent1", ent1);
        item.put("sai1", sai1);
        return item;
    }

    /** Envia os dias como UM lote pela folha indicada (é sempre um POST só — C10). */
    private void retificarVia(PontoLotePagina pagina, LocalDate... dias) {
        List<Map<String, Object>> itens = new ArrayList<>();
        for (LocalDate d : dias) itens.add(dia(d, "08:00", "12:00"));
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("dias", itens);
        service.criarRetificacoes(pagina.getId(), operador.getId(), corpo);
    }

    private Map<String, Object> listar(PontoLotePagina pagina) {
        return tx.execute(status -> service.listarRetificacoes(pagina.getId(), operador.getId()));
    }

    /** Datas (ISO) que a listagem da folha devolve — o que a tela usa para marcar "✓ Retificado". */
    @SuppressWarnings("unchecked")
    private List<String> diasListados(PontoLotePagina pagina) {
        List<Map<String, Object>> rs = (List<Map<String, Object>>) listar(pagina).get("retificacoes");
        return rs.stream().map(m -> String.valueOf(m.get("data"))).toList();
    }

    /**
     * Retrocede o PUBLICADO_EM do lote em 10 dias: a janela de 5 dias DELE vence, sem tocar na do
     * outro lote. É como se a folha A tivesse sido publicada há mais de uma semana — que é o caso
     * real das semanais cumulativas.
     */
    private void vencerJanela(PontoLote lote) {
        tx.executeWithoutResult(status -> em.createNativeQuery(
                        "UPDATE PNT_LOTE SET PUBLICADO_EM = :quando WHERE ID = :id")
                .setParameter("quando", LocalDateTime.now().minusDays(10))
                .setParameter("id", lote.getId())
                .executeUpdate());
    }

    /**
     * O cenário do achado: o dia 05 foi retificado pela folha A enquanto a janela dela estava aberta;
     * depois A venceu, e a folha B (publicada agora) cobre o mesmo dia.
     */
    private void diaRetificadoPelaFolhaAQueDepoisVenceu() {
        retificarVia(paginaA, DIA_05);
        vencerJanela(loteA);
    }

    // ══════════════════════════════════════════════════════════════
    // EXIBIÇÃO — a listagem é por pessoa+dia no período, não por página
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("corrige F32 — o dia retificado pela folha A aparece retificado na listagem da folha B (a chave é pessoa+dia, não PAGINA_ID)")
    void diaRetificadoPorOutraFolhaApareceNaFolhaB() {
        diaRetificadoPelaFolhaAQueDepoisVenceu();

        assertEquals(List.of("2026-06-05"), diasListados(paginaB),
                "o dia retificado por outra folha da MESMA pessoa tem de vir marcado aqui — "
                        + "senão a tela o oferece livre e o envio leva 400 sem explicação");
    }

    @Test
    @DisplayName("corrige F32 — a folha B mostra o dia, e o envio dele pela folha B continua recusado (a UK trava; o lote inteiro cai)")
    void diaJaRetificadoSegueTravadoNaFolhaB() {
        diaRetificadoPelaFolhaAQueDepoisVenceu();

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> retificarVia(paginaB, DIA_05, DIA_06));   // 05 travado; 06 livre — tudo-ou-nada (C10)

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("O dia 05/06/2026 já foi retificado.", ex.getMessage());
        assertEquals(List.of("2026-06-05"), diasListados(paginaB),
                "o dia livre do mesmo lote não podia ter entrado: a recusa derruba o lote todo");
    }

    @Test
    @DisplayName("corrige F32 — as BORDAS do período entram (Between inclusivo): o 1º e o último dia da folha aparecem")
    void bordasDoPeriodoAparecem() {
        retificarVia(paginaB, B_INICIO, B_FIM);   // 01/06 e 19/06 — as bordas exatas da folha B

        assertEquals(List.of("2026-06-01", "2026-06-19"), diasListados(paginaB),
                "dataInicio e dataFim são inclusivos — o Between não pode cortar as bordas");
    }

    @Test
    @DisplayName("corrige F32 — o recorte é o PERÍODO da folha consultada: o dia 15 (só dentro de B) não aparece na folha A, que termina no dia 12")
    void diaForaDoPeriodoDaFolhaNaoAparece() {
        retificarVia(paginaB, DIA_15);   // dentro de B (01–19), fora de A (01–12)

        assertEquals(List.of("2026-06-15"), diasListados(paginaB));
        assertTrue(diasListados(paginaA).isEmpty(),
                "a folha A vai só até 12/06: o dia 15 não é dela e não pode aparecer na tela dela");
    }

    // ══════════════════════════════════════════════════════════════
    // PRAZO — a janela de 5 dias é DA FOLHA (§4.2)
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("corrige F32 — a folha B (publicada agora) REABRE a janela dos dias ainda livres: o dia 06 grava por ela, mesmo com a janela de A vencida")
    void folhaNovaReabreAJanelaDosDiasLivres() {
        diaRetificadoPelaFolhaAQueDepoisVenceu();

        retificarVia(paginaB, DIA_06);   // livre — a janela de B está aberta

        assertEquals(List.of("2026-06-05", "2026-06-06"), diasListados(paginaB),
                "o dia livre entra pela folha recém-publicada");
    }

    @Test
    @DisplayName("corrige F32 — a reabertura vale SÓ pela folha nova: o mesmo dia livre enviado pela folha A (vencida) leva PRAZO_EXPIRADO")
    void folhaVencidaNaoRetificaNemOsDiasLivres() {
        diaRetificadoPelaFolhaAQueDepoisVenceu();

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> retificarVia(paginaA, DIA_07));   // dia livre, mas a janela DE A venceu

        assertEquals("PRAZO_EXPIRADO", ex.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(List.of("2026-06-05"), diasListados(paginaB),
                "nada de novo pode ter entrado pela folha vencida");
    }

    @Test
    @DisplayName("corrige F32 — a folha A vencida diz 'prazo encerrado' MAS mostra o dia retificado pela folha B (a janela é da folha; a exibição é da pessoa)")
    void folhaVencidaMostraORetificadoPelaFolhaNova() {
        diaRetificadoPelaFolhaAQueDepoisVenceu();
        retificarVia(paginaB, DIA_06);   // entra pela janela de B

        Map<String, Object> folhaA = listar(paginaA);
        assertEquals(Boolean.TRUE, folhaA.get("prazo_expirado"),
                "a janela de A venceu — e continua vencida, ainda que a publicação de B tenha reaberto dias");
        assertEquals(List.of("2026-06-05", "2026-06-06"), diasListados(paginaA),
                "o dia gravado PELA FOLHA B tem de aparecer também na folha A: as duas cobrem o dia 06");

        Map<String, Object> folhaB = listar(paginaB);
        assertEquals(Boolean.FALSE, folhaB.get("prazo_expirado"), "a janela de B está aberta");
        assertFalse(String.valueOf(folhaB.get("limite_fmt")).equals(String.valueOf(folhaA.get("limite_fmt"))),
                "cada folha tem o SEU dia-limite: o da consultada, não o de onde a retificação nasceu");
    }
}
