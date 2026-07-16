package br.leg.senado.nusp.controller.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import br.leg.senado.nusp.config.JwtConfig;
import br.leg.senado.nusp.security.JwtTokenProvider;

/**
 * Fábrica de tokens JWT reais (HS256) para os testes de controller.
 *
 * Gera pelo {@link JwtTokenProvider} do contexto (secret do perfil "test"),
 * com as variantes que a matriz RBAC exige: válido por papel, expirado,
 * assinatura adulterada (outro secret) e claims incompletos (sem sid).
 *
 * Perfis reais do app (claim "perfil", minúsculo): "administrador",
 * "operador", "tecnico" — vindos do SQL de AuthService.findUserForLogin.
 */
public final class TokenFactory {

    public static final String ADMIN = "administrador";
    public static final String OPERADOR = "operador";
    public static final String TECNICO = "tecnico";

    /** Identidade fixa embutida em todos os tokens (UUID é String no projeto). */
    public static final String USER_ID = "0f0e0d0c-0b0a-4990-8877-665544332211";
    public static final Long SID = 7L;

    private final JwtTokenProvider provider;

    public TokenFactory(JwtTokenProvider provider) {
        this.provider = provider;
    }

    /** Token válido e sessão coerente com o perfil pedido. */
    public String valido(String perfil) {
        return provider.generateToken(claimsBase(perfil));
    }

    /** Claims íntegros, mas exp no passado → parseToken lança JwtException → 401. */
    public String expirado(String perfil) {
        long agora = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>(claimsBase(perfil));
        claims.put("iat", agora - 7_200);
        claims.put("exp", agora - 3_600);
        return provider.generateToken(claims);
    }

    /** Assinado com OUTRO secret (≥32 bytes) → verificação HS256 falha → 401. */
    public String assinaturaAdulterada(String perfil) {
        JwtConfig outra = new JwtConfig();
        outra.setSecret("secret-adulterado-de-teste-hs256-fedcba9876543210fedcba");
        return new JwtTokenProvider(outra).generateToken(claimsBase(perfil));
    }

    /** Sem o claim obrigatório "sid" → filtro responde 401 "Token incompleto". */
    public String semSid(String perfil) {
        Map<String, Object> claims = new LinkedHashMap<>(claimsBase(perfil));
        claims.remove("sid");
        return provider.generateToken(claims);
    }

    /** buildClaims devolve Map imutável — copiar antes de alterar nas variantes. */
    private Map<String, Object> claimsBase(String perfil) {
        return provider.buildClaims(USER_ID, perfil, "teste." + perfil,
                "Usuário de Teste", "teste@senado.leg.br", SID);
    }
}
