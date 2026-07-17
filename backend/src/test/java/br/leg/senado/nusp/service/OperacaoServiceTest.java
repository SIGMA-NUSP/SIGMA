package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.RegistroOperacaoOperadorHistorico;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.entity.Suspensao;
import br.leg.senado.nusp.enums.TipoEvento;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.EntradaOperadorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.RegistroOperacaoAudioRepository;
import br.leg.senado.nusp.repository.RegistroOperacaoOperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.repository.SuspensaoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Invariantes de {@link OperacaoService} que são regra/validação/ordem de chamada, com mocks.
 * A SEMÂNTICA dos UPDATEs (sticky via NVL/CASE, propagação efetiva no banco) fica na suíte de
 * integração ({@link OperacaoServiceIT}); aqui o contrato de chamada é travado com SQL casado
 * por fragmento-chave (nunca {@code anyString()}).
 */
@ExtendWith(MockitoExtension.class)
class OperacaoServiceTest {

    @Mock private RegistroOperacaoAudioRepository audioRepo;
    @Mock private RegistroOperacaoOperadorRepository entradaRepo;
    @Mock private EntradaOperadorRepository entradaOperadorRepo;
    @Mock private SuspensaoRepository suspensaoRepo;
    @Mock private SalaRepository salaRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private EntityManager entityManager;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private OperacaoService service;

    private static final String TITULAR = "uuid-op-titular";
    private static final String OUTRO = "uuid-op-outro";
    private static final long ENTRADA_ID = 101L;
    private static final long REGISTRO_ID = 70L;
    private static final int SALA_COMUM_ID = 3;

    // ── Helpers de stub (SQL casado por fragmento-chave, nunca anyString()) ──

    /**
     * Mock de Query devolvido só quando o SQL contém o fragmento-chave.
     * RETURNS_SELF resolve o encadeamento .setParameter(...); getSingleResult/
     * getResultList exigem stub explícito em quem os consome: getSingleResult
     * não-stubado devolveria o próprio mock (ClassCastException ruidosa), mas
     * getResultList não-stubado devolve lista VAZIA silenciosa — falso-verde.
     */
    private Query mockQueryPara(String fragmento) {
        Query q = mock(Query.class, RETURNS_SELF);
        when(entityManager.createNativeQuery(argThat((String sql) -> sql != null && sql.contains(fragmento))))
                .thenReturn(q);
        return q;
    }

    private void stubGateTitular(String dono) {
        when(entradaRepo.findOperadorIdByEntradaId(ENTRADA_ID)).thenReturn(Optional.of(dono));
    }

    /** isDonoOuAdicional — SELECT EXISTS na junction OPR_ENTRADA_OPERADOR → permitido. */
    private Query stubOwnershipPermitido() {
        Query q = mockQueryPara("OPR_ENTRADA_OPERADOR");
        when(q.getSingleResult()).thenReturn(BigDecimal.ONE);
        return q;
    }

    /** Sessão da entrada + flag MULTI_OPERADOR da sala da sessão. */
    private Query stubSessaoMultiOp(boolean multi) {
        when(entradaRepo.findRegistroIdByEntradaId(ENTRADA_ID)).thenReturn(Optional.of(REGISTRO_ID));
        Query q = mockQueryPara("s.MULTI_OPERADOR");
        when(q.getResultList()).thenReturn(List.of(multi ? 1 : 0));
        return q;
    }

    private Query stubOrdemEntrada(int ordem) {
        Query q = mockQueryPara("SELECT ORDEM FROM");
        when(q.getResultList()).thenReturn(List.of(ordem));
        // Com o término presente no body (invariante: ≥1 término), a regra 4 do
        // validarHorarios sempre consulta o operador adjacente — sem vizinho aqui.
        Query adjacente = mockQueryPara("JOIN PES_OPERADOR");
        when(adjacente.getResultList()).thenReturn(List.of());
        return q;
    }

    /** Snapshot de 14 colunas (getSnapshot) + contagem de entradas da sessão. */
    private void stubSnapshot(Integer salaIdDaSessao, int houveAnormalidade) {
        Object[] r = new Object[14];
        r[0] = "Evento Anterior";
        r[1] = "Resp Anterior";
        r[3] = "13:00:00";
        r[5] = "operacao";
        r[10] = houveAnormalidade;
        r[11] = salaIdDaSessao;
        List<Object[]> rows = new ArrayList<>();
        rows.add(r);
        when(entradaRepo.getSnapshot(ENTRADA_ID)).thenReturn(rows);
        when(entradaRepo.countEntradasPorSessao(ENTRADA_ID)).thenReturn(1);
    }

