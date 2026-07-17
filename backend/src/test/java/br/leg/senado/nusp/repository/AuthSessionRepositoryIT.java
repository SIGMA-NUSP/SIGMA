package br.leg.senado.nusp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.AuthSession;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;

/**
 * ITs dos 2 statements nativos de {@link AuthSessionRepository} contra Oracle real
 * (janela de inatividade com NUMTODSINTERVAL e revogação idempotente).
 *
 * Todo cenário ancora LAST_ACTIVITY no relógio do BANCO (SYSTIMESTAMP, via
 * {@link #fixarLastActivity}) — o carimbo do @PrePersist vem da JVM e a janela de
 * maxAge é avaliada pelo Oracle; ancorar num relógio só elimina flakiness. A
 * releitura de verificação é feita por SQL nativo, não por em.find().
 */
@OracleIT
class AuthSessionRepositoryIT {

    @Autowired
    private AuthSessionRepository repo;

    @Autowired
    private TestEntityManager em;

    /** Reposiciona LAST_ACTIVITY em (SYSTIMESTAMP - segundos) e limpa o cache de 1º nível. */
    private void fixarLastActivity(Long sessaoId, int segundosAtras) {
        CenarioFactory.fixarTimestamp(em.getEntityManager(), "PES_AUTH_SESSION", "LAST_ACTIVITY",
                sessaoId, segundosAtras);
    }

    private Timestamp lerLastActivity(Long sessaoId) {
        return (Timestamp) em.getEntityManager()
                .createNativeQuery("SELECT LAST_ACTIVITY FROM PES_AUTH_SESSION WHERE ID = :id")
                .setParameter("id", sessaoId)
                .getSingleResult();
    }

    private int lerRevoked(Long sessaoId) {
        Number revoked = (Number) em.getEntityManager()
                .createNativeQuery("SELECT REVOKED FROM PES_AUTH_SESSION WHERE ID = :id")
                .setParameter("id", sessaoId)
                .getSingleResult();
        return revoked.intValue();
    }

    @Nested
    @DisplayName("touchSession")
    class TouchSession {

        @Test
        @DisplayName("touchSession — sessão viva dentro do maxAge retorna 1, LAST_ACTIVITY avança e a outra sessão do usuário fica intacta")
        void touchSession_sessaoVivaRetorna1ELastActivityAvanca() {
            String userId = UUID.randomUUID().toString();
            AuthSession sessao = CenarioFactory.novaSessao(em.getEntityManager(), userId);
            AuthSession controle = CenarioFactory.novaSessao(em.getEntityManager(), userId);
            fixarLastActivity(sessao.getId(), 60);
            fixarLastActivity(controle.getId(), 60);
            Timestamp antes = lerLastActivity(sessao.getId());
            Timestamp controleAntes = lerLastActivity(controle.getId());

            int tocadas = repo.touchSession(sessao.getId(), userId, 3600);

            assertEquals(1, tocadas);
            Timestamp depois = lerLastActivity(sessao.getId());
            assertTrue(depois.after(antes),
                    "LAST_ACTIVITY deveria avançar de SYSTIMESTAMP-60s para SYSTIMESTAMP");
            assertEquals(controleAntes, lerLastActivity(controle.getId()),
                    "a outra sessão do mesmo usuário não pode ser tocada (predicado ID = :sid)");
        }

        @Test
        @DisplayName("touchSession — userId de outro usuário retorna 0 e não altera LAST_ACTIVITY")
        void touchSession_userIdDeOutroUsuarioRetorna0() {
            String userId = UUID.randomUUID().toString();
            AuthSession sessao = CenarioFactory.novaSessao(em.getEntityManager(), userId);
            fixarLastActivity(sessao.getId(), 10);
            Timestamp antes = lerLastActivity(sessao.getId());

            int tocadas = repo.touchSession(sessao.getId(), UUID.randomUUID().toString(), 3600);

            assertEquals(0, tocadas,
                    "sessão viva de OUTRO usuário não pode casar o predicado USER_ID = :userId");
            assertEquals(antes, lerLastActivity(sessao.getId()),
                    "LAST_ACTIVITY não pode ser tocado por chamada com userId de outro usuário");
        }

        @Test
        @DisplayName("touchSession — sessão revogada retorna 0 e não altera LAST_ACTIVITY")
        void touchSession_sessaoRevogadaRetorna0() {
            String userId = UUID.randomUUID().toString();
            AuthSession sessao = CenarioFactory.novaSessao(em.getEntityManager(), userId);
            sessao.setRevoked(true);
            em.getEntityManager().flush();
            // LAST_ACTIVITY recente prova que o 0 vem do REVOKED, não da janela
            fixarLastActivity(sessao.getId(), 10);
            Timestamp antes = lerLastActivity(sessao.getId());

            int tocadas = repo.touchSession(sessao.getId(), userId, 3600);

            assertEquals(0, tocadas,
                    "sessão revogada não pode casar o predicado REVOKED = 0");
            assertEquals(antes, lerLastActivity(sessao.getId()),
                    "LAST_ACTIVITY não pode ser tocado numa sessão revogada");
        }

        @Test
        @DisplayName("touchSession — além do maxAge retorna 0 (NUMTODSINTERVAL real) e não altera LAST_ACTIVITY")
        void touchSession_alemDoMaxAgeRetorna0() {
            String userId = UUID.randomUUID().toString();
            AuthSession sessao = CenarioFactory.novaSessao(em.getEntityManager(), userId);
            fixarLastActivity(sessao.getId(), 120);
            Timestamp antes = lerLastActivity(sessao.getId());

            int tocadas = repo.touchSession(sessao.getId(), userId, 60);

            assertEquals(0, tocadas,
                    "LAST_ACTIVITY a 120s com maxAge de 60s deveria cair fora da janela NUMTODSINTERVAL");
            assertEquals(antes, lerLastActivity(sessao.getId()),
                    "LAST_ACTIVITY não pode ser tocado numa sessão expirada");
        }
    }

    @Nested
    @DisplayName("revokeSession")
    class RevokeSession {

        @Test
        @DisplayName("revokeSession — só o dono revoga (1), a repetição é no-op (0) e a outra sessão do usuário sobrevive")
        void revokeSession_primeiraRetorna1SegundaRetorna0() {
            String userId = UUID.randomUUID().toString();
            AuthSession sessao = CenarioFactory.novaSessao(em.getEntityManager(), userId);
            AuthSession controle = CenarioFactory.novaSessao(em.getEntityManager(), userId);

            assertEquals(0, repo.revokeSession(sessao.getId(), UUID.randomUUID().toString()),
                    "userId de outro usuário não pode revogar a sessão");
            assertEquals(1, repo.revokeSession(sessao.getId(), userId));
            em.clear();
            assertEquals(1, lerRevoked(sessao.getId()));
            assertEquals(0, lerRevoked(controle.getId()),
                    "a outra sessão do mesmo usuário não pode ser revogada (predicado ID = :sid)");

            assertEquals(0, repo.revokeSession(sessao.getId(), userId),
                    "segunda revogação não deveria casar o predicado REVOKED = 0");
        }
    }
}
