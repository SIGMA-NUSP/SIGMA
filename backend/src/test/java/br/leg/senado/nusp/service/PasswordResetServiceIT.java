package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PasswordResetToken;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.PasswordResetTokenRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;

/**
 * IT do lookup do "esqueci minha senha" ({@code findUserByUsername}, via {@code requestReset})
 * contra Oracle real. O {@link PasswordResetServiceTest} casa o SQL por fragmento em mocks: prova
 * que a query MENCIONA as três tabelas, não que o Oracle ENCONTRA o usuário — nem se a comparação
 * distingue caixa; quem responde isso é o banco, daí este IT.
 *
 * <p>O SUT é construído à mão (EntityManager real do slice; repositório de token, encoder e
 * mailSender mockados — fora do alvo).
 */
@OracleIT
class PasswordResetServiceIT {

    @Autowired
    private TestEntityManager em;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    private PasswordResetTokenRepository tokenRepository;
    private PasswordResetService service;

    @BeforeEach
    void montarSut() {
        tokenRepository = mock(PasswordResetTokenRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        service = new PasswordResetService(tokenRepository, encoder, mailSender, emReal());
        ReflectionTestUtils.setField(service, "tokenTtlMinutes", 30);
        ReflectionTestUtils.setField(service, "baseUrl", "https://senado-nusp.cloud");
        ReflectionTestUtils.setField(service, "fromEmail", "no-reply@senado-nusp.cloud");

        // O envio real é irrelevante aqui; sem uma MimeMessage de verdade o helper recusa a mensagem.
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    /** Perfil gravado no token do reset — é ele que decide, depois, a tabela do UPDATE do hash. */
    private String perfilDoTokenGerado() {
        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(captor.capture());
        return captor.getValue().getUserType();
    }

    @Test
    @DisplayName("requestReset acha o usuário com o username digitado em MAIÚSCULAS, nos 3 papéis")
    void requestReset_caixaDiferenteEncontra() {
        // O lookup do reset precisa ser case-insensitive como o login: sem isso, quem entrasse com
        // "Fulano.Silva" logava e, no "esqueci minha senha", ouvia "Username não encontrado".
        // Os usernames são gravados sempre em minúsculas (setters das 3 entidades).
        Operador operador = CenarioFactory.novoOperador(emReal());
        emReal().flush();

        Map<String, String> resultado = service.requestReset(operador.getUsername().toUpperCase());

        assertNotNull(resultado, "LOWER(USERNAME) = LOWER(:username) — a caixa do que se digita não importa");
        assertNotNull(resultado.get("email_masked"), "o e-mail mascarado volta, como no caminho feliz");
        assertEquals("operador", perfilDoTokenGerado());
    }

    @Test
    @DisplayName("o administrador em maiúsculas também é encontrado (1º ramo do UNION ALL)")
    void requestReset_administradorCaixaDiferente() {
        Administrador admin = CenarioFactory.novoAdministrador(emReal());
        emReal().flush();

        assertNotNull(service.requestReset(admin.getUsername().toUpperCase()));
        assertEquals("administrador", perfilDoTokenGerado());
    }

    @Test
    @DisplayName("o técnico em maiúsculas é encontrado (3º ramo do UNION ALL)")
    void requestReset_tecnicoCaixaDiferente() {
        Tecnico tecnico = CenarioFactory.novoTecnico(emReal());
        emReal().flush();

        assertNotNull(service.requestReset(tecnico.getUsername().toUpperCase()),
                "antes da correção o técnico não era encontrado nem com a caixa certa");
        assertEquals("tecnico", perfilDoTokenGerado(),
                "o perfil do token decide a tabela do UPDATE do hash — tem de ser 'tecnico'");
    }

    @Test
    @DisplayName("requestReset — username inexistente segue devolvendo null, sem token nem e-mail")
    void requestReset_inexistenteContinuaNull() {
        CenarioFactory.novoOperador(emReal()); // ruído: a tabela não está vazia
        emReal().flush();

        assertNull(service.requestReset("nao.existe." + UUID.randomUUID()));

        verify(tokenRepository, never()).save(any());
    }
}