    /**
     * Serialização do snapshot: casa apenas o map com o estado ANTERIOR plantado
     * por stubSnapshot — se o SUT serializar outra coisa (ex.: snapshot vazio),
     * o stub não casa e o STRICT_STUBS derruba o teste (anti-falso-verde).
     */
    private void stubSerializacaoOk() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(argThat(o -> o instanceof Map<?, ?> m
                && "Evento Anterior".equals(m.get("nome_evento"))
                && "Resp Anterior".equals(m.get("responsavel_evento")))))
                .thenReturn("{\"nome_evento\":\"Evento Anterior\"}");
    }

    /** UPDATE grande de OPR_REGISTRO_ENTRADA com as flags sticky (CASE/NVL). */
    private Query stubUpdateGrande() {
        return mockQueryPara("NOME_EVENTO_EDITADO");
    }

    /** UPDATE de propagação dos campos compartilhados (exclui a entrada de origem). */
    private Query stubPropagacao() {
        return mockQueryPara("ID != :entradaId");
    }

    private static Map<String, Object> bodyEdicaoValido() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome_evento", "Sessão Deliberativa");
        body.put("hora_inicio", "14:00");
        body.put("responsavel_evento", "Mesa Diretora");
        // Invariante do sistema: o fluxo real sempre carrega um término — sem ele a
        // edição é recusada antes de qualquer escrita.
        body.put("hora_saida", "18:00");
        return body;
    }

    private static List<Object[]> rowsSessaoAberta() {
        Object[] row = {REGISTRO_ID, "2026-07-09", SALA_COMUM_ID, "Plenário 2", null, null, null};
        List<Object[]> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }

    private static Suspensao suspensao(String hs, String hr, int ordem) {
        Suspensao s = new Suspensao();
        s.setEntradaId(ENTRADA_ID);
        s.setHoraSuspensao(hs);
        s.setHoraReabertura(hr);
        s.setOrdem(ordem);
        return s;
    }

    // ── Permissões de editarEntrada ───────────────────────────

    @Nested
    class PermissaoEdicaoEntrada {

        @Test
        @DisplayName("editarEntrada — entrada inexistente responde 404 (not_found) antes de qualquer 403")
        void editarEntrada_entradaInexistente() {
            when(entradaRepo.findOperadorIdByEntradaId(ENTRADA_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, bodyEdicaoValido(), TITULAR));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("not_found", ex.getMessage());
            verify(entradaRepo).findOperadorIdByEntradaId(ENTRADA_ID);
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("editarEntrada — terceiro (não-titular) recebe 403 forbidden sem nenhuma escrita; "
                + "nenhum atributo do operador (ex.: fixo do Plenário Principal) dá bypass")
        void editarEntrada_terceiroNaoTitular() {
            stubGateTitular(TITULAR);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, bodyEdicaoValido(), OUTRO));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            assertEquals("forbidden", ex.getMessage());
            // A decisão é só ownership: PES_OPERADOR nunca é consultado (fixo do
            // Plenário lê via dashboard, mas não escreve aqui).
            verifyNoInteractions(operadorRepo);
            verifyNoInteractions(entityManager);
            verify(entradaRepo, never()).getSnapshot(anyLong());
        }

        @Test
        @DisplayName("caracteriza comportamento atual — operador ADICIONAL (junction) também recebe 403: "
                + "o gate do titular precede isDonoOuAdicional (divergência conhecida; NÃO corrigir aqui)")
        void editarEntrada_adicionalDaJunction() {
            // O 1º passo do editarEntrada compara userId com OPERADOR_ID da entrada.
            // Um operador adicional (presente em OPR_ENTRADA_OPERADOR, mas não titular)
            // é barrado ANTES da checagem de junction: isDonoOuAdicional nunca chega
            // a ser executado.
            stubGateTitular(TITULAR);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, bodyEdicaoValido(), OUTRO));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            // A junction não é consultada: nenhuma query de ownership roda no EM.
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("editarEntrada — titular edita: UPDATE recebe os valores normalizados e o retorno traz houve_anormalidade_nova")
        void editarEntrada_titularEdita() throws Exception {
            stubGateTitular(TITULAR);
            Query ownership = stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            stubSnapshot(null, 0);
            stubOrdemEntrada(1);
            stubSerializacaoOk();
            Query update = stubUpdateGrande();
            stubPropagacao();

            Map<String, Object> body = bodyEdicaoValido();
            body.put("houve_anormalidade", "sim");

            Map<String, Object> out = service.editarEntrada(ENTRADA_ID, body, TITULAR);

            assertEquals(ENTRADA_ID, out.get("entrada_id"));
            assertEquals(REGISTRO_ID, out.get("registro_id"));
            // snapshot sem anormalidade + body "sim" → nova anormalidade sinalizada
            assertEquals(Boolean.TRUE, out.get("houve_anormalidade_nova"));

            verify(ownership).setParameter(1, ENTRADA_ID);
            verify(ownership).setParameter(2, TITULAR);
            verify(update).executeUpdate();
            verify(update).setParameter("ne", "Sessão Deliberativa");
            verify(update).setParameter("ne2", "Sessão Deliberativa");       // par NVL da flag sticky
            verify(update).setParameter("hi", "14:00:00");                   // normalizeTime: "14:00" → "14:00:00"
            verify(update).setParameter("re", "Mesa Diretora");
            verify(update).setParameter("userId", TITULAR);
            verify(update).setParameter("entradaId", ENTRADA_ID);
        }
    }

    // ── Anti-duplicidade de salvarEntrada ─────────────────────

    @Nested
    class AntiDuplicidadeSalvarEntrada {

        @Test
        @DisplayName("salvarEntrada — registro do mesmo operador/sala há < 5 min barra a criação com 400")
        void salvarEntrada_duplicidadeRecente() {
            when(audioRepo.findSessaoAbertaPorSala(SALA_COMUM_ID)).thenReturn(List.of());
            Query dup = mockQueryPara("INTERVAL '5' MINUTE");
            when(dup.getSingleResult()).thenReturn(BigDecimal.ONE);

            Map<String, Object> body = bodyCriacao(String.valueOf(SALA_COMUM_ID));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEntrada(body, TITULAR));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("menos de 5 minutos"));
            verify(dup).setParameter(1, SALA_COMUM_ID);
            verify(dup).setParameter(2, TITULAR);
            verify(audioRepo, never()).save(any());
            verify(entradaRepo, never()).save(any());
        }

        private Map<String, Object> bodyCriacao(String salaId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data_operacao", "2026-07-09");
            body.put("sala_id", salaId);
            body.put("nome_evento", "Audiência Pública");
            body.put("hora_inicio", "14:00");
            return body;
        }

        @Test
        @DisplayName("salvarEntrada — sala Demais Salas (11) sem nome_demais_salas barra a criação da sessão com 400")
        void salvarEntrada_demaisSalasSemNome() {
            when(audioRepo.findSessaoAbertaPorSala(OperacaoService.SALA_DEMAIS_SALAS_ID)).thenReturn(List.of());
            Query dup = mockQueryPara("INTERVAL '5' MINUTE");
            when(dup.getSingleResult()).thenReturn(BigDecimal.ZERO);

            Map<String, Object> body = bodyCriacao(String.valueOf(OperacaoService.SALA_DEMAIS_SALAS_ID));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEntrada(body, TITULAR));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Informe o nome da sala.", ex.getMessage());
            verify(audioRepo, never()).save(any());
            verify(entradaRepo, never()).save(any());
        }
    }

    // ── Histórico obrigatório antes da alteração ──────────────

    @Nested
    class HistoricoAntesDaAlteracao {

        @Test
        @DisplayName("editarEntrada — snapshot é persistido no histórico ANTES do UPDATE da entrada (inOrder)")
        void editarEntrada_historicoAntesDoUpdate() throws Exception {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            stubSnapshot(null, 0);
            stubOrdemEntrada(1);
            stubSerializacaoOk();
            Query update = stubUpdateGrande();
            stubPropagacao();

            service.editarEntrada(ENTRADA_ID, bodyEdicaoValido(), TITULAR);

            InOrder ordem = inOrder(entityManager, update);
            ordem.verify(entityManager).persist(any(RegistroOperacaoOperadorHistorico.class));
            ordem.verify(update).executeUpdate();
            // O map serializado é o estado ANTERIOR montado pelo SUT (getSnapshot),
            // não os valores novos do body — trava o conteúdo, não só a ordem.
            verify(objectMapper).writeValueAsString(argThat(o -> o instanceof Map<?, ?> m
                    && "Evento Anterior".equals(m.get("nome_evento"))
                    && "13:00:00".equals(m.get("horario_inicio"))));
            verify(entityManager).persist(argThat((Object h) ->
                    h instanceof RegistroOperacaoOperadorHistorico hist
                            && hist.getEntradaId() == ENTRADA_ID
                            && TITULAR.equals(hist.getEditadoPor())
                            && hist.getSnapshot().contains("Evento Anterior")));
        }

        @Test
        @DisplayName("editarEntrada — falha de serialização do snapshot grava histórico '{}' e NÃO aborta a edição")
        void editarEntrada_falhaSerializacaoSnapshotVazio() throws Exception {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            stubSnapshot(null, 0);
            stubOrdemEntrada(1);
            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new JsonProcessingException("falha simulada") {});
            Query update = stubUpdateGrande();
            stubPropagacao();

            Map<String, Object> out = service.editarEntrada(ENTRADA_ID, bodyEdicaoValido(), TITULAR);

            assertNotNull(out);
            verify(entityManager).persist(argThat((Object h) ->
                    h instanceof RegistroOperacaoOperadorHistorico hist && "{}".equals(hist.getSnapshot())));
            verify(update).executeUpdate();
        }
    }

    // ── Regra "Demais Salas" na edição ────────────────────────

    @Nested
    class RegraDemaisSalasEdicao {

        /** Stubs do fluxo de edição do titular com a sessão na sala informada; devolve o mock do UPDATE grande. */
        private Query stubEdicaoComSalaDaSessao(Integer salaIdDaSessao) throws JsonProcessingException {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            stubSnapshot(salaIdDaSessao, 0);
            stubOrdemEntrada(1);
            stubSerializacaoOk();
            Query update = stubUpdateGrande();
            stubPropagacao();
            return update;
        }

        @Test
        @DisplayName("editarEntrada — sessão em sala comum (≠ 11) força NOME_DEMAIS_SALAS a NULL")
        void editarEntrada_salaComumZeraNomeDemaisSalas() throws Exception {
            stubEdicaoComSalaDaSessao(SALA_COMUM_ID);
            Query limpar = mockQueryPara("NOME_DEMAIS_SALAS = NULL");

            service.editarEntrada(ENTRADA_ID, bodyEdicaoValido(), TITULAR);

            verify(limpar).executeUpdate();
            verify(limpar).setParameter("rid", REGISTRO_ID);
        }

        @Test
        @DisplayName("editarEntrada — sessão na sala 11 grava o nome_demais_salas recebido")
        void editarEntrada_sala11GravaNome() throws Exception {
            stubEdicaoComSalaDaSessao(OperacaoService.SALA_DEMAIS_SALAS_ID);
            Query gravar = mockQueryPara("NOME_DEMAIS_SALAS = :n");
            Map<String, Object> body = bodyEdicaoValido();
            body.put("nome_demais_salas", "Sala de Reuniões Anexo II");

            service.editarEntrada(ENTRADA_ID, body, TITULAR);

            verify(gravar).executeUpdate();
            verify(gravar).setParameter("n", "Sala de Reuniões Anexo II");
            verify(gravar).setParameter("rid", REGISTRO_ID);
        }

        @Test
        @DisplayName("editarEntrada — sessão na sala 11 sem nome_demais_salas responde 400 "
                + "(caracterização: o UPDATE da entrada já rodou; o rollback transacional desfaz em produção)")
        void editarEntrada_sala11SemNome() throws Exception {
            Query update = stubEdicaoComSalaDaSessao(OperacaoService.SALA_DEMAIS_SALAS_ID);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, bodyEdicaoValido(), TITULAR));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Informe o nome da sala.", ex.getMessage());
            // Trava a caracterização do comentário acima: a escrita precede a validação.
            verify(update).executeUpdate();
        }
    }

    // ── Contrato de propagarCamposSessao ──────────────────────

    @Nested
    class PropagacaoCamposSessao {

        @Test
        @DisplayName("editarEntrada — entrada de ordem 1 propaga os 6 campos compartilhados para as demais entradas (ID != :entradaId)")
        void editarEntrada_ordem1PropagaCampos() throws Exception {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            stubSnapshot(null, 0);
            stubOrdemEntrada(1);
            stubSerializacaoOk();
            stubUpdateGrande();
            Query propagar = stubPropagacao();

            Map<String, Object> body = bodyEdicaoValido();
            body.put("horario_pauta", "13:30");
            body.put("tipo_evento", "operacao");
            body.put("comissao_id", "9");

            service.editarEntrada(ENTRADA_ID, body, TITULAR);

            verify(propagar).executeUpdate();
            verify(propagar).setParameter("ne", "Sessão Deliberativa");
            verify(propagar).setParameter("hp", "13:30:00");
            verify(propagar).setParameter("hi", "14:00:00");
            verify(propagar).setParameter("te", "operacao");
            verify(propagar).setParameter("ci", 9L);
            verify(propagar).setParameter("re", "Mesa Diretora");
            verify(propagar).setParameter("regId", REGISTRO_ID);
            verify(propagar).setParameter("entradaId", ENTRADA_ID);
        }

        @Test
        @DisplayName("editarEntrada — entrada de ordem ≠ 1 NÃO propaga campos compartilhados")
        void editarEntrada_ordem2NaoPropaga() throws Exception {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            stubSnapshot(null, 0);
            stubOrdemEntrada(2);
            stubSerializacaoOk();
            stubUpdateGrande();
            // ordem 2 exige hora_entrada (regra 5); as consultas de adjacente (regras 3 e 4)
            // já são cobertas pelo stub sem vizinho de stubOrdemEntrada

            Map<String, Object> body = bodyEdicaoValido();
            body.put("hora_entrada", "15:00");

            service.editarEntrada(ENTRADA_ID, body, TITULAR);

            verify(entityManager, never())
                    .createNativeQuery(argThat((String sql) -> sql != null && sql.contains("ID != :entradaId")));
        }
    }

    // ── atualizarSuspensoes (edição multi-operador) ───────────

    @Nested
    class AtualizarSuspensoes {

        private void stubEdicaoMultiOp() throws JsonProcessingException {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(true);
            stubSnapshot(null, 0);
            stubSerializacaoOk();
            stubUpdateGrande();
            when(suspensaoRepo.findByEntradaIdOrderByOrdemAsc(ENTRADA_ID))
                    .thenReturn(List.of(suspensao("10:00:00", "10:30:00", 1)));
        }

        private void editarComSuspensoes(List<Map<String, Object>> suspensoesNovas) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nome_evento", "Sessão Deliberativa");
            body.put("hora_inicio", "14:00");
            body.put("hora_saida", "18:00"); // invariante: ≥1 término
            body.put("suspensoes", suspensoesNovas);
            service.editarEntrada(ENTRADA_ID, body, TITULAR);
        }

        private static Map<String, Object> susp(String hs, String hr) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hora_suspensao", hs);
            m.put("hora_reabertura", hr);
            return m;
        }

        @Test
        @DisplayName("atualizarSuspensoes — delete-all + reinsert, e chave alterada marca SUSPENSOES_EDITADO = 1")
        void atualizarSuspensoes_chaveMudou() throws Exception {
            stubEdicaoMultiOp();
            Query sticky = mockQueryPara("SUSPENSOES_EDITADO = 1");

            editarComSuspensoes(List.of(susp("11:00", "11:15")));

            InOrder ordem = inOrder(suspensaoRepo);
            // as antigas são lidas 2× (montarSnapshot + captura da chave) ANTES do delete-all
            ordem.verify(suspensaoRepo, times(2)).findByEntradaIdOrderByOrdemAsc(ENTRADA_ID);
            ordem.verify(suspensaoRepo).deleteByEntradaId(ENTRADA_ID);
            ordem.verify(suspensaoRepo).save(argThat(s ->
                    "11:00:00".equals(s.getHoraSuspensao())
                            && "11:15:00".equals(s.getHoraReabertura())
                            && s.getOrdem() == 1
                            && s.getEntradaId() == ENTRADA_ID));
            verify(sticky).executeUpdate();
            verify(sticky).setParameter("id", ENTRADA_ID);
        }

        @Test
        @DisplayName("atualizarSuspensoes — chave idêntica ainda recria as linhas, mas NÃO marca SUSPENSOES_EDITADO")
        void atualizarSuspensoes_chaveIgual() throws Exception {
            stubEdicaoMultiOp();

            editarComSuspensoes(List.of(susp("10:00", "10:30")));

            // a chave antiga precisa ser capturada ANTES do delete-all — se a leitura
            // viesse depois, a chave "antiga" seria sempre vazia e o sticky marcaria sempre
            InOrder ordem = inOrder(suspensaoRepo);
            ordem.verify(suspensaoRepo, times(2)).findByEntradaIdOrderByOrdemAsc(ENTRADA_ID);
            ordem.verify(suspensaoRepo).deleteByEntradaId(ENTRADA_ID);
            ordem.verify(suspensaoRepo).save(argThat(s -> "10:00:00".equals(s.getHoraSuspensao())));
            verify(entityManager, never())
                    .createNativeQuery(argThat((String sql) -> sql != null && sql.contains("SUSPENSOES_EDITADO = 1")));
        }
    }

    // ── obterEstadoSessao ─────────────────────────────────────

    @Nested
    class ObterEstadoSessao {

        @Test
        @DisplayName("obterEstadoSessao — sem sessão aberta devolve map 'vazio' (nunca null): "
                + "existe_sessao_aberta=false, registro_id=0, situacao_operador=sem_sessao, max=2")
        void obterEstadoSessao_semSessaoAberta() {
            when(audioRepo.findSessaoAbertaPorSala(SALA_COMUM_ID)).thenReturn(List.of());

            Map<String, Object> estado = service.obterEstadoSessao(SALA_COMUM_ID, TITULAR);

            assertNotNull(estado);
            assertEquals(false, estado.get("existe_sessao_aberta"));
            assertEquals(0, estado.get("registro_id"));
            assertEquals("sem_sessao", estado.get("situacao_operador"));
            assertEquals(2, estado.get("max_entradas_por_operador"));
            assertEquals(SALA_COMUM_ID, estado.get("sala_id"));
            assertEquals("operacao", estado.get("tipo_evento"));
            assertEquals(true, estado.get("permite_anormalidade"));
            assertEquals(List.of(), estado.get("entradas_operador"));
            assertEquals(List.of(), estado.get("entradas_sessao"));
        }
    }

    // ── finalizarSessao ───────────────────────────────────────

    @Nested
    class FinalizarSessao {

        @Test
        @DisplayName("finalizarSessao — sem registro aberto para a sala responde 400 e não fecha nada")
        void finalizarSessao_semSessaoAberta() {
            when(audioRepo.findSessaoAbertaPorSala(SALA_COMUM_ID)).thenReturn(List.of());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.finalizarSessao(SALA_COMUM_ID, TITULAR));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Não existe registro aberto para este local.", ex.getMessage());
            verify(audioRepo, never()).finalizarSessao(anyLong(), anyString());
        }

        @Test
        @DisplayName("finalizarSessao — com sessão aberta fecha o registro e devolve status finalizado")
        void finalizarSessao_comSessaoAberta() {
            when(audioRepo.findSessaoAbertaPorSala(SALA_COMUM_ID)).thenReturn(rowsSessaoAberta());

            Map<String, Object> out = service.finalizarSessao(SALA_COMUM_ID, TITULAR);

            verify(audioRepo).finalizarSessao(REGISTRO_ID, TITULAR);
            assertEquals(REGISTRO_ID, out.get("registro_id"));
            assertEquals(SALA_COMUM_ID, out.get("sala_id"));
            assertEquals("finalizado", out.get("status"));
        }
    }

    // ── lookupRegistroOperacao ────────────────────────────────

    @Nested
    class LookupRegistroOperacao {

        @Test
        @DisplayName("lookupRegistroOperacao — registro não encontrado retorna null")
        void lookupRegistroOperacao_naoEncontrado() {
            when(entradaRepo.findDadosParaAnormalidade(REGISTRO_ID, null)).thenReturn(List.of());

            assertNull(service.lookupRegistroOperacao(REGISTRO_ID, null));
        }

        @Test
        @DisplayName("lookupRegistroOperacao — encontrado devolve o map de pré-preenchimento da anormalidade")
        void lookupRegistroOperacao_encontrado() {
            Object[] row = {REGISTRO_ID, "2026-07-09", SALA_COMUM_ID, "Sessão Especial", "Mesa Diretora", "Sala Anexa"};
            List<Object[]> rows = new ArrayList<>();
            rows.add(row);
            when(entradaRepo.findDadosParaAnormalidade(REGISTRO_ID, ENTRADA_ID)).thenReturn(rows);

            Map<String, Object> out = service.lookupRegistroOperacao(REGISTRO_ID, ENTRADA_ID);

            assertNotNull(out);
            assertEquals(REGISTRO_ID, out.get("id"));
            assertEquals("2026-07-09", out.get("data"));
            assertEquals(SALA_COMUM_ID, out.get("sala_id"));
            assertEquals("Sessão Especial", out.get("nome_evento"));
            assertEquals("Mesa Diretora", out.get("responsavel_evento"));
            assertEquals("Sala Anexa", out.get("nome_demais_salas"));
        }
    }

    // ── régua de horário nas portas do módulo de operação ──

    @Nested
    class ReguaDeHorario {

        /** Body mínimo de criação com um término válido (invariante: ≥1 término). */
        private Map<String, Object> bodyCriacaoValido() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data_operacao", "2026-07-16");
            body.put("sala_id", String.valueOf(SALA_COMUM_ID));
            body.put("nome_evento", "Audiência Pública");
            body.put("hora_inicio", "14:00");
            body.put("hora_saida", "18:00");
            return body;
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.CsvSource({
                "horario_pauta, Horário da Pauta",
                "hora_inicio, Início do evento",
                "hora_fim, Término do evento",
                "hora_entrada, Início da operação",
                "hora_saida, Término da operação",
        })
        @DisplayName("criação: hora torta em qualquer dos 5 campos recusa 400 nomeando o rótulo, antes de tocar o banco")
        void salvarEntrada_criacao_campoTortoRecusa(String campo, String rotulo) {
            Map<String, Object> body = bodyCriacaoValido();
            body.put(campo, "24:00:00");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEntrada(body, TITULAR));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("'" + rotulo + "'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'24:00:00'"), ex.getMessage());
            // A recusa acontece na leitura do body (lerDadosEntrada), antes de qualquer consulta
            verifyNoInteractions(audioRepo, entradaRepo, entityManager);
        }

        @Test
        @DisplayName("edição pela mesma porta (entrada_id presente) passa pela mesma régua")
        void salvarEntrada_edicao_campoTortoRecusa() {
            Map<String, Object> body = bodyCriacaoValido();
            body.put("entrada_id", String.valueOf(ENTRADA_ID));
            body.put("hora_saida", "12:60:00");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEntrada(body, TITULAR));

            assertTrue(ex.getMessage().contains("'Término da operação'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'12:60:00'"), ex.getMessage());
            verifyNoInteractions(audioRepo, entradaRepo, entityManager);
        }

        @Test
        @DisplayName("editarEntrada (tela de detalhe): hora torta recusa 400 antes do snapshot/UPDATE")
        void editarEntrada_campoTortoRecusa() {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);

            Map<String, Object> body = bodyEdicaoValido();
            body.put("hora_fim", "12:00:60");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, body, TITULAR));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("'Término do evento'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'12:00:60'"), ex.getMessage());
            verify(entradaRepo, never()).getSnapshot(anyLong());
        }

        @Test
        @DisplayName("suspensões tortas na edição multi-operador recusam 400 nomeando o rótulo")
        void atualizarSuspensoes_suspensaoTortaRecusa() throws Exception {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(true);
            stubSnapshot(null, 0);
            stubSerializacaoOk();
            stubUpdateGrande();
            when(suspensaoRepo.findByEntradaIdOrderByOrdemAsc(ENTRADA_ID)).thenReturn(List.of());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nome_evento", "Sessão Deliberativa");
            body.put("hora_inicio", "14:00");
            body.put("hora_saida", "18:00");
            Map<String, Object> susp = new LinkedHashMap<>();
            susp.put("hora_suspensao", "25:00:00");
            body.put("suspensoes", List.of(susp));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, body, TITULAR));

            assertTrue(ex.getMessage().contains("'Suspensa em'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'25:00:00'"), ex.getMessage());
            verify(suspensaoRepo, never()).save(any());
        }

        @Test
        @DisplayName("a recusa de FORMATO vem antes das regras de ordem (torta não vira mensagem de 'posterior a')")
        void formatoAntesDaOrdem() {
            // hora_fim torta E anterior ao início: sem a precedência, validarHorarios
            // responderia "deve ser posterior ao início da operação"
            Map<String, Object> body = bodyCriacaoValido();
            body.put("hora_inicio", "23:00");
            body.put("hora_fim", "xx");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEntrada(body, TITULAR));

            assertTrue(ex.getMessage().startsWith("Horário inválido em 'Término do evento'"),
                    "a mensagem é a de formato, não a de ordem: " + ex.getMessage());
        }

        @Test
        @DisplayName("regressão — horários válidos seguem normalizados a HH:MM:SS e gravados como antes")
        void valoresValidosSeguemGravando() {
            when(audioRepo.findSessaoAbertaPorSala(SALA_COMUM_ID)).thenReturn(List.of());
            Query dup = mockQueryPara("INTERVAL '5' MINUTE");
            when(dup.getSingleResult()).thenReturn(BigDecimal.ZERO);
            when(audioRepo.findChecklistDoDia("2026-07-16", SALA_COMUM_ID)).thenReturn(List.of());
            when(audioRepo.save(any())).thenAnswer(inv -> {
                RegistroOperacaoAudio a = inv.getArgument(0);
                a.setId(REGISTRO_ID);
                return a;
            });
            Sala sala = new Sala();
            sala.setId(SALA_COMUM_ID);
            sala.setMultiOperador(false);
            when(salaRepo.findById(SALA_COMUM_ID)).thenReturn(Optional.of(sala));
            Query adjacente = mockQueryPara("JOIN PES_OPERADOR");
            when(adjacente.getResultList()).thenReturn(List.of());
            when(entradaRepo.save(any())).thenAnswer(inv -> {
                RegistroOperacaoOperador e = inv.getArgument(0);
                e.setId(ENTRADA_ID);
                return e;
            });

            Map<String, Object> out = service.salvarEntrada(bodyCriacaoValido(), TITULAR);

            assertEquals(ENTRADA_ID, out.get("entrada_id"));
            verify(entradaRepo).save(argThat((RegistroOperacaoOperador e) ->
                    "14:00:00".equals(e.getHorarioInicio()) && "18:00:00".equals(e.getHoraSaida())));
        }
    }

    // ── invariante de presença — toda entrada tem ≥ 1 término ──

    @Nested
    class PresencaDeTermino {

        private static final String MSG_PRESENCA = "Informe o 'Término do evento' ou o 'Término da operação'.";

        /** Stubs do caminho de criação até a invariante (sessão nova, sem duplicidade). */
        private void stubCriacaoAteInvariante() {
            when(audioRepo.findSessaoAbertaPorSala(SALA_COMUM_ID)).thenReturn(List.of());
            Query dup = mockQueryPara("INTERVAL '5' MINUTE");
            when(dup.getSingleResult()).thenReturn(BigDecimal.ZERO);
            when(audioRepo.findChecklistDoDia("2026-07-16", SALA_COMUM_ID)).thenReturn(List.of());
            when(audioRepo.save(any())).thenAnswer(inv -> {
                RegistroOperacaoAudio a = inv.getArgument(0);
                a.setId(REGISTRO_ID);
                return a;
            });
        }

        private Map<String, Object> bodyCriacaoSemTerminos() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data_operacao", "2026-07-16");
            body.put("sala_id", String.valueOf(SALA_COMUM_ID));
            body.put("nome_evento", "Audiência Pública");
            body.put("hora_inicio", "14:00");
            return body;
        }

        @Test
        @DisplayName("criação sem NENHUM término recusa 400 nomeando os dois campos (nenhuma entrada gravada)")
        void criacao_semNenhumTermino_recusa() {
            stubCriacaoAteInvariante();

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEntrada(bodyCriacaoSemTerminos(), TITULAR));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(MSG_PRESENCA, ex.getMessage());
            // A invariante roda antes até da consulta à sala (vale para multi-operador também)
            verifyNoInteractions(salaRepo);
            verify(entradaRepo, never()).save(any());
        }

        @Test
        @DisplayName("criação com SÓ o Término do evento grava e encerra a sessão")
        void criacao_soTerminoDoEvento_grava() {
            stubCriacaoAteInvariante();
            Sala sala = new Sala();
            sala.setId(SALA_COMUM_ID);
            sala.setMultiOperador(false);
            when(salaRepo.findById(SALA_COMUM_ID)).thenReturn(Optional.of(sala));
            Query adjacente = mockQueryPara("JOIN PES_OPERADOR");
            when(adjacente.getResultList()).thenReturn(List.of());
            when(entradaRepo.save(any())).thenAnswer(inv -> {
                RegistroOperacaoOperador e = inv.getArgument(0);
                e.setId(ENTRADA_ID);
                return e;
            });

            Map<String, Object> body = bodyCriacaoSemTerminos();
            body.put("hora_fim", "18:30");

            service.salvarEntrada(body, TITULAR);

            verify(entradaRepo).save(argThat((RegistroOperacaoOperador e) ->
                    "18:30:00".equals(e.getHorarioTermino()) && e.getHoraSaida() == null));
            verify(audioRepo).finalizarSessao(REGISTRO_ID, TITULAR);
        }

        @Test
        @DisplayName("criação com SÓ o Término da operação grava e a sessão fica aberta")
        void criacao_soTerminoDaOperacao_grava() {
            stubCriacaoAteInvariante();
            Sala sala = new Sala();
            sala.setId(SALA_COMUM_ID);
            sala.setMultiOperador(false);
            when(salaRepo.findById(SALA_COMUM_ID)).thenReturn(Optional.of(sala));
            Query adjacente = mockQueryPara("JOIN PES_OPERADOR");
            when(adjacente.getResultList()).thenReturn(List.of());
            when(entradaRepo.save(any())).thenAnswer(inv -> {
                RegistroOperacaoOperador e = inv.getArgument(0);
                e.setId(ENTRADA_ID);
                return e;
            });

            service.salvarEntrada(bodyCriacaoValidoComSaida(), TITULAR);

            verify(entradaRepo).save(argThat((RegistroOperacaoOperador e) ->
                    e.getHorarioTermino() == null && "18:00:00".equals(e.getHoraSaida())));
            verify(audioRepo, never()).finalizarSessao(anyLong(), anyString());
        }

        private Map<String, Object> bodyCriacaoValidoComSaida() {
            Map<String, Object> body = bodyCriacaoSemTerminos();
            body.put("hora_saida", "18:00");
            return body;
        }

        @Test
        @DisplayName("edição pela porta salvar-entrada (entrada_id) que limpa os dois términos recusa 400")
        void edicaoDaSessao_semNenhumTermino_recusa() {
            // Sessão aberta com a entrada do próprio titular (o body edita a entrada 101)
            when(audioRepo.findSessaoAbertaPorSala(SALA_COMUM_ID)).thenReturn(rowsSessaoAberta());
            Object[] row = new Object[20];
            row[0] = ENTRADA_ID;      // entrada_id
            row[1] = REGISTRO_ID;     // registro_id
            row[2] = TITULAR;         // operador_id
            row[3] = "Operador Um";   // operador_nome
            row[4] = 1;               // ordem
            row[5] = 1;               // seq
            List<Object[]> entradas = new ArrayList<>();
            entradas.add(row);
            when(entradaRepo.listarEntradasDaSessao(REGISTRO_ID)).thenReturn(entradas);

            Map<String, Object> body = bodyCriacaoSemTerminos();
            body.put("entrada_id", String.valueOf(ENTRADA_ID));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEntrada(body, TITULAR));

            assertEquals(MSG_PRESENCA, ex.getMessage());
            verify(entradaRepo, never()).updateEntradaBasica(anyLong(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("editarEntrada que RESULTARIA em nenhum término recusa 400 antes do histórico")
        void editarEntrada_resultanteSemTermino_recusa() {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            stubSnapshot(null, 0); // snapshot sem horario_termino; countEntradas = 1
            Query ordem = mockQueryPara("SELECT ORDEM FROM");
            when(ordem.getResultList()).thenReturn(List.of(1));

            Map<String, Object> body = bodyEdicaoValido();
            body.remove("hora_saida"); // sem hora_fim e sem hora_saida → estado resultante sem término

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, body, TITULAR));

            assertEquals(MSG_PRESENCA, ex.getMessage());
            // Recusa antes de escrever qualquer coisa: nem histórico, nem UPDATE
            verify(entityManager, never()).persist(any());
        }

        @Test
        @DisplayName("sessão multi-entrada: o Término do evento do SNAPSHOT conta como término do estado resultante")
        void editarEntrada_snapshotComTermino_passa() throws Exception {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(false);
            // Snapshot próprio: sessão com 2 entradas e a editada foi quem encerrou o evento
            Object[] r = new Object[14];
            r[0] = "Evento Anterior";
            r[1] = "Resp Anterior";
            r[3] = "13:00:00";
            r[4] = "17:00:00"; // horario_termino do snapshot
            r[5] = "operacao";
            r[10] = 0;
            List<Object[]> rows = new ArrayList<>();
            rows.add(r);
            when(entradaRepo.getSnapshot(ENTRADA_ID)).thenReturn(rows);
            when(entradaRepo.countEntradasPorSessao(ENTRADA_ID)).thenReturn(2);
            Query ordem = mockQueryPara("SELECT ORDEM FROM");
            when(ordem.getResultList()).thenReturn(List.of(2));
            // ordem 2 com hora_entrada → regra 3 consulta o operador anterior (sem vizinho)
            Query adjacente = mockQueryPara("JOIN PES_OPERADOR");
            when(adjacente.getResultList()).thenReturn(List.of());
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            Query update = stubUpdateGrande();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nome_evento", "Sessão Deliberativa");
            body.put("hora_inicio", "14:00");
            body.put("responsavel_evento", "Mesa Diretora");
            body.put("hora_entrada", "15:00"); // ordem 2 exige início da operação
            // sem hora_fim e sem hora_saida no body — o término vem do snapshot

            service.editarEntrada(ENTRADA_ID, body, TITULAR);

            verify(update).executeUpdate();
        }

        @Test
        @DisplayName("edição multi-operador também exige o término (a invariante não é pulada no Plenário)")
        void editarEntrada_multiOp_semTermino_recusa() {
            stubGateTitular(TITULAR);
            stubOwnershipPermitido();
            stubSessaoMultiOp(true);
            stubSnapshot(null, 0);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nome_evento", "Sessão PP");
            body.put("hora_inicio", "09:00");
            // multi-op não exige responsavel_evento; sem nenhum término

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editarEntrada(ENTRADA_ID, body, TITULAR));

            assertEquals(MSG_PRESENCA, ex.getMessage());
            verify(entityManager, never()).persist(any());
        }
    }

    // ── Confirmação por ausência ───────────────────────

    @Test
    @DisplayName("OperacaoService não colabora com AnormalidadeService — syncHouveAnormalidade é inalcançável daqui")
    void operacaoService_naoDependeDeAnormalidadeService() {
        List<String> tiposInjetados = Arrays.stream(OperacaoService.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getSimpleName)
                .toList();
        assertFalse(tiposInjetados.stream().anyMatch(t -> t.contains("Anormalidade")),
                "OperacaoService não deve depender de AnormalidadeService; quem sincroniza "
                        + "HOUVE_ANORMALIDADE é o AnormalidadeService (§4.3). Deps atuais: " + tiposInjetados);
    }
}
