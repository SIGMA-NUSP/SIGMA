package br.leg.senado.nusp.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import br.leg.senado.nusp.entity.PasswordResetToken;
import br.leg.senado.nusp.repository.PasswordResetTokenRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unitário de {@link PasswordResetService} — criado no T18 (§4.17).
 *
 * <p>Trava os invariantes de segurança do fluxo de reset: validade do token
 * (não-usado E não-expirado), rejeição de token usado/expirado/inexistente e,
 * no sucesso, a <b>ordem obrigatória</b> das 4 operações na mesma transação —
 * UPDATE do hash → token {@code USED=1} → invalidar demais pendentes → revogar
 * sessões. {@code requestReset} de username inexistente devolve {@code null}
 * sem enviar e-mail.</p>
 *
 * <p><b>F1 (§5 do plano) CORRIGIDO no C2:</b> {@code requestReset} passou a fazer
 * {@code UNION ALL} nas três tabelas de pessoa (o técnico era invisível ao reset), e
 * {@code updatePasswordHash} mapeia papel→tabela por {@code NativeQueryUtils.tableForRole}
 * — papel desconhecido agora <b>aborta</b> em vez de cair silenciosamente em
 * {@code PES_OPERADOR} (era esse fallback que gravava o hash do técnico na tabela errada).
 * Os testes {@code corrige F1} abaixo travam os três caminhos.</p>
 *
 * <p>Stubs de {@code Query} seguem a §0.5: SQL casado por fragmento
 * ({@code contains(...)}), nunca {@code anyString()}.</p>
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JavaMailSender mailSender;
    @Mock private EntityManager entityManager;

    @InjectMocks
    private PasswordResetService service;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static PasswordResetToken token(String userType, String userId, boolean used, Instant expiresAt) {
        PasswordResetToken t = new PasswordResetToken();
        t.setUserId(userId);
        t.setUserType(userType);
        t.setToken("tok-123");
        t.setUsed(used);
        t.setExpiresAt(expiresAt);
        return t;
    }

    private static PasswordResetToken valido(String userType) {
        return token(userType, "uuid-user", false, Instant.now().plus(20, ChronoUnit.MINUTES));
    }

    private static PasswordResetToken expirado() {
        return token("operador", "uuid-user", false, Instant.now().minus(5, ChronoUnit.MINUTES));
    }

    private static PasswordResetToken usado() {
        return token("operador", "uuid-user", true, Instant.now().plus(20, ChronoUnit.MINUTES));
    }

    /** Query fluente (setParameter devolve a si mesma) com executeUpdate configurável. */
    private static Query fluente(int linhasAfetadas) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.executeUpdate()).thenReturn(linhasAfetadas);
        return q;
    }

    // ══ validateToken ═════════════════════════════════════════════════════════

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("token válido (não-usado, não-expirado) — true")
        void valido_true() {
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(valido("operador")));
            assertTrue(service.validateToken("tok-123"));
        }

        @Test
        @DisplayName("token expirado — false")
        void expirado_false() {
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(expirado()));
            assertFalse(service.validateToken("tok-123"));
        }

        @Test
        @DisplayName("token já usado — false")
        void usado_false() {
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(usado()));
            assertFalse(service.validateToken("tok-123"));
        }

        @Test
        @DisplayName("token inexistente — false")
        void inexistente_false() {
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.empty());
            assertFalse(service.validateToken("tok-123"));
        }
    }

    // ══ resetPassword ═════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        @Test
        @DisplayName("token expirado — false, sem tocar hash/sessões")
        void expirado_false() {
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(expirado()));

            assertFalse(service.resetPassword("tok-123", "NovaSenha@1"));

            verify(passwordEncoder, never()).encode(anyString());
            verify(tokenRepository, never()).save(any());
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("token já usado — false, sem tocar hash/sessões")
        void usado_false() {
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(usado()));

            assertFalse(service.resetPassword("tok-123", "NovaSenha@1"));

            verify(passwordEncoder, never()).encode(anyString());
            verify(tokenRepository, never()).save(any());
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("token inexistente — false, sem tocar hash/sessões")
        void inexistente_false() {
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.empty());

            assertFalse(service.resetPassword("tok-123", "NovaSenha@1"));

            verify(passwordEncoder, never()).encode(anyString());
            verify(tokenRepository, never()).save(any());
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("sucesso — ordem obrigatória: UPDATE hash → token USED=1 → invalida pendentes → revoga sessões")
        void sucesso_ordemDas4Operacoes() {
            PasswordResetToken resetToken = valido("operador");
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(resetToken));
            when(passwordEncoder.encode("NovaSenha@1")).thenReturn("hash-novo");

            Query hashQ = fluente(1);            // updatePasswordHash precisa afetar > 0 linhas
            Query invalidaQ = fluente(0);
            Query revogaQ = fluente(0);
            when(entityManager.createNativeQuery(contains("PASSWORD_HASH"))).thenReturn(hashQ);
            when(entityManager.createNativeQuery(contains("PES_PASSWORD_RESET"))).thenReturn(invalidaQ);
            when(entityManager.createNativeQuery(contains("PES_AUTH_SESSION"))).thenReturn(revogaQ);

            assertTrue(service.resetPassword("tok-123", "NovaSenha@1"));
            assertTrue(resetToken.getUsed()); // o token foi marcado como consumido

            // A ordem é o invariante de segurança (mesma transação).
            InOrder ord = inOrder(entityManager, tokenRepository);
            ord.verify(entityManager).createNativeQuery(contains("PASSWORD_HASH"));
            ord.verify(tokenRepository).save(resetToken);
            ord.verify(entityManager).createNativeQuery(contains("PES_PASSWORD_RESET"));
            ord.verify(entityManager).createNativeQuery(contains("PES_AUTH_SESSION"));

            // Disciplina §0.5(c): o hash novo e o id do usuário chegam à query.
            verify(hashQ).setParameter(eq("hash"), eq("hash-novo"));
            verify(hashQ).setParameter(eq("id"), eq("uuid-user"));
        }

        @Test
        @DisplayName("sucesso com UPDATE afetando 0 linhas — false (usuário sumiu entre a busca e o update)")
        void updateAfeta0Linhas_false() {
            PasswordResetToken resetToken = valido("operador");
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(resetToken));
            when(passwordEncoder.encode("NovaSenha@1")).thenReturn("hash-novo");
            Query hashQ = fluente(0); // extrair antes do when externo (fluente() faz stubbing interno)
            when(entityManager.createNativeQuery(contains("PASSWORD_HASH"))).thenReturn(hashQ);

            assertFalse(service.resetPassword("tok-123", "NovaSenha@1"));

            // Aborta antes de consumir o token e de mexer em pendentes/sessões.
            assertFalse(resetToken.getUsed());
            verify(tokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("corrige F1 — token de técnico grava o hash em PES_TECNICO (a tabela do próprio técnico)")
        void tecnico_gravaEmPesTecnico_corrigeF1() {
            // F1 (§5 do plano): updatePasswordHash mapeava binariamente
            // "administrador"→PES_ADMINISTRADOR e QUALQUER outro→PES_OPERADOR — o token de
            // técnico caía em PES_OPERADOR e o hash do técnico real nunca era atualizado.
            // Agora o papel resolve a tabela por tableForRole (fonte única).
            PasswordResetToken resetToken = valido("tecnico");
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(resetToken));
            when(passwordEncoder.encode("NovaSenha@1")).thenReturn("hash-novo");
            Query hashQ = fluente(1);       // extrair antes do when externo (stubbing interno de fluente())
            Query invalidaQ = fluente(0);
            Query revogaQ = fluente(0);
            when(entityManager.createNativeQuery(contains("PASSWORD_HASH"))).thenReturn(hashQ);
            when(entityManager.createNativeQuery(contains("PES_PASSWORD_RESET"))).thenReturn(invalidaQ);
            when(entityManager.createNativeQuery(contains("PES_AUTH_SESSION"))).thenReturn(revogaQ);

            assertTrue(service.resetPassword("tok-123", "NovaSenha@1"));

            verify(entityManager).createNativeQuery(argThat(sql ->
                    sql.contains("PASSWORD_HASH") && sql.contains("PES_TECNICO") && !sql.contains("PES_OPERADOR")));
        }

        @Test
        @DisplayName("corrige F1 — papel desconhecido no token aborta com exceção: nenhuma tabela é tocada")
        void papelDesconhecido_abortaSemGravar() {
            // O fallback silencioso em PES_OPERADOR era a armadilha do F1: um USER_TYPE que o
            // service não sabe mapear tem de estourar, não escolher uma tabela por descarte.
            PasswordResetToken resetToken = valido("faxineiro");
            when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(resetToken));
            when(passwordEncoder.encode("NovaSenha@1")).thenReturn("hash-novo");

            assertThrows(IllegalStateException.class,
                    () -> service.resetPassword("tok-123", "NovaSenha@1"));

            verifyNoInteractions(entityManager);          // nenhum UPDATE partiu
            assertFalse(resetToken.getUsed());            // o token não foi consumido
            verify(tokenRepository, never()).save(any());
        }
    }

    // ══ requestReset ══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("requestReset")
    class RequestReset {

        /** Stub do UNION do findUserByUsername devolvendo "nenhum usuário". */
        private void mockBuscaVazia() {
            Query q = mock(Query.class);
            when(entityManager.createNativeQuery(contains("PES_ADMINISTRADOR"))).thenReturn(q);
            when(q.setParameter(eq("username"), anyString())).thenReturn(q);
            when(q.getResultList()).thenReturn(List.of());
        }

        @Test
        @DisplayName("username inexistente — devolve null sem enviar e-mail nem gravar token")
        void inexistente_nullSemEmail() {
            mockBuscaVazia();

            assertNull(service.requestReset("naoexiste"));

            verifyNoInteractions(mailSender);
            verify(tokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("corrige F1 — o UNION ALL do requestReset cobre as TRÊS tabelas de pessoa")
        void unionCobreAsTresTabelas_corrigeF1() {
            // F1 (§5 do plano): o UNION ALL cobria só PES_ADMINISTRADOR e PES_OPERADOR — um
            // técnico não era encontrado, caía no ramo null e nunca recebia o e-mail.
            mockBuscaVazia();

            assertNull(service.requestReset("naoexiste"));

            verify(entityManager).createNativeQuery(argThat(sql ->
                    sql.contains("PES_ADMINISTRADOR") && sql.contains("PES_OPERADOR") && sql.contains("PES_TECNICO")));
        }

        @Test
        @DisplayName("corrige F1 — técnico é encontrado: token nasce com perfil 'tecnico' e o e-mail sai")
        void tecnicoRecebeReset_corrigeF1() {
            // A prova de comportamento (o SQL casado por contains não basta): a row de técnico
            // atravessa o fluxo — token com USER_TYPE 'tecnico' (que o resetPassword usa para
            // achar PES_TECNICO) e e-mail efetivamente enviado.
            Query q = mock(Query.class);
            when(entityManager.createNativeQuery(contains("PES_TECNICO"))).thenReturn(q);
            when(q.setParameter(eq("username"), eq("tecnico.qualquer"))).thenReturn(q);
            when(q.getResultList()).thenReturn(List.<Object[]>of(new Object[]{
                    "tecnico", "uuid-tec", "Fulano Técnico", "tecnico.qualquer", "fulano@senado.leg.br"}));
            when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
            // @Value não é injetado com @InjectMocks; sem remetente o MimeMessageHelper recusa a mensagem.
            ReflectionTestUtils.setField(service, "fromEmail", "no-reply@senado-nusp.cloud");
            ReflectionTestUtils.setField(service, "baseUrl", "https://senado-nusp.cloud");

            Map<String, String> resultado = service.requestReset("tecnico.qualquer");

            assertNotNull(resultado);
            assertEquals("f***@senado.leg.br", resultado.get("email_masked"));
            verify(tokenRepository).save(argThat(t ->
                    "tecnico".equals(t.getUserType()) && "uuid-tec".equals(t.getUserId()) && !t.getUsed()));
            verify(mailSender).send(any(MimeMessage.class));
        }
    }
}
