package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.config.JwtConfig;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

import static br.leg.senado.nusp.controller.ControllerUtils.senhaCurtaBadRequest;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Login, logout, refresh de token e sessão")
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;

    // =========================================================================
    // POST /api/login
    // Equivale ao login_view() do Python
    // =========================================================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                                   HttpServletResponse response) {
        String usuario = (body.getOrDefault("usuario", "")).strip();
        String senha   = (body.getOrDefault("senha",   "")).strip();

        if (usuario.isEmpty() || senha.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciais inválidas"));
        }

        Map<String, String> user = authService.findUserForLogin(usuario);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciais inválidas"));
        }

        if (!authService.verifyPassword(senha, user.get("password_hash"))) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciais inválidas"));
        }

        Long sid = authService.createSession(user.get("id"));
        Map<String, Object> claims = jwtTokenProvider.buildClaims(
                user.get("id"), user.get("perfil"), user.get("username"),
                user.get("nome_completo"), user.get("email"), sid
        );
        String token = jwtTokenProvider.generateToken(claims);

        setAuthCookie(response, token);

        String fotoUrl = authService.getFotoUrl(user.get("id"), user.get("perfil"));

        String role = user.get("perfil");
        String uname = user.get("username");
        boolean senhaProvisoria = "1".equals(user.get("senha_provisoria"));

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.ofEntries(
                        Map.entry("id",       user.get("id")),
                        Map.entry("role",     user.get("perfil")),
                        Map.entry("username", user.get("username")),
                        Map.entry("nome",     user.get("nome_completo")),
                        Map.entry("email",    user.get("email")),
                        Map.entry("foto_url", fotoUrl != null ? fotoUrl : ""),
                        Map.entry("canEditObsSupervisor", authService.canEditObsSupervisor(role, uname)),
                        Map.entry("canEditObsChefe",      authService.canEditObsChefe(role, uname)),
                        Map.entry("isMaster",             authService.isMaster(role, uname)),
                        Map.entry("senhaProvisoria",      senhaProvisoria),
                        Map.entry("tem_folha_ponto",      authService.temFolhaPonto(role, user.get("id")))
                )
        ));
    }

    // =========================================================================
    // GET /api/whoami
    // Equivale ao whoami_view() do Python
    // =========================================================================
    @GetMapping("/whoami")
    public ResponseEntity<?> whoami(@AuthenticationPrincipal UserPrincipal principal) {
        String fotoUrl = authService.getFotoUrl(principal.getId(), principal.getRole());
        String role = principal.getRole();
        String uname = principal.getUsername();
        boolean senhaProvisoria = authService.isSenhaProvisoria(principal.getId(), principal.getRole());

        return ResponseEntity.ok(Map.of(
                "ok",   true,
                "user", Map.ofEntries(
                        Map.entry("id",       principal.getId()),
                        Map.entry("username", principal.getUsername()),
                        Map.entry("name",     principal.getName()),
                        Map.entry("email",    principal.getEmail()),
                        Map.entry("foto_url", fotoUrl != null ? fotoUrl : ""),
                        Map.entry("canEditObsSupervisor", authService.canEditObsSupervisor(role, uname)),
                        Map.entry("canEditObsChefe",      authService.canEditObsChefe(role, uname)),
                        Map.entry("isMaster",             authService.isMaster(role, uname)),
                        Map.entry("senhaProvisoria",      senhaProvisoria),
                        Map.entry("tem_folha_ponto",      authService.temFolhaPonto(role, principal.getId()))
                ),
                "role", principal.getRole(),
                "exp",  principal.getExp()
        ));
    }

    // =========================================================================
    // POST /api/auth/change-password
    // Troca a senha do usuário autenticado. Limpa a flag SENHA_PROVISORIA.
    // Body: { "novaSenha": "..." }
    // =========================================================================
    @PostMapping("/auth/change-password")
    public ResponseEntity<?> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                            @RequestBody Map<String, String> body) {
        String novaSenha = body.getOrDefault("novaSenha", "").strip();
        if (novaSenha.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Senha vazia."));
        }
        ResponseEntity<Map<String, Object>> senhaCurta = senhaCurtaBadRequest(novaSenha);
        if (senhaCurta != null) return senhaCurta;

        boolean ok = authService.changePassword(principal.getId(), principal.getRole(), novaSenha);
        if (!ok) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", "Falha ao trocar senha."));
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "Senha alterada com sucesso."));
    }

    // =========================================================================
    // POST /api/auth/logout
    // Equivale ao logout_view() do Python
    // =========================================================================
    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(@AuthenticationPrincipal UserPrincipal principal,
                                    HttpServletResponse response) {
        int revoked = authService.revokeSession(principal.getSid(), principal.getId());
        clearAuthCookie(response);
        return ResponseEntity.ok(Map.of("ok", true, "revoked", revoked > 0));
    }

    // =========================================================================
    // POST /api/auth/refresh
    // Equivale ao refresh_view() do Python
    // =========================================================================
    @PostMapping("/auth/refresh")
    public ResponseEntity<?> refresh(@AuthenticationPrincipal UserPrincipal principal,
                                     HttpServletResponse response) {
        Map<String, Object> claims = jwtTokenProvider.buildClaims(
                principal.getId(), principal.getRole(), principal.getUsername(),
                principal.getName(), principal.getEmail(), principal.getSid()
        );
        String token = jwtTokenProvider.generateToken(claims);
        setAuthCookie(response, token);

        return ResponseEntity.ok(Map.of(
                "ok",    true,
                "token", token,
                "exp",   claims.get("exp")
        ));
    }

    // =========================================================================
    // GET /api/auth/html-guard
    // Equivale ao html_guard_view() do Python (lê JWT do cookie, usado pelo Nginx)
    // =========================================================================
    @GetMapping("/auth/html-guard")
    public ResponseEntity<?> htmlGuard(HttpServletRequest request) {
        String token = Arrays.stream(request.getCookies() != null ? request.getCookies() : new Cookie[0])
                .filter(c -> jwtConfig.getCookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);

        if (token == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "not_authenticated"));
        }

        return ResponseEntity.ok(authService.validarHtmlGuard(token));
    }

    // =========================================================================
    // Helpers de cookie
    // =========================================================================
    private void setAuthCookie(HttpServletResponse response, String token) {
        String domain = jwtConfig.getCookieDomain();
        String cookieHeader = String.format(
                "%s=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Lax%s%s",
                jwtConfig.getCookieName(),
                token,
                jwtConfig.getTtlSeconds(),
                domain.isEmpty() ? "" : "; Domain=" + domain,
                ""  // Secure apenas em produção; configurar via profile se necessário
        );
        response.addHeader("Set-Cookie", cookieHeader);
    }

    private void clearAuthCookie(HttpServletResponse response) {
        String domain = jwtConfig.getCookieDomain();
        String cookieHeader = String.format(
                "%s=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax%s",
                jwtConfig.getCookieName(),
                domain.isEmpty() ? "" : "; Domain=" + domain
        );
        response.addHeader("Set-Cookie", cookieHeader);
    }
}
