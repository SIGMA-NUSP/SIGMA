package br.leg.senado.nusp.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.leg.senado.nusp.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unitário de {@link JwtTokenProvider}. Classe isolada, sem I/O: o único colaborador
 * é o {@link JwtConfig} (POJO de configuração), instanciado de verdade — não há
 * Mockito aqui.
 *
 * <p>Invariante coberta: {@code parseToken} <b>nunca</b> devolve {@code null} nem um
 * boolean em falha — sempre lança {@link JwtException} (é o {@code AuthService} quem
 * captura e traduz para 401).</p>
 */
class JwtTokenProviderTest {

    private static final String SECRET = "chave-fixa-de-teste-hs256-nusp-test-0123456789abcdef"; // >= 32 bytes
    private static final int TTL = 1800;

    private JwtTokenProvider providerCom(String secret) {
        JwtConfig cfg = new JwtConfig();
        cfg.setSecret(secret);
        cfg.setTtlSeconds(TTL);
        return new JwtTokenProvider(cfg);
    }

    private JwtTokenProvider provider() {
        return providerCom(SECRET);
    }

    @Test
    @DisplayName("round-trip — buildClaims/generateToken/parseToken preserva os claims; sid é String e exp = iat + ttl")
    void roundTrip_claimsIntegrosSidStringExpIatMaisTtl() {
        JwtTokenProvider provider = provider();

        Map<String, Object> claims = provider.buildClaims(
                "uuid-42", "operador", "joao.silva",
                "João da Silva", "joao@senado.leg.br", 91L);
        String token = provider.generateToken(claims);

        Claims parsed = provider.parseToken(token);

        assertEquals("uuid-42", parsed.get("sub", String.class));
        assertEquals("operador", parsed.get("perfil", String.class));
        assertEquals("joao.silva", parsed.get("username", String.class));
        assertEquals("João da Silva", parsed.get("nome", String.class));
        assertEquals("joao@senado.leg.br", parsed.get("email", String.class));

        // sid é gravado como String (String.valueOf), nunca como número.
        assertEquals("91", parsed.get("sid", String.class));

        // exp = iat + ttlSeconds (ambos epoch-segundos).
        long iat = parsed.getIssuedAt().toInstant().getEpochSecond();
        long exp = parsed.getExpiration().toInstant().getEpochSecond();
        assertEquals(TTL, exp - iat);
    }

    @Test
    @DisplayName("parseToken — token expirado lança JwtException (nunca retorna null)")
    void parseToken_expirado_lancaJwtException() {
        JwtTokenProvider provider = provider();

        long agora = Instant.now().getEpochSecond();
        Map<String, Object> claims = new HashMap<>(provider.buildClaims(
                "uuid-42", "operador", "joao.silva",
                "João da Silva", "joao@senado.leg.br", 91L));
        claims.put("iat", agora - 7_200);
        claims.put("exp", agora - 3_600); // exp no passado
        String token = provider.generateToken(claims);

        assertThrows(JwtException.class, () -> provider.parseToken(token));
    }

    @Test
    @DisplayName("parseToken — assinatura adulterada (outro secret) lança JwtException")
    void parseToken_assinaturaAdulterada_lancaJwtException() {
        JwtTokenProvider emissor = provider();
        Map<String, Object> claims = emissor.buildClaims(
                "uuid-42", "operador", "joao.silva",
                "João da Silva", "joao@senado.leg.br", 91L);

        // Assinado com OUTRO secret (>= 32 bytes) → a verificação HS256 falha.
        String token = providerCom("secret-adulterado-de-teste-hs256-fedcba9876543210fedcba")
                .generateToken(claims);

        assertThrows(JwtException.class, () -> emissor.parseToken(token));
    }

    @Test
    @DisplayName("parseToken — lixo não-JWT lança JwtException")
    void parseToken_lixo_lancaJwtException() {
        JwtTokenProvider provider = provider();

        assertThrows(JwtException.class, () -> provider.parseToken("isto-nao-e-um-jwt"));
        assertThrows(JwtException.class, () -> provider.parseToken("aaa.bbb.ccc"));
    }
}
