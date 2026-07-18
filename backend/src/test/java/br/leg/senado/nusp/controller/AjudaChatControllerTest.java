package br.leg.senado.nusp.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import br.leg.senado.nusp.service.AjudaChatService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link AjudaChatController}.
 *
 * Segurança real (filtro; a rota cai no anyRequest().authenticated());
 * {@link AjudaChatService} mockado — aqui NÃO se testa regra de negócio, só o
 * contrato: shape do 200, 400 do reqTexto para campos ausentes e o mapeamento
 * service→HTTP (429/503) via {@code GlobalExceptionHandler}. A matriz papel ×
 * rota → status vive na {@code RbacMatrixComumTest}, como nas demais rotas comuns.
 */
@SigmaControllerTest(AjudaChatController.class)
class AjudaChatControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AjudaChatService ajudaChatService;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chat(String corpo) {
        return Requests.post("/api/ajuda/chat")
                .header("Authorization", operador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo);
    }

    @Test
    @DisplayName("POST /api/ajuda/chat — {ok:true, resposta} com o texto do service; "
            + "id do usuário logado, página, pergunta e histórico cru chegam ao service")
    void chat_sucesso() throws Exception {
        when(ajudaChatService.responder(anyString(), anyString(), anyString(), any()))
                .thenReturn("Folgas só de amanhã em diante.");

        mockMvc.perform(chat("""
                        {"pergunta":"Posso marcar hoje?","pagina":"ponto-banco",
                         "historico":[{"de":"usuario","texto":"oi"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.resposta").value("Folgas só de amanhã em diante."));

        var historico = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(ajudaChatService).responder(eq(TokenFactory.USER_ID), eq("ponto-banco"),
                eq("Posso marcar hoje?"), historico.capture());
        assertEquals(List.of(Map.of("de", "usuario", "texto", "oi")), historico.getValue());
    }

    @Test
    @DisplayName("pergunta ausente → 400 do reqTexto com o nome do campo")
    void chat_semPergunta() throws Exception {
        mockMvc.perform(chat("{\"pagina\":\"ponto-banco\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Campo obrigatório ausente: pergunta."));
    }

    @Test
    @DisplayName("pagina ausente → 400 do reqTexto com o nome do campo")
    void chat_semPagina() throws Exception {
        mockMvc.perform(chat("{\"pergunta\":\"Oi?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Campo obrigatório ausente: pagina."));
    }

    @Test
    @DisplayName("família (c) — ServiceValidationException do service mapeada pelo GlobalExceptionHandler "
            + "(429 do rate limit e 503 de indisponível, com {ok:false, error})")
    void chat_servicoLanca_mapeadoPeloHandler() throws Exception {
        // thenThrow encadeado: re-stubar com when() numa mock que já lança dispararia a 1ª exceção
        when(ajudaChatService.responder(anyString(), anyString(), anyString(), any()))
                .thenThrow(new ServiceValidationException("Muitas perguntas em pouco tempo.",
                        HttpStatus.TOO_MANY_REQUESTS))
                .thenThrow(new ServiceValidationException("O assistente de ajuda está indisponível no momento.",
                        HttpStatus.SERVICE_UNAVAILABLE));

        mockMvc.perform(chat("{\"pergunta\":\"Oi?\",\"pagina\":\"ponto-banco\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Muitas perguntas em pouco tempo."));

        mockMvc.perform(chat("{\"pergunta\":\"Oi?\",\"pagina\":\"ponto-banco\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ok").value(false));
    }
}
