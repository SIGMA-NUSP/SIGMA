package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PasswordResetToken;
import br.leg.senado.nusp.repository.PasswordResetTokenRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.leg.senado.nusp.service.NativeQueryUtils.tableForRole;
import static br.leg.senado.nusp.service.NativeQueryUtils.userRowToMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final EntityManager entityManager;

    @Value("${app.password-reset.token-ttl-minutes:30}")
    private int tokenTtlMinutes;

    @Value("${app.password-reset.base-url:http://localhost:4200}")
    private String baseUrl;

    @Value("${spring.mail.username:no-reply@senado-nusp.cloud}")
    private String fromEmail;

    /**
     * Busca usuário por username nas três tabelas de pessoa (administrador, operador e técnico).
     * Retorna Map com: id, perfil, nome_completo, username, email — ou null.
     *
     * <p>A comparação é case-insensitive, como a do login ({@code AuthService.findUserForLogin}):
     * quem entra com maiúscula tem de conseguir recuperar a senha com a mesma grafia com que loga.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> findUserByUsername(String username) {
        String sql = """
                SELECT 'administrador' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL
                FROM PES_ADMINISTRADOR
                WHERE LOWER(USERNAME) = LOWER(:username)
                UNION ALL
                SELECT 'operador' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL
                FROM PES_OPERADOR
                WHERE LOWER(USERNAME) = LOWER(:username)
                UNION ALL
                SELECT 'tecnico' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL
                FROM PES_TECNICO
                WHERE LOWER(USERNAME) = LOWER(:username)
                FETCH FIRST 1 ROWS ONLY
                """;

        List<Object[]> rows = entityManager
                .createNativeQuery(sql)
                .setParameter("username", username)
                .getResultList();

        if (rows.isEmpty()) return null;

        return userRowToMap(rows.get(0));
    }

    /**
     * Solicita reset de senha por username.
     * @return Map com "email_masked" se o username existir, ou null se não existir.
     */
    @Transactional
    public Map<String, String> requestReset(String username) {
        Map<String, String> user = findUserByUsername(username);
        if (user == null) return null;

        // Gera novo token (tokens anteriores continuam válidos até expirar ou o reset ser feito)
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUserId(user.get("id"));
        resetToken.setUserType(user.get("perfil"));
        resetToken.setToken(token);
        resetToken.setExpiresAt(Instant.now().plus(tokenTtlMinutes, ChronoUnit.MINUTES));
        resetToken.setUsed(false);
        tokenRepository.save(resetToken);

        // Envia e-mail
        String email = user.get("email");
        String nome = user.get("nome_completo");
        sendResetEmail(email, nome, token);

        // Retorna e-mail mascarado
        Map<String, String> result = new HashMap<>();
        result.put("email_masked", maskEmail(email));
        return result;
    }

    /**
     * Valida se um token é válido (existe, não usado, não expirado).
     */
    public boolean validateToken(String token) {
        return tokenRepository.findByToken(token)
                .map(PasswordResetService::tokenValido)
                .orElse(false);
    }

    private static boolean tokenValido(PasswordResetToken t) {
        return !t.getUsed() && t.getExpiresAt().isAfter(Instant.now());
    }

    /**
     * Redefine a senha usando o token.
     * @return true se o reset foi bem-sucedido.
     */
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token).orElse(null);
        if (resetToken == null || resetToken.getUsed() || resetToken.getExpiresAt().isBefore(Instant.now())) {
            return false;
        }

        String hash = passwordEncoder.encode(newPassword);
        String userId = resetToken.getUserId();
        String userType = resetToken.getUserType();

        if (updatePasswordHash(userType, userId, hash) == 0) return false;

        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
        invalidatePendingTokens(userId);

        revokeActiveSessions(userId);

        return true;
    }

    private int updatePasswordHash(String userType, String userId, String hash) {
        // Papel desconhecido é invariante violada (o USER_TYPE do token vem do próprio
        // findUserByUsername): abortar, nunca gravar numa tabela "de consolo" — era o
        // fallback silencioso em PES_OPERADOR que fazia o técnico perder o reset (F1).
        String table = tableForRole(userType);
        if (table == null) {
            throw new IllegalStateException("Papel desconhecido no token de reset: " + userType);
        }
        return entityManager.createNativeQuery(
                "UPDATE " + table + " SET PASSWORD_HASH = :hash, ATUALIZADO_EM = SYSTIMESTAMP WHERE ID = :id")
                .setParameter("hash", hash)
                .setParameter("id", userId)
                .executeUpdate();
    }

    private void revokeActiveSessions(String userId) {
        entityManager.createNativeQuery(
                "UPDATE PES_AUTH_SESSION SET REVOKED = 1 WHERE USER_ID = :userId AND REVOKED = 0")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private void invalidatePendingTokens(String userId) {
        entityManager.createNativeQuery(
                "UPDATE PES_PASSWORD_RESET SET USED = 1 WHERE USER_ID = :userId AND USED = 0")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private void sendResetEmail(String toEmail, String nome, String token) {
        String resetUrl = baseUrl + "/reset-password?token=" + token;
        String subject = "NUSP — Redefinição de Senha";
        String html = buildResetEmailHtml(nome, resetUrl);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "NUSP - Senado Federal");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("E-mail de reset enviado para {}", maskEmail(toEmail));
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Falha ao enviar e-mail de reset para {}: {}", maskEmail(toEmail), e.getMessage());
            throw new RuntimeException("Falha ao enviar e-mail de recuperação de senha", e);
        }
    }

    private String buildResetEmailHtml(String nome, String resetUrl) {
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                  <div style="background: #003b63; padding: 20px; text-align: center;">
                    <h1 style="color: #f2c94c; margin: 0; font-size: 24px;">NUSP - Senado Federal</h1>
                  </div>
                  <div style="padding: 30px; background: #f9f9f9;">
                    <p>Olá, <strong>%s</strong>,</p>
                    <p>Recebemos uma solicitação para redefinir a senha da sua conta no sistema NUSP.</p>
                    <p>Clique no botão abaixo para criar uma nova senha:</p>
                    <div style="text-align: center; margin: 30px 0;">
                      <a href="%s"
                         style="background: #003b63; color: #ffffff; padding: 14px 32px;
                                text-decoration: none; border-radius: 6px; font-size: 16px;
                                display: inline-block;">
                        Redefinir Senha
                      </a>
                    </div>
                    <p style="color: #666; font-size: 14px;">
                      Este link expira em <strong>%d minutos</strong>. Se você não solicitou a redefinição, ignore este e-mail.
                    </p>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 20px 0;">
                    <p style="color: #999; font-size: 12px;">
                      Se o botão não funcionar, copie e cole o link abaixo no seu navegador:<br>
                      <a href="%s" style="color: #003b63;">%s</a>
                    </p>
                  </div>
                </div>
                """.formatted(nome, resetUrl, tokenTtlMinutes, resetUrl, resetUrl);
    }

    /**
     * Mascara o e-mail: d***@senado.gov.br
     */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
