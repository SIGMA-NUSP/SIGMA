package br.leg.senado.nusp.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.security.AdminOnly;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AvisoService;
import br.leg.senado.nusp.service.BancoHorasService;
import br.leg.senado.nusp.service.DashboardQueryHelper;
import br.leg.senado.nusp.service.EscalaSemanalService;
import br.leg.senado.nusp.service.GradeRetificacaoService;
import br.leg.senado.nusp.service.MarcacaoService;
import br.leg.senado.nusp.service.MetabaseEmbedService;
import br.leg.senado.nusp.service.PontoExclusaoService;
import br.leg.senado.nusp.service.PontoService;
import br.leg.senado.nusp.service.PontoXlsxService;
import br.leg.senado.nusp.service.ReportDocxService;
import br.leg.senado.nusp.service.ReportPdfService;
import br.leg.senado.nusp.service.ReportService;
import br.leg.senado.nusp.service.RetificacaoService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova que {@link AdminOnly} (method security) barra sozinha: {@code addFilters = false}
 * desliga a cadeia de filtros e tira do caminho o matcher {@code /api/admin/**} do
 * {@code SecurityConfig} — com a cadeia real, a anotação nunca chega a ser avaliada para um
 * não-admin e as duas camadas não se distinguem. Sem os filtros, o {@code SecurityContext} é
 * populado na mão (como o {@code JwtAuthenticationFilter} faz em produção): o post-processor
 * {@code authentication()} do spring-security-test depende do {@code SecurityContextHolderFilter}
 * e sem ele a method security acusa {@code AuthenticationCredentialsNotFoundException}.
 * O 403 (e não 500) vem do handler de {@code AccessDeniedException} do
 * {@code GlobalExceptionHandler}: a negação nasce dentro do dispatch, onde o
 * {@code @ExceptionHandler(Exception.class)} a capturaria antes do {@code ExceptionTranslationFilter}.
 */
@SigmaControllerTest({EscalaSemanalController.class, AvisoController.class, PontoController.class,
        MetabaseDashboardController.class})
@AutoConfigureMockMvc(addFilters = false)
class AdminOnlyMethodSecurityTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MetabaseEmbedService metabaseEmbedService;
    @MockitoBean private EscalaSemanalService escalaSemanalService;
    @MockitoBean private OperadorRepository operadorRepository;
    @MockitoBean private AvisoService avisoService;
    @MockitoBean private PontoService pontoService;
    @MockitoBean private PontoExclusaoService pontoExclusaoService;
    @MockitoBean private RetificacaoService retificacaoService;
    @MockitoBean private MarcacaoService marcacaoService;
    @MockitoBean private GradeRetificacaoService gradeRetificacaoService;
    @MockitoBean private PontoXlsxService pontoXlsxService;
    @MockitoBean private BancoHorasService bancoHorasService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReportPdfService reportPdfService;
    @MockitoBean private ReportDocxService reportDocxService;

    @BeforeEach
    void setUp() {
        // Stubs explícitos: o que as rotas de sucesso deste teste chamam.
        when(escalaSemanalService.listarEscalasPaginado(1, 10))
                .thenReturn(Map.of("data", List.of(), "meta", Map.of()));
        when(avisoService.listarTodosPaginado(1, 10, "", "data", "desc", null))
                .thenReturn(new DashboardQueryHelper.PagedResult(List.of(), 0, Map.of()));
        when(avisoService.buscarPendentes(anyString(), any(PapelPessoa.class), anyString()))
                .thenReturn(List.of());
        when(pontoService.listarLotes()).thenReturn(List.of());
        when(pontoService.minhasFolhas(TokenFactory.USER_ID)).thenReturn(List.of());
    }

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    /** Uma rota admin (anotada) de cada um dos 3 controllers mistos. */
    static Stream<Arguments> rotasAdminAnotadas() {
        return Stream.of(
                Arguments.of("/api/admin/escala/list", TokenFactory.OPERADOR),
                Arguments.of("/api/admin/escala/list", TokenFactory.TECNICO),
                Arguments.of("/api/admin/avisos/list", TokenFactory.OPERADOR),
                Arguments.of("/api/admin/avisos/list", TokenFactory.TECNICO),
                Arguments.of("/api/admin/ponto/lotes", TokenFactory.OPERADOR),
                Arguments.of("/api/admin/ponto/lotes", TokenFactory.TECNICO));
    }

    @ParameterizedTest(name = "[{index}] sem o matcher, {1} em {0} → 403 (só a anotação barra)")
    @MethodSource("rotasAdminAnotadas")
    void rotaAdminAnotada_naoAdmin_403(String rota, String perfil) throws Exception {
        autenticarComo(perfil);
        mockMvc.perform(Requests.get(rota))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Acesso negado."));
    }

    @ParameterizedTest(name = "[{index}] administrador em {0} → 200 (a anotação não barra quem deve passar)")
    @MethodSource("rotasAdminAnotadas")
    void rotaAdminAnotada_admin_200(String rota, String perfilIgnorado) throws Exception {
        autenticarComo(TokenFactory.ADMIN);
        mockMvc.perform(Requests.get(rota))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /**
     * As rotas comuns dos MESMOS controllers NÃO herdaram a anotação.
     *
     * <p>Com os filtros desligados nenhuma autorização de ROTA é avaliada, então isto não prova
     * "aberta a qualquer papel" — quem prova isso é a matriz de autorização com a cadeia real. O que
     * estes casos provam é o oposto do risco: a anotação ficou nos métodos admin e não subiu para
     * a classe nem escorregou para uma rota de operador/técnico, o que os fecharia em produção.
     */
    static Stream<Arguments> rotasComuns() {
        return Stream.of(
                Arguments.of("/api/escala/list", TokenFactory.OPERADOR),
                Arguments.of("/api/escala/list", TokenFactory.TECNICO),
                Arguments.of("/api/avisos/pendentes", TokenFactory.OPERADOR),
                Arguments.of("/api/avisos/pendentes", TokenFactory.TECNICO),
                Arguments.of("/api/ponto/minhas-folhas", TokenFactory.OPERADOR),
                Arguments.of("/api/ponto/minhas-folhas", TokenFactory.TECNICO));
    }

    @ParameterizedTest(name = "[{index}] {1} em {0} → 200 (a anotação não vazou para a rota comum)")
    @MethodSource("rotasComuns")
    void rotaComum_semAnotacao_200(String rota, String perfil) throws Exception {
        autenticarComo(perfil);
        mockMvc.perform(Requests.get(rota))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /**
     * Controller 100% administrativo: o {@code @AdminOnly} está na CLASSE e vale para todos os
     * métodos — o Metabase é o representante (2 rotas, 1 dependência).
     */
    @ParameterizedTest(name = "[{index}] {0} em /api/admin/metabase/dashboards → 403 (anotação de classe)")
    @MethodSource("papeisNaoAdmin")
    void controllerAdminPuro_naoAdmin_403(String perfil) throws Exception {
        autenticarComo(perfil);
        mockMvc.perform(Requests.get("/api/admin/metabase/dashboards"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Acesso negado."));
    }

    @Test
    @DisplayName("controller 100% admin: o administrador passa (a anotação de classe não barra quem deve entrar)")
    void controllerAdminPuro_admin_200() throws Exception {
        when(metabaseEmbedService.listarAtivos()).thenReturn(List.of());
        autenticarComo(TokenFactory.ADMIN);
        mockMvc.perform(Requests.get("/api/admin/metabase/dashboards"))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> papeisNaoAdmin() {
        return Stream.of(Arguments.of(TokenFactory.OPERADOR), Arguments.of(TokenFactory.TECNICO));
    }

    /**
     * Autenticação equivalente à que o {@code JwtAuthenticationFilter} monta em produção:
     * principal = {@link UserPrincipal} (os controllers o exigem em {@code @AuthenticationPrincipal})
     * e authorities = as do próprio principal ({@code ROLE_<PERFIL em maiúsculas>}).
     */
    private void autenticarComo(String perfil) {
        UserPrincipal principal = new UserPrincipal(TokenFactory.USER_ID, perfil, "teste." + perfil,
                "Usuário de Teste", "teste@senado.leg.br", TokenFactory.SID, 0L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
