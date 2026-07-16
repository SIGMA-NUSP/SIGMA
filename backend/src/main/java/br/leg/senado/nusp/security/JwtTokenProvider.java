package br.leg.senado.nusp.security;

import br.leg.senado.nusp.config.JwtConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Map;

/**
 * Equivalente às funções jwt_encode() e jwt_decode() do Python.
 * Usa HS256 com a mesma chave secreta.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtConfig jwtConfig;

    private Key signingKey() {
        return Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Gera um JWT com os claims fornecidos.
     * Equivale a jwt.encode(payload, settings.AUTH_JWT_SECRET, algorithm="HS256") do Python.
     */
    public String generateToken(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Valida e decodifica um JWT. Lança JwtException se inválido.
     * Equivale a jwt.decode(token, settings.AUTH_JWT_SECRET, algorithms=["HS256"]) do Python.
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Monta os claims do JWT — idêntico ao _build_claims() do Python.
     * Claims: sub, perfil, username, nome, email, sid, iat, exp
     */
    public Map<String, Object> buildClaims(String userId, String perfil, String username,
                                            String nome, String email, Long sid) {
        long iat = Instant.now().getEpochSecond();
        long exp = iat + jwtConfig.getTtlSeconds();
        return Map.of(
                "sub",      userId,
                "perfil",   perfil,
                "username", username,
                "nome",     nome,
                "email",    email,
                "sid",      String.valueOf(sid),
                "iat",      iat,
                "exp",      exp
        );
    }
}
