package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.ChecklistItemTipo;
import br.leg.senado.nusp.entity.ChecklistResposta;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.StatusResposta;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.ChecklistItemTipoRepository;
import br.leg.senado.nusp.repository.ChecklistOperadorRepository;
import br.leg.senado.nusp.repository.ChecklistRepository;
import br.leg.senado.nusp.repository.ChecklistRespostaRepository;
import br.leg.senado.nusp.repository.SalaRepository;

/**
 * ITs dos 2 SQL nativos de {@link ChecklistService} contra Oracle real: a janela
 * anti-duplicidade de 5 min do {@code registrar} (SYSTIMESTAMP + INTERVAL) e o
 * {@code DELETE ... NOT IN (:ids)} com bind de lista do {@code editar}.
 *
 * Service construído à mão com EM e repositories reais do slice: sem proxy Spring,
 * o {@code @Transactional} do service é inerte — a transação (e o rollback) vêm do
 * próprio {@code @DataJpaTest}.
 */
@OracleIT
class ChecklistServiceIT {

    @Autowired
    private ChecklistRepository checklistRepo;

    @Autowired
    private ChecklistRespostaRepository respostaRepo;

    @Autowired
    private ChecklistItemTipoRepository itemTipoRepo;

    @Autowired
    private ChecklistOperadorRepository checklistOperadorRepo;

    @Autowired
    private SalaRepository salaRepo;

    @Autowired
    private TestEntityManager em;

    private ChecklistService service;

    @BeforeEach
    void setUp() {
        // findAndRegisterModules: sem o módulo jsr310 o snapshot do histórico (contém
        // LocalDate) cairia no fallback "{}" — em produção o ObjectMapper do Spring o tem
        service = new ChecklistService(checklistRepo, respostaRepo, itemTipoRepo,
                checklistOperadorRepo, salaRepo, em.getEntityManager(),
                new ObjectMapper().findAndRegisterModules());
    }

    private Map<String, Object> payloadRegistrar(Sala sala, ChecklistItemTipo tipo) {
        Map<String, Object> body = new HashMap<>();
        body.put("data_operacao", "2026-07-01");
        body.put("sala_id", sala.getId());
        body.put("hora_inicio_testes", "08:00:00");
        body.put("hora_termino_testes", "08:30:00");
        body.put("turno", "Matutino");
        body.put("itens", List.of(Map.of("item_tipo_id", tipo.getId(), "status", "Ok")));
        return body;
    }

    /**
     * Reposiciona CRIADO_EM do checklist em (SYSTIMESTAMP - segundos). Ancorar no
     * relógio do banco deixa os dois lados da janela determinísticos (o caso "fora
     * da janela" não pode esperar 5 minutos reais) e independentes do carimbo do
     * @PrePersist, que vem da JVM — em UTC nos ITs por força do argLine do failsafe.
     */
    private void fixarCriadoEm(long checklistId, int segundosAtras) {
        int linhas = em.getEntityManager().createNativeQuery("""
                UPDATE FRM_CHECKLIST
                SET CRIADO_EM = SYSTIMESTAMP - NUMTODSINTERVAL(:seg, 'SECOND')
                WHERE ID = :id
                """)
                .setParameter("seg", segundosAtras)
                .setParameter("id", checklistId)
                .executeUpdate();
        assertEquals(1, linhas, "ancoragem não encontrou o checklist — INSERT não materializado?");
        em.clear();
    }

    @Nested
    @DisplayName("registrar — janela anti-duplicidade de 5 minutos (SYSTIMESTAMP real)")
    class RegistrarJanelaDuplicata {

        @Test
        @DisplayName("registrar — 2ª chamada do mesmo operador/sala dentro da janela é rejeitada")
        void registrar_duplicataDentroDaJanelaRejeitada() {
            Sala sala = CenarioFactory.novaSala(em.getEntityManager());
            Operador operador = CenarioFactory.novoOperador(em.getEntityManager());
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(em.getEntityManager());

            Map<String, Object> primeiro = service.registrar(payloadRegistrar(sala, tipo), operador.getId());
            long checklistId = ((Number) primeiro.get("checklist_id")).longValue();
            assertEquals(1, ((Number) primeiro.get("total_respostas")).intValue());
            // 4min50s atrás no relógio do Oracle: perto da borda, ainda dentro — um
            // encolhimento da janela (5 min → menos) faz este teste falhar
            fixarCriadoEm(checklistId, 290);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrar(payloadRegistrar(sala, tipo), operador.getId()));

