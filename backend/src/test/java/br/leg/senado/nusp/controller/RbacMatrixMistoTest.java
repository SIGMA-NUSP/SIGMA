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
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AvisoService;
import br.leg.senado.nusp.service.DashboardQueryHelper;
import br.leg.senado.nusp.service.EscalaSemanalService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz RBAC — controllers MISTOS: sem @RequestMapping de classe, misturam
 * rotas /api/admin/... e comuns no MESMO arquivo — a única regra de papel
 * é o matcher /api/admin/** do SecurityConfig, e a matriz vigia esse
 * estado. O caso-chave: no mesmo controller, a rota admin exige
 * ADMINISTRADOR e a rota comum aceita qualquer papel autenticado.
 *
 * Representativo desta classe de rota: EscalaSemanalController.
 */
@SigmaControllerTest({EscalaSemanalController.class, AvisoController.class})
class RbacMatrixMistoTest {

    private static final String SEM_TOKEN = "sem-token";
    private static final String ROTA_ADMIN_REPRESENTATIVA = "/api/admin/escala/list";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private EscalaSemanalService escalaSemanalService;
    @MockitoBean private OperadorRepository operadorRepository; // dep de construtor; não usado no /list
    @MockitoBean private AvisoService avisoService; // ObjectMapper do AvisoController vem do slice

    private TokenFactory tokens;

    @BeforeEach
    void setUp() {
        tokens = new TokenFactory(jwtTokenProvider);
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
        // Chave "data"/"meta" obrigatórias: Map sem elas (ou null) vira NPE→500 no helper do controller.
        when(escalaSemanalService.listarEscalasPaginado(1, 10))
                .thenReturn(Map.of("data", List.of(), "meta", Map.of()));
        when(avisoService.listarTodosPaginado(1, 10, "", "data", "desc", null))
                .thenReturn(new DashboardQueryHelper.PagedResult(List.of(), 0, Map.of()));
        // /api/avisos/pendentes toleraria o default (lista vazia), mas o stub é explícito.
        when(avisoService.buscarPendentes(anyString(), any(PapelPessoa.class), anyString()))
                .thenReturn(List.of());
        when(avisoService.escalasDisponiveis()).thenReturn(List.of());
        when(avisoService.listarPessoas()).thenReturn(List.of());
    }

    static Stream<Arguments> matriz() {
        return Stream.of(
                // Rota admin do controller misto: só ADMINISTRADOR passa
                Arguments.of("/api/admin/escala/list", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/escala/list", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/escala/list", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/escala/list", SEM_TOKEN, 401),
                // Rota comum do MESMO controller: qualquer papel autenticado passa
                Arguments.of("/api/escala/list", TokenFactory.ADMIN, 200),
                Arguments.of("/api/escala/list", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/escala/list", TokenFactory.TECNICO, 200),
                Arguments.of("/api/escala/list", SEM_TOKEN, 401),
                // AvisoController — mesma mistura: rota admin com paths absolutos...
                Arguments.of("/api/admin/avisos/list", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/avisos/list", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/avisos/list", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/avisos/list", SEM_TOKEN, 401),
                // ...e rota comum no MESMO arquivo
                Arguments.of("/api/avisos/pendentes", TokenFactory.ADMIN, 200),
                Arguments.of("/api/avisos/pendentes", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/avisos/pendentes", TokenFactory.TECNICO, 200),
                Arguments.of("/api/avisos/pendentes", SEM_TOKEN, 401),
                // escalas-disponiveis (Etapa 3): rota admin do MESMO controller misto → só ADMINISTRADOR
                Arguments.of("/api/admin/avisos/escalas-disponiveis", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/avisos/escalas-disponiveis", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/avisos/escalas-disponiveis", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/avisos/escalas-disponiveis", SEM_TOKEN, 401),
                // pessoas (card Pessoal): rota admin do MESMO controller misto → só ADMINISTRADOR
                Arguments.of("/api/admin/avisos/pessoas", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/avisos/pessoas", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/avisos/pessoas", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/avisos/pessoas", SEM_TOKEN, 401)
        );
    }

    @ParameterizedTest(name = "[{index}] {1} em {0} → {2}")
    @MethodSource("matriz")
    void matrizRbac(String rota, String papel, int esperado) throws Exception {
        executar(rota, papel).andExpect(status().is(esperado));
    }

    @Nested
    @DisplayName("modos de token inválido — 401 do próprio filtro na rota admin do controller misto")
    class TokenInvalido {

        @Test
        @DisplayName("sem token — 401 com {error: unauthorized} do filtro")
        void semToken_corpoDoFiltro() throws Exception {
            mockMvc.perform(Requests.get(ROTA_ADMIN_REPRESENTATIVA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    .andExpect(jsonPath("$.message").value("Missing Authorization header"));
        }

        @Test
        @DisplayName("assinatura adulterada — 401")
        void assinaturaAdulterada() throws Exception {
            mockMvc.perform(Requests.get(ROTA_ADMIN_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.assinaturaAdulterada(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }

        @Test
        @DisplayName("token expirado — 401")
        void tokenExpirado() throws Exception {
            mockMvc.perform(Requests.get(ROTA_ADMIN_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.expirado(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }

        @Test
        @DisplayName("claims incompletos (sem sid) — 401 Token incompleto")
        void claimsIncompletos() throws Exception {
            mockMvc.perform(Requests.get(ROTA_ADMIN_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.semSid(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Token incompleto"));
        }

        @Test
        @DisplayName("sessão revogada (touchSession=0) — 401 mesmo com token válido")
        void sessaoRevogada() throws Exception {
            when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(0);
            mockMvc.perform(Requests.get(ROTA_ADMIN_REPRESENTATIVA)
                            .header("Authorization", "Bearer " + tokens.valido(TokenFactory.ADMIN)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    // mensagem exclusiva do branch touched==0 do filtro (com ponto final)
                    .andExpect(jsonPath("$.message").value("Token inválido ou expirado."));
        }
    }

    /** A matriz principal é GET-only; o "visto" da agenda é POST comum e ganha seu par de casos aqui. */
    @Nested
    @DisplayName("rota POST /api/avisos/{id}/visto (AvisoController) — comum: 3 papéis passam, sem token 401")
    class RotaPostVisto {

        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @MethodSource("br.leg.senado.nusp.controller.RbacMatrixMistoTest#papeisComuns")
        void porPapel(String papel, int esperado) throws Exception {
            MockHttpServletRequestBuilder req = Requests.post("/api/avisos/cad-1/visto");
            if (!SEM_TOKEN.equals(papel)) {
                req.header("Authorization", "Bearer " + tokens.valido(papel));
            }
            mockMvc.perform(req).andExpect(status().is(esperado));
        }
    }

    static Stream<Arguments> papeisComuns() {
        return Stream.of(
                Arguments.of(TokenFactory.ADMIN, 200),
                Arguments.of(TokenFactory.OPERADOR, 200),
                Arguments.of(TokenFactory.TECNICO, 200),
                Arguments.of(SEM_TOKEN, 401)
        );
    }

    private ResultActions executar(String rota, String papel) throws Exception {
        MockHttpServletRequestBuilder req = Requests.get(rota);
        if (!SEM_TOKEN.equals(papel)) {
            req.header("Authorization", "Bearer " + tokens.valido(papel));
        }
        return mockMvc.perform(req);
    }
}
