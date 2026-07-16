package br.leg.senado.nusp.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Envio de e-mails de alerta de monitoramento (ex.: falha na sincronização da Agenda).
 *
 * Reaproveita o mesmo {@link JavaMailSender} (SMTP Hostinger) usado pela recuperação de senha,
 * com remetente {@code spring.mail.username} ({@code no-reply@senado-nusp.cloud}).
 *
 * Destino em {@code app.alerts.sync-to} (1+ e-mails separados por vírgula). Vazio = desligado.
 * O assunto recebe o prefixo do ambiente ({@code [NUSP][PROD]} / {@code [NUSP][HOMOLOG]}).
 *
 * Nunca propaga exceção: uma falha de envio não pode quebrar quem chamou (ex.: o polling).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertEmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:no-reply@senado-nusp.cloud}")
    private String fromEmail;

    @Value("${app.alerts.sync-to:}")
    private String destino;

    @Value("${app.env.label:}")
    private String envLabel;

    /** Indica se há destino configurado (alertas habilitados). */
    public boolean isEnabled() {
        return destino != null && !destino.isBlank();
    }

    /**
     * Envia um alerta HTML para o(s) destino(s) configurado(s). No-op se desabilitado.
     */
    public void enviarAlerta(String assunto, String htmlCorpo) {
        if (!isEnabled()) {
            log.debug("Alerta não enviado (app.alerts.sync-to vazio): {}", assunto);
            return;
        }
        String ambiente = (envLabel == null || envLabel.isBlank()) ? "PROD" : envLabel.toUpperCase();
        String assuntoFinal = "[NUSP][" + ambiente + "] " + assunto;
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "NUSP - Monitoramento");
            helper.setTo(destino.split("\\s*,\\s*"));
            helper.setSubject(assuntoFinal);
            helper.setText(htmlCorpo, true);
            mailSender.send(message);
            log.info("Alerta enviado para {}: {}", destino, assuntoFinal);
        } catch (Exception e) {
            log.error("Falha ao enviar alerta '{}': {}", assuntoFinal, e.getMessage());
        }
    }
}
