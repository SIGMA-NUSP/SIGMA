package br.leg.senado.nusp.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AgendaLegislativaService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato dos endpoints de leitura do {@link AgendaLegislativaController}
 * (T17), com datas fixas e {@link AgendaLegislativaService} mockado.
 *
 * O SSE {@code /api/agenda/stream} fica deliberadamente fora: a decisão do
 * plano exclui a assincronia do {@code SseEmitter} deste slice.
 */
@SigmaControllerTest(AgendaLegislativaController.class)
class AgendaLegislativaControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AgendaLegislativaService agendaService;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Test
    @DisplayName("agenda hoje — converte data ISO e repassa sala_id exata ao service")
    void agendaHoje_dataESalaExatas() throws Exception {
        LocalDate data = LocalDate.of(2026, 7, 9);
        when(agendaService.getAgendaParaData(data, 12))
                .thenReturn(List.of(Map.of("codigo", "R-1", "sala_id", 12)));

        mockMvc.perform(Requests.get("/api/agenda/hoje?data=2026-07-09&sala_id=12")
                        .header("Authorization", operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].codigo").value("R-1"))
                .andExpect(jsonPath("$.data[0].sala_id").value(12));

        verify(agendaService).getAgendaParaData(LocalDate.of(2026, 7, 9), 12);
    }

    @Test
    @DisplayName("agenda plenário — converte data ISO e devolve envelope {ok,data}")
    void agendaPlenario_dataExata() throws Exception {
        LocalDate data = LocalDate.of(2026, 7, 8);
        when(agendaService.getAgendaPlenarioParaData(data))
                .thenReturn(List.of(Map.of("codigo", "PLEN-1", "titulo", "Sessão Deliberativa")));

        mockMvc.perform(Requests.get("/api/agenda/plenario?data=2026-07-08")
                        .header("Authorization", operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].codigo").value("PLEN-1"))
                .andExpect(jsonPath("$.data[0].titulo").value("Sessão Deliberativa"));

        verify(agendaService).getAgendaPlenarioParaData(LocalDate.of(2026, 7, 8));
    }

    @Test
    @DisplayName("corrige F6 — binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_corrigeF6_400() throws Exception {
        // data usa LocalDate.parse no controller: formato não ISO gera DateTimeParseException,
        // agora tratada pelo handler de requisição malformada do GlobalExceptionHandler → 400.
        // Achado F6 da §5 do plano (test-implementation-plan-2026-07.md) — corrigido no C4.
        mockMvc.perform(Requests.get("/api/agenda/hoje?data=09-07-2026")
                        .header("Authorization", operador))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));

        verifyNoInteractions(agendaService);
    }
}