            assertTrue(ex.getMessage().contains("menos de 5 minutos"),
                    "mensagem inesperada: " + ex.getMessage());
        }

        @Test
        @DisplayName("registrar — a janela discrimina por sala E por operador (nem outra sala nem outro operador são bloqueados)")
        void registrar_janelaDiscriminaSalaEOperador() {
            Sala salaUm = CenarioFactory.novaSala(em.getEntityManager());
            Sala salaDois = CenarioFactory.novaSala(em.getEntityManager());
            Operador operadorUm = CenarioFactory.novoOperador(em.getEntityManager());
            Operador operadorDois = CenarioFactory.novoOperador(em.getEntityManager());
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(em.getEntityManager());

            Map<String, Object> primeiro = service.registrar(payloadRegistrar(salaUm, tipo), operadorUm.getId());
            long primeiroId = ((Number) primeiro.get("checklist_id")).longValue();
            fixarCriadoEm(primeiroId, 0); // dentro da janela para (salaUm, operadorUm)

            // mesma sala, OUTRO operador → não é duplicata
            Map<String, Object> outroOperador = service.registrar(payloadRegistrar(salaUm, tipo), operadorDois.getId());
            assertNotEquals(primeiroId, ((Number) outroOperador.get("checklist_id")).longValue());

            // OUTRA sala, mesmo operador → não é duplicata
            Map<String, Object> outraSala = service.registrar(payloadRegistrar(salaDois, tipo), operadorUm.getId());
            assertNotEquals(primeiroId, ((Number) outraSala.get("checklist_id")).longValue());
        }

