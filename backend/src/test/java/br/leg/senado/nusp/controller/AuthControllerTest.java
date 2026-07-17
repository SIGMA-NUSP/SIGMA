package br.leg.senado.nusp.controller;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AuthService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link AuthController}. A segurança, o filtro e o provider JWT
 * são reais; somente o {@link AuthService} é mockado. A fixture de login é
 * parametrizada por papel, cobrindo os smokes administrador/operador/técnico
 * sem duplicar a montagem fiel do usuário.
 */
@SigmaControllerTest(AuthController.class)
class AuthControllerTest {

    private static final String LOGIN_USER_ID = "11111111-2222-4333-8444-555555555555";
    private static final String LOGIN_USERNAME = "fulano.silva";
    private static final String LOGIN_PASSWORD = "senha-segura";
    private static final String LOGIN_HASH = "$2a$10$hash-controlado-pelo-service-mock";
    private static final Long LOGIN_SID = 91L;
    private static final String FOTO_URL = "/files/operadores/fulano.jpg";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AuthService authService;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) faria as rotas autenticadas responderem 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Nested
    @DisplayName("POST /api/login")
    class Login {

        @Test
        @DisplayName("campos pt-BR {usuario,senha} — sucesso devolve JWT, usuário completo e cookie HttpOnly")
        void sucesso_ptBrCookieECorpo() throws Exception {
            stubLoginFeliz(TokenFactory.OPERADOR);

            MvcResult result = mockMvc.perform(Requests.post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            // O controller deve remover as bordas antes de consultar/verificar.
                            .content("""
                                    {"usuario":"  fulano.silva  ","senha":"  senha-segura  "}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString())
                    .andExpect(jsonPath("$.user.id").value(LOGIN_USER_ID))
                    .andExpect(jsonPath("$.user.role").value(TokenFactory.OPERADOR))
                    .andExpect(jsonPath("$.user.username").value(LOGIN_USERNAME))
                    .andExpect(jsonPath("$.user.nome").value("Fulano Silva"))
                    .andExpect(jsonPath("$.user.email").value("fulano.silva@senado.leg.br"))
                    .andExpect(jsonPath("$.user.foto_url").value(FOTO_URL))
                    .andExpect(jsonPath("$.user.canEditObsSupervisor").value(true))
                    .andExpect(jsonPath("$.user.canEditObsChefe").value(true))
                    .andExpect(jsonPath("$.user.isMaster").value(true))
                    .andExpect(jsonPath("$.user.senhaProvisoria").value(true))
                    .andExpect(jsonPath("$.user.tem_folha_ponto").value(true))
                    .andReturn();

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            String token = body.path("token").asText();
            assertFalse(token.isBlank());
            assertCookieDeAutenticacao(result.getResponse().getHeader(HttpHeaders.SET_COOKIE), token);

            Claims claims = jwtTokenProvider.parseToken(token);
            assertEquals(LOGIN_USER_ID, claims.get("sub", String.class));
            assertEquals(TokenFactory.OPERADOR, claims.get("perfil", String.class));
            assertEquals(String.valueOf(LOGIN_SID), claims.get("sid", String.class));
            assertEquals(LOGIN_USERNAME, claims.get("username", String.class));
            assertEquals("Fulano Silva", claims.get("nome", String.class));
            assertEquals("fulano.silva@senado.leg.br", claims.get("email", String.class));

            verify(authService).findUserForLogin(LOGIN_USERNAME);
            verify(authService).verifyPassword(LOGIN_PASSWORD, LOGIN_HASH);
            verify(authService).createSession(LOGIN_USER_ID);
            verify(authService).getFotoUrl(LOGIN_USER_ID, TokenFactory.OPERADOR);
            verify(authService).canEditObsSupervisor(TokenFactory.OPERADOR, LOGIN_USERNAME);
            verify(authService).canEditObsChefe(TokenFactory.OPERADOR, LOGIN_USERNAME);
            verify(authService).isMaster(TokenFactory.OPERADOR, LOGIN_USERNAME);
            verify(authService).temFolhaPonto(TokenFactory.OPERADOR, LOGIN_USER_ID);
        }

        @Test
        @DisplayName("senha errada — 401 genérico e nenhuma sessão é criada")
        void senhaErrada_401() throws Exception {
            Map<String, String> user = usuarioLogin(TokenFactory.OPERADOR);
            when(authService.findUserForLogin(LOGIN_USERNAME)).thenReturn(user);
            when(authService.verifyPassword("incorreta", LOGIN_HASH)).thenReturn(false);

            mockMvc.perform(Requests.post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"usuario":"fulano.silva","senha":"incorreta"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Credenciais inválidas"));

            verify(authService).findUserForLogin(LOGIN_USERNAME);
            verify(authService).verifyPassword("incorreta", LOGIN_HASH);
            verify(authService, never()).createSession(anyString());
        }

        // ── Smokes de fluxo: completam os 5 fluxos de login. ──────────────────
        // Os felizes por papel faltantes (admin, técnico) e o usuário inexistente;
        // o feliz-operador (sucesso_ptBrCookieECorpo, contrato completo) e a
        // senha-errada já são cobertos acima. Token ausente/adulterado são provados
        // na matriz RBAC — aqui não se duplica.

        @Test
        @DisplayName("feliz administrador — smoke: 200, JWT e papel administrador no corpo")
        void sucessoAdmin_smoke() throws Exception {
            stubLoginFeliz(TokenFactory.ADMIN);

            mockMvc.perform(Requests.post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"usuario\":\"fulano.silva\",\"senha\":\"senha-segura\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString())
                    .andExpect(jsonPath("$.user.role").value(TokenFactory.ADMIN));

            verify(authService).findUserForLogin(LOGIN_USERNAME);
            verify(authService).verifyPassword(LOGIN_PASSWORD, LOGIN_HASH);
            verify(authService).createSession(LOGIN_USER_ID);
        }

        @Test
        @DisplayName("feliz técnico — smoke: 200, JWT e papel técnico no corpo")
        void sucessoTecnico_smoke() throws Exception {
            stubLoginFeliz(TokenFactory.TECNICO);

            mockMvc.perform(Requests.post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"usuario\":\"fulano.silva\",\"senha\":\"senha-segura\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString())
                    .andExpect(jsonPath("$.user.role").value(TokenFactory.TECNICO));

            verify(authService).findUserForLogin(LOGIN_USERNAME);
            verify(authService).verifyPassword(LOGIN_PASSWORD, LOGIN_HASH);
            verify(authService).createSession(LOGIN_USER_ID);
        }

        @Test
        @DisplayName("usuário inexistente — 401 genérico, sem verificar senha nem criar sessão")
        void usuarioInexistente_401() throws Exception {
            when(authService.findUserForLogin(LOGIN_USERNAME)).thenReturn(null);

            mockMvc.perform(Requests.post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"usuario\":\"fulano.silva\",\"senha\":\"senha-segura\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Credenciais inválidas"));

            verify(authService).findUserForLogin(LOGIN_USERNAME);
            verify(authService, never()).verifyPassword(anyString(), anyString());
            verify(authService, never()).createSession(anyString());
        }
    }

    @Test
    @DisplayName("binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_400() throws Exception {
        // @RequestBody malformado → HttpMessageNotReadableException, tratada pelo handler
        // de requisição malformada do GlobalExceptionHandler → 400.
        mockMvc.perform(Requests.post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("GET /api/whoami — projeta identidade do JWT e flags conforme o AuthService")
    void whoami_identidadeEFlags() throws Exception {
        String username = "teste." + TokenFactory.OPERADOR;
        long expDoToken = jwtTokenProvider.parseToken(operador.substring("Bearer ".length()))
                .getExpiration().toInstant().getEpochSecond();
        when(authService.getFotoUrl(TokenFactory.USER_ID, TokenFactory.OPERADOR)).thenReturn(FOTO_URL);
        when(authService.isSenhaProvisoria(TokenFactory.USER_ID, TokenFactory.OPERADOR)).thenReturn(true);
        when(authService.canEditObsSupervisor(TokenFactory.OPERADOR, username)).thenReturn(true);
        when(authService.canEditObsChefe(TokenFactory.OPERADOR, username)).thenReturn(true);
        when(authService.isMaster(TokenFactory.OPERADOR, username)).thenReturn(true);
        when(authService.temFolhaPonto(TokenFactory.OPERADOR, TokenFactory.USER_ID)).thenReturn(true);

        mockMvc.perform(Requests.get("/api/whoami").header(HttpHeaders.AUTHORIZATION, operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.role").value(TokenFactory.OPERADOR))
                .andExpect(jsonPath("$.exp").value(expDoToken))
                .andExpect(jsonPath("$.user.id").value(TokenFactory.USER_ID))
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.name").value("Usuário de Teste"))
                .andExpect(jsonPath("$.user.email").value("teste@senado.leg.br"))
                .andExpect(jsonPath("$.user.foto_url").value(FOTO_URL))
                .andExpect(jsonPath("$.user.canEditObsSupervisor").value(true))
                .andExpect(jsonPath("$.user.canEditObsChefe").value(true))
                .andExpect(jsonPath("$.user.isMaster").value(true))
                .andExpect(jsonPath("$.user.senhaProvisoria").value(true))
                .andExpect(jsonPath("$.user.tem_folha_ponto").value(true));

        verify(authService).getFotoUrl(TokenFactory.USER_ID, TokenFactory.OPERADOR);
        verify(authService).isSenhaProvisoria(TokenFactory.USER_ID, TokenFactory.OPERADOR);
        verify(authService).canEditObsSupervisor(TokenFactory.OPERADOR, username);
        verify(authService).canEditObsChefe(TokenFactory.OPERADOR, username);
        verify(authService).isMaster(TokenFactory.OPERADOR, username);
        verify(authService).temFolhaPonto(TokenFactory.OPERADOR, TokenFactory.USER_ID);
    }

    @Nested
    @DisplayName("POST /api/auth/change-password")
    class ChangePassword {

        @Test
        @DisplayName("senha com menos de 6 caracteres — 400 sem chamar o service")
        void senhaCurta_400() throws Exception {
            mockMvc.perform(Requests.post("/api/auth/change-password")
                            .header(HttpHeaders.AUTHORIZATION, operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"novaSenha\":\"12345\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value("A senha deve ter no mínimo 6 caracteres."));

            verifyNoInteractions(authService);
        }

        @Test
        @DisplayName("sucesso — remove espaços de borda, repassa principal e responde 200")
        void sucesso_200() throws Exception {
            when(authService.changePassword(TokenFactory.USER_ID, TokenFactory.OPERADOR, "abcdef"))
                    .thenReturn(true);

            mockMvc.perform(Requests.post("/api/auth/change-password")
                            .header(HttpHeaders.AUTHORIZATION, operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"novaSenha\":\"  abcdef  \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.message").value("Senha alterada com sucesso."));

            verify(authService).changePassword(TokenFactory.USER_ID, TokenFactory.OPERADOR, "abcdef");
        }

        @Test
        @DisplayName("service não atualiza — 500 com falha contratual")
        void serviceRetornaFalse_500() throws Exception {
            when(authService.changePassword(TokenFactory.USER_ID, TokenFactory.OPERADOR, "abcdef"))
                    .thenReturn(false);

            mockMvc.perform(Requests.post("/api/auth/change-password")
                            .header(HttpHeaders.AUTHORIZATION, operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"novaSenha\":\"abcdef\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value("Falha ao trocar senha."));

            verify(authService).changePassword(TokenFactory.USER_ID, TokenFactory.OPERADOR, "abcdef");
        }
    }

    @Test
    @DisplayName("POST /api/auth/logout — revoga SID do principal e expira o cookie")
    void logout_revogaELimpaCookie() throws Exception {
        when(authService.revokeSession(TokenFactory.SID, TokenFactory.USER_ID)).thenReturn(1);

        mockMvc.perform(Requests.post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(result -> assertCookieExpirado(
                        result.getResponse().getHeader(HttpHeaders.SET_COOKIE)));

        verify(authService).revokeSession(TokenFactory.SID, TokenFactory.USER_ID);
    }

    @Test
    @DisplayName("POST /api/auth/refresh — devolve claims do principal e renova o mesmo cookie contratual")
    void refresh_corpoClaimsECookie() throws Exception {
        MvcResult result = mockMvc.perform(Requests.post("/api/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.exp").isNumber())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.path("token").asText();
        assertFalse(token.isBlank());
        assertCookieDeAutenticacao(result.getResponse().getHeader(HttpHeaders.SET_COOKIE), token);

        Claims claims = jwtTokenProvider.parseToken(token);
        assertEquals(TokenFactory.USER_ID, claims.get("sub", String.class));
        assertEquals(TokenFactory.OPERADOR, claims.get("perfil", String.class));
        assertEquals(String.valueOf(TokenFactory.SID), claims.get("sid", String.class));
        assertEquals("teste.operador", claims.get("username", String.class));
        assertEquals("Usuário de Teste", claims.get("nome", String.class));
        assertEquals("teste@senado.leg.br", claims.get("email", String.class));
        assertEquals(claims.getExpiration().toInstant().getEpochSecond(), body.path("exp").asLong());
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("GET /api/auth/html-guard público — ServiceValidationException conserva 401 e error")
    void htmlGuard_serviceValidation_401() throws Exception {
        when(authService.validarHtmlGuard("token-invalido"))
                .thenThrow(new ServiceValidationException("invalid_token", HttpStatus.UNAUTHORIZED));

        mockMvc.perform(Requests.get("/api/auth/html-guard")
                        .cookie(new Cookie("sn_auth_jwt", "token-invalido")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("invalid_token"));

        verify(authService).validarHtmlGuard("token-invalido");
    }

    /** Fixture completa e reaproveitável pelos três papéis do smoke de login. */
    private void stubLoginFeliz(String perfil) {
        Map<String, String> user = usuarioLogin(perfil);
        when(authService.findUserForLogin(LOGIN_USERNAME)).thenReturn(user);
        when(authService.verifyPassword(LOGIN_PASSWORD, LOGIN_HASH)).thenReturn(true);
        when(authService.createSession(LOGIN_USER_ID)).thenReturn(LOGIN_SID);
        when(authService.getFotoUrl(LOGIN_USER_ID, perfil)).thenReturn(FOTO_URL);
        // Valores não-default: cada campo do contrato precisa provar que veio do service.
        when(authService.canEditObsSupervisor(perfil, LOGIN_USERNAME)).thenReturn(true);
        when(authService.canEditObsChefe(perfil, LOGIN_USERNAME)).thenReturn(true);
        when(authService.isMaster(perfil, LOGIN_USERNAME)).thenReturn(true);
        when(authService.temFolhaPonto(perfil, LOGIN_USER_ID)).thenReturn(true);
    }

    private static Map<String, String> usuarioLogin(String perfil) {
        return Map.of(
                "id", LOGIN_USER_ID,
                "perfil", perfil,
                "username", LOGIN_USERNAME,
                "nome_completo", "Fulano Silva",
                "email", "fulano.silva@senado.leg.br",
                "password_hash", LOGIN_HASH,
                "senha_provisoria", "1"
        );
    }

    private static void assertCookieDeAutenticacao(String header, String token) {
        assertNotNull(header);
        assertTrue(header.startsWith("sn_auth_jwt=" + token + ";"));
        assertTrue(header.contains("Max-Age=5400"));
        assertTrue(header.contains("Path=/"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
    }

    private static void assertCookieExpirado(String header) {
        assertNotNull(header);
        assertTrue(header.startsWith("sn_auth_jwt=;"));
        assertTrue(header.contains("Max-Age=0"));
        assertTrue(header.contains("Path=/"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
    }
}
