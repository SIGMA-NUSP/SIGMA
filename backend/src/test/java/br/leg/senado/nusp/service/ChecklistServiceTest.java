package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.Turno;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.ChecklistItemTipoRepository;
import br.leg.senado.nusp.repository.ChecklistOperadorRepository;
import br.leg.senado.nusp.repository.ChecklistRepository;
import br.leg.senado.nusp.repository.ChecklistRespostaRepository;
import br.leg.senado.nusp.repository.SalaRepository;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T5 — invariantes de ChecklistService (§4.2 da auditoria): janela de 5 min
 * do registro, DELETE-antes-do-reload na edição e o mapeamento de
 * itensTipoPorSala. Disciplina §0.5: SQL casado por fragmento-chave, nunca
 * anyString(), com verify dos setParameter relevantes.
 */
@ExtendWith(MockitoExtension.class)
class ChecklistServiceTest {

    @Mock private ChecklistRepository checklistRepo;
    @Mock private ChecklistRespostaRepository respostaRepo;
    @Mock private ChecklistItemTipoRepository itemTipoRepo;
    @Mock private ChecklistOperadorRepository checklistOperadorRepo;
    @Mock private SalaRepository salaRepo;
    @Mock private EntityManager entityManager;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private ChecklistService service;

    private static final String USER_ID = "uuid-operador-1";
    private static final long CHECKLIST_ID = 5L;

    // ── Helper de stub (mesma disciplina de OperacaoServiceTest/§0.5) ──

    /**
     * Mock de Query devolvido só quando o SQL contém o fragmento-chave.
     * RETURNS_SELF resolve o encadeamento .setParameter(...); getSingleResult/
     * executeUpdate exigem stub explícito em quem os consome (default do
     * Mockito seria falso-verde).
     */
    private Query mockQueryPara(String fragmento) {
        Query q = mock(Query.class, RETURNS_SELF);
        when(entityManager.createNativeQuery(argThat((String sql) -> sql != null && sql.contains(fragmento))))
                .thenReturn(q);
        return q;
    }

    private static Sala salaComum(int id) {
        Sala s = new Sala();
        s.setId(id);
        s.setMultiOperador(false);
        return s;
    }

