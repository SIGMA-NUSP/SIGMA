package br.leg.senado.nusp.controller;

import java.util.LinkedHashMap;
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
import br.leg.senado.nusp.service.OperacaoService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP representativo do {@link OperacaoController} (T17).
 *
 * <p>Segurança real e service mockado. Cobre o status condicional singular de
 * salvar-entrada, a validação inline e o contrato real de edição 404/403. Os
 * demais endpoints homogêneos de delegação ficam fora por dimensionamento.</p>
 */
@SigmaControllerTest(OperacaoController.class)
class OperacaoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private OperacaoService operacaoService;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Nested
    @DisplayName("contrato singular — salvar-entrada distingue criação de edição")
    class SalvarEntrada {

        @Test
        @DisplayName("POST salvar-entrada — criação devolve 201 e repassa body + principal.id exatos")
        void criacao_201() throws Exception {
            Map<String, Object> body = Map.of(
                    "data_operacao", "2026-07-10",
                    "sala_id", 2,
                    "nome_evento", "Sessão",
                    "hora_inicio", "10:00",
                    "responsavel_evento", "Secretaria");
            // O controller acrescenta "ok" diretamente: o service real devolve mapa mutável.
            Map<String, Object> result = new LinkedHashMap<>(Map.of(
                    "registro_id", 101L,
                    "entrada_id", 11L,
                    "tipo_evento", "operacao",
                    "seq", 1,
                    "is_edicao", false,
                    "houve_anormalidade", false));
            when(operacaoService.salvarEntrada(body, TokenFactory.USER_ID)).thenReturn(result);

            mockMvc.perform(Requests.post("/api/operacao/audio/salvar-entrada")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"data_operacao":"2026-07-10","sala_id":2,
                                     "nome_evento":"Sessão","hora_inicio":"10:00",
                                     "responsavel_evento":"Secretaria"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.registro_id").value(101))
                    .andExpect(jsonPath("$.entrada_id").value(11))
                    .andExpect(jsonPath("$.tipo_evento").value("operacao"))
                    .andExpect(jsonPath("$.seq").value(1))
                    .andExpect(jsonPath("$.is_edicao").value(false))
                    .andExpect(jsonPath("$.houve_anormalidade").value(false));

            verify(operacaoService).salvarEntrada(body, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("POST salvar-entrada — edição devolve 200 quando service marca is_edicao=true")
        void edicao_200() throws Exception {
            Map<String, Object> body = Map.of(
                    "entrada_id", 12,
                    "data_operacao", "2026-07-10",
                    "sala_id", 2,
                    "nome_evento", "Sessão editada",
                    "hora_inicio", "10:05",
                    "responsavel_evento", "Secretaria",
                    "houve_anormalidade", "sim");
            Map<String, Object> result = new LinkedHashMap<>(Map.of(
                    "registro_id", 101L,
                    "entrada_id", 12L,
                    "tipo_evento", "operacao",
                    "seq", 1,
                    "is_edicao", true,
                    "houve_anormalidade", true));
            when(operacaoService.salvarEntrada(body, TokenFactory.USER_ID)).thenReturn(result);

            mockMvc.perform(Requests.post("/api/operacao/audio/salvar-entrada")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"entrada_id":12,"data_operacao":"2026-07-10","sala_id":2,
                                     "nome_evento":"Sessão editada","hora_inicio":"10:05",
                                     "responsavel_evento":"Secretaria","houve_anormalidade":"sim"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.registro_id").value(101))
                    .andExpect(jsonPath("$.entrada_id").value(12))
                    .andExpect(jsonPath("$.tipo_evento").value("operacao"))
                    .andExpect(jsonPath("$.seq").value(1))
                    .andExpect(jsonPath("$.is_edicao").value(true))
                    .andExpect(jsonPath("$.houve_anormalidade").value(true));

            verify(operacaoService).salvarEntrada(body, TokenFactory.USER_ID);
        }
    }

    @Nested
    @DisplayName("PUT editar-entrada — validação inline e contrato real de ownership")
    class EditarEntrada {

        @Test
        @DisplayName("edição válida → 200 e repassa entradaId, body e principal.id exatos")
        void sucesso_200() throws Exception {
            Map<String, Object> body = Map.of(
                    "entrada_id", 31,
                    "nome_evento", "Sessão editada",
                    "hora_inicio", "10:10",
                    "responsavel_evento", "Secretaria");
            Map<String, Object> result = new LinkedHashMap<>(Map.of(
                    "entrada_id", 31L,
                    "registro_id", 301L,
                    "houve_anormalidade_nova", false));
            when(operacaoService.editarEntrada(31L, body, TokenFactory.USER_ID)).thenReturn(result);

            mockMvc.perform(Requests.put("/api/operacao/audio/editar-entrada")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"entrada_id":31,"nome_evento":"Sessão editada",
                                     "hora_inicio":"10:10","responsavel_evento":"Secretaria"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.entrada_id").value(31))
                    .andExpect(jsonPath("$.registro_id").value(301))
                    .andExpect(jsonPath("$.houve_anormalidade_nova").value(false));

            verify(operacaoService).editarEntrada(31L, body, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("body sem entrada_id → 400 e não toca o service")
        void semEntradaId_400() throws Exception {
            mockMvc.perform(Requests.put("/api/operacao/audio/editar-entrada")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("entrada_id obrigatório"));

            verifyNoInteractions(operacaoService);
        }

        @Test
        @DisplayName("entrada inexistente — service real verifica 404 antes do ownership")
        void entradaInexistente_404() throws Exception {
            Map<String, Object> body = Map.of("entrada_id", 404);
            when(operacaoService.editarEntrada(404L, body, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException("not_found", HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.put("/api/operacao/audio/editar-entrada")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entrada_id\":404}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("not_found"));

            verify(operacaoService).editarEntrada(404L, body, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("operador adicional — contrato real de edição continua 403 forbidden")
        void operadorAdicional_403() throws Exception {
            // T6: o gate de titular roda antes da junction; adicional não ganha escrita.
            Map<String, Object> body = Map.of("entrada_id", 43);
            when(operacaoService.editarEntrada(43L, body, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.put("/api/operacao/audio/editar-entrada")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entrada_id\":43}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("forbidden"));

            verify(operacaoService).editarEntrada(43L, body, TokenFactory.USER_ID);
        }
    }

    @Test
    @DisplayName("corrige F6 — binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_corrigeF6_400() throws Exception {
        // @RequestBody malformado → HttpMessageNotReadableException, agora tratada pelo handler
        // de requisição malformada do GlobalExceptionHandler → 400. Achado F6 da §5 (C4).
        mockMvc.perform(Requests.post("/api/operacao/audio/salvar-entrada")
                        .header("Authorization", operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));

        verifyNoInteractions(operacaoService);
    }
}
