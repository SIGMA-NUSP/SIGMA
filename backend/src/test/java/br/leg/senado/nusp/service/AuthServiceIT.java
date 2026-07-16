package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;

/**
 * ITs dos 4 statements nativos de {@link AuthService} contra Oracle real — o
 * UNION ALL do login, a tabela interpolada por papel (isSenhaProvisoria /
 * changePassword) e o SERVIDOR_PUBLICO por trás do temFolhaPonto.
 *
 * O SUT é construído à mão (EntityManager e repositories reais do slice;
 * PasswordEncoder e JwtTokenProvider mockados — fora do alvo). Os campos
 * {@code @Value} ficam sem valor: nenhum dos métodos exercitados aqui os usa.
 *
 * SENHA_PROVISORIA existe nas 3 tabelas, mas só {@link Administrador} a mapeia —
 * em PES_OPERADOR/PES_TECNICO o valor é semeado por UPDATE nativo
 * ({@link #fixarSenhaProvisoria}). Toda releitura de verificação também é nativa:
 * ela precisa enxergar o que o SQL do SUT gravou, não o cache de 1º nível.
 */
@OracleIT
class AuthServiceIT {

    private static final String SENHA_NOVA = "senha-nova-do-teste";
    private static final String HASH_NOVO = "$2b$12$hash.novo.produzido.pelo.encoder.mockado.0123456789";

    @Autowired
    private TestEntityManager em;
    @Autowired
    private OperadorRepository operadorRepository;
    @Autowired
    private AdministradorRepository administradorRepository;
    @Autowired
    private TecnicoRepository tecnicoRepository;
    @Autowired
    private AuthSessionRepository authSessionRepository;