        @Test
        @DisplayName("registrar — checklist anterior com mais de 5 minutos não bloqueia o novo")
        void registrar_foraDaJanelaPermiteNovoRegistro() {
            Sala sala = CenarioFactory.novaSala(em.getEntityManager());
            Operador operador = CenarioFactory.novoOperador(em.getEntityManager());
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(em.getEntityManager());

            Map<String, Object> primeiro = service.registrar(payloadRegistrar(sala, tipo), operador.getId());
            long primeiroId = ((Number) primeiro.get("checklist_id")).longValue();
            fixarCriadoEm(primeiroId, 360); // 6 min atrás — fora da janela de 5

            Map<String, Object> segundo = service.registrar(payloadRegistrar(sala, tipo), operador.getId());

            assertNotEquals(primeiroId, ((Number) segundo.get("checklist_id")).longValue());
        }
    }

    @Nested
    @DisplayName("editar — sincronização de respostas (DELETE ... NOT IN com bind de lista)")
    class EditarSincronizaRespostas {

        @Test
        @DisplayName("editar — trocar de sala apaga DE FATO as respostas de itens fora do payload")
        void editar_trocaDeSalaRemoveRespostasForaDoPayload() {
            Sala salaOriginal = CenarioFactory.novaSala(em.getEntityManager());
            Sala salaNova = CenarioFactory.novaSala(em.getEntityManager());
            Operador operador = CenarioFactory.novoOperador(em.getEntityManager());
            ChecklistItemTipo tipoAntigoUm = CenarioFactory.novoItemTipo(em.getEntityManager());
            ChecklistItemTipo tipoAntigoDois = CenarioFactory.novoItemTipo(em.getEntityManager());
            ChecklistItemTipo tipoNovo = CenarioFactory.novoItemTipo(em.getEntityManager());
            Checklist checklist = CenarioFactory.novoChecklist(em.getEntityManager(), salaOriginal, operador);
            CenarioFactory.novaResposta(em.getEntityManager(), checklist, tipoAntigoUm, StatusResposta.OK, null);
            CenarioFactory.novaResposta(em.getEntityManager(), checklist, tipoAntigoDois, StatusResposta.OK, null);
            // checklist-ruído de outro operador com resposta do MESMO tipo: o DELETE não pode vazar
            Operador outroOperador = CenarioFactory.novoOperador(em.getEntityManager());
            Checklist ruido = CenarioFactory.novoChecklist(em.getEntityManager(), salaOriginal, outroOperador);
            ChecklistResposta respostaRuido = CenarioFactory.novaResposta(em.getEntityManager(), ruido,
                    tipoAntigoUm, StatusResposta.OK, null);

            Map<String, Object> body = new HashMap<>();
            body.put("data_operacao", "2026-07-01");
            body.put("sala_id", salaNova.getId());
            body.put("itens", List.of(Map.of("item_tipo_id", tipoNovo.getId(), "status", "Ok")));
            service.editar(checklist.getId(), body, operador.getId());

            em.flush(); // materializa os UPDATEs pendentes do dirty-check (em produção, o commit)
            em.clear();
            List<ChecklistResposta> restantes = respostaRepo.findByChecklistId(checklist.getId());
            assertEquals(1, restantes.size(),
                    "o DELETE ... NOT IN deveria ter removido as respostas dos itens da sala antiga");
            assertEquals(tipoNovo.getId(), restantes.get(0).getItemTipoId());
            assertEquals(salaNova.getId(), em.find(Checklist.class, checklist.getId()).getSalaId());
            List<ChecklistResposta> doRuido = respostaRepo.findByChecklistId(ruido.getId());
            assertEquals(1, doRuido.size(), "o DELETE vazou para respostas de outro checklist");
            assertEquals(respostaRuido.getId(), doRuido.get(0).getId());
        }

        @Test
        @DisplayName("editar — mantendo os mesmos itens nenhuma resposta é apagada (update in-place)")
        void editar_mesmosItensNaoApagaRespostas() {
            Sala sala = CenarioFactory.novaSala(em.getEntityManager());
            Operador operador = CenarioFactory.novoOperador(em.getEntityManager());
            ChecklistItemTipo tipoUm = CenarioFactory.novoItemTipo(em.getEntityManager());
            ChecklistItemTipo tipoDois = CenarioFactory.novoItemTipo(em.getEntityManager());
            Checklist checklist = CenarioFactory.novoChecklist(em.getEntityManager(), sala, operador);
            ChecklistResposta respostaUm = CenarioFactory.novaResposta(em.getEntityManager(), checklist,
                    tipoUm, StatusResposta.OK, null);
            ChecklistResposta respostaDois = CenarioFactory.novaResposta(em.getEntityManager(), checklist,
                    tipoDois, StatusResposta.OK, null);

            Map<String, Object> body = new HashMap<>();
            body.put("data_operacao", "2026-07-01");
            body.put("sala_id", sala.getId());
            body.put("itens", List.of(
                    Map.of("item_tipo_id", tipoUm.getId(), "status", "Falha",
                            "descricao_falha", "Problema detectado durante os testes"),
                    Map.of("item_tipo_id", tipoDois.getId(), "status", "Ok")));
            service.editar(checklist.getId(), body, operador.getId());

            em.flush(); // sem flush antes do clear, os UPDATEs de status pendentes seriam descartados
            em.clear();
            List<ChecklistResposta> restantes = respostaRepo.findByChecklistId(checklist.getId());
            assertEquals(2, restantes.size(), "nenhuma resposta deveria ter sido apagada");
            assertEquals(Set.of(respostaUm.getId(), respostaDois.getId()),
                    restantes.stream().map(ChecklistResposta::getId).collect(Collectors.toSet()),
                    "os IDs originais deveriam sobreviver — update in-place, não delete+recreate");
            ChecklistResposta umDepois = restantes.stream()
                    .filter(r -> r.getItemTipoId().equals(tipoUm.getId())).findFirst().orElseThrow();
            assertEquals(StatusResposta.FALHA, umDepois.getStatus());
            assertEquals(Boolean.TRUE, umDepois.getEditado());
        }
    }
}
