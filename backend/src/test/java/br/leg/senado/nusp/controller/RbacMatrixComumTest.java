package br.leg.senado.nusp.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.ComissaoRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AgendaLegislativaService;
import br.leg.senado.nusp.service.AnormalidadeService;
import br.leg.senado.nusp.service.AuthService;
import br.leg.senado.nusp.service.ChecklistService;
import br.leg.senado.nusp.service.DashboardQueryHelper;
import br.leg.senado.nusp.service.OperacaoService;
import br.leg.senado.nusp.service.OperadorDashboardService;
import br.leg.senado.nusp.service.PasswordResetService;
import br.leg.senado.nusp.service.ReportPdfService;
import br.leg.senado.nusp.service.ReportService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;


import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz RBAC — rotas comuns (autenticadas sem restrição de papel) e públicas
 * (permitAll). Segurança real, services/repositories mockados (só contrato
 * papel × rota → status; nenhuma regra de negócio).
 *
 * Representativo desta classe de rota: LookupController — inclusive o ⚠ da
 * tabela do plano: o javadoc da classe diz "públicos", mas NÃO há permitAll
 * para /api/forms/lookup/** → sem token é 401 (cai no anyRequest().authenticated()).
 */
@SigmaControllerTest({LookupController.class, AgendaLegislativaController.class,
        AnormalidadeController.class, AuthController.class, ChecklistController.class,
        HealthController.class, OperacaoController.class, OperadorDashboardController.class,
        PasswordResetController.class})
class RbacMatrixComumTest {

    private static final String SEM_TOKEN = "sem-token";
    private static final String ROTA_REPRESENTATIVA = "/api/forms/lookup/salas";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private OperadorRepository operadorRepository;
    @MockitoBean private SalaRepository salaRepository;
    @MockitoBean private ComissaoRepository comissaoRepository;
    @MockitoBean private AgendaLegislativaService agendaLegislativaService;
    @MockitoBean private AnormalidadeService anormalidadeService;
    @MockitoBean private AuthService authService;
    @MockitoBean private ChecklistService checklistService;
    @MockitoBean private OperacaoService operacaoService;
    // Deps de construtor do OperadorDashboardController (ObjectMapper vem do slice).
    @MockitoBean private OperadorDashboardService operadorDashboardService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReportPdfService reportPdfService;
    @MockitoBean private PasswordResetService passwordResetService;

    private TokenFactory tokens;

    @BeforeEach
    void setUp() {
        tokens = new TokenFactory(jwtTokenProvider);
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
        // Stubs mínimos p/ 2xx (default null do mock viraria NPE→500 nos handlers).
        // Para /api/whoami e /api/password/validate-token o default do mock JÁ dá 200
        // (booleans primitivos e getFotoUrl null tolerado) — sem stub, por decisão da
        // tabela do T15; o valor asserido aqui é o STATUS, nunca o default do mock.
        when(salaRepository.findAtivasOrdenadas()).thenReturn(List.of());
        when(agendaLegislativaService.getAgendaParaData(any(LocalDate.class), isNull())).thenReturn(List.of());
        when(anormalidadeService.buscarPorEntrada(1L)).thenReturn(Map.of());
        when(checklistService.itensTipoPorSala(1)).thenReturn(List.of());
        when(operacaoService.listOperadoresPlenario()).thenReturn(List.of());
        when(operadorDashboardService.listMeusChecklists(TokenFactory.USER_ID, 1, 25, "data", "desc", null))
                .thenReturn(new DashboardQueryHelper.PagedResult(List.of(), 0, Map.of()));
    }

