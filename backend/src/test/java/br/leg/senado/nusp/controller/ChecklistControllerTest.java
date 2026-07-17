package br.leg.senado.nusp.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.ChecklistService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP representativo do {@link ChecklistController}.
 *
 * <p>Cobre criação, lookup público, edição com identidade exata, validação
 * inline e o 404/403 real do service. Regras de negócio permanecem mockadas.</p>
 */
@SigmaControllerTest(ChecklistController.class)
class ChecklistControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private ChecklistService checklistService;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Test
    @DisplayName("POST registro — 201 {ok, checklist_id} e repassa body + principal.id exatos")
    void registro_201() throws Exception {
        List<Map<String, Object>> itens = List.of(Map.of("item_tipo_id", 7, "status", "Ok"));
        Map<String, Object> body = Map.of(
                "sala_id", 2,
                "data_operacao", "2026-07-10",
                "hora_inicio_testes", "08:00",
                "hora_termino_testes", "08:15",
                "itens", itens);
        // O service real devolve Map.of; o controller copia antes de acrescentar "ok".
        when(checklistService.registrar(body, TokenFactory.USER_ID))
                .thenReturn(Map.of("checklist_id", 21L, "total_respostas", 1));

        mockMvc.perform(Requests.post("/api/forms/checklist/registro")
                        .header("Authorization", operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sala_id":2,"data_operacao":"2026-07-10",
                                 "hora_inicio_testes":"08:00","hora_termino_testes":"08:15",
                                 "itens":[{"item_tipo_id":7,"status":"Ok"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.checklist_id").value(21))
                .andExpect(jsonPath("$.total_respostas").value(1));

        verify(checklistService).registrar(body, TokenFactory.USER_ID);
    }

    @Test
    @DisplayName("GET itens-tipo — rota pública sem token devolve 200 {ok,data}")
    void itensTipo_publico_200() throws Exception {
        when(checklistService.itensTipoPorSala(2)).thenReturn(List.of(
                Map.of("id", 7, "nome", "Áudio", "tipo_widget", "radio")));

        mockMvc.perform(Requests.get("/api/forms/checklist/itens-tipo?sala_id=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].nome").value("Áudio"))
                .andExpect(jsonPath("$.data[0].tipo_widget").value("radio"));

        verify(checklistService).itensTipoPorSala(2);
    }

    @Nested
    @DisplayName("PUT editar — validação inline e contrato real de ownership")
    class Editar {

        @Test
        @DisplayName("edição válida → 200 e repassa checklistId, body e principal.id exatos")
        void sucesso_200() throws Exception {
            List<Map<String, Object>> itens = List.of(Map.of("item_tipo_id", 7, "status", "Ok"));
            Map<String, Object> body = Map.of(
                    "checklist_id", 31,
                    "data_operacao", "2026-07-10",
                    "sala_id", 2,
                    "observacoes", "Revisado",
                    "itens", itens);
            // editar devolve LinkedHashMap no service real; o controller o complementa in-place.
            Map<String, Object> result = new LinkedHashMap<>(
                    Map.of("checklist_id", 31L, "total_respostas_atualizadas", 1));
            when(checklistService.editar(31L, body, TokenFactory.USER_ID)).thenReturn(result);

            mockMvc.perform(Requests.put("/api/forms/checklist/editar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"checklist_id":31,"data_operacao":"2026-07-10","sala_id":2,
                                     "observacoes":"Revisado",
                                     "itens":[{"item_tipo_id":7,"status":"Ok"}]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.checklist_id").value(31))
                    .andExpect(jsonPath("$.total_respostas_atualizadas").value(1));

            verify(checklistService).editar(31L, body, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("body sem checklist_id → 400 e não toca o service")
        void semChecklistId_400() throws Exception {
            mockMvc.perform(Requests.put("/api/forms/checklist/editar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("checklist_id obrigatório"));

            verifyNoInteractions(checklistService);
        }

        @Test
        @DisplayName("checklist inexistente — service real verifica 404 antes do ownership")
        void checklistInexistente_404() throws Exception {
            Map<String, Object> body = Map.of("checklist_id", 404);
            when(checklistService.editar(404L, body, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException("not_found", HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.put("/api/forms/checklist/editar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"checklist_id\":404}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("not_found"));

            verify(checklistService).editar(404L, body, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("operador adicional — contrato real de edição continua 403 forbidden")
        void operadorAdicional_403() throws Exception {
            // O pré-gate de criado_por barra o adicional antes da junction.
            Map<String, Object> body = Map.of("checklist_id", 43);
            when(checklistService.editar(43L, body, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.put("/api/forms/checklist/editar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"checklist_id\":43}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("forbidden"));

            verify(checklistService).editar(43L, body, TokenFactory.USER_ID);
        }
    }

    @Test
    @DisplayName("binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_400() throws Exception {
        // @RequestBody malformado → HttpMessageNotReadableException, tratada pelo handler
        // de requisição malformada do GlobalExceptionHandler → 400.
        mockMvc.perform(Requests.post("/api/forms/checklist/registro")
                        .header("Authorization", operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));

        verifyNoInteractions(checklistService);
    }
}
