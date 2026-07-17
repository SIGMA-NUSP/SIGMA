package br.leg.senado.nusp.service;

import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unitário de {@link AuthService}: os métodos puros e baratos —
 * {@code canEditObs*}/{@code isMaster}, {@code temFolhaPonto}, {@code getFotoUrl} e
 * {@code verifyPassword}.
 *
 * <p>{@code findUserForLogin} não é testado aqui: com EntityManager mockado o SQL
 * passaria sem verificação — a cobertura real do UNION ALL/precedência está no
 * {@code AuthServiceIT}, contra Oracle real. Em {@code getFotoUrl}, os ramos são
 * stubados com URL <b>não-vazia</b>: o default do Mockito ({@code Optional.empty()}
 * → {@code ""}) coincidiria com o valor esperado e produziria um falso verde.
 * {@code isSenhaProvisoria}/{@code changePassword}/{@code createSession}/
 * {@code revokeSession}/{@code validarHtmlGuard} ficam fora deste unitário.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private OperadorRepository operadorRepository;
    @Mock private AdministradorRepository administradorRepository;
    @Mock private TecnicoRepository tecnicoRepository;
    @Mock private AuthSessionRepository authSessionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EntityManager entityManager;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthService service;

    @BeforeEach
    void configurarUsernamesAdministrativos() {
        // @Value não é injetado por @InjectMocks — semeado por reflexão.
        ReflectionTestUtils.setField(service, "supervisorUsername", "supervisor.teste");
        ReflectionTestUtils.setField(service, "chefeUsername", "chefe.teste");
        ReflectionTestUtils.setField(service, "masterUsername", "master.teste");
    }

    // ══ verifyPassword ════════════════════════════════════════════════════════

    @Test
    @DisplayName("verifyPassword — senha correta retorna true")
    void verifyPassword_correct() {
        when(passwordEncoder.matches("Teste@2026", "$2a$10$hash")).thenReturn(true);
        assertTrue(service.verifyPassword("Teste@2026", "$2a$10$hash"));
    }

    @Test
    @DisplayName("verifyPassword — senha incorreta retorna false")
    void verifyPassword_incorrect() {
        when(passwordEncoder.matches("errada", "$2a$10$hash")).thenReturn(false);
        assertFalse(service.verifyPassword("errada", "$2a$10$hash"));
    }

    // ══ getFotoUrl — falso verde corrigido + ramo técnico ═════════════════════

    @Nested
    @DisplayName("getFotoUrl")
    class GetFotoUrl {

        @Test
        @DisplayName("operador — devolve a URL do repositório de operador")
        void operador_comFoto() {
            when(operadorRepository.findFotoUrlById("uuid-op"))
                    .thenReturn(Optional.of("/files/operadores/foto.jpg"));
            assertEquals("/files/operadores/foto.jpg", service.getFotoUrl("uuid-op", "operador"));
        }

        @Test
        @DisplayName("técnico — devolve a URL do repositório de técnico (ramo antes null no SUT)")
        void tecnico_comFoto() {
            when(tecnicoRepository.findFotoUrlById("uuid-tec"))
                    .thenReturn(Optional.of("/files/tecnicos/foto.png"));
            assertEquals("/files/tecnicos/foto.png", service.getFotoUrl("uuid-tec", "tecnico"));
        }

        @Test
        @DisplayName("administrador com foto — devolve a URL (falso verde eliminado: stub NÃO-vazio asserido)")
        void admin_comFoto() {
            // URL não-vazia, diferente do default do Mockito.
            // Mutar este stub para outro valor DEVE quebrar a asserção abaixo.
            when(administradorRepository.findFotoUrlById("uuid-adm"))
                    .thenReturn(Optional.of("/files/administradores/adm.jpg"));
            assertEquals("/files/administradores/adm.jpg", service.getFotoUrl("uuid-adm", "administrador"));
        }

        @Test
        @DisplayName("administrador sem foto — repositório vazio devolve string vazia")
        void admin_semFoto() {
            when(administradorRepository.findFotoUrlById("uuid-adm")).thenReturn(Optional.empty());
            assertEquals("", service.getFotoUrl("uuid-adm", "administrador"));
            // O esperado "" coincide com o .orElse("") do default do Mockito; sem este
            // verify, o teste passaria mesmo se getFotoUrl devolvesse "" ignorando o repositório.
            verify(administradorRepository).findFotoUrlById("uuid-adm");
        }
    }

    // ══ temFolhaPonto — completo ══════════════════════════════════════════════

    @Nested
    @DisplayName("temFolhaPonto")
    class TemFolhaPonto {

        @Test
        @DisplayName("operador — sempre tem folha, sem tocar o banco")
        void operador_semBanco() {
            assertTrue(service.temFolhaPonto("operador", "uuid-1"));
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("técnico — sempre tem folha, sem tocar o banco")
        void tecnico_semBanco() {
            assertTrue(service.temFolhaPonto("tecnico", "uuid-1"));
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("papel desconhecido — não tem folha, sem tocar o banco")
        void roleDesconhecido_semBanco() {
            assertFalse(service.temFolhaPonto("visitante", "uuid-1"));
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("administrador servidor público (SP=1) — NÃO tem folha")
        void adminServidorPublico_naoTem() {
            Query q = mockServidorPublico(1);
            assertFalse(service.temFolhaPonto("administrador", "uuid-adm"));
            verify(entityManager).createNativeQuery(contains("SERVIDOR_PUBLICO"));
            verify(q).setParameter("id", "uuid-adm"); // o id certo chega ao bind
        }

        @Test
        @DisplayName("administrador não-servidor (SP=0) — tem folha")
        void adminNaoServidor_tem() {
            Query q = mockServidorPublico(0);
            assertTrue(service.temFolhaPonto("administrador", "uuid-adm"));
            verify(entityManager).createNativeQuery(contains("SERVIDOR_PUBLICO"));
            verify(q).setParameter("id", "uuid-adm"); // o id certo chega ao bind
        }

        /** Stub disciplinado: SQL casado por fragmento e valor ≠ default do Mockito;
         *  devolve o Query para o verify do binding do id. */
        private Query mockServidorPublico(int valor) {
            Query mockQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("SERVIDOR_PUBLICO"))).thenReturn(mockQuery);
            when(mockQuery.setParameter(eq("id"), anyString())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of(valor));
            return mockQuery;
        }
    }

    // ══ canEditObs* / isMaster — puros, case-insensitive e role errado ════════

    @Nested
    @DisplayName("papéis administrativos especiais (case-insensitive, role errado)")
    class PapeisAdministrativos {

        @Test
        @DisplayName("canEditObsSupervisor — administrador com o username certo (caixa alta) é aceito")
        void supervisor_caseInsensitive() {
            assertTrue(service.canEditObsSupervisor("administrador", "SUPERVISOR.teste"));
        }

        @Test
        @DisplayName("canEditObsSupervisor — mesmo username, papel ≠ administrador é rejeitado")
        void supervisor_roleErrado() {
            assertFalse(service.canEditObsSupervisor("operador", "supervisor.teste"));
        }

        @Test
        @DisplayName("canEditObsChefe — administrador com o username certo (caixa mista) é aceito")
        void chefe_caseInsensitive() {
            assertTrue(service.canEditObsChefe("administrador", "Chefe.Teste"));
        }

        @Test
        @DisplayName("canEditObsChefe — mesmo username, papel ≠ administrador é rejeitado")
        void chefe_roleErrado() {
            assertFalse(service.canEditObsChefe("tecnico", "chefe.teste"));
        }

        @Test
        @DisplayName("isMaster — administrador com o username certo (caixa alta) é aceito")
        void master_caseInsensitive() {
            assertTrue(service.isMaster("administrador", "MASTER.TESTE"));
        }

        @Test
        @DisplayName("isMaster — mesmo username, papel ≠ administrador é rejeitado")
        void master_roleErrado() {
            assertFalse(service.isMaster("operador", "master.teste"));
        }
    }
}
