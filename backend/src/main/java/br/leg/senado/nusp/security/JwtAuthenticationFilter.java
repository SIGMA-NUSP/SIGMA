package br.leg.senado.nusp.security;

import br.leg.senado.nusp.config.JwtConfig;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Equivalente ao decorator @jwt_required do Python.
 *
 * Lê o JWT do header Authorization: Bearer <token>.
 * Valida o token e chama session_touch_ok para verificar a sessão.
 * Se válido, injeta o UserPrincipal no SecurityContextHolder.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthSessionRepository authSessionRepository;
    private final JwtConfig jwtConfig;

    @Value("${app.session.touch-max-age-seconds}")
    private int maxAgeSeconds;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/login")
                || path.equals("/api/health")
                || path.equals("/api/forms/checklist/itens-tipo")
                || path.startsWith("/api/password/")
                || path.equals("/api/auth/html-guard")
                || path.startsWith("/files/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractBearerToken(request);

        if (token == null && "/api/agenda/stream".equals(request.getServletPath())) {
            // Fallback query param APENAS para SSE — EventSource não suporta headers.
            // Restrito a este path para evitar vazamento do JWT em logs/Referer em outros endpoints.
            token = request.getParameter("token");
        }

        if (token == null) {
            // Tenta cookie como fallback (para compatibilidade)
            token = extractCookieToken(request);
        }

        if (token == null) {
            sendUnauthorized(response, "Missing Authorization header");
            return;
        }

        Claims claims;
        try {
            claims = jwtTokenProvider.parseToken(token);
        } catch (JwtException e) {
            sendUnauthorized(response, "Token inválido ou expirado");
            return;
        }

        // Valida claims obrigatórios (igual ao Python)
        String sub     = claims.get("sub", String.class);
        String perfil  = claims.get("perfil", String.class);
        String username = claims.get("username", String.class);
        String nome    = claims.get("nome", String.class);
        String email   = claims.get("email", String.class);
        String sidStr  = claims.get("sid", String.class);

        if (sub == null || perfil == null || username == null
                || nome == null || email == null || sidStr == null) {
            sendUnauthorized(response, "Token incompleto");
            return;
        }

        Long sid;
        try {
            sid = Long.parseLong(sidStr);
        } catch (NumberFormatException e) {
            sendUnauthorized(response, "Token inválido");
            return;
        }

        // session_touch_ok — equivalente ao Python
        int touched = authSessionRepository.touchSession(sid, sub, maxAgeSeconds);
        if (touched == 0) {
            sendUnauthorized(response, "Token inválido ou expirado.");
            return;
        }

        long exp = claims.get("exp", Long.class);
        UserPrincipal principal = new UserPrincipal(sub, perfil, username, nome, email, sid, exp);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) header = request.getHeader("authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private String extractCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (jwtConfig.getCookieName().equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), Map.of("error", "unauthorized", "message", message));
    }
}
