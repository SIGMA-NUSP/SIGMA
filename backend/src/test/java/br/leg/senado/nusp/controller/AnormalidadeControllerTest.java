package br.leg.senado.nusp.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import br.leg.senado.nusp.service.AnormalidadeService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP representativo do {@link AnormalidadeController} (T17).
 *
 * <p>A consulta tem gate próprio: existência 404 antes de acesso 403, acesso
 * concedido antes da busca do RAOA e um segundo 404 quando a busca retorna
 * null. O service permanece integralmente mockado.</p>
 */
@SigmaControllerTest(AnormalidadeController.class)
class AnormalidadeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AnormalidadeService anormalidadeService;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Nested
    @DisplayName("GET registro — gate de acesso antes da busca por entrada")
    class BuscarPorEntrada {

        @Test
        @DisplayName("acesso validado e RAOA presente → 200; repassa principal.id e preserva a ordem")
        void presente_200() throws Exception {
            when(anormalidadeService.buscarPorEntrada(41L))
                    .thenReturn(Map.of("id", 9L, "entrada_id", 41L));

            mockMvc.perform(Requests.get("/api/operacao/anormalidade/registro?entrada_id=41")
                            .header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.id").value(9))
                    .andExpect(jsonPath("$.data.entrada_id").value(41));

            InOrder ordem = inOrder(anormalidadeService);
            ordem.verify(anormalidadeService).validarAcessoEntrada(41L, TokenFactory.USER_ID);
            ordem.verify(anormalidadeService).buscarPorEntrada(41L);
        }

        @Test
        @DisplayName("entrada inexistente → 404 antes da verificação de acesso; não busca RAOA")
        void entradaInexistente_404() throws Exception {
            doThrow(new ServiceValidationException("not_found", HttpStatus.NOT_FOUND))
                    .when(anormalidadeService).validarAcessoEntrada(404L, TokenFactory.USER_ID);

            mockMvc.perform(Requests.get("/api/operacao/anormalidade/registro?entrada_id=404")
                            .header("Authorization", operador))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("not_found"));

            verify(anormalidadeService).validarAcessoEntrada(404L, TokenFactory.USER_ID);
            verify(anormalidadeService, never()).buscarPorEntrada(404L);
        }

        @Test
        @DisplayName("entrada existente sem acesso → 403 e não busca RAOA")
        void semAcesso_403() throws Exception {
            doThrow(new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN))
                    .when(anormalidadeService).validarAcessoEntrada(43L, TokenFactory.USER_ID);

            mockMvc.perform(Requests.get("/api/operacao/anormalidade/registro?entrada_id=43")
                            .header("Authorization", operador))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("forbidden"));

            verify(anormalidadeService).validarAcessoEntrada(43L, TokenFactory.USER_ID);
            verify(anormalidadeService, never()).buscarPorEntrada(43L);
        }

        @Test
        @DisplayName("acesso validado sem RAOA vinculada → 404 not_found")
        void semRaoa_404() throws Exception {
            when(anormalidadeService.buscarPorEntrada(44L)).thenReturn(null);

            mockMvc.perform(Requests.get("/api/operacao/anormalidade/registro?entrada_id=44")
                            .header("Authorization", operador))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("not_found"));

            verify(anormalidadeService).validarAcessoEntrada(44L, TokenFactory.USER_ID);
            verify(anormalidadeService).buscarPorEntrada(44L);
        }
    }

    @Nested
    @DisplayName("POST registro — criação e edição mantêm status 201")
    class Registrar {

        @Test
        @DisplayName("criação → 201 e repassa body + principal.id exatos")
        void criacao_201() throws Exception {
            Map<String, Object> body = Map.of(
                    "registro_id", 7,
                    "sala_id", 2,
                    "data", "2026-07-10",
                    "nome_evento", "Sessão Deliberativa",
                    "hora_inicio_anormalidade", "10:20",
                    "descricao_anormalidade", "Falha no retorno de áudio",
                    "responsavel_evento", "Secretaria");
            Map<String, Object> result = new LinkedHashMap<>(
                    Map.of("registro_anormalidade_id", 70L, "registro_id", 7L));
            when(anormalidadeService.registrar(body, TokenFactory.USER_ID)).thenReturn(result);

            mockMvc.perform(Requests.post("/api/operacao/anormalidade/registro")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"registro_id":7,"sala_id":2,"data":"2026-07-10",
                                     "nome_evento":"Sessão Deliberativa","hora_inicio_anormalidade":"10:20",
                                     "descricao_anormalidade":"Falha no retorno de áudio",
                                     "responsavel_evento":"Secretaria"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.registro_anormalidade_id").value(70))
                    .andExpect(jsonPath("$.registro_id").value(7));

            verify(anormalidadeService).registrar(body, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("edição → também 201; o controller não troca o status quando id está presente")
        void edicao_201() throws Exception {
            Map<String, Object> body = Map.of(
                    "id", 71,
                    "registro_id", 7,
                    "sala_id", 2,
                    "data", "2026-07-10",
                    "nome_evento", "Sessão Deliberativa",
                    "hora_inicio_anormalidade", "10:20",
                    "descricao_anormalidade", "Falha corrigida",
                    "responsavel_evento", "Secretaria");
            Map<String, Object> result = new LinkedHashMap<>(
                    Map.of("registro_anormalidade_id", 71L, "registro_id", 7L));
            when(anormalidadeService.registrar(body, TokenFactory.USER_ID)).thenReturn(result);

            mockMvc.perform(Requests.post("/api/operacao/anormalidade/registro")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"id":71,"registro_id":7,"sala_id":2,"data":"2026-07-10",
                                     "nome_evento":"Sessão Deliberativa","hora_inicio_anormalidade":"10:20",
                                     "descricao_anormalidade":"Falha corrigida",
                                     "responsavel_evento":"Secretaria"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.registro_anormalidade_id").value(71));

            verify(anormalidadeService).registrar(body, TokenFactory.USER_ID);
        }
    }

    @Test
    @DisplayName("corrige F6 — binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_corrigeF6_400() throws Exception {
        // @RequestBody malformado → HttpMessageNotReadableException, agora tratada pelo handler
        // de requisição malformada do GlobalExceptionHandler → 400. Achado F6 da §5 (C4).
        mockMvc.perform(Requests.post("/api/operacao/anormalidade/registro")
                        .header("Authorization", operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));

        verifyNoInteractions(anormalidadeService);
    }
}
