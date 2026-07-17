package br.leg.senado.nusp.controller;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.service.PasswordResetService;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP das rotas públicas do {@link PasswordResetController}.
 * O service é integralmente mockado; a segurança real prova que nenhuma rota
 * sob {@code /api/password/**} exige token.
 */
@SigmaControllerTest(PasswordResetController.class)
class PasswordResetControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PasswordResetService passwordResetService;

    @Nested
    @DisplayName("POST /api/password/forgot")
    class ForgotPassword {

        @Test
        @DisplayName("username existente — trim, 200 e email mascarado")
        void existente_emailMascarado() throws Exception {
            when(passwordResetService.requestReset("fulano.silva"))
                    .thenReturn(Map.of("email_masked", "f***@senado.leg.br"));

            mockMvc.perform(Requests.post("/api/password/forgot")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"  fulano.silva  \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.email_masked").value("f***@senado.leg.br"));

            verify(passwordResetService).requestReset("fulano.silva");
        }

        @Test
        @DisplayName("username inexistente — requestReset retorna null, mas responde 200 sem email_masked")
        void inexistente_nullSemEmailMascarado() throws Exception {
            // Null é o contrato expresso do service para usuário inexistente; o verify
            // exato e o caso irmão não-null evitam depender só do default do Mockito.
            when(passwordResetService.requestReset("inexistente")).thenReturn(null);

            mockMvc.perform(Requests.post("/api/password/forgot")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"inexistente\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.email_masked").doesNotExist());

            verify(passwordResetService).requestReset("inexistente");
        }
    }

    @Nested
    @DisplayName("GET /api/password/validate-token")
    class ValidateToken {

        @Test
        @DisplayName("token válido — repassa ao service e devolve {ok:true,valid:true}")
        void valido_true() throws Exception {
            when(passwordResetService.validateToken("token-valido")).thenReturn(true);

            mockMvc.perform(Requests.get("/api/password/validate-token?token=token-valido"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.valid").value(true));

            verify(passwordResetService).validateToken("token-valido");
        }

        @Test
        @DisplayName("binding inválido responde 400 no shape padrão de erro")
        void semParametro_400() throws Exception {
            // MissingServletRequestParameterException é tratada pelo handler de requisição
            // malformada do GlobalExceptionHandler → 400.
            mockMvc.perform(Requests.get("/api/password/validate-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));

            verifyNoInteractions(passwordResetService);
        }
    }

    @Nested
    @DisplayName("POST /api/password/reset")
    class ResetPassword {

        @Test
        @DisplayName("dados incompletos — 400 antes de chamar o service")
        void incompleto_400() throws Exception {
            mockMvc.perform(Requests.post("/api/password/reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"token-valido\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value("Dados incompletos."));

            verifyNoInteractions(passwordResetService);
        }

        @Test
        @DisplayName("token inválido ou expirado — service false vira 400 contratual")
        void serviceFalse_400() throws Exception {
            when(passwordResetService.resetPassword("token-expirado", "abcdef")).thenReturn(false);

            mockMvc.perform(Requests.post("/api/password/reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"token-expirado\",\"novaSenha\":\"abcdef\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value("Token inválido ou expirado."));

            verify(passwordResetService).resetPassword("token-expirado", "abcdef");
        }

        @Test
        @DisplayName("token válido — service true vira 200 com mensagem de sucesso")
        void sucesso_200() throws Exception {
            when(passwordResetService.resetPassword("token-valido", "abcdef")).thenReturn(true);

            mockMvc.perform(Requests.post("/api/password/reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"token-valido\",\"novaSenha\":\"abcdef\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.message").value("Senha redefinida com sucesso."));

            verify(passwordResetService).resetPassword("token-valido", "abcdef");
        }
    }
}
