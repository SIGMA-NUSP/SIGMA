package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Contrato do {@link RetificacaoService} — grava SEMPRE em LOTE ({@code {"dias":[…]}}), numa única
 * transação.
 *
 * <p>O que o unitário prova aqui: a validação por dia (nomeando o DIA na recusa), a leitura
 * guardada do corpo (shape torto → 400, não 500) e o fato de que o primeiro dia recusado
 * INTERROMPE o lote. A atomicidade propriamente dita — o rollback dos dias já gravados — é do
 * container, e por isso vive no {@code PontoRetificacaoLoteIT} (Oracle real).
 */
@ExtendWith(MockitoExtension.class)
class RetificacaoServiceTest {

    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private PontoLoteRepository loteRepo;
    @Mock private PontoRetificacaoRepository retificacaoRepo;

    @InjectMocks
    private RetificacaoService service;

    private static final String PAG = "pag-1";
    private static final String LOTE = "lote-1";
    private static final String DONO = "op-1";
    /** Período do lote de todas as folhas mockadas aqui — as bordas que a listagem tem de repassar. */
    private static final LocalDate INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate FIM = LocalDate.of(2026, 6, 30);
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Configura só a página (dono OPERADOR, do lote LOTE) — o lote fica a cargo de quem chama. */
    private void mockPagina(String pessoaId) {
        mockPagina(pessoaId, "OPERADOR");
    }

    private void mockPagina(String pessoaId, String pessoaTipo) {
        PontoLotePagina pg = new PontoLotePagina();
        pg.setPessoaId(pessoaId);
        pg.setPessoaTipo(pessoaTipo);
        pg.setLoteId(LOTE);
        when(paginaRepo.findById(PAG)).thenReturn(Optional.of(pg));
    }

    /** Configura página (dono/publicada) + lote (período jun/2026, publicado em pub). */
    private void mockFolha(String pessoaId, LocalDateTime pub, String status) {
        mockFolha(pessoaId, "OPERADOR", pub, status);
    }

    private void mockFolha(String pessoaId, String pessoaTipo, LocalDateTime pub, String status) {
        mockPagina(pessoaId, pessoaTipo);
        PontoLote lote = new PontoLote();
        lote.setStatus(status);
        lote.setPublicadoEm(pub);
        lote.setDataInicio(INICIO);
        lote.setDataFim(FIM);
        lenient().when(loteRepo.findById(LOTE)).thenReturn(Optional.of(lote));
    }

