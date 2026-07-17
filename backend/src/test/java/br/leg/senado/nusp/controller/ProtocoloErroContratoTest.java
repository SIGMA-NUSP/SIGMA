package br.leg.senado.nusp.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AuthService;

import static org.hamcrest.Matchers.containsString;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Erros de PROTOCOLO ponta a ponta: verbo errado (405 + Allow), {@code Content-Type} que a rota
 * não consome (415 + Accept) e caminho que não existe (404) — os três nascem no despacho, antes
 * de qualquer método de controller. O {@code ExceptionHandlerExceptionResolver} (que consulta o
 * advice) roda antes do {@code DefaultHandlerExceptionResolver}, que é quem conheceria o status
 * certo — por isso o advice precisa mapeá-los explicitamente.
 *
 * <p>Dois casos vizinhos ficam no {@code GlobalExceptionHandlerTest}, na unidade: o upload acima
 * do limite (imposto pelo resolver de multipart do contêiner — o MockMvc não o aplica) e o
 * {@code Accept} não atendível, cujo corpo não é escrevível quando o próprio {@code Accept}
 * exclui JSON (ver o javadoc do handler).
 *
 * <p>{@code AuthController} é o representante: {@code /api/login} é POST-only e público (o 405/415
 * é medido sem o ruído de token), e o service mockado prova que nada do domínio foi tocado.
 */
@SigmaControllerTest(AuthController.class)
class ProtocoloErroContratoTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AuthService authService;

    private String operador;

    @BeforeEach
    void setUp() {
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
        operador = "Bearer " + new TokenFactory(jwtTokenProvider).valido(TokenFactory.OPERADOR);
    }

    @Test
    @DisplayName("verbo errado responde 405 (com Allow), não 500")
    void verboErrado_405() throws Exception {
        mockMvc.perform(Requests.get("/api/login")) // /api/login é POST-only
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Método não permitido para este recurso."));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("Content-Type não suportado responde 415, não 500")
    void contentTypeNaoSuportado_415() throws Exception {
        mockMvc.perform(Requests.post("/api/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("usuario=x&senha=y"))
                .andExpect(status().isUnsupportedMediaType())
                // O Accept vem da própria exceção e lista o que os converters da rota consomem
                // (application/json + os variantes) — o advice tinha de repô-lo, como o Allow no 405.
                .andExpect(header().string("Accept", containsString("application/json")))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Formato de conteúdo não suportado."));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("rota inexistente responde 404, não 500")
    void rotaInexistente_404() throws Exception {
        mockMvc.perform(Requests.get("/api/rota-que-nao-existe").header("Authorization", operador))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Recurso não encontrado."));

        verifyNoInteractions(authService);
    }
}