    private static Map<String, Object> bodyRegistrarValido() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data_operacao", "2026-03-17");
        body.put("sala_id", "7");
        body.put("hora_inicio_testes", "08:00:00");
        body.put("hora_termino_testes", "09:00:00");
        body.put("itens", List.of(Map.of("item_tipo_id", 10, "status", "Ok")));
        return body;
    }

    private static Map<String, Object> bodyEditarValido() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data_operacao", "2026-03-18");
        body.put("sala_id", "1");
        body.put("itens", List.of(Map.of("item_tipo_id", 10, "status", "Ok")));
        return body;
    }

    // ══ registrar ═════════════════════════════════════════════

    @Nested
    @DisplayName("registrar")
    class Registrar {

        @Test
        @DisplayName("checklist do mesmo userId+salaId há <5 min → ServiceValidationException")
        void duplicataRecente_throws() {
            Query dup = mockQueryPara("INTERVAL '5' MINUTE");
            when(dup.getSingleResult()).thenReturn(1);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrar(bodyRegistrarValido(), USER_ID));

            assertTrue(ex.getMessage().contains("menos de 5 minutos"));
            verify(dup).setParameter(1, 7);
            verify(dup).setParameter(2, USER_ID);
        }

        @Test
        @DisplayName("corrige F29 — 'itens': null responde 400 (antes: NPE no validarItens → 500)")
        void itensNull_400() {
            Map<String, Object> body = bodyRegistrarValido();
            body.put("itens", null);   // a CHAVE existe: o getOrDefault não protegia

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, USER_ID));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            verifyNoInteractions(checklistRepo, respostaRepo);
        }

        @Test
        @DisplayName("corrige F29 — item que não é objeto responde 400 (antes: ClassCastException → 500)")
        void itemNaoEhObjeto_400() {
            Map<String, Object> body = bodyRegistrarValido();
            body.put("itens", List.of("nao-sou-um-objeto"));   // o cast some no erasure

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, USER_ID));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Itens do formulário em formato inválido.", ex.getMessage());
            verifyNoInteractions(checklistRepo, respostaRepo);
        }

        @Test
        @DisplayName("caminho feliz → cria checklist e respostas")
        void caminhoFeliz_criaChecklistERespostas() {
            Query dup = mockQueryPara("INTERVAL '5' MINUTE");
            when(dup.getSingleResult()).thenReturn(0);

            when(itemTipoRepo.findAllOrdered()).thenReturn(List.of());
            when(salaRepo.findById(7)).thenReturn(Optional.of(salaComum(7)));

            Checklist saved = new Checklist();
            saved.setId(42L);
            when(checklistRepo.save(any())).thenReturn(saved);

            Map<String, Object> result = service.registrar(bodyRegistrarValido(), USER_ID);

            assertEquals(42L, result.get("checklist_id"));
            assertEquals(1, result.get("total_respostas"));
            verify(respostaRepo).save(argThat(r -> r.getItemTipoId() == 10));
        }
    }

    // ══ editar ════════════════════════════════════════════════

    @Nested
    @DisplayName("editar")
    class Editar {

        @Test
        @DisplayName("checklist inexistente → 404 (gate de ownership roda antes de qualquer SQL nativo)")
        void checklistInexistente_404() {
            when(checklistRepo.findCriadoPorById(CHECKLIST_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editar(CHECKLIST_ID, bodyEditarValido(), USER_ID));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("usuário não é o criador (inclusive se fosse operador adicional via junction) → "
                + "403 — achado: o gate de ownership por CRIADO_POR roda ANTES de isDonoOuAdicional, "
                + "tornando o acesso via FRM_CHECKLIST_OPERADOR inalcançável neste fluxo")
        void naoCriador_403() {
            when(checklistRepo.findCriadoPorById(CHECKLIST_ID)).thenReturn(Optional.of("outro-uuid"));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.editar(CHECKLIST_ID, bodyEditarValido(), USER_ID));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("trocando de sala → o DELETE das respostas órfãs precede o recarregamento")
        void trocandoSala_deletePrecedeReload() {
            when(checklistRepo.findCriadoPorById(CHECKLIST_ID)).thenReturn(Optional.of(USER_ID));

            Query perm = mockQueryPara("FRM_CHECKLIST_OPERADOR");
            when(perm.getSingleResult()).thenReturn(1);

            Checklist existente = new Checklist();
            existente.setId(CHECKLIST_ID);
            existente.setSalaId(1);
            existente.setDataOperacao(LocalDate.of(2026, 3, 17));
            existente.setTurno(Turno.MATUTINO);
            existente.setHoraInicioTestes("08:00:00");
            existente.setHoraTerminoTestes("09:00:00");
            when(checklistRepo.findById(CHECKLIST_ID)).thenReturn(Optional.of(existente));

            when(respostaRepo.findByChecklistIdNative(CHECKLIST_ID)).thenReturn(List.of());
            when(checklistOperadorRepo.findByChecklistId(CHECKLIST_ID)).thenReturn(List.of());

            Query delete = mockQueryPara("FRM_CHECKLIST_RESPOSTA");
            when(delete.executeUpdate()).thenReturn(1);

            when(respostaRepo.findByChecklistId(CHECKLIST_ID)).thenReturn(List.of());
            when(salaRepo.findById(2)).thenReturn(Optional.of(salaComum(2)));

            Map<String, Object> body = bodyEditarValido();
            body.put("sala_id", "2");

            Map<String, Object> result = service.editar(CHECKLIST_ID, body, USER_ID);

            InOrder inOrder = inOrder(delete, respostaRepo);
            inOrder.verify(delete).executeUpdate();
            inOrder.verify(respostaRepo).findByChecklistId(CHECKLIST_ID);

            verify(delete).setParameter("cid", CHECKLIST_ID);
            verify(delete).setParameter(eq("ids"), any());
            assertEquals(CHECKLIST_ID, result.get("checklist_id"));
        }
    }

    // ══ itensTipoPorSala ══════════════════════════════════════

    @Nested
    @DisplayName("itensTipoPorSala")
    class ItensTipoPorSala {

        @Test
        @DisplayName("mapeia via ChecklistItemTipoRepository.findItensPorSala (com defaults de coluna nula)")
        void mapeiaItens() {
            Object[] row1 = {1, "Microfone", 1, "radio"};
            Object[] row2 = {2, "Fone", null, null};
            when(itemTipoRepo.findItensPorSala(3)).thenReturn(List.of(row1, row2));

            List<Map<String, Object>> result = service.itensTipoPorSala(3);

            assertEquals(2, result.size());
            assertEquals(1, result.get(0).get("id"));
            assertEquals("Microfone", result.get(0).get("nome"));
            assertEquals(1, result.get(0).get("ordem"));
            assertEquals("radio", result.get(0).get("tipo_widget"));
            assertNull(result.get(1).get("ordem"));
            assertEquals("radio", result.get(1).get("tipo_widget")); // default quando NULL
        }
    }

    // ── corrige F73 (C19): régua de horário na porta do checklist ──

    @Nested
    class ReguaDeHorarioF73 {

        @Test
        @DisplayName("corrige F73 — hora_inicio_testes torta recusa 400 nomeando 'Início dos testes', antes de tocar o banco")
        void registrar_horaInicioTorta_recusa() {
            Map<String, Object> body = bodyRegistrarValido();
            body.put("hora_inicio_testes", "24:00:00");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, USER_ID));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("'Início dos testes'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'24:00:00'"), ex.getMessage());
            verifyNoInteractions(checklistRepo, respostaRepo, entityManager);
        }

        @Test
        @DisplayName("corrige F73 — hora_termino_testes torta recusa 400 nomeando 'Término dos testes'")
        void registrar_horaTerminoTorta_recusa() {
            Map<String, Object> body = bodyRegistrarValido();
            body.put("hora_termino_testes", "xx");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, USER_ID));

            assertTrue(ex.getMessage().contains("'Término dos testes'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'xx'"), ex.getMessage());
            verifyNoInteractions(checklistRepo, respostaRepo, entityManager);
        }

        @Test
        @DisplayName("regressão — HH:MM é completado para HH:MM:SS na gravação (formato 100% vivo das colunas), e HH:MM:SS com segundos vivos fica intacto")
        void registrar_validosNormalizamComoOBanco() {
            Query dup = mockQueryPara("INTERVAL '5' MINUTE");
            when(dup.getSingleResult()).thenReturn(0);
            when(itemTipoRepo.findAllOrdered()).thenReturn(List.of());
            when(checklistRepo.save(any())).thenAnswer(inv -> {
                Checklist c = inv.getArgument(0);
                c.setId(CHECKLIST_ID);
                return c;
            });
            when(salaRepo.findById(7)).thenReturn(Optional.of(salaComum(7)));

            Map<String, Object> body = bodyRegistrarValido();
            body.put("hora_inicio_testes", "08:00");      // HH:MM → completa :00
            body.put("hora_termino_testes", "10:36:50");  // segundos vivos do wizard → intacto

            service.registrar(body, USER_ID);

            verify(checklistRepo).save(argThat((Checklist c) ->
                    "08:00:00".equals(c.getHoraInicioTestes())
                            && "10:36:50".equals(c.getHoraTerminoTestes())
                            && c.getTurno() == Turno.MATUTINO)); // inferirTurno segue lendo a hora
        }
    }
}
