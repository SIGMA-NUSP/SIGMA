package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AuthSession;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    /**
     * Atualiza last_activity se a sessão não estiver revogada e dentro do tempo limite.
     * Equivale ao session_touch_ok() do Python.
     * Retorna 1 se a sessão foi tocada (válida), 0 se expirada/revogada.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE PES_AUTH_SESSION
            SET LAST_ACTIVITY = SYSTIMESTAMP
            WHERE ID = :sid
              AND USER_ID = :userId
              AND REVOKED = 0
              AND LAST_ACTIVITY > SYSTIMESTAMP - NUMTODSINTERVAL(:maxAge, 'SECOND')
            """, nativeQuery = true)
    int touchSession(@Param("sid") Long sid,
                     @Param("userId") String userId,
                     @Param("maxAge") int maxAge);

    /**
     * Revoga a sessão. Equivale ao revoke_session() do Python.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE PES_AUTH_SESSION
            SET REVOKED = 1
            WHERE ID = :sid
              AND USER_ID = :userId
              AND REVOKED = 0
            """, nativeQuery = true)
    int revokeSession(@Param("sid") Long sid, @Param("userId") String userId);
}
