package br.leg.senado.nusp.controller;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AdminCrudService;
import br.leg.senado.nusp.service.AdminDashboardService;
import br.leg.senado.nusp.service.AuthService;
import br.leg.senado.nusp.service.DashboardQueryHelper;
import br.leg.senado.nusp.service.MetabaseEmbedService;
import br.leg.senado.nusp.service.RdsXlsxService;
import br.leg.senado.nusp.service.ReportDocxService;
import br.leg.senado.nusp.service.ReportPdfService;
import br.leg.senado.nusp.service.ReportService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz RBAC — rotas exclusivamente administrativas (/api/admin/**).
 *
 * Segurança real (filtro JWT + matchers do SecurityConfig); services 100%
 * mockados — aqui NÃO se testa regra de negócio, só papel × rota → status.
 * Os 4 modos de token inválido (adulterado, expirado, claims incompletos,
 * sessão revogada) são provados no controller representativo desta classe
 * de rota (MetabaseDashboardController), incluindo o corpo JSON que o
 * próprio JwtAuthenticationFilter emite no 401.
 */
@SigmaControllerTest({MetabaseDashboardController.class, AdminCrudController.class, AdminDashboardController.class})
class RbacMatrixAdminTest {

    private static final String SEM_TOKEN = "sem-token";
    private static final String ROTA_REPRESENTATIVA = "/api/admin/metabase/dashboards";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private MetabaseEmbedService metabaseEmbedService;
    @MockitoBean private AdminCrudService adminCrudService;
    // Deps de construtor do AdminDashboardController (só dashboardService é stubado;
    // o ObjectMapper vem do auto-config Jackson do slice — não mockar).
    @MockitoBean private AdminDashboardService adminDashboardService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReportPdfService reportPdfService;
    @MockitoBean private ReportDocxService reportDocxService;
    @MockitoBean private RdsXlsxService rdsXlsxService;
    @MockitoBean private AuthService authService;

    private TokenFactory tokens;

    @BeforeEach
    void setUp() {
        tokens = new TokenFactory(jwtTokenProvider);
        // Sessão viva por padrão; o default do Mockito (0) significaria sessão inválida.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
        // Stubs mínimos p/ 2xx (retorno null viraria NPE→500 no handler).
        when(metabaseEmbedService.listarAtivos()).thenReturn(List.of());
        when(adminCrudService.getOperadorPerfil("1")).thenReturn(Map.of());
        when(adminDashboardService.listOperadores(1, 25, "", "nome", "asc", null))
                .thenReturn(new DashboardQueryHelper.PagedResult(List.of(), 0, Map.of()));
    }

    static Stream<Arguments> matriz() {
        return Stream.of(
                // /api/admin/metabase (MetabaseDashboardController)
                Arguments.of("/api/admin/metabase/dashboards", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/metabase/dashboards", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/metabase/dashboards", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/metabase/dashboards", SEM_TOKEN, 401),
                // /api/admin (AdminCrudController)
                Arguments.of("/api/admin/operador/1", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/operador/1", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/operador/1", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/operador/1", SEM_TOKEN, 401),
                // /api/admin (AdminDashboardController)
                Arguments.of("/api/admin/dashboard/operadores", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/dashboard/operadores", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/dashboard/operadores", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/dashboard/operadores", SEM_TOKEN, 401)
        );
    }

    @ParameterizedTest(name = "[{index}] {1} em {0} → {2}")
    @MethodSource("matriz")
    void matrizRbac(String rota, String papel, int esperado) throws Exception {
        executar(rota, papel).andExpect(status().is(esperado));
    }

    @Nested
    @DisplayName("modos de token inválido — o 401 vem do próprio filtro, com corpo JSON")
    class TokenInvalido {

        @Test
        @DisplayName("sem token — 401 com {error: unauthorized} (resposta do filtro, não do Spring Security)")
        void semToken_corpoDoFiltro() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    .andExpect(jsonPath("$.message").value("Missing Authorization header"));
        }

        @Test
        @DisplayName("assinatura adulterada (outro secret) — 401")
        void assinaturaAdulterada() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.assinaturaAdulterada(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    .andExpect(jsonPath("$.message").value("Token inválido ou expirado"));
        }

        @Test
        @DisplayName("token expirado — 401")
        void tokenExpirado() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.expirado(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    .andExpect(jsonPath("$.message").value("Token inválido ou expirado"));
        }

        @Test
        @DisplayName("claims incompletos (sem sid) — 401 Token incompleto")
        void claimsIncompletos() throws Exception {
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.semSid(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Token incompleto"));
        }

        @Test
        @DisplayName("sessão revogada/expirada em banco (touchSession=0) — 401 mesmo com token válido")
        void sessaoRevogada() throws Exception {
            when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(0);
            mockMvc.perform(Requests.get(ROTA_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.valido(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    // mensagem exclusiva do branch touched==0 do filtro (com ponto final)
                    .andExpect(jsonPath("$.message").value("Token inválido ou expirado."));
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