    static Stream<Arguments> matriz() {
        return Stream.of(
                // Lookup: autenticada sem papel — 3 papéis passam; sem token 401 (⚠ javadoc engana)
                Arguments.of("/api/forms/lookup/salas", TokenFactory.ADMIN, 200),
                Arguments.of("/api/forms/lookup/salas", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/forms/lookup/salas", TokenFactory.TECNICO, 200),
                Arguments.of("/api/forms/lookup/salas", SEM_TOKEN, 401),
                // /api/agenda (AgendaLegislativaController)
                Arguments.of("/api/agenda/hoje", TokenFactory.ADMIN, 200),
                Arguments.of("/api/agenda/hoje", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/agenda/hoje", TokenFactory.TECNICO, 200),
                Arguments.of("/api/agenda/hoje", SEM_TOKEN, 401),
                // /api/operacao/anormalidade (AnormalidadeController)
                Arguments.of("/api/operacao/anormalidade/registro?entrada_id=1", TokenFactory.ADMIN, 200),
                Arguments.of("/api/operacao/anormalidade/registro?entrada_id=1", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/operacao/anormalidade/registro?entrada_id=1", TokenFactory.TECNICO, 200),
                Arguments.of("/api/operacao/anormalidade/registro?entrada_id=1", SEM_TOKEN, 401),
                // /api (AuthController — whoami)
                Arguments.of("/api/whoami", TokenFactory.ADMIN, 200),
                Arguments.of("/api/whoami", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/whoami", TokenFactory.TECNICO, 200),
                Arguments.of("/api/whoami", SEM_TOKEN, 401),
                // /api (OperacaoController)
                Arguments.of("/api/operacao/lookup/operadores-plenario", TokenFactory.ADMIN, 200),
                Arguments.of("/api/operacao/lookup/operadores-plenario", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/operacao/lookup/operadores-plenario", TokenFactory.TECNICO, 200),
                Arguments.of("/api/operacao/lookup/operadores-plenario", SEM_TOKEN, 401),
                // /api/operador (OperadorDashboardController) — ⚠ SEM restrição de papel no
                // SecurityConfig: admin e técnico também passam (escopo por dono fica no service)
                Arguments.of("/api/operador/meus-checklists", TokenFactory.ADMIN, 200),
                Arguments.of("/api/operador/meus-checklists", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/operador/meus-checklists", TokenFactory.TECNICO, 200),
                Arguments.of("/api/operador/meus-checklists", SEM_TOKEN, 401),
                // permitAll exato: /api/forms/checklist/itens-tipo — 200 até SEM token
                Arguments.of("/api/forms/checklist/itens-tipo?sala_id=1", TokenFactory.ADMIN, 200),
                Arguments.of("/api/forms/checklist/itens-tipo?sala_id=1", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/forms/checklist/itens-tipo?sala_id=1", TokenFactory.TECNICO, 200),
                Arguments.of("/api/forms/checklist/itens-tipo?sala_id=1", SEM_TOKEN, 200),
                // permitAll: /api/health
                Arguments.of("/api/health", TokenFactory.ADMIN, 200),
                Arguments.of("/api/health", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/health", TokenFactory.TECNICO, 200),
                Arguments.of("/api/health", SEM_TOKEN, 200),
                // permitAll: /api/password/** (com ?token= — sem o param obrigatório seria o 500 do F6)
                Arguments.of("/api/password/validate-token?token=x", TokenFactory.ADMIN, 200),
                Arguments.of("/api/password/validate-token?token=x", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/password/validate-token?token=x", TokenFactory.TECNICO, 200),
                Arguments.of("/api/password/validate-token?token=x", SEM_TOKEN, 200)
        );
    }

    @ParameterizedTest(name = "[{index}] {1} em {0} → {2}")
    @MethodSource("matriz")
    void matrizRbac(String rota, String papel, int esperado) throws Exception {
        executar(rota, papel).andExpect(status().is(esperado));
    }

    @Nested
    @DisplayName("modos de token inválido — 401 do próprio filtro na rota comum")
    class TokenInvalido {

        @Test
        @DisplayName("sem token — 401 com {error: unauthorized} do filtro")
        void semToken_corpoDoFiltro() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    .andExpect(jsonPath("$.message").value("Missing Authorization header"));
        }

        @Test
        @DisplayName("assinatura adulterada — 401")
        void assinaturaAdulterada() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.assinaturaAdulterada(TokenFactory.OPERADOR)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }

        @Test
        @DisplayName("token expirado — 401")
        void tokenExpirado() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.expirado(TokenFactory.OPERADOR)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }

        @Test
        @DisplayName("claims incompletos (sem sid) — 401 Token incompleto")
        void claimsIncompletos() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.semSid(TokenFactory.OPERADOR)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Token incompleto"));
        }

        @Test
        @DisplayName("sessão revogada (touchSession=0) — 401 mesmo com token válido")
        void sessaoRevogada() throws Exception {
            when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(0);
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.valido(TokenFactory.OPERADOR)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    // mensagem exclusiva do branch touched==0 do filtro (com ponto final)
                    .andExpect(jsonPath("$.message").value("Token inválido ou expirado."));
        }
    }

    @Nested
    @DisplayName("rotas públicas (permitAll) — nunca barradas pelo filtro")
    class RotasPublicas {

        @Test
        @DisplayName("GET /api/health sem token — 200 com {ok:true, envLabel} (permitAll + shouldNotFilter)")
        void health_corpo() throws Exception {
            mockMvc.perform(Requests.get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.envLabel").exists());
        }

        @Test
        @DisplayName("POST /api/login sem token — o 401 de credenciais vazias é do HANDLER "
                + "(error='Credenciais inválidas'), não o 401 do filtro (error='unauthorized'): prova o permitAll")
        void login_permitAllProvadoPeloCorpo() throws Exception {
            mockMvc.perform(Requests.post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"usuario\":\"\",\"senha\":\"\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Credenciais inválidas"));
        }

        @Test
        @DisplayName("GET /api/auth/html-guard sem cookie — 401 do HANDLER (error='not_authenticated'), "
                + "não do filtro: prova o permitAll")
        void htmlGuard_semCookie_401DoHandler() throws Exception {
            mockMvc.perform(Requests.get("/api/auth/html-guard"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("not_authenticated"));
        }

        @Test
        @DisplayName("GET /api/auth/html-guard com cookie — 200 (handler delega ao service; filtro nem roda)")
        void htmlGuard_comCookie_200() throws Exception {
            when(authService.validarHtmlGuard("tok-qualquer")).thenReturn(Map.of("ok", true));
            mockMvc.perform(Requests.get("/api/auth/html-guard")
                            .cookie(new jakarta.servlet.http.Cookie("sn_auth_jwt", "tok-qualquer")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }
    }

    private ResultActions executar(String rota, String papel) throws Exception {
        MockHttpServletRequestBuilder req = Requests.get(rota);
        if (!SEM_TOKEN.equals(papel)) {
            req.header("Authorization", "Bearer " + tokens.valido(papel));
        }
        return mockMvc.perform(req);
    }
}
