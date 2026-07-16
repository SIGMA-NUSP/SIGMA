package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AuthSession;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.service.NativeQueryUtils.boolVal;
import static br.leg.senado.nusp.service.NativeQueryUtils.tableForRole;
import static br.leg.senado.nusp.service.NativeQueryUtils.userRowToMap;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final OperadorRepository operadorRepository;
    private final AdministradorRepository administradorRepository;
    private final TecnicoRepository tecnicoRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.session.touch-max-age-seconds}")
    private int maxAgeSeconds;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ══ Papéis administrativos especiais (supervisor/chefe/master) ═══════════

    @Value("${app.admin.supervisor-username}")
    private String supervisorUsername;
    @Value("${app.admin.chefe-username}")
    private String chefeUsername;
    @Value("${app.admin.master-username}")
    private String masterUsername;

    /** O usuário é o administrador-supervisor configurado (edita a observação de supervisor)? */
    public boolean canEditObsSupervisor(String role, String username) {
        return "administrador".equals(role) && supervisorUsername.equalsIgnoreCase(username);
    }

    /** O usuário é o administrador-chefe configurado (edita a observação de chefe)? */
    public boolean canEditObsChefe(String role, String username) {
        return "administrador".equals(role) && chefeUsername.equalsIgnoreCase(username);
    }

    /** O usuário é o administrador-master configurado (gerencia os demais administradores)? */
    public boolean isMaster(String role, String username) {
        return "administrador".equals(role) && masterUsername.equalsIgnoreCase(username);
    }

    /**
     * Busca usuário por username ou email nas três tabelas de pessoa (administrador, operador e
     * técnico). Equivale ao get_user_for_login() do Python (UNION ALL).
     *
     * <p>A comparação é case-insensitive nos dois lados: no PostgreSQL legado USERNAME/EMAIL eram
     * {@code citext}, e no Oracle a normalização sobrou só nos setters das entidades (escrita).
     *
     * @return Map com campos: id, perfil, nome_completo, username, email, password_hash
     *         ou null se não encontrado.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> findUserForLogin(String usuario) {
        String sql = """
                SELECT 'administrador' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL, PASSWORD_HASH, SENHA_PROVISORIA
                FROM PES_ADMINISTRADOR
                WHERE LOWER(USERNAME) = LOWER(:usuario) OR LOWER(EMAIL) = LOWER(:usuario)
                UNION ALL
                SELECT 'operador' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL, PASSWORD_HASH, SENHA_PROVISORIA
                FROM PES_OPERADOR
                WHERE LOWER(USERNAME) = LOWER(:usuario) OR LOWER(EMAIL) = LOWER(:usuario)
                UNION ALL
                SELECT 'tecnico' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL, PASSWORD_HASH, SENHA_PROVISORIA
                FROM PES_TECNICO
                WHERE LOWER(USERNAME) = LOWER(:usuario) OR LOWER(EMAIL) = LOWER(:usuario)
                FETCH FIRST 1 ROWS ONLY
                """;

        List<Object[]> rows = entityManager
                .createNativeQuery(sql)
                .setParameter("usuario", usuario)
                .getResultList();

        if (rows.isEmpty()) return null;

        Object[] row = rows.get(0);
        Map<String, String> user = userRowToMap(row);
        user.put("password_hash",     String.valueOf(row[5]));
        user.put("senha_provisoria",  boolVal(row[6]) ? "1" : "0");
        return user;
    }

    /**
     * Verifica se o usuário tem senha provisória (precisa trocar antes de acessar).
     * Procura nas três tabelas (administrador, operador, técnico).
     */
    @SuppressWarnings("unchecked")
    public boolean isSenhaProvisoria(String userId, String role) {
        String table = tableForRole(role);
        if (table == null) return false;

        List<Number> rows = entityManager
                .createNativeQuery("SELECT SENHA_PROVISORIA FROM " + table + " WHERE ID = :id")
                .setParameter("id", userId)
                .getResultList();

        return !rows.isEmpty() && boolVal(rows.get(0));
    }

    /**
     * O usuário possui folha de ponto (Q35)? Operador e técnico sempre têm;
     * administrador só quando NÃO é servidor público efetivo. Helper único da
     * regra — consumido pelo whoami e pelo login (AuthController, 2 pontos).
     */
    public boolean temFolhaPonto(String role, String userId) {
        if ("operador".equals(role) || "tecnico".equals(role)) return true;
        if ("administrador".equals(role)) return !isServidorPublico(userId);
        return false;
    }

    /** Administrador é servidor público efetivo (SERVIDOR_PUBLICO = 1)? */
    @SuppressWarnings("unchecked")
    private boolean isServidorPublico(String adminId) {
        List<Number> rows = entityManager
                .createNativeQuery("SELECT SERVIDOR_PUBLICO FROM PES_ADMINISTRADOR WHERE ID = :id")
                .setParameter("id", adminId)
                .getResultList();
        return !rows.isEmpty() && boolVal(rows.get(0));
    }

    /**
     * Troca a senha do usuário autenticado e limpa a flag SENHA_PROVISORIA.
     * Não exige senha atual — supõe que o caller já está autenticado e quer apenas trocar.
     * @return true se a senha foi atualizada.
     */
    @Transactional
    public boolean changePassword(String userId, String role, String novaSenha) {
        String table = tableForRole(role);
        if (table == null) return false;

        String hash = passwordEncoder.encode(novaSenha);
        int updated = entityManager.createNativeQuery(
                "UPDATE " + table + " SET PASSWORD_HASH = :hash, SENHA_PROVISORIA = 0, ATUALIZADO_EM = SYSTIMESTAMP WHERE ID = :id")
                .setParameter("hash", hash)
                .setParameter("id", userId)
                .executeUpdate();
        return updated > 0;
    }

    /**
     * Verifica a senha com BCrypt.
     * Equivale ao bcrypt.checkpw() do Python.
     */
    public boolean verifyPassword(String rawPassword, String hash) {
        try {
            return passwordEncoder.matches(rawPassword, hash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cria sessão em PES_AUTH_SESSION.
     * Equivale ao create_session() do Python.
     * @return sid (id da sessão criada)
     */
    @Transactional
    public Long createSession(String userId) {
        // 128 bits de entropia — equivale ao secrets.token_hex(16) do Python
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        String refreshTokenHash = java.util.HexFormat.of().formatHex(bytes);

        AuthSession session = new AuthSession();
        session.setUserId(userId);
        session.setRefreshTokenHash(refreshTokenHash);
        session.setRevoked(false);
        return authSessionRepository.save(session).getId();
    }

    /**
     * Revoga a sessão. Equivale ao revoke_session() do Python.
     * @return número de linhas afetadas (1 se revogou, 0 se já estava revogada)
     */
    @Transactional
    public int revokeSession(Long sid, String userId) {
        return authSessionRepository.revokeSession(sid, userId);
    }

    /**
     * Valida o token do html-guard (usado pelo Nginx): parse do JWT, claims
     * sid/sub e touch da sessão. Falha lança 401 com o error do contrato
     * (invalid_token para token/claims inválidos; not_authenticated para
     * sessão revogada/expirada).
     *
     * @return body do 200: {ok, user{id,role,username,nome,email}, exp}
     */
    public Map<String, Object> validarHtmlGuard(String token) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseToken(token);
        } catch (JwtException e) {
            throw new ServiceValidationException("invalid_token", HttpStatus.UNAUTHORIZED);
        }

        String sidStr = claims.get("sid", String.class);
        String sub    = claims.get("sub", String.class);
        if (sidStr == null || sub == null) {
            throw new ServiceValidationException("invalid_token", HttpStatus.UNAUTHORIZED);
        }

        Long sid;
        try {
            sid = Long.parseLong(sidStr);
        } catch (NumberFormatException e) {
            throw new ServiceValidationException("invalid_token", HttpStatus.UNAUTHORIZED);
        }

        int touched = authSessionRepository.touchSession(sid, sub, maxAgeSeconds);
        if (touched == 0) {
            throw new ServiceValidationException("not_authenticated", HttpStatus.UNAUTHORIZED);
        }

        return Map.of(
                "ok", true,
                "user", Map.of(
                        "id",       sub,
                        "role",     claims.get("perfil", String.class),
                        "username", claims.get("username", String.class),
                        "nome",     claims.get("nome", String.class),
                        "email",    claims.get("email", String.class)
                ),
                "exp", claims.get("exp")
        );
    }

    /**
     * Retorna a foto_url de um operador.
     * Equivale ao get_foto_url_by_id() do Python.
     */
    public String getFotoUrl(String userId, String role) {
        if ("operador".equals(role))      return operadorRepository.findFotoUrlById(userId).orElse("");
        if ("tecnico".equals(role))       return tecnicoRepository.findFotoUrlById(userId).orElse("");
        if ("administrador".equals(role)) return administradorRepository.findFotoUrlById(userId).orElse("");
        return "";
    }
}
