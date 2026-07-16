package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.ChecklistItemTipo;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.TipoWidget;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * T8 — AdminCrudService (§4.8 da auditoria).
 *
 * <p>Os 3 {@code @Nested} originais (CriarOperador, CriarAdministrador, ListFormEdit) foram
 * mantidos; o estágio acrescenta os grupos de maior risco: duplicatas globais entre os 3 tipos
 * de usuário, invariante da substituição de foto, regras de turno por papel, guarda do master
 * e os toggles/operações pontuais.
 *
 * <p>Disciplina anti-falso-verde (§0.5 do plano): nenhum valor esperado vem do default do
 * Mockito. Os {@code Optional.empty()} usados como pré-condição de "sem conflito" são stubados
 * explicitamente (e todos são efetivamente chamados — ver o comentário de short-circuit em
 * {@link #semConflitoDeEmail}); onde um {@code Optional.empty()} é o próprio insumo do caso
 * (404), o stub explícito é acompanhado do {@code verify} do argumento exato.
 */
@ExtendWith(MockitoExtension.class)
class AdminCrudServiceTest {

    @Mock private OperadorRepository operadorRepo;
    @Mock private AdministradorRepository administradorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private SalaRepository salaRepo;
    @Mock private ComissaoRepository comissaoRepo;
    @Mock private ChecklistItemTipoRepository itemTipoRepo;
    @Mock private ChecklistSalaConfigRepository salaConfigRepo;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminCrudService service;

    private void setMasterUsername(String username) {
        ReflectionTestUtils.setField(service, "masterUsername", username);
    }

    /**
     * Os testes do F12 simulam a transação com {@code TransactionSynchronizationManager}, cujo estado
     * é um ThreadLocal — o surefire reusa a thread entre testes. O hook vive na classe EXTERNA (e não
     * no @Nested que hoje o usa) para que qualquer teste futuro que ative sincronização, em qualquer
     * grupo, seja limpo: um vazamento aqui apareceria como falha em cascata, difícil de rastrear.
     */
    @AfterEach
    void limparSincronizacaoDeTransacao() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ══ Helpers compartilhados ══════════════════════════════════

    /**
     * Pré-condição "e-mail livre nas 3 tabelas". {@code verificarConflitoUsernameEmail} avalia
     * {@code op || adm || tec} com short-circuit: como os 3 devolvem vazio, os 3 são chamados —
     * nenhum stub fica ocioso sob STRICT_STUBS. Em cenários de conflito, os stubs são escritos
     * à mão (só até o repo que devolve presente).
     */
    private void semConflitoDeEmail(String email) {
        when(operadorRepo.findByEmail(email)).thenReturn(Optional.empty());
        when(administradorRepo.findByEmail(email)).thenReturn(Optional.empty());
        when(tecnicoRepo.findByEmail(email)).thenReturn(Optional.empty());
    }

    /** Idem para o username. A linha {@code usernameExists} roda sempre (não é curto-circuitada pelo e-mail). */
    private void semConflitoDeUsername(String username) {
        when(operadorRepo.findByUsername(username)).thenReturn(Optional.empty());
        when(administradorRepo.findByUsername(username)).thenReturn(Optional.empty());
        when(tecnicoRepo.findByUsername(username)).thenReturn(Optional.empty());
    }

    private Operador operadorFixture() {
        Operador op = new Operador();
        op.setId("op-1");
        op.setNomeCompleto("João Antigo");
        op.setNomeExibicao("Jota");
        op.setEmail("joao@senado.leg.br");
        op.setUsername("joao");
        op.setPasswordHash("$2b$12$antigo");
        op.setTurno("M");
        op.setCargaHoraria(30);
        op.setHorarioTrabalhoInicio("08:00");
        op.setHorarioTrabalhoFim("14:00");
        op.setPlenarioPrincipal(true);
        op.setPlenarioPrincipalFixo(false);
        op.setParticipaEscala(true);
        return op;
    }

    private Tecnico tecnicoFixture() {
        Tecnico t = new Tecnico();
        t.setId("tec-1");
        t.setNomeCompleto("Maria Técnica");
        t.setEmail("maria@senado.leg.br");
        t.setUsername("maria");
        t.setTurno("M");
        t.setCargaHoraria(40);
        t.setHorarioTrabalhoInicio("09:00");
        t.setHorarioTrabalhoFim("18:00");
        return t;
    }

    private Administrador administradorFixture() {
        Administrador a = new Administrador();
        a.setId("adm-1");
        a.setNomeCompleto("Chefe Antigo");
        a.setEmail("chefe@senado.leg.br");
        a.setUsername("chefe");
        a.setServidorPublico(false);
        a.setTurno("M");
        a.setCargaHoraria(40);
        a.setHorarioTrabalhoInicio("08:00");
        a.setHorarioTrabalhoFim("17:00");
        return a;
    }

    /** {@code save} devolvendo a própria entidade — os métodos de atualização mapeiam o retorno. */
    private static <T> org.mockito.stubbing.Answer<T> echo() {
        return inv -> inv.getArgument(0);
    }

    // ══ Criar Operador ══════════════════════════════════════════

    @Nested
    @DisplayName("criarOperador")
    class CriarOperador {

        @Test
        @DisplayName("campos faltando → erro 400 com lista de missing")
        void missingFields_throws400() {
            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador(null, null, null, null, null, null, false, false));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("invalid_payload", ex.getMessage());
            assertNotNull(ex.getExtraFields());
            assertTrue(ex.getExtraFields().get("missing").toString().contains("nome_completo"));
        }

        @Test
        @DisplayName("email duplicado → erro 409")
        void duplicateEmail_throws409() {
            when(operadorRepo.findByEmail("joao@senado.leg.br")).thenReturn(Optional.of(new Operador()));
            when(operadorRepo.findByUsername("joao")).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("João", "Joãozinho", "joao@senado.leg.br", "joao", "123456", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertTrue(ex.getExtraFields().get("message").toString().contains("E-mail já cadastrado"));
        }

        @Test
        @DisplayName("username duplicado → erro 409")
        void duplicateUsername_throws409() {
            when(operadorRepo.findByEmail("novo@senado.leg.br")).thenReturn(Optional.empty());
            when(operadorRepo.findByUsername("joao")).thenReturn(Optional.of(new Operador()));

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("João", "Joãozinho", "novo@senado.leg.br", "joao", "123456", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertTrue(ex.getExtraFields().get("message").toString().contains("usuário já cadastrado"));
        }

        @Test
        @DisplayName("dados válidos → cria operador e retorna mapa")
        void validData_success() {
            when(operadorRepo.findByEmail("novo@senado.leg.br")).thenReturn(Optional.empty());
            when(operadorRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("Senha@2026")).thenReturn("$2a$10$hash");

            Operador saved = new Operador();
            saved.setId("uuid-new");
            saved.setNomeCompleto("Novo Operador");
            saved.setNomeExibicao("Novo");
            saved.setEmail("novo@senado.leg.br");
            saved.setUsername("novo");
            when(operadorRepo.save(any())).thenReturn(saved);

            Map<String, Object> result = service.criarOperador(
                    "Novo Operador", "Novo", "novo@senado.leg.br", "novo", "Senha@2026", null, false, false);

            assertEquals("uuid-new", result.get("id"));
            assertEquals("Novo Operador", result.get("nome_completo"));
            verify(passwordEncoder).encode("Senha@2026");
            // O Map de retorno é montado a partir do objeto DEVOLVIDO por save() — asserir só
            // sobre ele seria circular. O verify abaixo inspeciona a entidade que o SUT construiu.
            verify(operadorRepo).save(argThat(o ->
                    "Novo Operador".equals(o.getNomeCompleto())
                            && "Novo".equals(o.getNomeExibicao())
                            && "novo@senado.leg.br".equals(o.getEmail())
                            && "novo".equals(o.getUsername())
                            && "$2a$10$hash".equals(o.getPasswordHash())
                            && o.getFotoUrl() == null));
        }
    }

    // ══ Criar Administrador ═════════════════════════════════════

    @Nested
    @DisplayName("criarAdministrador")
    class CriarAdministrador {

        @Test
        @DisplayName("usuário sem permissão → erro 403")
        void forbidden_throws403() {
            setMasterUsername("douglas.antunes");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarAdministrador("Admin", "admin@senado.leg.br", "admin", "123", "outro.usuario"));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("master user com dados válidos → cria admin")
        void masterUser_success() {
            setMasterUsername("douglas.antunes");
            when(administradorRepo.findByEmail("new@senado.leg.br")).thenReturn(Optional.empty());
            when(administradorRepo.findByUsername("newadmin")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("Admin@2026")).thenReturn("$2a$10$hash");

            Administrador saved = new Administrador();
            saved.setId("uuid-admin");
            saved.setNomeCompleto("Novo Admin");
            saved.setEmail("new@senado.leg.br");
            saved.setUsername("newadmin");
            when(administradorRepo.save(any())).thenReturn(saved);

            Map<String, Object> result = service.criarAdministrador(
                    "Novo Admin", "new@senado.leg.br", "newadmin", "Admin@2026", "douglas.antunes");

            assertEquals("uuid-admin", result.get("id"));
            assertEquals("newadmin", result.get("username"));
            // Idem CriarOperador.validData_success: o Map espelha o retorno do stub, não a
            // entidade construída. `senhaProvisoria=true` só é observável por aqui.
            verify(administradorRepo).save(argThat(a ->
                    "Novo Admin".equals(a.getNomeCompleto())
                            && "new@senado.leg.br".equals(a.getEmail())
                            && "newadmin".equals(a.getUsername())
                            && "$2a$10$hash".equals(a.getPasswordHash())
                            && Boolean.TRUE.equals(a.getSenhaProvisoria())));
        }

        @Test
        @DisplayName("campos faltando → erro 400")
        void missingFields_throws400() {
            setMasterUsername("douglas.antunes");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarAdministrador("", "", "", "", "douglas.antunes"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
    }

    // ══ Criar Técnico ═══════════════════════════════════════════

    @Nested
    @DisplayName("criarTecnico")
    class CriarTecnico {

        @Test
        @DisplayName("dados válidos → cria técnico com senha codificada e foto_url vazia")
        void validData_success() {
            semConflitoDeEmail("tec@senado.leg.br");
            semConflitoDeUsername("tec.novo");
            when(passwordEncoder.encode("Tec@2026")).thenReturn("$2b$12$tecnico");
            when(tecnicoRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.criarTecnico(
                    "Técnico Novo", "tec@senado.leg.br", "tec.novo", "Tec@2026", null);

            verify(tecnicoRepo).save(argThat(t ->
                    "Técnico Novo".equals(t.getNomeCompleto())
                            && "tec@senado.leg.br".equals(t.getEmail())
                            && "tec.novo".equals(t.getUsername())
                            && "$2b$12$tecnico".equals(t.getPasswordHash())
                            && t.getFotoUrl() == null));
            assertEquals("tec.novo", result.get("username"));
            assertEquals("", result.get("foto_url"));
            verify(passwordEncoder).encode("Tec@2026");
        }
    }

    // ══ Normalização e formato das credenciais ══════════════════

    @Nested
    @DisplayName("normalização e formato das credenciais (criarOperador)")
    class NormalizacaoDeCredenciais {

        @Test
        @DisplayName("e-mail/username são normalizados (strip+lowercase) ANTES da busca de conflito")
        void normalizaAntesDeBuscarConflito() {
            // Só a forma normalizada está stubada: se o SUT buscasse a string crua, o conflito
            // passaria despercebido (e o stub ficaria ocioso sob STRICT_STUBS).
            when(operadorRepo.findByEmail("joao@senado.leg.br")).thenReturn(Optional.of(new Operador()));
            semConflitoDeUsername("joao");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("João", "Jota", "  JOAO@Senado.LEG.BR  ", "  JOAO  ",
                            "Senha@2026", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        }

        @Test
        @DisplayName("caminho feliz grava nome/e-mail/username normalizados e as flags do Plenário Principal")
        void gravaValoresNormalizados() {
            semConflitoDeEmail("joao@senado.leg.br");
            semConflitoDeUsername("joao");
            when(passwordEncoder.encode("Senha@2026")).thenReturn("$2b$12$hash");
            when(operadorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.criarOperador("  João Silva  ", "  Jota  ",
                    "  JOAO@Senado.LEG.BR  ", "  JOAO  ", "Senha@2026", null, true, true);

            verify(operadorRepo).save(argThat(o ->
                    "João Silva".equals(o.getNomeCompleto())
                            && "Jota".equals(o.getNomeExibicao())
                            && "joao@senado.leg.br".equals(o.getEmail())
                            && "joao".equals(o.getUsername())
                            && "$2b$12$hash".equals(o.getPasswordHash())
                            && Boolean.TRUE.equals(o.getPlenarioPrincipal())
                            && Boolean.TRUE.equals(o.getPlenarioPrincipalFixo())));
            assertEquals("joao@senado.leg.br", result.get("email"));
            assertEquals("joao", result.get("username"));
        }

        @Test
        @DisplayName("username fora do USERNAME_PATTERN → 400 invalid_username, sem tocar os repositórios")
        void usernameInvalido_throws400AntesDoConflito() {
            // A validação de formato (que barra path traversal no nome do arquivo de foto)
            // precede a consulta de conflito — provado pela ausência de interações.
            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("João", "Jota", "joao@senado.leg.br", "../etc",
                            "Senha@2026", null, false, false));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("invalid_username", ex.getMessage());
            assertTrue(ex.getExtraFields().get("message").toString().contains("letras minúsculas"));
            verifyNoInteractions(operadorRepo, administradorRepo, tecnicoRepo, passwordEncoder);
        }
    }

    // ══ Duplicatas globais (operador + admin + técnico) ═════════

    @Nested
    @DisplayName("validação global de duplicatas (entre os 3 tipos de usuário)")
    class DuplicatasGlobais {

        @Test
        @DisplayName("criarOperador — e-mail já usado por ADMINISTRADOR → 409, sem salvar")
        void criarOperador_emailDeAdmin_throws409() {
            when(operadorRepo.findByEmail("x@senado.leg.br")).thenReturn(Optional.empty());
            when(administradorRepo.findByEmail("x@senado.leg.br")).thenReturn(Optional.of(new Administrador()));
            semConflitoDeUsername("xis");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("X", "X", "x@senado.leg.br", "xis", "s", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("E-mail já cadastrado", ex.getExtraFields().get("message"));
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("criarOperador — e-mail já usado por TÉCNICO → 409, sem salvar")
        void criarOperador_emailDeTecnico_throws409() {
            when(operadorRepo.findByEmail("x@senado.leg.br")).thenReturn(Optional.empty());
            when(administradorRepo.findByEmail("x@senado.leg.br")).thenReturn(Optional.empty());
            when(tecnicoRepo.findByEmail("x@senado.leg.br")).thenReturn(Optional.of(new Tecnico()));
            semConflitoDeUsername("xis");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("X", "X", "x@senado.leg.br", "xis", "s", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("E-mail já cadastrado", ex.getExtraFields().get("message"));
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("criarOperador — username já usado por TÉCNICO → 409, sem salvar")
        void criarOperador_usernameDeTecnico_throws409() {
            semConflitoDeEmail("x@senado.leg.br");
            when(operadorRepo.findByUsername("xis")).thenReturn(Optional.empty());
            when(administradorRepo.findByUsername("xis")).thenReturn(Optional.empty());
            when(tecnicoRepo.findByUsername("xis")).thenReturn(Optional.of(new Tecnico()));

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("X", "X", "x@senado.leg.br", "xis", "s", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("Nome de usuário já cadastrado", ex.getExtraFields().get("message"));
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("criarOperador — username já usado por ADMINISTRADOR → 409, sem salvar")
        void criarOperador_usernameDeAdmin_throws409() {
            semConflitoDeEmail("x@senado.leg.br");
            when(operadorRepo.findByUsername("xis")).thenReturn(Optional.empty());
            when(administradorRepo.findByUsername("xis")).thenReturn(Optional.of(new Administrador()));

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("X", "X", "x@senado.leg.br", "xis", "s", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("Nome de usuário já cadastrado", ex.getExtraFields().get("message"));
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("criarAdministrador — username já usado por OPERADOR → 409, sem salvar (lacuna da §2.1)")
        void criarAdministrador_usernameDeOperador_throws409() {
            setMasterUsername("douglas.antunes");
            semConflitoDeEmail("adm@senado.leg.br");
            when(operadorRepo.findByUsername("chefia")).thenReturn(Optional.of(new Operador()));

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarAdministrador("Adm", "adm@senado.leg.br", "chefia", "s", "douglas.antunes"));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("Nome de usuário já cadastrado", ex.getExtraFields().get("message"));
            verify(administradorRepo, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("criarTecnico — e-mail já usado por OPERADOR → 409, sem salvar")
        void criarTecnico_emailDeOperador_throws409() {
            when(operadorRepo.findByEmail("t@senado.leg.br")).thenReturn(Optional.of(new Operador()));
            semConflitoDeUsername("tecnico");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarTecnico("T", "t@senado.leg.br", "tecnico", "s", null));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("E-mail já cadastrado", ex.getExtraFields().get("message"));
            verify(tecnicoRepo, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("e-mail em ADMIN e username em TÉCNICO → mensagem combinada")
        void emailEUsernameDuplicados_mensagemCombinada() {
            when(operadorRepo.findByEmail("x@senado.leg.br")).thenReturn(Optional.empty());
            when(administradorRepo.findByEmail("x@senado.leg.br")).thenReturn(Optional.of(new Administrador()));
            when(operadorRepo.findByUsername("xis")).thenReturn(Optional.empty());
            when(administradorRepo.findByUsername("xis")).thenReturn(Optional.empty());
            when(tecnicoRepo.findByUsername("xis")).thenReturn(Optional.of(new Tecnico()));

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("X", "X", "x@senado.leg.br", "xis", "s", null, false, false));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("E-mail e usuário já cadastrados", ex.getExtraFields().get("message"));
        }

        // A unicidade global também vale na ATUALIZAÇÃO (verificarConflitoEmail), por outro
        // caminho de código: os 3 repositórios são consultados sempre (sem short-circuit) e o
        // próprio registro é excluído por (tipo, id).

        @Test
        @DisplayName("atualizarOperador — trocar para e-mail já usado por TÉCNICO → 409, sem salvar")
        void atualizarOperador_novoEmailDeTecnico_throws409() {
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(operadorFixture()));
            when(operadorRepo.findByEmail("outro@senado.leg.br")).thenReturn(Optional.empty());
            when(administradorRepo.findByEmail("outro@senado.leg.br")).thenReturn(Optional.empty());
            when(tecnicoRepo.findByEmail("outro@senado.leg.br")).thenReturn(Optional.of(tecnicoFixture()));

            // e-mail cru (caixa mista + espaços): prova que a normalização precede a busca também aqui
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarOperador("op-1", "João", "Jota", "  OUTRO@Senado.LEG.BR  ",
                            "M", "30", "08:00", "14:00", true, false, true, null));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("E-mail já cadastrado para outro usuário.", ex.getExtraFields().get("message"));
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("atualizarOperador — e-mail novo e livre → consulta os 3 repositórios e grava o novo")
        void atualizarOperador_novoEmailLivre_grava() {
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(operadorFixture()));
            when(operadorRepo.findByEmail("novo@senado.leg.br")).thenReturn(Optional.empty());
            when(administradorRepo.findByEmail("novo@senado.leg.br")).thenReturn(Optional.empty());
            when(tecnicoRepo.findByEmail("novo@senado.leg.br")).thenReturn(Optional.empty());
            when(operadorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarOperador("op-1", "João", "Jota",
                    "novo@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, null);

            assertEquals("novo@senado.leg.br", result.get("email"));
            verify(operadorRepo).findByEmail("novo@senado.leg.br");
            verify(administradorRepo).findByEmail("novo@senado.leg.br");
            verify(tecnicoRepo).findByEmail("novo@senado.leg.br");
        }
    }

    // ══ Invariante da foto ══════════════════════════════════════

    @Nested
    @DisplayName("invariante da substituição de foto (atualizarOperador)")
    class InvarianteDaFoto {

        @TempDir Path filesDir;

        private Path dirOperadores;
        private Path fotoAntiga;
        private Operador op;

        @BeforeEach
        void preparar() throws IOException {
            ReflectionTestUtils.setField(service, "filesDir", filesDir.toString());
            // Barra final de propósito: exercita o replaceAll("/$", "") de salvarFoto/apagarFotoFisica.
            ReflectionTestUtils.setField(service, "filesUrlPrefix", "/files/");
            ReflectionTestUtils.setField(service, "operadoresDirname", "operadores");

            dirOperadores = Files.createDirectories(filesDir.resolve("operadores"));
            fotoAntiga = Files.writeString(dirOperadores.resolve("joao_111.jpg"), "conteudo-antigo");

            op = operadorFixture();
            op.setFotoUrl("/files/operadores/joao_111.jpg");
        }

        private long arquivosNoDiretorio() throws IOException {
            try (Stream<Path> s = Files.list(dirOperadores)) {
                return s.count();
            }
        }

        @Test
        @DisplayName("foto nova é gravada ANTES de a antiga ser apagada, e a antiga some ao fim")
        void fotoNova_gravaAntesDeApagarAntiga() throws Exception {
            MultipartFile foto = mock(MultipartFile.class);
            when(foto.isEmpty()).thenReturn(false);
            when(foto.getOriginalFilename()).thenReturn("avatar.PNG");
            // A asserção DENTRO do transferTo é o que trava a ordem: se o SUT apagasse a antiga
            // antes de gravar a nova, este assert falharia no instante da gravação.
            doAnswer(inv -> {
                File destino = inv.getArgument(0);
                assertTrue(Files.exists(fotoAntiga),
                        "a foto antiga ainda deve existir no instante da gravação da nova");
                Files.writeString(destino.toPath(), "conteudo-novo");
                return null;
            }).when(foto).transferTo(any(File.class));

            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(operadorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarOperador("op-1", "João Silva", "Jota",
                    "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, foto);

            String fotoUrl = (String) result.get("foto_url");
            assertTrue(fotoUrl.startsWith("/files/operadores/joao_"), "url inesperada: " + fotoUrl);
            assertTrue(fotoUrl.endsWith(".png"), "extensão deve vir do originalFilename, em minúsculas: " + fotoUrl);

            Path fotoNova = dirOperadores.resolve(fotoUrl.substring(fotoUrl.lastIndexOf('/') + 1));
            assertTrue(Files.exists(fotoNova), "a foto nova deve ter sido gravada");
            assertEquals("conteudo-novo", Files.readString(fotoNova));
            assertFalse(Files.exists(fotoAntiga), "a foto antiga deve ter sido apagada ao fim");
            assertEquals(1L, arquivosNoDiretorio());
        }

        @Test
        @DisplayName("falha ao gravar a foto nova → 500 e a foto antiga permanece intacta")
        void falhaAoGravar_mantemFotoAntigaENaoSalva() throws Exception {
            MultipartFile foto = mock(MultipartFile.class);
            when(foto.isEmpty()).thenReturn(false);
            when(foto.getOriginalFilename()).thenReturn("avatar.jpg");
            doThrow(new IOException("disco cheio")).when(foto).transferTo(any(File.class));

            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarOperador("op-1", "João Silva", "Jota",
                            "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, foto));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
            assertEquals("Erro ao salvar foto", ex.getMessage());
            assertTrue(Files.exists(fotoAntiga), "a foto antiga não pode ser perdida quando a gravação falha");
            assertEquals("conteudo-antigo", Files.readString(fotoAntiga));
            assertEquals(1L, arquivosNoDiretorio());
            verify(operadorRepo, never()).save(any());
            assertEquals("/files/operadores/joao_111.jpg", op.getFotoUrl(), "a fotoUrl da entidade não muda");
        }

        @Test
        @DisplayName("sem foto nova → fotoUrl preservada e nenhum arquivo tocado")
        void semFotoNova_preservaAtual() throws Exception {
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(operadorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarOperador("op-1", "João Silva", "Jota",
                    "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, null);

            assertEquals("/files/operadores/joao_111.jpg", result.get("foto_url"));
            assertTrue(Files.exists(fotoAntiga));
            assertEquals(1L, arquivosNoDiretorio());
        }

        // ── F11: whitelist de extensão e contenção do caminho ────────────────────────
        // O diretório é servido publicamente (/files/** é permitAll e isento do filtro JWT):
        // gravar a extensão que o cliente mandar é XSS armazenado (.html/.svg servidos no
        // domínio da aplicação). A extensão passa a sair sempre da whitelist {jpg,jpeg,png,gif,webp}.

        /** Sobe uma foto pelo caminho real (atualizarOperador) e devolve a foto_url resultante. */
        private String enviarFoto(MultipartFile foto) {
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(operadorRepo.save(any())).thenAnswer(echo());
            Map<String, Object> result = service.atualizarOperador("op-1", "João Silva", "Jota",
                    "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, foto);
            return (String) result.get("foto_url");
        }

        private MultipartFile fotoMock(String originalFilename, String contentType) throws Exception {
            MultipartFile foto = mock(MultipartFile.class);
            when(foto.isEmpty()).thenReturn(false);
            when(foto.getOriginalFilename()).thenReturn(originalFilename);
            lenient().when(foto.getContentType()).thenReturn(contentType);
            lenient().doAnswer(inv -> {
                File destino = inv.getArgument(0);
                Files.writeString(destino.toPath(), "conteudo-novo");
                return null;
            }).when(foto).transferTo(any(File.class));
            return foto;
        }

        @Test
        @DisplayName("corrige F11 — nome 'foto.html' com contentType image/png → grava .png (a extensão do cliente é ignorada)")
        void extensaoForaDaWhitelist_caiNoContentType() throws Exception {
            String fotoUrl = enviarFoto(fotoMock("foto.html", "image/png"));

            assertTrue(fotoUrl.endsWith(".png"), "deveria cair no fallback por contentType: " + fotoUrl);
            assertFalse(fotoUrl.contains(".html"));
            Path gravada = dirOperadores.resolve(fotoUrl.substring(fotoUrl.lastIndexOf('/') + 1));
            assertTrue(Files.exists(gravada));
            assertEquals(1L, arquivosNoDiretorio(), "a antiga foi substituída pela nova .png");
        }

        @Test
        @DisplayName("corrige F11 — 'foto.svg' com contentType text/html → 400 e NADA é gravado")
        void extensaoENemContentTypeMapeiam_rejeita() throws Exception {
            MultipartFile foto = fotoMock("foto.svg", "text/html");
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarOperador("op-1", "João Silva", "Jota",
                            "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, foto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("FORMATO_INVALIDO", ex.getMessage());
            verify(foto, never()).transferTo(any(File.class));
            verify(operadorRepo, never()).save(any());
            assertTrue(Files.exists(fotoAntiga), "a foto antiga não pode ser tocada");
            assertEquals(1L, arquivosNoDiretorio());
        }

        @Test
        @DisplayName("nome com traversal ('../../evil.png') não escapa: só a extensão é aproveitada (documental — "
                + "o nome do cliente NUNCA compôs o caminho; este caso passa também no código pré-F11)")
        void nomeComTraversal_naoInfluenciaOCaminho() throws Exception {
            String fotoUrl = enviarFoto(fotoMock("../../evil.png", "image/png"));

            assertEquals("/files/operadores/", fotoUrl.substring(0, fotoUrl.lastIndexOf('/') + 1));
            assertTrue(fotoUrl.matches("/files/operadores/joao_\\d+\\.png"), "url inesperada: " + fotoUrl);
            assertEquals(1L, arquivosNoDiretorio(), "nenhum arquivo foi criado fora do diretório");
        }

        @Test
        @DisplayName("corrige F11 — username com traversal (dado do banco) → destino fora da base é rejeitado, sem gravar")
        void usernameComTraversal_contencaoRejeita() throws Exception {
            // O USERNAME_PATTERN barra isso na criação; a contenção é a rede de segurança do WRITE,
            // em paridade com a que apagarFotoFisica já tinha no DELETE.
            op.setUsername("../fora");
            MultipartFile foto = fotoMock("avatar.jpg", "image/jpeg");
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarOperador("op-1", "João Silva", "Jota",
                            "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, foto));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
            assertEquals("Erro ao salvar foto", ex.getMessage());
            verify(foto, never()).transferTo(any(File.class));
            verify(operadorRepo, never()).save(any());
            try (Stream<Path> s = Files.list(filesDir)) {
                assertEquals(1L, s.count(), "nada foi criado ao lado do diretório de fotos");
            }
        }

        @Test
        @DisplayName("caminho feliz .jpg segue inalterado (a whitelist não estorva o uso normal)")
        void extensaoValida_gravaComoAntes() throws Exception {
            String fotoUrl = enviarFoto(fotoMock("avatar.jpg", "image/jpeg"));

            assertTrue(fotoUrl.endsWith(".jpg"), "url inesperada: " + fotoUrl);
            assertFalse(Files.exists(fotoAntiga));
            assertEquals(1L, arquivosNoDiretorio());
        }

        // ── F12: os arquivos acompanham o desfecho da TRANSAÇÃO ──────────────────────
        // Sob proxy transacional os testes acima descrevem só o "sem transação ativa" (o delete
        // sai na hora, como antes). Com transação, o service registra uma sincronização: no COMMIT
        // some a antiga; no ROLLBACK some a NOVA — e a antiga, que o banco volta a referenciar,
        // continua no disco. Aqui a transação é simulada pelo TransactionSynchronizationManager:
        // é o mesmo mecanismo que o Spring usa, sem precisar de contexto nem de banco.

        private void simularDesfecho(int status) {
            List<TransactionSynchronization> syncs =
                    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
            assertFalse(syncs.isEmpty(), "o service deveria ter registrado a sincronização das fotos");
            syncs.forEach(s -> s.afterCompletion(status));
        }

        @Test
        @DisplayName("corrige F12 — substituição: save falha e a transação reverte → foto ANTIGA preservada e a NOVA removida")
        void substituicao_rollback_preservaAntigaERemoveNova() throws Exception {
            TransactionSynchronizationManager.initSynchronization();
            MultipartFile foto = fotoMock("avatar.jpg", "image/jpeg");
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(operadorRepo.save(any())).thenThrow(new DataIntegrityViolationException("ORA-00001"));

            assertThrows(DataIntegrityViolationException.class,
                    () -> service.atualizarOperador("op-1", "João Silva", "Jota",
                            "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, foto));

            // Antes do desfecho: a antiga ainda existe (o delete não é mais feito na hora — era o bug).
            assertTrue(Files.exists(fotoAntiga), "a antiga não pode ser apagada antes do commit");
            assertEquals(2L, arquivosNoDiretorio(), "nova gravada + antiga preservada");

            simularDesfecho(TransactionSynchronization.STATUS_ROLLED_BACK);

            assertTrue(Files.exists(fotoAntiga), "rollback: o banco volta a apontar para a antiga — ela TEM de existir");
            assertEquals("conteudo-antigo", Files.readString(fotoAntiga));
            assertEquals(1L, arquivosNoDiretorio(), "a foto nova (órfã) foi removida no rollback");
        }

        @Test
        @DisplayName("corrige F12 — substituição: transação commitada → a antiga é apagada (e só então)")
        void substituicao_commit_apagaAntiga() throws Exception {
            TransactionSynchronizationManager.initSynchronization();
            MultipartFile foto = fotoMock("avatar.jpg", "image/jpeg");
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(operadorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarOperador("op-1", "João Silva", "Jota",
                    "joao@senado.leg.br", "M", "30", "08:00", "14:00", true, false, true, foto);

            assertTrue(Files.exists(fotoAntiga), "durante a transação a antiga permanece");

            simularDesfecho(TransactionSynchronization.STATUS_COMMITTED);

            String fotoUrl = (String) result.get("foto_url");
            Path fotoNova = dirOperadores.resolve(fotoUrl.substring(fotoUrl.lastIndexOf('/') + 1));
            assertTrue(Files.exists(fotoNova), "a foto nova é a que fica");
            assertFalse(Files.exists(fotoAntiga), "commit: a antiga é apagada");
            assertEquals(1L, arquivosNoDiretorio());
        }

        @Test
        @DisplayName("corrige F12 — criação: INSERT falha (sem transação) → a foto recém-gravada é removida, sem órfã")
        void criacao_saveFalha_naoDeixaFotoOrfa() throws Exception {
            ReflectionTestUtils.setField(service, "tecnicosDirname", "operadores");  // reusa o TempDir
            semConflitoDeEmail("tec@senado.leg.br");
            semConflitoDeUsername("tec.novo");
            when(passwordEncoder.encode("s")).thenReturn("$2b$12$h");
            when(tecnicoRepo.save(any())).thenThrow(new DataIntegrityViolationException("ORA-00001"));
            MultipartFile foto = fotoMock("avatar.jpg", "image/jpeg");

            assertThrows(DataIntegrityViolationException.class, () -> service.criarTecnico(
                    "Técnico Novo", "tec@senado.leg.br", "tec.novo", "s", foto));

            verify(foto).transferTo(any(File.class));   // a foto CHEGOU a ser gravada...
            assertEquals(1L, arquivosNoDiretorio(),     // ...e sobrou só a foto pré-existente do fixture
                    "a foto do técnico que não nasceu não pode ficar órfã no disco");
            assertTrue(Files.exists(fotoAntiga));
        }

        @Test
        @DisplayName("corrige F12 — criação: transação reverte DEPOIS do save (constraint no commit) → foto removida")
        void criacao_rollbackTardio_removeFoto() throws Exception {
            TransactionSynchronizationManager.initSynchronization();
            ReflectionTestUtils.setField(service, "tecnicosDirname", "operadores");
            semConflitoDeEmail("tec@senado.leg.br");
            semConflitoDeUsername("tec.novo");
            when(passwordEncoder.encode("s")).thenReturn("$2b$12$h");
            when(tecnicoRepo.save(any())).thenAnswer(echo());
            MultipartFile foto = fotoMock("avatar.jpg", "image/jpeg");

            Map<String, Object> result = service.criarTecnico(
                    "Técnico Novo", "tec@senado.leg.br", "tec.novo", "s", foto);

            String fotoUrl = (String) result.get("foto_url");
            Path fotoNova = dirOperadores.resolve(fotoUrl.substring(fotoUrl.lastIndexOf('/') + 1));
            assertTrue(Files.exists(fotoNova), "gravada durante a transação");

            // O INSERT com ID de aplicação só vai ao banco no flush/commit: a violação de unicidade
            // pode estourar DEPOIS do save() — cenário que o catch sozinho não cobriria.
            simularDesfecho(TransactionSynchronization.STATUS_ROLLED_BACK);

            assertFalse(Files.exists(fotoNova), "rollback tardio: a foto não pode sobreviver ao registro");
            assertEquals(1L, arquivosNoDiretorio());
        }
    }

    // ══ Regras de turno por papel ═══════════════════════════════

    @Nested
    @DisplayName("regras de turno por papel")
    class TurnoPorPapel {

        @Test
        @DisplayName("operador — turno ausente → 400 TURNO_INVALIDO, sem salvar")
        void operador_turnoObrigatorio() {
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(operadorFixture()));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarOperador("op-1", "João", "Jota", "joao@senado.leg.br",
                            null, "30", "08:00", "14:00", true, false, true, null));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("TURNO_INVALIDO", ex.getMessage());
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("operador — turno 'I' (Integral) é rejeitado: só M/V")
        void operador_naoAceitaIntegral() {
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(operadorFixture()));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarOperador("op-1", "João", "Jota", "joao@senado.leg.br",
                            "I", "30", "08:00", "14:00", true, false, true, null));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            String msg = ex.getExtraFields().get("message").toString();
            assertFalse(msg.contains("Integral"), "a mensagem do operador não menciona Integral: " + msg);
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("técnico — turno é opcional: ausente grava NULL")
        void tecnico_turnoOpcionalGravaNull() {
            Tecnico tec = tecnicoFixture();
            when(tecnicoRepo.findById("tec-1")).thenReturn(Optional.of(tec));
            when(tecnicoRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarTecnico("tec-1", "Maria Técnica",
                    "maria@senado.leg.br", "   ", "40", "09:00", "18:00", null);

            assertNull(result.get("turno"));
            verify(tecnicoRepo).save(argThat(t -> t.getTurno() == null && Integer.valueOf(40).equals(t.getCargaHoraria())));
        }

        @Test
        @DisplayName("técnico — turno 'I' é rejeitado: só M/V quando informado")
        void tecnico_naoAceitaIntegral() {
            when(tecnicoRepo.findById("tec-1")).thenReturn(Optional.of(tecnicoFixture()));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarTecnico("tec-1", "Maria", "maria@senado.leg.br",
                            "I", "40", "09:00", "18:00", null));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("TURNO_INVALIDO", ex.getMessage());
            assertFalse(ex.getExtraFields().get("message").toString().contains("Integral"));
            verify(tecnicoRepo, never()).save(any());
        }

        @Test
        @DisplayName("administrador não-servidor — turno 'I' (Integral) é aceito e gravado")
        void administrador_aceitaIntegral() {
            setMasterUsername("douglas.antunes");
            when(administradorRepo.findById("adm-1")).thenReturn(Optional.of(administradorFixture()));
            when(administradorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarAdministrador("adm-1", "Chefe Novo",
                    "chefe@senado.leg.br", false, "I", "30", "07:00", "13:00", null, "douglas.antunes");

            assertEquals("I", result.get("turno"));
            assertEquals(30, result.get("carga_horaria"));
            assertEquals("07:00", result.get("horario_trabalho_inicio"));
            assertEquals("13:00", result.get("horario_trabalho_fim"));
            assertEquals(false, result.get("servidor_publico"));
        }

        @Test
        @DisplayName("administrador não-servidor — turno fora de M/V/I → 400 mencionando Integral")
        void administrador_turnoInvalido() {
            setMasterUsername("douglas.antunes");
            when(administradorRepo.findById("adm-1")).thenReturn(Optional.of(administradorFixture()));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarAdministrador("adm-1", "Chefe", "chefe@senado.leg.br",
                            false, "X", "30", "07:00", "13:00", null, "douglas.antunes"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("TURNO_INVALIDO", ex.getMessage());
            assertTrue(ex.getExtraFields().get("message").toString().contains("Integral"));
            verify(administradorRepo, never()).save(any());
        }

        @Test
        @DisplayName("administrador servidor público → turno/carga/horário gravados NULL e nem sequer validados")
        void administrador_servidorPublicoZeraCampos() {
            setMasterUsername("douglas.antunes");
            when(administradorRepo.findById("adm-1")).thenReturn(Optional.of(administradorFixture()));
            when(administradorRepo.save(any())).thenAnswer(echo());

            // Valores deliberadamente inválidos: com servidorPublico=true o SUT nem chama os
            // validadores (normalizarTurnoAdmin/parseCargaHoraria/normalizarHora) — grava NULL.
            Map<String, Object> result = service.atualizarAdministrador("adm-1", "Chefe Servidor",
                    "chefe@senado.leg.br", true, "XYZ", "99", "99:99", "25:61", null, "douglas.antunes");

            assertEquals(true, result.get("servidor_publico"));
            assertNull(result.get("turno"));
            assertNull(result.get("carga_horaria"));
            assertNull(result.get("horario_trabalho_inicio"));
            assertNull(result.get("horario_trabalho_fim"));
            verify(administradorRepo).save(argThat(a ->
                    Boolean.TRUE.equals(a.getServidorPublico())
                            && a.getTurno() == null
                            && a.getCargaHoraria() == null
                            && a.getHorarioTrabalhoInicio() == null
                            && a.getHorarioTrabalhoFim() == null));
        }
    }

    // ══ Guarda do master ════════════════════════════════════════

    @Nested
    @DisplayName("guarda do administrador master")
    class GuardaDoMaster {

        @Test
        @DisplayName("getAdministradorPerfil com caller ≠ master → 403 antes de tocar o repositório")
        void getPerfil_naoMaster_403() {
            setMasterUsername("douglas.antunes");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getAdministradorPerfil("adm-1", "outro.usuario"));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            assertEquals("forbidden", ex.getMessage());
            verifyNoInteractions(administradorRepo);
        }

        @Test
        @DisplayName("getAdministradorPerfil com caller nulo → 403 (não NPE)")
        void getPerfil_callerNulo_403() {
            setMasterUsername("douglas.antunes");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getAdministradorPerfil("adm-1", null));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            verifyNoInteractions(administradorRepo);
        }

        @Test
        @DisplayName("atualizarAdministrador com caller ≠ master → 403 antes de tocar o repositório")
        void atualizar_naoMaster_403() {
            setMasterUsername("douglas.antunes");

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarAdministrador("adm-1", "Chefe", "chefe@senado.leg.br",
                            false, "M", "40", "08:00", "17:00", null, "outro.usuario"));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            verifyNoInteractions(administradorRepo);
        }

        @Test
        @DisplayName("getAdministradorPerfil aceita o master com caixa diferente (equalsIgnoreCase)")
        void getPerfil_masterCaixaAlta_ok() {
            setMasterUsername("douglas.antunes");
            when(administradorRepo.findById("adm-1")).thenReturn(Optional.of(administradorFixture()));

            Map<String, Object> result = service.getAdministradorPerfil("adm-1", "DOUGLAS.ANTUNES");

            assertEquals("adm-1", result.get("id"));
            assertEquals("chefe", result.get("username"));
            assertEquals("M", result.get("turno"));
            assertEquals(40, result.get("carga_horaria"));
            assertEquals(false, result.get("servidor_publico"));
            assertEquals("", result.get("foto_url"));
        }

        @Test
        @DisplayName("administrador inexistente (com master) → 404")
        void administradorInexistente_404() {
            setMasterUsername("douglas.antunes");
            // Optional.empty() é o insumo do caso (não um valor esperado vindo do default):
            // o verify do argumento exato garante que o SUT consultou o id certo.
            when(administradorRepo.findById("nao-existe")).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getAdministradorPerfil("nao-existe", "douglas.antunes"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("NOT_FOUND", ex.getMessage());
            verify(administradorRepo).findById("nao-existe");
        }
    }

    // ══ Plenário Principal / fixo em atualizarOperador ══════════

    @Nested
    @DisplayName("regra “fixo do Plenário Principal exige aptidão”")
    class PlenarioPrincipalFixo {

        @Test
        @DisplayName("corrige F10 — criarOperador com fixo=true sem apto → 400 INVALIDO, sem tocar repositórios")
        void criarOperador_fixoSemApto_throws400() {
            // §5 do plano, achado F10: `atualizarOperador` (400) e `togglePlenarioPrincipalFixo`
            // (400) barram o par (apto=false, fixo=true); `criarOperador` gravava sem checar — um
            // operador NASCIA no estado que os outros dois caminhos proíbem, e o editor de perfil
            // passava a rejeitar qualquer salvamento dele. Agora a mesma guarda vale na criação, e
            // precede a gravação da foto e o INSERT (provado pela ausência de interações).
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criarOperador("X", "X", "x@senado.leg.br", "xis",
                            "s", null, false, true));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("INVALIDO", ex.getMessage());
            assertTrue(ex.getExtraFields().get("message").toString().contains("apto a Plenário Principal"));
            verifyNoInteractions(operadorRepo, administradorRepo, tecnicoRepo, passwordEncoder);
        }

        @Test
        @DisplayName("criarOperador com apto=true e fixo=true → aceito e gravado (a guarda não barra o par coerente)")
        void criarOperador_aptoEFixo_grava() {
            semConflitoDeEmail("x@senado.leg.br");
            semConflitoDeUsername("xis");
            when(passwordEncoder.encode("s")).thenReturn("$2b$12$h");
            when(operadorRepo.save(any())).thenAnswer(echo());

            assertDoesNotThrow(() -> service.criarOperador("X", "X", "x@senado.leg.br", "xis",
                    "s", null, true, true));

            verify(operadorRepo).save(argThat(o ->
                    Boolean.TRUE.equals(o.getPlenarioPrincipal())
                            && Boolean.TRUE.equals(o.getPlenarioPrincipalFixo())));
        }

        @Test
        @DisplayName("fixo=true com plenarioPrincipal=false → 400 INVALIDO, sem salvar (não é coerção silenciosa)")
        void fixoSemApto_throws400() {
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(operadorFixture()));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarOperador("op-1", "João", "Jota", "joao@senado.leg.br",
                            "M", "30", "08:00", "14:00", false, true, true, null));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("INVALIDO", ex.getMessage());
            assertTrue(ex.getExtraFields().get("message").toString().contains("apto a Plenário Principal"));
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("operador que era fixo e passa a não-apto (fixo=false) → grava ambos false")
        void deixaDeSerAptoEFixo_gravaAmbosFalse() {
            Operador op = operadorFixture();
            op.setPlenarioPrincipalFixo(true);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(operadorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarOperador("op-1", "João", "Jota",
                    "joao@senado.leg.br", "M", "30", "08:00", "14:00", false, false, true, null);

            assertEquals(false, result.get("plenario_principal"));
            assertEquals(false, result.get("plenario_principal_fixo"));
            verify(operadorRepo).save(argThat(o ->
                    Boolean.FALSE.equals(o.getPlenarioPrincipal())
                            && Boolean.FALSE.equals(o.getPlenarioPrincipalFixo())));
        }

        @Test
        @DisplayName("apto + fixo → grava ambos true, com participa_escala e demais campos")
        void aptoEFixo_gravaAmbosTrue() {
            Operador op = operadorFixture();
            op.setParticipaEscala(true);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(operadorRepo.save(any())).thenAnswer(echo());

            Map<String, Object> result = service.atualizarOperador("op-1", "João", "Jota",
                    "joao@senado.leg.br", "V", "40", "13:00", "20:00", true, true, false, null);

            assertEquals(true, result.get("plenario_principal"));
            assertEquals(true, result.get("plenario_principal_fixo"));
            assertEquals(false, result.get("participa_escala"));
            assertEquals("V", result.get("turno"));
            assertEquals(40, result.get("carga_horaria"));
            assertEquals("13:00", result.get("horario_trabalho_inicio"));
        }
    }

    // ══ Toggles e operações pontuais ════════════════════════════

    @Nested
    @DisplayName("toggles e operações pontuais do operador")
    class TogglesEOperacoesPontuais {

        @Test
        @DisplayName("setTurnoOperador com turno inválido → 400 antes de buscar o operador")
        void setTurno_invalido_400SemBuscar() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.setTurnoOperador("op-1", "X"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("TURNO_INVALIDO", ex.getMessage());
            verifyNoInteractions(operadorRepo);
        }

        @Test
        @DisplayName("setTurnoOperador com 'V' → grava e devolve o turno")
        void setTurno_valido_gravaEDevolve() {
            Operador op = operadorFixture();  // turno "M"
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            assertEquals("V", service.setTurnoOperador("op-1", "V"));
            verify(operadorRepo).save(argThat(o -> "V".equals(o.getTurno())));
        }

        @Test
        @DisplayName("changeOperadorPassword sempre grava o hash do encoder, nunca a senha crua")
        void changePassword_usaEncoder() {
            Operador op = operadorFixture();
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));
            when(passwordEncoder.encode("nova-senha")).thenReturn("$2b$12$novo");

            service.changeOperadorPassword("op-1", "nova-senha");

            verify(passwordEncoder).encode("nova-senha");
            verify(operadorRepo).save(argThat(o ->
                    "$2b$12$novo".equals(o.getPasswordHash()) && !"nova-senha".equals(o.getPasswordHash())));
        }

        @Test
        @DisplayName("togglePlenarioPrincipal true→false desmarca também o 'fixo'")
        void togglePP_desmarcandoZeraFixo() {
            Operador op = operadorFixture();
            op.setPlenarioPrincipal(true);
            op.setPlenarioPrincipalFixo(true);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            assertFalse(service.togglePlenarioPrincipal("op-1"));
            verify(operadorRepo).save(argThat(o ->
                    Boolean.FALSE.equals(o.getPlenarioPrincipal())
                            && Boolean.FALSE.equals(o.getPlenarioPrincipalFixo())));
        }

        @Test
        @DisplayName("togglePlenarioPrincipal false→true não mexe no 'fixo'")
        void togglePP_marcandoPreservaFixo() {
            Operador op = operadorFixture();
            op.setPlenarioPrincipal(false);
            op.setPlenarioPrincipalFixo(true);  // estado herdado: o ramo de marcar não o toca
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            assertTrue(service.togglePlenarioPrincipal("op-1"));
            verify(operadorRepo).save(argThat(o ->
                    Boolean.TRUE.equals(o.getPlenarioPrincipal())
                            && Boolean.TRUE.equals(o.getPlenarioPrincipalFixo())));
        }

        @Test
        @DisplayName("togglePlenarioPrincipalFixo em operador apto → marca fixo")
        void toggleFixo_aptoMarca() {
            Operador op = operadorFixture();
            op.setPlenarioPrincipal(true);
            op.setPlenarioPrincipalFixo(false);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            assertTrue(service.togglePlenarioPrincipalFixo("op-1"));
            verify(operadorRepo).save(argThat(o -> Boolean.TRUE.equals(o.getPlenarioPrincipalFixo())));
        }

        @Test
        @DisplayName("togglePlenarioPrincipalFixo em operador não-apto → 400, sem salvar")
        void toggleFixo_naoApto_400() {
            Operador op = operadorFixture();
            op.setPlenarioPrincipal(false);
            op.setPlenarioPrincipalFixo(false);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.togglePlenarioPrincipalFixo("op-1"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("INVALIDO", ex.getMessage());
            verify(operadorRepo, never()).save(any());
        }

        @Test
        @DisplayName("togglePlenarioPrincipalFixo desmarcando não exige aptidão")
        void toggleFixo_desmarcandoSempreOk() {
            Operador op = operadorFixture();
            op.setPlenarioPrincipal(false);
            op.setPlenarioPrincipalFixo(true);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            assertFalse(service.togglePlenarioPrincipalFixo("op-1"));
            verify(operadorRepo).save(argThat(o -> Boolean.FALSE.equals(o.getPlenarioPrincipalFixo())));
        }

        @Test
        @DisplayName("toggleParticipaEscala true→false")
        void toggleEscala_desmarca() {
            Operador op = operadorFixture();
            op.setParticipaEscala(true);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            assertFalse(service.toggleParticipaEscala("op-1"));
            verify(operadorRepo).save(argThat(o -> Boolean.FALSE.equals(o.getParticipaEscala())));
        }

        @Test
        @DisplayName("toggleParticipaEscala com valor nulo é tratado como false → marca")
        void toggleEscala_nuloVira() {
            Operador op = operadorFixture();
            op.setParticipaEscala(null);
            when(operadorRepo.findById("op-1")).thenReturn(Optional.of(op));

            assertTrue(service.toggleParticipaEscala("op-1"));
            verify(operadorRepo).save(argThat(o -> Boolean.TRUE.equals(o.getParticipaEscala())));
        }

        @Test
        @DisplayName("operador inexistente → 404 NOT_FOUND")
        void operadorInexistente_404() {
            // Optional.empty() é o insumo do caso; o verify do id exato impede o falso verde.
            when(operadorRepo.findById("nao-existe")).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.togglePlenarioPrincipal("nao-existe"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("NOT_FOUND", ex.getMessage());
            verify(operadorRepo).findById("nao-existe");
            verify(operadorRepo, never()).save(any());
        }
    }

    // ══ Aplicar config a todos os plenários ═════════════════════

    @Nested
    @DisplayName("applySalaConfigToAll — tudo-ou-nada (corrige F13)")
    class ApplySalaConfigToAll {

        private static final int ITEM_TIPO_ID = 7;

        private Sala sala(int id, String nome) {
            Sala s = new Sala();
            s.setId(id);
            s.setNome(nome);
            return s;
        }

        /** Um item ativo — o suficiente para exercitar deactivate + upsert por sala. */
        private List<Map<String, Object>> items() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nome", "Microfone");
            item.put("tipo_widget", "radio");
            item.put("ativo", true);
            return List.of(item);
        }

        /** Cadastro do homolog reduzido: 2 plenários numerados + 1 sala que o filtro descarta. */
        private void stubSalasAtivas() {
            when(salaRepo.findAtivasOrdenadas()).thenReturn(List.of(
                    sala(2, "Plenário 01"), sala(11, "Demais Salas"), sala(3, "Plenário 02")));
        }

        private void stubItemTipoExistente() {
            ChecklistItemTipo tipo = new ChecklistItemTipo();
            tipo.setId(ITEM_TIPO_ID);
            tipo.setNome("Microfone");
            tipo.setTipoWidget(TipoWidget.RADIO);
            when(itemTipoRepo.findByNomeAndTipoWidget("Microfone", TipoWidget.RADIO))
                    .thenReturn(Optional.of(tipo));
        }

        @Test
        @DisplayName("caminho feliz — aplica só nos plenários numerados e conta as salas atualizadas")
        void aplicaSomenteNosPlenariosNumerados() {
            stubSalasAtivas();
            stubItemTipoExistente();
            when(salaConfigRepo.findBySalaIdAndItemTipoId(2, ITEM_TIPO_ID)).thenReturn(Optional.empty());
            when(salaConfigRepo.findBySalaIdAndItemTipoId(3, ITEM_TIPO_ID)).thenReturn(Optional.empty());

            Map<String, Object> result = service.applySalaConfigToAll(2, items());

            assertEquals(2, result.get("salas_atualizadas"));
            assertEquals(2, result.get("source_sala_id"));
            verify(salaConfigRepo).deactivateAllBySalaId(2);
            verify(salaConfigRepo).deactivateAllBySalaId(3);
            verify(salaConfigRepo, never()).deactivateAllBySalaId(11);   // "Demais Salas" fica de fora
            verify(salaConfigRepo, times(2)).save(any());
        }

        @Test
        @DisplayName("corrige F13 — falha numa sala PROPAGA (sem contagem parcial): o catch que engolia ou "
                + "descartava tudo num 500 opaco (rollback-only) ou devolvia 200 com contagem parcial")
        void falhaEmUmaSala_propagaSemContagemParcial() {
            stubSalasAtivas();
            stubItemTipoExistente();
            when(salaConfigRepo.findBySalaIdAndItemTipoId(2, ITEM_TIPO_ID)).thenReturn(Optional.empty());
            // 2ª sala (Plenário 02) estoura na persistência. Com o catch antigo, o desfecho dependia
            // de QUEM lançava: método CRUD do repositório (transacional) → rollback-only → o commit
            // deste próprio método estourava UnexpectedRollbackException (500 opaco, tudo descartado);
            // query method derivado → commit passava com gravação parcial e 200. Nenhum dos dois é
            // aceitável — agora o erro real chega ao cliente.
            when(salaConfigRepo.findBySalaIdAndItemTipoId(3, ITEM_TIPO_ID))
                    .thenThrow(new DataIntegrityViolationException("ORA-00001"));

            assertThrows(DataIntegrityViolationException.class,
                    () -> service.applySalaConfigToAll(2, items()));

            // A 1ª sala chegou a ser tocada — e é por isso que o erro precisa vir à tona: o rollback
            // vai desfazê-la, e o cliente tem de saber que a operação não valeu.
            verify(salaConfigRepo).deactivateAllBySalaId(2);
            verify(salaConfigRepo).deactivateAllBySalaId(3);
        }

        @Test
        @DisplayName("sala de origem inválida (id ≤ 0) → 400 antes de qualquer consulta")
        void sourceSalaIdInvalido_400() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.applySalaConfigToAll(0, items()));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("LOCAL_ID_INVALIDO", ex.getMessage());
            verifyNoInteractions(salaRepo, salaConfigRepo, itemTipoRepo);
        }
    }

    // ══ Form Edit ═══════════════════════════════════════════════

    @Nested
    @DisplayName("listFormEditItems")
    class ListFormEdit {

        @Test
        @DisplayName("entidade inválida → erro 400")
        void invalidEntity_throws400() {
            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.listFormEditItems("invalida"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("ENTIDADE_INVALIDA"));
        }
    }
}