    /**
     * Stub da chave de LEITURA da listagem: pessoa + tipo + período DA FOLHA CONSULTADA.
     * Os argumentos são EXATOS de propósito — se o service passar outra pessoa, outro pessoa_tipo ou
     * outro período, o stub não casa, o Mockito devolve lista vazia e o teste que espera conteúdo cai.
     */
    private void mockListagem(String pessoaId, String pessoaTipo, PontoRetificacao... rs) {
        when(retificacaoRepo.findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                pessoaId, pessoaTipo, INICIO, FIM)).thenReturn(List.of(rs));
    }

    /** UM dia do lote (chaves ausentes quando o valor é nulo — como o JSON que o front manda). */
    private Map<String, Object> dia(String data, String e1, String s1, String e2, String s2) {
        Map<String, Object> m = new HashMap<>();
        if (data != null) m.put("data", data);
        if (e1 != null) m.put("ent1", e1);
        if (s1 != null) m.put("sai1", s1);
        if (e2 != null) m.put("ent2", e2);
        if (s2 != null) m.put("sai2", s2);
        return m;
    }

    /** Corpo do lote a partir dos dias. */
    @SafeVarargs
    private static Map<String, Object> lote(Map<String, Object>... dias) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("dias", List.of(dias));
        return corpo;
    }

    /** Corpo de lote com UM dia — a forma da maioria dos casos. */
    private Map<String, Object> body(String data, String e1, String s1, String e2, String s2) {
        return lote(dia(data, e1, s1, e2, s2));
    }

    private static PontoRetificacao retificacao(LocalDate data, String e1, String s1,
                                                String e2, String s2, String obs) {
        PontoRetificacao r = new PontoRetificacao();
        r.setData(data);
        r.setEnt1(e1);
        r.setSai1(s1);
        r.setEnt2(e2);
        r.setSai2(s2);
        r.setObservacoes(obs);
        return r;
    }

    /** Retificação ANCORADA EM OUTRA PÁGINA (outra folha publicada da mesma pessoa). */
    private static PontoRetificacao retificacaoDaPagina(String paginaId, LocalDate data,
                                                        String e1, String s1) {
        PontoRetificacao r = retificacao(data, e1, s1, null, null, null);
        r.setPaginaId(paginaId);
        return r;
    }

    @Test
    @DisplayName("limiteRetificacao = publicação + 5 dias; null se não publicado ou se o lote for nulo")
    void limite() {
        PontoLote lote = new PontoLote();
        lote.setPublicadoEm(LocalDateTime.of(2026, 7, 8, 17, 44));
        assertEquals(LocalDate.of(2026, 7, 13), service.limiteRetificacao(lote));
        assertNull(service.limiteRetificacao(new PontoLote()));  // publicadoEm null
        assertNull(service.limiteRetificacao(null));
    }

    @Test
    @DisplayName("prazo é INCLUSIVO: no próprio dia-limite (publicação + 5) a retificação ainda passa")
    void prazoNoUltimoDia() {
        mockFolha(DONO, LocalDateTime.now().minusDays(5), "PUBLICADO");   // limite = HOJE
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 15)))
                .thenReturn(false);

        service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null));

        verify(retificacaoRepo).saveAndFlush(any());
    }

    @Test
    @DisplayName("um dia depois do limite (publicação + 6) o prazo já venceu → PRAZO_EXPIRADO")
    void prazoUmDiaDepoisDoLimite() {
        mockFolha(DONO, LocalDateTime.now().minusDays(6), "PUBLICADO");   // limite = ONTEM

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));

        assertEquals("PRAZO_EXPIRADO", ex.getMessage());
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("acesso negado (403) quando o solicitante não é o dono da folha")
    void acessoNegado() {
        mockFolha("outro-operador", LocalDateTime.now(), "PUBLICADO");
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("página inexistente → 404 'Folha não encontrada.' (o lote nem é consultado)")
    void paginaInexistente() {
        when(paginaRepo.findById(PAG)).thenReturn(Optional.empty());

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("Folha não encontrada.", ex.getMessage());
        verifyNoInteractions(loteRepo, retificacaoRepo);
    }

    @Test
    @DisplayName("lote inexistente (página órfã) → 404 'Lote não encontrado.'")
    void loteInexistente() {
        mockPagina(DONO);
        when(loteRepo.findById(LOTE)).thenReturn(Optional.empty());

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("Lote não encontrado.", ex.getMessage());
        verifyNoInteractions(retificacaoRepo);
    }

    @Test
    @DisplayName("lote não publicado (status REVISAO) → 404 'Folha indisponível.', ainda que o dono confira")
    void loteNaoPublicado() {
        mockFolha(DONO, null, "REVISAO");   // sem PUBLICADO_EM, como todo lote em revisão

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("Folha indisponível.", ex.getMessage());
        verifyNoInteractions(retificacaoRepo);
    }

    @Test
    @DisplayName("data fora do período da folha → 400 nomeando o dia")
    void foraDoPeriodo() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-07-01", "08:00", "12:00", null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().startsWith("O dia 01/07/2026"), ex.getMessage());
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("data ANTES do início do período → 400, com o período (início a fim) na mensagem")
    void antesDoPeriodo() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-05-31", "08:00", "12:00", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("01/06/2026 a 30/06/2026"), ex.getMessage());
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("as bordas do período (1º e último dia) são aceitas")
    void bordasDoPeriodoAceitas() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(eq(DONO), eq("OPERADOR"), any()))
                .thenReturn(false);

        service.criarRetificacoes(PAG, DONO, body("2026-06-01", "08:00", "12:00", null, null));
        service.criarRetificacoes(PAG, DONO, body("2026-06-30", "08:00", "12:00", null, null));

        verify(retificacaoRepo).saveAndFlush(argThat(r -> LocalDate.of(2026, 6, 1).equals(r.getData())));
        verify(retificacaoRepo).saveAndFlush(argThat(r -> LocalDate.of(2026, 6, 30).equals(r.getData())));
    }

    @Test
    @DisplayName("período é validado ANTES do prazo: com as duas violações, o erro é o do período")
    void periodoPrecedeOPrazo() {
        mockFolha(DONO, LocalDateTime.now().minusDays(10), "PUBLICADO");   // prazo também vencido

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-07-01", "08:00", "12:00", null, null)));

        assertNotEquals("PRAZO_EXPIRADO", ex.getMessage());
        assertTrue(ex.getMessage().contains("fora do período"), ex.getMessage());
    }

    @Test
    @DisplayName("página ainda sem match (PESSOA_ID nulo) → 403, não 500")
    void paginaSemMatch() {
        mockPagina(null);

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(loteRepo, retificacaoRepo);
    }

    @Test
    @DisplayName("prazo vencido (publicação há mais de 5 dias) → 400 PRAZO_EXPIRADO com o dia-limite no payload")
    void prazoVencido() {
        LocalDateTime pub = LocalDateTime.now().minusDays(10);
        mockFolha(DONO, pub, "PUBLICADO");
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));
        assertEquals("PRAZO_EXPIRADO", ex.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertNotNull(ex.getExtraFields());
        assertTrue(String.valueOf(ex.getExtraFields().get("message"))
                        .contains(pub.toLocalDate().plusDays(5).format(BR)),
                String.valueOf(ex.getExtraFields()));
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("folha PUBLICADA sem PUBLICADO_EM (anomalia de dado): criar → PRAZO_EXPIRADO; listar → limite null e prazo vencido")
    void publicadaSemPublicadoEm() {
        mockFolha(DONO, null, "PUBLICADO");
        mockListagem(DONO, "OPERADOR");

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));
        assertEquals("PRAZO_EXPIRADO", ex.getMessage());

        Map<String, Object> out = service.listarRetificacoes(PAG, DONO);
        assertNull(out.get("limite"));
        assertNull(out.get("limite_fmt"));
        assertEquals(Boolean.TRUE, out.get("prazo_expirado"));
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("número ímpar de horários (3) → 400 (pares incompletos), nomeando o dia")
    void horarioImpar() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", "13:00", null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("15/06/2026"), ex.getMessage());
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("1º par incompleto (entrada sem saída) e 2º par sem o 1º → 400")
    void paresIncompletos() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        List<Map<String, Object>> corpos = List.of(
                body("2026-06-15", "08:00", null, null, null),          // ent1 sem sai1
                body("2026-06-15", null, null, "13:00", "17:00"));      // par 2 sem par 1

        for (Map<String, Object> corpo : corpos) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criarRetificacoes(PAG, DONO, corpo));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("pares"), ex.getMessage());
        }
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("zero horários → 400 nomeando o dia e o par exigido; NADA persiste")
    void zeroHorariosRecusado() {
        // A regra 0/2/4 aceitava os 4 nulos (par de nulos é "completo" por vacuidade) e nascia uma
        // retificação VAZIA: a jusante ela vence a precedência da grade e da planilha da chefia, o
        // dia que dizia "Banco de horas"/"Férias" vira célula vazia, a contagem de folgas cai 1 — e,
        // sem edição nem exclusão na v1, só o DBA desfazia. O mínimo agora é UM par completo.
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        Map<String, Object> vazio = dia("2026-06-15", null, null, null, null);
        vazio.put("observacoes", "estava em reunião externa");   // nem a observação salva o dia sem horários

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, lote(vazio)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("15/06/2026"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Ent. 1") && ex.getMessage().contains("Saí. 1"), ex.getMessage());
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("o par 2 sozinho continua recusado: o mínimo é o par 1, e as colunas são sequenciais")
    void parMinimoEhOPar1() {
        // O "≥1 par completo" NÃO relaxa a sequência: ENT.2/SAÍ.2 são a SEGUNDA entrada/saída do
        // dia, não "o turno da tarde" — um dia de duas marcações é sempre o par 1.
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", null, null, "13:00", "17:00")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("pares") && ex.getMessage().contains("15/06/2026"), ex.getMessage());
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    // ══════════════════════════════════════════════════════════════
    // observação: teto de 300 caracteres (a coluna é VARCHAR2(2000) em BYTES)
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("observação de 300 caracteres passa (o limite é inclusivo)")
    void observacaoNoLimitePassa() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(anyString(), anyString(), any()))
                .thenReturn(false);
        String obs = "ç".repeat(300);   // 600 bytes em UTF-8 — dentro do budget da coluna

        Map<String, Object> item = dia("2026-06-15", "08:00", "12:00", null, null);
        item.put("observacoes", obs);
        service.criarRetificacoes(PAG, DONO, lote(item));

        verify(retificacaoRepo).saveAndFlush(argThat(r -> obs.equals(r.getObservacoes())));
    }

    @Test
    @DisplayName("observação de 301 caracteres → 400 nomeando o CAMPO e o dia; nada persiste")
    void observacaoAcimaDoLimiteRecusada() {
        // Antes: o texto ia cru ao banco, o ORA-12899 virava DataIntegrityViolationException e o catch
        // (escrito para a UK) respondia "O dia … já foi retificado." — o usuário ia embora convencido
        // de que gravou, não repetia, e o prazo de 5 dias corria.
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        Map<String, Object> item = dia("2026-06-15", "08:00", "12:00", null, null);
        item.put("observacoes", "ç".repeat(301));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, lote(item)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("observação") && ex.getMessage().contains("15/06/2026"), ex.getMessage());
        assertTrue(ex.getMessage().contains("300 caracteres"), ex.getMessage());
        assertFalse(ex.getMessage().contains("já foi retificado"), "não pode mentir dizendo que o dia já existe");
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("horário mal formatado → 400")
    void horaInvalida() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "8h", "12:00", null, null)));
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("horário fora da faixa HH:MM (24:00, 08:60, 8:00, 0800) → 400 nomeando o dia e o valor")
    void horaForaDaFaixa() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        for (String hora : List.of("24:00", "08:60", "8:00", "0800")) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", hora, "12:00", null, null)));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("HH:MM"), ex.getMessage());
            assertTrue(ex.getMessage().contains("15/06/2026") && ex.getMessage().contains(hora), ex.getMessage());
        }
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("data ausente ou mal formatada no corpo → 400 (não 500)")
    void dataInvalidaNoCorpo() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        ServiceValidationException semData = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body(null, "08:00", "12:00", null, null)));
        assertEquals("Data obrigatória.", semData.getMessage());

        for (String data : List.of("15-06-2026", "2026-06-31")) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criarRetificacoes(PAG, DONO, body(data, "08:00", "12:00", null, null)));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("AAAA-MM-DD"), ex.getMessage());
        }
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("dia já retificado → 400 (UK pessoa-dia)")
    void diaRepetido() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 15)))
                .thenReturn(true);
        assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("caso válido (2 horários, dentro do prazo/período) → persiste")
    void sucesso() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(anyString(), anyString(), any()))
                .thenReturn(false);
        Map<String, Object> out = service.criarRetificacoes(PAG, DONO,
                body("2026-06-15", "08:00", "12:00", null, null));
        assertEquals(1, out.get("total"));
        verify(retificacaoRepo).saveAndFlush(argThat(r ->
                DONO.equals(r.getPessoaId()) && "OPERADOR".equals(r.getPessoaTipo())
                        && LocalDate.of(2026, 6, 15).equals(r.getData())
                        && "08:00".equals(r.getEnt1()) && "12:00".equals(r.getSai1())
                        && r.getEnt2() == null && r.getSai2() == null));
    }

    @Test
    @DisplayName("caso válido com 4 horários (2º par) → persiste os quatro + página e observações aparadas")
    void sucessoQuatroHorarios() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 15)))
                .thenReturn(false);
        Map<String, Object> item = dia("2026-06-15", "08:00", "12:00", "13:00", "17:00");
        item.put("observacoes", "  Esqueci de bater a saída.  ");

        Map<String, Object> out = service.criarRetificacoes(PAG, DONO, lote(item));

        assertEquals(1, out.get("total"));
        verify(retificacaoRepo).saveAndFlush(argThat(r ->
                PAG.equals(r.getPaginaId())
                        && DONO.equals(r.getPessoaId()) && "OPERADOR".equals(r.getPessoaTipo())
                        && LocalDate.of(2026, 6, 15).equals(r.getData())
                        && "08:00".equals(r.getEnt1()) && "12:00".equals(r.getSai1())
                        && "13:00".equals(r.getEnt2()) && "17:00".equals(r.getSai2())
                        && "Esqueci de bater a saída.".equals(r.getObservacoes())));
    }

    @Test
    @DisplayName("corrida: violação da UK no flush vira 400 (não 500) — o nome da constraint é achado NA CADEIA de causas")
    void duplicataConcorrente() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(anyString(), anyString(), any()))
                .thenReturn(false);
        when(retificacaoRepo.saveAndFlush(any())).thenThrow(violacaoUk());

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("O dia 15/06/2026 já foi retificado.", ex.getMessage());
    }

    @Test
    @DisplayName("violação de integridade que NÃO é a UK não é capturada: sobe (500 honesto), em vez de mentir 'já foi retificado'")
    void violacaoQueNaoEhAUkNaoViraJaRetificado() {
        // O catch antigo assumia que TODA DataIntegrityViolationException era a corrida da UK — e
        // respondia "O dia … já foi retificado.", mandando o usuário embora convencido de que gravou,
        // com o prazo de 5 dias correndo. As outras constraints da tabela (035) produzem DIVE de
        // verdade: aqui, a CHECK dos pares (ORA-02290) — a última linha de defesa se alguma mudança
        // futura na aplicação deixar passar um par torto. Ela não pode virar "já retificado".
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(anyString(), anyString(), any()))
                .thenReturn(false);
        SQLException ora02290 = new SQLIntegrityConstraintViolationException(
                "ORA-02290: check constraint (NUSP.CK_PNT_RETIF_PARES) violated");
        DataIntegrityViolationException outra =
                new DataIntegrityViolationException("could not execute statement", ora02290);
        when(retificacaoRepo.saveAndFlush(any())).thenThrow(outra);

        DataIntegrityViolationException propagada = assertThrows(DataIntegrityViolationException.class,
                () -> service.criarRetificacoes(PAG, DONO, body("2026-06-15", "08:00", "12:00", null, null)));

        assertSame(outra, propagada, "a exceção original deve subir intacta para o handler global");
    }

    /**
     * A violação da UK como o Oracle a entrega: o nome da constraint NÃO está na mensagem de topo —
     * ele viaja na causa (ORA-00001 do ojdbc, repetido pelo Hibernate). É a cadeia que o service varre.
     */
    private static DataIntegrityViolationException violacaoUk() {
        SQLException ora00001 = new SQLIntegrityConstraintViolationException(
                "ORA-00001: unique constraint (NUSP.UK_PNT_RETIF_PESSOA_DIA) violated");
        return new DataIntegrityViolationException("could not execute statement", ora00001);
    }

    // ══════════════════════════════════════════════════════════════
    // a gravação é um LOTE só (contrato e interrupção)
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("lote de 3 dias válidos: os 3 são gravados na MESMA chamada, e a folha é resolvida UMA vez")
    void loteValidoGravaTodosOsDias() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(eq(DONO), eq("OPERADOR"), any()))
                .thenReturn(false);

        Map<String, Object> out = service.criarRetificacoes(PAG, DONO, lote(
                dia("2026-06-15", "08:00", "12:00", null, null),
                dia("2026-06-16", "09:00", "15:00", null, null),
                dia("2026-06-17", "08:00", "12:00", "13:00", "17:00")));

        assertEquals(3, out.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> criadas = (List<Map<String, Object>>) out.get("retificacoes");
        assertEquals(List.of("2026-06-15", "2026-06-16", "2026-06-17"),
                criadas.stream().map(m -> m.get("data")).toList());

        verify(retificacaoRepo, times(3)).saveAndFlush(any());
        // o acesso/publicação da folha é verificado UMA vez para o lote inteiro (não N vezes)
        verify(paginaRepo).findById(PAG);
        verify(loteRepo).findById(LOTE);
    }

    @Test
    @DisplayName("dia recusado NO MEIO do lote interrompe tudo: o 3º nem é tentado e a recusa nomeia o 2º")
    void diaRecusadoInterrompeOLote() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 15)))
                .thenReturn(false);
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 16)))
                .thenReturn(true);   // este dia já foi retificado antes

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, lote(
                        dia("2026-06-15", "08:00", "12:00", null, null),
                        dia("2026-06-16", "09:00", "15:00", null, null),
                        dia("2026-06-17", "08:00", "12:00", null, null))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("O dia 16/06/2026 já foi retificado.", ex.getMessage(),
                "a recusa tem de dizer QUAL dia consertar");
        // o 1º dia chegou a ser gravado na sessão — quem o desfaz é o rollback da transação (IT);
        // o 3º nem foi tentado: o lote parou no primeiro erro.
        verify(retificacaoRepo, times(1)).saveAndFlush(any());
        verify(retificacaoRepo).saveAndFlush(argThat(r -> LocalDate.of(2026, 6, 15).equals(r.getData())));
        verify(retificacaoRepo, never())
                .existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 17));
    }

    @Test
    @DisplayName("o mesmo dia repetido DENTRO do corpo é recusado (a UK só veria o 2º INSERT)")
    void diaRepetidoNoProprioLote() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 15)))
                .thenReturn(false);

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, lote(
                        dia("2026-06-15", "08:00", "12:00", null, null),
                        dia("2026-06-15", "09:00", "15:00", null, null))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("15/06/2026") && ex.getMessage().contains("mais de uma vez"),
                ex.getMessage());
    }

    @Test
    @DisplayName("corpo sem 'dias' (ou com a lista vazia, ou sem corpo nenhum) → 400 nomeando o campo, não 500")
    void corpoSemDias() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        List<Map<String, Object>> corpos = new java.util.ArrayList<>();
        corpos.add(null);                                    // sem corpo
        corpos.add(new LinkedHashMap<>());                   // corpo vazio
        Map<String, Object> listaVazia = new LinkedHashMap<>();
        listaVazia.put("dias", List.of());
        corpos.add(listaVazia);                              // "dias": []

        for (Map<String, Object> corpo : corpos) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criarRetificacoes(PAG, DONO, corpo));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Informe ao menos um dia em 'dias'.", ex.getMessage());
        }
        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("shape torto no corpo ('dias' não-lista; item não-objeto) → 400 nomeando o campo, não 500")
    void corpoComShapeErrado() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");

        Map<String, Object> naoLista = new LinkedHashMap<>();
        naoLista.put("dias", "2026-06-15");
        ServiceValidationException lista = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, naoLista));
        assertEquals(HttpStatus.BAD_REQUEST, lista.getStatus());
        assertEquals("Campo 'dias' deve ser uma lista.", lista.getMessage());

        Map<String, Object> itemTorto = new LinkedHashMap<>();
        itemTorto.put("dias", List.of("2026-06-15"));   // string onde deveria vir o objeto do dia
        ServiceValidationException item = assertThrows(ServiceValidationException.class,
                () -> service.criarRetificacoes(PAG, DONO, itemTorto));
        assertEquals(HttpStatus.BAD_REQUEST, item.getStatus());
        assertEquals("Item de 'dias' deve ser um objeto.", item.getMessage());

        verify(retificacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("listarRetificacoes: dias retificados + dia-limite (ISO e dd/MM/yyyy) com o prazo em aberto")
    void listarRetificacoesDentroDoPrazo() {
        LocalDateTime pub = LocalDateTime.now().minusDays(1);   // limite = ontem + 5 → prazo aberto em qualquer dia
        mockFolha(DONO, pub, "PUBLICADO");
        mockListagem(DONO, "OPERADOR",
                retificacao(LocalDate.of(2026, 6, 10), "09:00", "15:00", null, null, null),
                retificacao(LocalDate.of(2026, 6, 15), "08:00", "12:00", "13:00", "17:00", "Esqueci de bater."));

        Map<String, Object> out = service.listarRetificacoes(PAG, DONO);

        LocalDate limite = pub.toLocalDate().plusDays(5);
        assertEquals(limite.toString(), out.get("limite"));
        assertEquals(limite.format(BR), out.get("limite_fmt"));
        assertEquals(Boolean.FALSE, out.get("prazo_expirado"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lista = (List<Map<String, Object>>) out.get("retificacoes");
        assertEquals(2, lista.size());
        assertEquals("2026-06-10", lista.get(0).get("data"));       // ordem do repositório, ISO
        assertEquals("09:00", lista.get(0).get("ent1"));
        assertEquals("15:00", lista.get(0).get("sai1"));
        assertNull(lista.get(0).get("ent2"));
        assertNull(lista.get(0).get("sai2"));
        assertNull(lista.get(0).get("observacoes"));
        assertEquals("2026-06-15", lista.get(1).get("data"));
        assertEquals("13:00", lista.get(1).get("ent2"));
        assertEquals("17:00", lista.get(1).get("sai2"));
        assertEquals("Esqueci de bater.", lista.get(1).get("observacoes"));
    }

    @Test
    @DisplayName("listarRetificacoes com prazo vencido: prazo_expirado=true e a lista continua visível")
    void listarRetificacoesPrazoVencido() {
        LocalDateTime pub = LocalDateTime.now().minusDays(10);
        mockFolha(DONO, pub, "PUBLICADO");
        mockListagem(DONO, "OPERADOR",
                retificacao(LocalDate.of(2026, 6, 10), "09:00", "15:00", null, null, null));

        Map<String, Object> out = service.listarRetificacoes(PAG, DONO);

        assertEquals(pub.toLocalDate().plusDays(5).toString(), out.get("limite"));
        assertEquals(Boolean.TRUE, out.get("prazo_expirado"));
        assertEquals(1, ((List<?>) out.get("retificacoes")).size());   // vencido não esconde o histórico
    }

    @Test
    @DisplayName("listarRetificacoes no próprio dia-limite: prazo_expirado=false (a fronteira é inclusiva)")
    void listarRetificacoesNoDiaLimite() {
        LocalDateTime pub = LocalDateTime.now().minusDays(5);   // limite = HOJE
        mockFolha(DONO, pub, "PUBLICADO");
        mockListagem(DONO, "OPERADOR",
                retificacao(LocalDate.of(2026, 6, 10), "09:00", "15:00", null, null, null));

        Map<String, Object> out = service.listarRetificacoes(PAG, DONO);

        assertEquals(pub.toLocalDate().plusDays(5).toString(), out.get("limite"));
        assertEquals(Boolean.FALSE, out.get("prazo_expirado"));
    }

    @Test
    @DisplayName("listarRetificacoes de folha alheia → 403 (mesma guarda do criar)")
    void listarRetificacoesAcessoNegado() {
        mockFolha("outro-operador", LocalDateTime.now(), "PUBLICADO");

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.listarRetificacoes(PAG, DONO));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(retificacaoRepo);
    }

    // ══════════════════════════════════════════════════════════════
    // a listagem lê pela chave da UK (pessoa+tipo+dia), recortada pelo PERÍODO da folha
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("retificação ancorada em OUTRA folha da mesma pessoa APARECE na listagem, e o limite continua sendo o da folha consultada")
    void retificacaoDeOutraFolhaAparece() {
        // O caso vivido: semanais cumulativas (01–05, 01–12…) dão à mesma pessoa 2 folhas publicadas
        // cobrindo o mesmo dia. A listagem filtrava por PAGINA_ID e a gravação valida pela UK
        // (pessoa+tipo+dia): na folha B o dia retificado pela folha A vinha LIVRE e habilitado, e o
        // envio levava 400 "já foi retificado" sem nenhuma retificação visível na tela — sem edição
        // nem exclusão na v1, o dia ficava congelado sem explicação.
        LocalDateTime pub = LocalDateTime.now();
        mockFolha(DONO, pub, "PUBLICADO");
        mockListagem(DONO, "OPERADOR",
                retificacaoDaPagina("pag-OUTRA-FOLHA", LocalDate.of(2026, 6, 5), "08:00", "12:00"));

        Map<String, Object> out = service.listarRetificacoes(PAG, DONO);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lista = (List<Map<String, Object>>) out.get("retificacoes");
        assertEquals(1, lista.size(), "o dia retificado por OUTRA folha da mesma pessoa tem de aparecer aqui");
        assertEquals("2026-06-05", lista.get(0).get("data"));
        assertEquals("08:00", lista.get(0).get("ent1"));
        // …e o prazo continua sendo o DESTA folha: a janela é da folha consultada, não da que
        // ancorou a retificação.
        assertEquals(pub.toLocalDate().plusDays(5).toString(), out.get("limite"));
        assertEquals(pub.toLocalDate().plusDays(5).format(BR), out.get("limite_fmt"));
        assertEquals(Boolean.FALSE, out.get("prazo_expirado"));
    }

    @Test
    @DisplayName("a consulta usa as 4 chaves certas: dono + pessoa_tipo da página + as BORDAS EXATAS do período do lote")
    void listagemConsultaPessoaTipoEPeriodoDoLote() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        mockListagem(DONO, "OPERADOR");

        service.listarRetificacoes(PAG, DONO);

        ArgumentCaptor<String> pessoa = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tipo = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> inicio = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> fim = ArgumentCaptor.forClass(LocalDate.class);
        verify(retificacaoRepo).findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                pessoa.capture(), tipo.capture(), inicio.capture(), fim.capture());

        assertEquals(DONO, pessoa.getValue());
        assertEquals("OPERADOR", tipo.getValue());
        // as bordas são as do lote, sem deslocamento: o Between é inclusivo, e é o que faz o 1º e o
        // último dia da folha entrarem (a inclusividade contra o banco real está no IT cross-folha).
        assertEquals(INICIO, inicio.getValue());
        assertEquals(FIM, fim.getValue());
    }

    @Test
    @DisplayName("o pessoa_tipo da CONSULTA também vem da página: a folha do técnico lista como TECNICO")
    void listagemUsaPessoaTipoDaPagina() {
        // O tipo hardcoded ("OPERADOR") passaria despercebido nas folhas de operador e devolveria
        // lista vazia para todo técnico — que veria seus dias já retificados como livres.
        mockFolha("tec-7", "TECNICO", LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                "tec-7", "TECNICO", INICIO, FIM))
                .thenReturn(List.of(retificacao(LocalDate.of(2026, 6, 9), "07:00", "13:00", null, null, null)));

        Map<String, Object> out = service.listarRetificacoes(PAG, "tec-7");

        assertEquals(1, ((List<?>) out.get("retificacoes")).size());
        verify(retificacaoRepo).findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                "tec-7", "TECNICO", INICIO, FIM);
    }

    @Test
    @DisplayName("o pessoa_tipo vem da PÁGINA, não é fixo: folha de técnico grava e consulta a UK como TECNICO")
    void pessoaTipoVemDaPagina() {
        mockFolha("tec-7", "TECNICO", LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData("tec-7", "TECNICO", LocalDate.of(2026, 6, 15)))
                .thenReturn(false);

        service.criarRetificacoes(PAG, "tec-7", body("2026-06-15", "08:00", "12:00", null, null));

        verify(retificacaoRepo).saveAndFlush(argThat(r ->
                "tec-7".equals(r.getPessoaId()) && "TECNICO".equals(r.getPessoaTipo())));
    }

    @Test
    @DisplayName("a resposta devolve o id gerado de cada retificação do lote")
    void respostaDevolveIdGerado() {
        mockFolha(DONO, LocalDateTime.now(), "PUBLICADO");
        when(retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", LocalDate.of(2026, 6, 15)))
                .thenReturn(false);
        when(retificacaoRepo.saveAndFlush(any())).thenAnswer(inv -> {
            PontoRetificacao r = inv.getArgument(0);
            r.setId("ret-9");    // o id nasce no INSERT (IDENTITY/UUID)
            return r;
        });

        Map<String, Object> out = service.criarRetificacoes(PAG, DONO,
                body("2026-06-15", "08:00", "12:00", null, null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> criadas = (List<Map<String, Object>>) out.get("retificacoes");
        assertEquals(1, criadas.size());
        assertEquals("ret-9", criadas.get(0).get("id"));
        assertEquals("2026-06-15", criadas.get(0).get("data"));
    }
}