    private PasswordEncoder passwordEncoder;
    private AuthService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = mock(PasswordEncoder.class);
        service = new AuthService(operadorRepository, administradorRepository, tecnicoRepository,
                authSessionRepository, passwordEncoder, emReal(), mock(JwtTokenProvider.class));
    }

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    /** PES_OPERADOR/PES_TECNICO não mapeiam SENHA_PROVISORIA na entidade — só o SQL alcança a coluna. */
    private void fixarSenhaProvisoria(String tabela, String id, int valor) {
        int linhas = emReal()
                .createNativeQuery("UPDATE " + tabela + " SET SENHA_PROVISORIA = :v WHERE ID = :id")
                .setParameter("v", valor)
                .setParameter("id", id)
                .executeUpdate();
        assertEquals(1, linhas, "seed de SENHA_PROVISORIA em " + tabela + " deveria afetar 1 linha");
        emReal().clear();
    }

    private String lerPasswordHash(String tabela, String id) {
        return (String) emReal()
                .createNativeQuery("SELECT PASSWORD_HASH FROM " + tabela + " WHERE ID = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    private int lerSenhaProvisoria(String tabela, String id) {
        Number valor = (Number) emReal()
                .createNativeQuery("SELECT SENHA_PROVISORIA FROM " + tabela + " WHERE ID = :id")
                .setParameter("id", id)
                .getSingleResult();
        return valor.intValue();
    }

    private Timestamp lerAtualizadoEm(String tabela, String id) {
        return (Timestamp) emReal()
                .createNativeQuery("SELECT ATUALIZADO_EM FROM " + tabela + " WHERE ID = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    @Nested
    @DisplayName("findUserForLogin")
    class FindUserForLogin {

        /** Falha com mensagem (e não com NPE na leitura do Map) quando o usuário não é encontrado. */
        private Map<String, String> login(String usuario) {
            Map<String, String> user = service.findUserForLogin(usuario);
            assertNotNull(user, "findUserForLogin deveria encontrar \"" + usuario + "\"");
            return user;
        }

        @Test
        @DisplayName("findUserForLogin — acha os 3 papéis por USERNAME, cada um com o próprio perfil")
        void findUserForLogin_porUsernameNosTresPapeis() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal());

            assertEquals("administrador", login(admin.getUsername()).get("perfil"));
            assertEquals("operador", login(operador.getUsername()).get("perfil"));
            assertEquals("tecnico", login(tecnico.getUsername()).get("perfil"));
        }

        /**
         * Guarda de comportamento do §3.3 (F30/C15). A colação da sessão passou a ser
         * {@code NLS_SORT=BINARY_AI} — mas SÓ o {@code NLS_SORT} (ordenação); o {@code NLS_COMP}
         * segue BINARY, e é ele quem governa este {@code =}. Se alguém "simplificar" o
         * {@code connection-init-sql} para {@code NLS_COMP=LINGUISTIC}, TODA igualdade de texto do
         * banco fica insensível a acento — e a autenticação passa a aceitar "dóuglas" como
         * "douglas". Sem este teste, isso não derruba nada (medido: a mutação
         * {@code NLS_COMP=LINGUISTIC} deixou este IT inteiro verde).
         *
         * <p>A CAIXA não entra aqui de propósito: o login é case-insensitive por decisão antiga
         * (F18 — o SQL usa {@code LOWER(USERNAME) = LOWER(:usuario)}), então "DOUGLAS" entrar já é o
         * comportamento correto. O que a colação não pode mudar é o ACENTO.
         */
        @Test
        @DisplayName("findUserForLogin — o ACENTO segue distinguindo: 'dóuglas' NÃO entra como 'douglas' (§3.3)")
        void findUserForLogin_acentoNaoEhIgnoradoNaAutenticacao() {
            Operador operador = CenarioFactory.novoOperador(emReal(), "Douglas Antunes", "douglas");
            String username = operador.getUsername();          // "douglas.<n>"

            assertNotNull(service.findUserForLogin(username), "o próprio username tem de entrar");
            assertNotNull(service.findUserForLogin(username.toUpperCase(Locale.ROOT)),
                    "a caixa é ignorada por decisão antiga (F18: LOWER = LOWER) — isto NÃO mudou");
            assertNull(service.findUserForLogin(username.replace("douglas", "dóuglas")),
                    "mas o acento continua distinguindo — se este assert cair, o NLS_COMP virou"
                            + " LINGUISTIC e a identidade textual do sistema afrouxou junto com a busca");
        }

        @Test
        @DisplayName("findUserForLogin — acha os 3 papéis por EMAIL (segundo ramo do OR), com o id correto")
        void findUserForLogin_porEmailNosTresPapeis() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal());

            assertEquals(admin.getId(), login(admin.getEmail()).get("id"));
            assertEquals(operador.getId(), login(operador.getEmail()).get("id"));
            assertEquals(tecnico.getId(), login(tecnico.getEmail()).get("id"));
        }

        @Test
        @DisplayName("findUserForLogin — layout do Map: 7 chaves, na ordem de colunas do SELECT, com senha_provisoria \"1\"")
        void findUserForLogin_layoutDoMapComSenhaProvisoria() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            admin.setSenhaProvisoria(true);
            emReal().flush();

            Map<String, String> user = service.findUserForLogin(admin.getUsername());

            assertNotNull(user);
            assertEquals(7, user.size(), "o Map do login tem exatamente 7 chaves");
            assertEquals("administrador", user.get("perfil"));
            assertEquals(admin.getId(), user.get("id"));
            assertEquals(admin.getNomeCompleto(), user.get("nome_completo"));
            assertEquals(admin.getUsername(), user.get("username"));
            assertEquals(admin.getEmail(), user.get("email"));
            assertEquals(admin.getPasswordHash(), user.get("password_hash"));
            assertEquals("1", user.get("senha_provisoria"),
                    "SENHA_PROVISORIA = 1 (NUMBER) tem de virar a string \"1\"");
        }

        @Test
        @DisplayName("findUserForLogin — SENHA_PROVISORIA = 0 vira a string \"0\" (operador, coluna não mapeada)")
        void findUserForLogin_senhaProvisoriaZeroViraString0() {
            Operador operador = CenarioFactory.novoOperador(emReal());
            fixarSenhaProvisoria("PES_OPERADOR", operador.getId(), 0);

            assertEquals("0", login(operador.getUsername()).get("senha_provisoria"));
        }

        @Test
        @DisplayName("findUserForLogin — username duplicado entre tabelas: o UNION ALL + FETCH FIRST devolve o administrador")
        void findUserForLogin_precedenciaAdministradorSobreOperador() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            operador.setUsername(admin.getUsername()); // UNIQUE é por tabela — a colisão entre tabelas é possível
            emReal().flush();

            Map<String, String> user = login(admin.getUsername());

            assertEquals("administrador", user.get("perfil"),
                    "o 1º ramo do UNION ALL (PES_ADMINISTRADOR) precede o 2º");
            assertEquals(admin.getId(), user.get("id"));
        }

        @Test
        @DisplayName("findUserForLogin — username duplicado operador/técnico: o operador precede o técnico")
        void findUserForLogin_precedenciaOperadorSobreTecnico() {
            Operador operador = CenarioFactory.novoOperador(emReal());
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal());
            tecnico.setUsername(operador.getUsername());
            emReal().flush();

            Map<String, String> user = login(operador.getUsername());

            assertEquals("operador", user.get("perfil"),
                    "o 2º ramo do UNION ALL (PES_OPERADOR) precede o 3º (PES_TECNICO)");
            assertEquals(operador.getId(), user.get("id"));
        }

        @Test
        @DisplayName("findUserForLogin — usuário inexistente devolve null")
        void findUserForLogin_inexistenteRetornaNull() {
            CenarioFactory.novoOperador(emReal()); // ruído: a tabela não está vazia
            assertNull(service.findUserForLogin("nao.existe." + UUID.randomUUID()));
        }

        @Test
        @DisplayName("corrige F18 — login é case-insensitive: username/email em maiúsculas encontram o mesmo usuário")
        void findUserForLogin_caixaDiferenteEncontra() {
            // §5 do plano, F18: as colunas eram citext no PostgreSQL legado; no port Oracle a
            // normalização sobrou só nos setters (escrita) e o SQL comparava com "=" puro — quem
            // digitasse maiúscula não logava. O SQL agora aplica LOWER() nos DOIS lados.
            Operador operador = CenarioFactory.novoOperador(emReal());

            Map<String, String> porUsername = service.findUserForLogin(operador.getUsername().toUpperCase());
            assertNotNull(porUsername, "LOWER(USERNAME) = LOWER(:usuario) — a caixa do que se digita não importa");
            assertEquals(operador.getId(), porUsername.get("id"));

            Map<String, String> porEmail = service.findUserForLogin(operador.getEmail().toUpperCase());
            assertNotNull(porEmail, "LOWER(EMAIL) = LOWER(:usuario) — idem para o e-mail");
            assertEquals(operador.getId(), porEmail.get("id"));

            assertNotNull(service.findUserForLogin(operador.getUsername()),
                    "o mesmo usuário em minúsculas continua sendo encontrado");
        }
    }

    @Nested
    @DisplayName("isSenhaProvisoria")
    class IsSenhaProvisoria {

        @Test
        @DisplayName("isSenhaProvisoria — true nos 3 papéis (tabela interpolada por tableForRole)")
        void isSenhaProvisoria_trueNosTresPapeis() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            admin.setSenhaProvisoria(true);
            emReal().flush();
            Operador operador = CenarioFactory.novoOperador(emReal());
            fixarSenhaProvisoria("PES_OPERADOR", operador.getId(), 1);
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal());
            fixarSenhaProvisoria("PES_TECNICO", tecnico.getId(), 1);

            assertTrue(service.isSenhaProvisoria(admin.getId(), "administrador"));
            assertTrue(service.isSenhaProvisoria(operador.getId(), "operador"));
            assertTrue(service.isSenhaProvisoria(tecnico.getId(), "tecnico"));
        }

        @Test
        @DisplayName("isSenhaProvisoria — false quando a coluna é 0, e o papel escolhe a tabela (id de operador lido como administrador → false)")
        void isSenhaProvisoria_falseComColunaZeroEPapelEscolheATabela() {
            Operador operador = CenarioFactory.novoOperador(emReal());
            fixarSenhaProvisoria("PES_OPERADOR", operador.getId(), 1);
            Administrador admin = CenarioFactory.novoAdministrador(emReal()); // default: 0
            emReal().flush();

            assertFalse(service.isSenhaProvisoria(admin.getId(), "administrador"),
                    "SENHA_PROVISORIA = 0 no administrador");
            assertFalse(service.isSenhaProvisoria(operador.getId(), "administrador"),
                    "o id do operador não existe em PES_ADMINISTRADOR — a tabela vem do papel, não do id");
            assertTrue(service.isSenhaProvisoria(operador.getId(), "operador"),
                    "o mesmo id, com o papel certo, acha a linha com SENHA_PROVISORIA = 1");
        }

        @Test
        @DisplayName("isSenhaProvisoria — papel desconhecido devolve false sem consultar tabela alguma")
        void isSenhaProvisoria_papelDesconhecidoRetornaFalse() {
            Operador operador = CenarioFactory.novoOperador(emReal());
            fixarSenhaProvisoria("PES_OPERADOR", operador.getId(), 1);

            assertFalse(service.isSenhaProvisoria(operador.getId(), "supervisor"),
                    "tableForRole devolve null → curto-circuito antes do SQL");
        }

        @Test
        @DisplayName("isSenhaProvisoria — id inexistente devolve false (resultado vazio)")
        void isSenhaProvisoria_idInexistenteRetornaFalse() {
            assertFalse(service.isSenhaProvisoria(UUID.randomUUID().toString(), "operador"));
        }
    }

    @Nested
    @DisplayName("temFolhaPonto / isServidorPublico")
    class TemFolhaPonto {

        @Test
        @DisplayName("temFolhaPonto — administrador servidor público (SERVIDOR_PUBLICO = 1) NÃO tem folha de ponto")
        void temFolhaPonto_administradorServidorPublicoNaoTem() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal()); // default da entidade: true

            assertFalse(service.temFolhaPonto("administrador", admin.getId()));
        }

        @Test
        @DisplayName("temFolhaPonto — administrador não-servidor (SERVIDOR_PUBLICO = 0) tem folha de ponto")
        void temFolhaPonto_administradorNaoServidorTem() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            admin.setServidorPublico(false);
            emReal().flush();

            assertTrue(service.temFolhaPonto("administrador", admin.getId()));
        }

        @Test
        @DisplayName("temFolhaPonto — operador e técnico têm folha sempre, sem ir ao banco (id inexistente basta)")
        void temFolhaPonto_operadorETecnicoSempreTem() {
            String idFantasma = UUID.randomUUID().toString();

            assertTrue(service.temFolhaPonto("operador", idFantasma));
            assertTrue(service.temFolhaPonto("tecnico", idFantasma));
            assertFalse(service.temFolhaPonto("supervisor", idFantasma), "papel desconhecido → false");
        }

        @Test
        @DisplayName("temFolhaPonto — administrador inexistente devolve true (isServidorPublico dá false no resultado vazio)")
        void temFolhaPonto_administradorInexistenteRetornaTrue() {
            // Borda do SELECT: sem linha, isServidorPublico devolve false e o admin "ganha" folha
            // de ponto. Inalcançável hoje (o id vem do JWT de um admin existente).
            assertTrue(service.temFolhaPonto("administrador", UUID.randomUUID().toString()));
        }
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        @Test
        @DisplayName("changePassword — operador: grava o hash novo, zera SENHA_PROVISORIA, avança ATUALIZADO_EM e não toca nas outras tabelas")
        void changePassword_operadorGravaNaTabelaCertaENaoVazaParaOutras() {
            Operador operador = CenarioFactory.novoOperador(emReal());
            fixarSenhaProvisoria("PES_OPERADOR", operador.getId(), 1);
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            admin.setSenhaProvisoria(true);
            emReal().flush();
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal());
            fixarSenhaProvisoria("PES_TECNICO", tecnico.getId(), 1);
            String hashAdminAntes = lerPasswordHash("PES_ADMINISTRADOR", admin.getId());
            String hashTecnicoAntes = lerPasswordHash("PES_TECNICO", tecnico.getId());
            CenarioFactory.fixarTimestamp(emReal(), "PES_OPERADOR", "ATUALIZADO_EM", operador.getId(), 60);
            Timestamp atualizadoAntes = lerAtualizadoEm("PES_OPERADOR", operador.getId());
            when(passwordEncoder.encode(SENHA_NOVA)).thenReturn(HASH_NOVO);

            assertTrue(service.changePassword(operador.getId(), "operador", SENHA_NOVA));

            emReal().clear();
            assertEquals(HASH_NOVO, lerPasswordHash("PES_OPERADOR", operador.getId()));
            assertEquals(0, lerSenhaProvisoria("PES_OPERADOR", operador.getId()));
            assertTrue(lerAtualizadoEm("PES_OPERADOR", operador.getId()).after(atualizadoAntes),
                    "ATUALIZADO_EM = SYSTIMESTAMP deveria avançar em relação à âncora de 60s atrás");
            assertEquals(hashAdminAntes, lerPasswordHash("PES_ADMINISTRADOR", admin.getId()),
                    "a troca do operador não pode alcançar PES_ADMINISTRADOR");
            assertEquals(1, lerSenhaProvisoria("PES_ADMINISTRADOR", admin.getId()));
            assertEquals(hashTecnicoAntes, lerPasswordHash("PES_TECNICO", tecnico.getId()),
                    "a troca do operador não pode alcançar PES_TECNICO");
            assertEquals(1, lerSenhaProvisoria("PES_TECNICO", tecnico.getId()));
        }

        @Test
        @DisplayName("changePassword — administrador: grava em PES_ADMINISTRADOR e zera a SENHA_PROVISORIA mapeada")
        void changePassword_administradorGravaNaTabelaDoPapel() {
            Administrador admin = CenarioFactory.novoAdministrador(emReal());
            admin.setSenhaProvisoria(true);
            emReal().flush();
            when(passwordEncoder.encode(SENHA_NOVA)).thenReturn(HASH_NOVO);

            assertTrue(service.changePassword(admin.getId(), "administrador", SENHA_NOVA));

            emReal().clear();
            assertEquals(HASH_NOVO, lerPasswordHash("PES_ADMINISTRADOR", admin.getId()));
            assertEquals(0, lerSenhaProvisoria("PES_ADMINISTRADOR", admin.getId()));
        }

        @Test
        @DisplayName("changePassword — técnico: grava em PES_TECNICO")
        void changePassword_tecnicoGravaNaTabelaDoPapel() {
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal());
            fixarSenhaProvisoria("PES_TECNICO", tecnico.getId(), 1);
            when(passwordEncoder.encode(SENHA_NOVA)).thenReturn(HASH_NOVO);

            assertTrue(service.changePassword(tecnico.getId(), "tecnico", SENHA_NOVA));

            emReal().clear();
            assertEquals(HASH_NOVO, lerPasswordHash("PES_TECNICO", tecnico.getId()));
            assertEquals(0, lerSenhaProvisoria("PES_TECNICO", tecnico.getId()));
        }

        @Test
        @DisplayName("changePassword — papel desconhecido devolve false sem sequer chamar o encoder")
        void changePassword_papelDesconhecidoRetornaFalse() {
            Operador operador = CenarioFactory.novoOperador(emReal());
            String hashOriginal = operador.getPasswordHash();

            assertFalse(service.changePassword(operador.getId(), "supervisor", SENHA_NOVA));

            emReal().clear();
            assertEquals(hashOriginal, lerPasswordHash("PES_OPERADOR", operador.getId()));
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("changePassword — id inexistente devolve false (UPDATE afeta 0 linhas)")
        void changePassword_idInexistenteRetornaFalse() {
            when(passwordEncoder.encode(SENHA_NOVA)).thenReturn(HASH_NOVO);

            assertFalse(service.changePassword(UUID.randomUUID().toString(), "operador", SENHA_NOVA));
        }
    }
}
