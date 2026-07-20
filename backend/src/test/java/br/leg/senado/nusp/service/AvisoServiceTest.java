package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.AvisoMensagem;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.enums.SubtipoAviso;
import br.leg.senado.nusp.enums.TipoAviso;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AvisoAlvoRepository;
import br.leg.senado.nusp.repository.AvisoCadastroRepository;
import br.leg.senado.nusp.repository.AvisoCienciaRepository;
import br.leg.senado.nusp.repository.AvisoMensagemRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.service.AvisoService.CriarAvisoRequest;
import br.leg.senado.nusp.service.AvisoService.DestinatarioAviso;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unitário de {@link AvisoService} + sentinela do {@code AvisoCienciaWriter}.
 *
 * <p>Trava o que é unitariamente testável: validações de {@code criar}, dedup e
 * coerência de público, idempotência de {@code registrarCiencia} e o contrato de
 * chamada do SQL de {@code buscarPendentes} (fragmento casado no argThat, nunca
 * anyString()). A SEMÂNTICA real do SQL e a demonstração em banco do REQUIRES_NEW do
 * writer (cuja sub-transação furaria o rollback do @DataJpaTest) ficam fora; do writer
 * resta aqui apenas o caso-sentinela por reflexão, única trava contra a remoção
 * silenciosa da anotação da qual a idempotência da ciência depende.
 */
@ExtendWith(MockitoExtension.class)
class AvisoServiceTest {

    @Mock private AvisoCadastroRepository cadastroRepo;
    @Mock private AvisoMensagemRepository mensagemRepo;
    @Mock private AvisoAlvoRepository alvoRepo;
    @Mock private AvisoCienciaRepository cienciaRepo;
    @Mock private SalaRepository salaRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private AdministradorRepository adminRepo;
    @Mock private AvisoCienciaWriter cienciaWriter;
    @Mock private EntityManager entityManager;

    @InjectMocks
    private AvisoService service;

    private static final String ADMIN_ID = "uuid-admin";
    private static final String ADMIN_NOME = "Evandro Pereira";
    private static final String CADASTRO_ID = "uuid-cadastro-novo";
    private static final String OPERADOR_ID = "uuid-operador";
    private static final String TECNICO_ID = "uuid-tecnico";
    private static final String OUTRO_ADMIN_ID = "uuid-admin-2";   // destinatário admin ≠ autor
    private static final int SALA_ID = 3;
    private static final int OUTRA_SALA_ID = 4;
    private static final String SALA_NOME = "Plenário 2";
    private static final long NUMERO_SEQUENCE = 77L;

    /** Com espaços de sobra nas pontas: prova o trim de {@code criar}/{@code criarPessoalIndividual}. */
    private static final String MSG_CRUA = "   Verifique o áudio da sala.   ";
    private static final String MSG_TRIM = "Verifique o áudio da sala.";

    // ── Helpers de stub ──────────────────────────────────────────

    /**
     * Mock de Query devolvido só quando o SQL contém TODOS os fragmentos-chave.
     * RETURNS_SELF resolve o encadeamento .setParameter(...). SQL que não casa
     * nenhum matcher registrado devolve null → NPE ruidoso no SUT (é o desejado:
     * um SQL alterado não pode passar silenciosamente por um stub genérico).
     */
    private Query mockQuery(String... fragmentos) {
        Query q = mock(Query.class, RETURNS_SELF);
        when(entityManager.createNativeQuery(argThat((String sql) ->
                sql != null && Stream.of(fragmentos).allMatch(sql::contains)))).thenReturn(q);
        return q;
    }

    /** SEQ_FRM_AVISO_CADASTRO.NEXTVAL — Oracle devolve BigDecimal (gotcha 4), lido como Number. */
    private Query stubSequenciaNumero() {
        Query q = mockQuery("SEQ_FRM_AVISO_CADASTRO.NEXTVAL");
        when(q.getSingleResult()).thenReturn(BigDecimal.valueOf(NUMERO_SEQUENCE));
        return q;
    }

    /**
     * validarSalasLivres: lista VAZIA = sala livre. É o único ponto do arquivo em que
     * o valor esperado coincide com o default do Mockito — por isso o stub é explícito
     * E o teste verifica os setParameter (a trava anti-falso-verde real aqui: se o SQL
     * mudar de fragmento, o createNativeQuery devolve null e o SUT quebra).
     */
    private Query stubSalasLivres() {
        Query q = mockQuery("FRM_AVISO_ALVO", "FETCH FIRST 1 ROW ONLY");
        when(q.getResultList()).thenReturn(List.of());
        return q;
    }

    /** Sala já ocupada pelo cadastro nº 42 do mesmo tipo. */
    private Query stubSalaOcupada(long numeroOcupante) {
        Query q = mockQuery("FRM_AVISO_ALVO", "FETCH FIRST 1 ROW ONLY");
        when(q.getResultList()).thenReturn(List.of(BigDecimal.valueOf(numeroOcupante)));
        return q;
    }

    private void stubAdminExistente() {
        Administrador a = new Administrador();
        a.setId(ADMIN_ID);
        a.setNomeCompleto(ADMIN_NOME);
        when(adminRepo.findById(ADMIN_ID)).thenReturn(Optional.of(a));
    }

    private void stubSalaExistente(int id, String nome) {
        Sala s = new Sala();
        s.setId(id);
        s.setNome(nome);
        when(salaRepo.findById(id)).thenReturn(Optional.of(s));
    }

    private void stubOperadorExistente(String id) {
        Operador o = new Operador();
        o.setId(id);
        o.setNomeCompleto("Operador " + id);
        when(operadorRepo.findById(id)).thenReturn(Optional.of(o));
    }

    private void stubTecnicoExistente(String id) {
        Tecnico t = new Tecnico();
        t.setId(id);
        t.setNomeCompleto("Técnico " + id);
        when(tecnicoRepo.findById(id)).thenReturn(Optional.of(t));
    }

    /** Administrador destinatário (≠ o autor stubado por {@link #stubAdminExistente()}). */
    private void stubAdminExistente(String id) {
        Administrador a = new Administrador();
        a.setId(id);
        a.setNomeCompleto("Admin " + id);
        when(adminRepo.findById(id)).thenReturn(Optional.of(a));
    }

    /** O save devolve a entidade com ID (o SUT depende de cad.getId() logo depois). */
    private AtomicReference<AvisoCadastro> stubSaveCadastro() {
        AtomicReference<AvisoCadastro> ref = new AtomicReference<>();
        when(cadastroRepo.save(any(AvisoCadastro.class))).thenAnswer(inv -> {
            AvisoCadastro c = inv.getArgument(0);
            c.setId(CADASTRO_ID);
            ref.set(c);
            return c;
        });
        return ref;
    }

    private List<AvisoMensagem> stubSaveMensagens() {
        List<AvisoMensagem> salvas = new ArrayList<>();
        when(mensagemRepo.save(any(AvisoMensagem.class))).thenAnswer(inv -> {
            salvas.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        return salvas;
    }

    private List<AvisoAlvo> stubSaveAllAlvos() {
        List<AvisoAlvo> salvos = new ArrayList<>();
        when(alvoRepo.saveAll(anyList())).thenAnswer(inv -> {
            List<AvisoAlvo> arg = inv.getArgument(0);
            salvos.addAll(arg);
            return arg;
        });
        return salvos;
    }

    private AvisoCadastro cadastro(TipoAviso tipo, StatusAviso status) {
        AvisoCadastro c = new AvisoCadastro();
        c.setId(CADASTRO_ID);
        c.setNumero(NUMERO_SEQUENCE);
        c.setTipo(tipo);
        c.setStatus(status);
        c.setCriadoPorId(ADMIN_ID);
        return c;
    }

    private static AvisoMensagem mensagem(int ordem, String texto) {
        AvisoMensagem m = new AvisoMensagem();
        m.setOrdem(ordem);
        m.setTexto(texto);
        return m;
    }

    /** Payload de criação: cada teste sobrescreve só o que lhe interessa (adminIds/escalaId nulos). */
    private static CriarAvisoRequest req(String tipo, Boolean permanente, Integer duracaoDias, Boolean manter,
                                         List<String> mensagens, String alvoTipo,
                                         List<Integer> salaIds, List<String> operadorIds, List<String> tecnicoIds) {
        return new CriarAvisoRequest(tipo, permanente, duracaoDias, manter, mensagens, alvoTipo,
                salaIds, operadorIds, tecnicoIds, null, null);
    }

    /** Payload com listas de pessoas (op/téc/adm) — modo PESSOAS ou público ADMIN individual (mensagem fixa). */
    private static CriarAvisoRequest reqPessoas(String tipo, Boolean permanente, Integer duracaoDias, Boolean manter,
                                                String alvoTipo, List<String> operadorIds, List<String> tecnicoIds,
                                                List<String> adminIds) {
        return new CriarAvisoRequest(tipo, permanente, duracaoDias, manter, List.of(MSG_CRUA), alvoTipo,
                null, operadorIds, tecnicoIds, adminIds, null);
    }

    /** VERIFICACAO permanente, público SALA — o caso real do frontend hoje. */
    private static CriarAvisoRequest reqSala(List<Integer> salaIds) {
        return req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "SALA", salaIds, null, null);
    }

    private void assertNadaEscrito() {
        verifyNoInteractions(cadastroRepo, mensagemRepo, alvoRepo, cienciaRepo, cienciaWriter);
    }

    // ═══ criar — validações (todas com caso negativo dedicado) ═══

    @Nested
    @DisplayName("criar — validações de payload")
    class CriarValidacoes {

        @Test
        @DisplayName("criar — tipo nulo/em branco é obrigatório e tipo desconhecido é inválido, antes de qualquer leitura ou escrita")
        void criar_tipoObrigatorioEInvalido() {
            ServiceValidationException nulo = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req(null, null, null, null, List.of(MSG_CRUA), "SALA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Tipo de aviso é obrigatório.", nulo.getMessage());
            assertEquals(HttpStatus.BAD_REQUEST, nulo.getStatus());

            ServiceValidationException branco = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("   ", null, null, null, List.of(MSG_CRUA), "SALA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Tipo de aviso é obrigatório.", branco.getMessage());

            ServiceValidationException invalido = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("XPTO", null, null, null, List.of(MSG_CRUA), "SALA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Tipo de aviso inválido.", invalido.getMessage());
            assertEquals(HttpStatus.BAD_REQUEST, invalido.getStatus());

            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — sem mensagem alguma (lista nula ou vazia) exige ao menos uma")
        void criar_semMensagens() {
            ServiceValidationException nula = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, null, "SALA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Informe ao menos uma mensagem.", nula.getMessage());

            ServiceValidationException vazia = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(), "SALA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Informe ao menos uma mensagem.", vazia.getMessage());

            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — 10 mensagens é o teto: a 11ª é rejeitada")
        void criar_acimaDoMaximoDeMensagens() {
            List<String> onze = new ArrayList<>();
            for (int i = 1; i <= 11; i++) onze.add("Mensagem " + i);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, onze, "SALA", List.of(SALA_ID), null, null), ADMIN_ID));

            assertEquals("Máximo de 10 avisos por cadastro.", ex.getMessage());
            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — nenhuma mensagem pode ser em branco: null e string só de espaços são rejeitados")
        void criar_mensagemEmBranco() {
            List<String> comEspacos = Arrays.asList("Mensagem válida", "   ");
            ServiceValidationException espacos = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, comEspacos, "SALA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Todas as mensagens devem ser preenchidas.", espacos.getMessage());

            // null vira "" no map do SUT e cai na mesma guarda (Arrays.asList aceita null; List.of não).
            List<String> comNull = Arrays.asList("Mensagem válida", null);
            ServiceValidationException nulo = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, comNull, "SALA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Todas as mensagens devem ser preenchidas.", nulo.getMessage());

            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — não-permanente exige duração entre 1 e 30 dias (nula, 0 e 31 são rejeitadas)")
        void criar_duracaoForaDaFaixa() {
            String msg = "A duração deve estar entre 1 e 30 dias.";
            for (Integer duracao : Arrays.asList(null, 0, -1, 31)) {
                ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                        () -> service.criar(req("VERIFICACAO", false, duracao, null, List.of(MSG_CRUA), "SALA", List.of(SALA_ID), null, null), ADMIN_ID),
                        "duração " + duracao + " deveria ser rejeitada");
                assertEquals(msg, ex.getMessage());
            }
            // Fronteiras válidas não param aqui: 1 e 30 seguem para a validação de público.
            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — tipo de público nulo/em branco é obrigatório e desconhecido é inválido")
        void criar_alvoTipoObrigatorioEInvalido() {
            ServiceValidationException nulo = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), null, List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Tipo de público é obrigatório.", nulo.getMessage());

            // parseAlvoTipo tem guarda de isBlank própria, independente da de parseTipo.
            ServiceValidationException branco = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "   ", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Tipo de público é obrigatório.", branco.getMessage());

            ServiceValidationException invalido = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "PLATEIA", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals("Tipo de público inválido.", invalido.getMessage());

            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }
    }

    // ═══ criar — coerência alvoTipo ↔ listas ═══

    @Nested
    @DisplayName("criar — coerência entre o tipo de público e as listas de destinatários")
    class CriarCoerenciaDoAlvo {

        @Test
        @DisplayName("criar — público SALA: lista vazia é rejeitada e seleção individual (operador/técnico) junto é proibida")
        void criar_publicoSalaExigeSalaESoSala() {
            ServiceValidationException semSala = assertThrows(ServiceValidationException.class,
                    () -> service.criar(reqSala(List.of()), ADMIN_ID));
            assertEquals("Selecione ao menos um local.", semSala.getMessage());

            ServiceValidationException comOperador = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "SALA",
                            List.of(SALA_ID), List.of(OPERADOR_ID), null), ADMIN_ID));
            assertEquals("Público por sala não aceita operadores/técnicos individuais.", comOperador.getMessage());

            ServiceValidationException comTecnico = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "SALA",
                            List.of(SALA_ID), null, List.of(TECNICO_ID)), ADMIN_ID));
            assertEquals("Público por sala não aceita operadores/técnicos individuais.", comTecnico.getMessage());

            verifyNoInteractions(entityManager, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — público OPERADOR: lista vazia é rejeitada e salas/técnicos juntos são proibidos")
        void criar_publicoOperadorExigeOperadorESoOperador() {
            ServiceValidationException semOperador = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "OPERADOR", null, List.of(), null), ADMIN_ID));
            assertEquals("Selecione ao menos um operador.", semOperador.getMessage());

            ServiceValidationException comSala = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "OPERADOR",
                            List.of(SALA_ID), List.of(OPERADOR_ID), null), ADMIN_ID));
            assertEquals("Público por operador não aceita salas/técnicos.", comSala.getMessage());

            ServiceValidationException comTecnico = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "OPERADOR",
                            null, List.of(OPERADOR_ID), List.of(TECNICO_ID)), ADMIN_ID));
            assertEquals("Público por operador não aceita salas/técnicos.", comTecnico.getMessage());

            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — público TECNICO: lista vazia é rejeitada e salas/operadores juntos são proibidos")
        void criar_publicoTecnicoExigeTecnicoESoTecnico() {
            ServiceValidationException semTecnico = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "TECNICO", null, null, List.of()), ADMIN_ID));
            assertEquals("Selecione ao menos um técnico.", semTecnico.getMessage());

            ServiceValidationException comOperador = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "TECNICO",
                            null, List.of(OPERADOR_ID), List.of(TECNICO_ID)), ADMIN_ID));
            assertEquals("Público por técnico não aceita salas/operadores.", comOperador.getMessage());

            ServiceValidationException comSala = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "TECNICO",
                            List.of(SALA_ID), null, List.of(TECNICO_ID)), ADMIN_ID));
            assertEquals("Público por técnico não aceita salas/operadores.", comSala.getMessage());

            verifyNoInteractions(entityManager, salaRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — públicos coletivos (TODOS_OPERADORES/TODOS_TECNICOS/TODOS) não aceitam nenhuma seleção individual")
        void criar_publicoColetivoNaoAceitaSelecao() {
            String msg = "Público coletivo não aceita seleção individual.";

            ServiceValidationException comSala = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("GERAL", null, null, null, List.of(MSG_CRUA), "TODOS", List.of(SALA_ID), null, null), ADMIN_ID));
            assertEquals(msg, comSala.getMessage());

            ServiceValidationException comOperador = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("GERAL", null, null, null, List.of(MSG_CRUA), "TODOS_OPERADORES", null, List.of(OPERADOR_ID), null), ADMIN_ID));
            assertEquals(msg, comOperador.getMessage());

            ServiceValidationException comTecnico = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("GERAL", null, null, null, List.of(MSG_CRUA), "TODOS_TECNICOS", null, null, List.of(TECNICO_ID)), ADMIN_ID));
            assertEquals(msg, comTecnico.getMessage());

            verifyNoInteractions(entityManager, salaRepo, operadorRepo, tecnicoRepo, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — sala inexistente responde 404 antes de checar ocupação (nenhuma query de sala livre é emitida)")
        void criar_salaInexistente() {
            when(salaRepo.findById(SALA_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(reqSala(List.of(SALA_ID)), ADMIN_ID));

            assertEquals("Local inválido: " + SALA_ID, ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(entityManager, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — operador inexistente responde 404")
        void criar_operadorInexistente() {
            when(operadorRepo.findById(OPERADOR_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "OPERADOR",
                            null, List.of(OPERADOR_ID), null), ADMIN_ID));

            assertEquals("Operador inválido: " + OPERADOR_ID, ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(entityManager, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — técnico inexistente responde 404")
        void criar_tecnicoInexistente() {
            when(tecnicoRepo.findById(TECNICO_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "TECNICO",
                            null, null, List.of(TECNICO_ID)), ADMIN_ID));

            assertEquals("Técnico inválido: " + TECNICO_ID, ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(entityManager, adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — autor inexistente em PES_ADMINISTRADOR responde 404 e nada é gravado, mesmo com público já validado")
        void criar_autorInexistente() {
            stubSalaExistente(SALA_ID, SALA_NOME);
            stubSalasLivres();
            when(adminRepo.findById(ADMIN_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(reqSala(List.of(SALA_ID)), ADMIN_ID));

            assertEquals("Administrador inválido.", ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            // Rejeita ANTES de consumir a sequence — o número humano não é queimado à toa.
            verify(entityManager, never()).createNativeQuery(argThat((String sql) -> sql.contains("NEXTVAL")));
            assertNadaEscrito();
        }
    }

    // ═══ criar — 1 aviso ativo por sala e tipo ═══

    @Nested
    @DisplayName("criar — regra do aviso ativo único por sala e tipo (validarSalasLivres)")
    class CriarSalasLivres {

        @Test
        @DisplayName("criar — sala com aviso ativo do mesmo tipo é rejeitada citando o nome da sala e o número do cadastro ocupante")
        void criar_salaJaOcupada() {
            stubSalaExistente(SALA_ID, SALA_NOME);
            Query q = stubSalaOcupada(42L);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(reqSala(List.of(SALA_ID)), ADMIN_ID));

            assertEquals(SALA_NOME + " já possui um aviso ativo (cadastro nº 42). Desative-o antes de cadastrar outro.",
                    ex.getMessage());
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            verify(q).setParameter(1, SALA_ID);
            verify(q).setParameter(2, "VERIFICACAO");   // filtra pelo MESMO tipo, não por qualquer aviso
            verifyNoInteractions(adminRepo);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — a ocupação é checada uma vez por sala distinta, com o tipo do aviso como bind")
        void criar_checaOcupacaoDeCadaSala() {
            stubSalaExistente(SALA_ID, SALA_NOME);
            stubSalaExistente(OUTRA_SALA_ID, "Plenário 3");
            Query q = stubSalasLivres();
            stubSequenciaNumero();
            stubAdminExistente();
            stubSaveCadastro();
            stubSaveMensagens();
            stubSaveAllAlvos();

            service.criar(reqSala(List.of(SALA_ID, OUTRA_SALA_ID)), ADMIN_ID);

            verify(q).setParameter(1, SALA_ID);
            verify(q).setParameter(1, OUTRA_SALA_ID);
            verify(q, times(2)).setParameter(2, "VERIFICACAO");
            verify(q, times(2)).getResultList();
        }

        @Test
        @DisplayName("criar — público não-SALA nem consulta ocupação: o único SQL emitido é o da sequence")
        void criar_publicoColetivoNaoChecaOcupacao() {
            stubSequenciaNumero();
            stubAdminExistente();
            stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            service.criar(req("GERAL", null, null, null, List.of(MSG_CRUA), "TODOS", null, null, null), ADMIN_ID);

            verify(entityManager).createNativeQuery(argThat((String sql) -> sql.contains("SEQ_FRM_AVISO_CADASTRO.NEXTVAL")));
            verifyNoMoreInteractions(entityManager);   // validarSalasLivres não rodou
            verifyNoInteractions(salaRepo, operadorRepo, tecnicoRepo);
            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.TODOS, alvos.get(0).getAlvoTipo());
            assertNull(alvos.get(0).getSalaId());
            assertNull(alvos.get(0).getOperadorId());
            assertNull(alvos.get(0).getTecnicoId());
        }
    }

    // ═══ criar — caminhos felizes ═══

    @Nested
    @DisplayName("criar — caminho feliz")
    class CriarCaminhoFeliz {

        @Test
        @DisplayName("criar — permanente por default: EXPIRA_EM e DURACAO_DIAS ficam nulos (mesmo com duracaoDias no payload) e manterAposCiencia nulo vira false")
        void criar_permanentePorDefault() {
            stubSalaExistente(SALA_ID, SALA_NOME);
            stubSalasLivres();
            stubSequenciaNumero();
            stubAdminExistente();
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            List<AvisoMensagem> mensagens = stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            // permanente=null (default true) mas duracaoDias=5 no payload: a duração é ignorada.
            Map<String, Object> out = service.criar(
                    req("VERIFICACAO", null, 5, null, List.of(MSG_CRUA), "SALA", List.of(SALA_ID), null, null), ADMIN_ID);

            AvisoCadastro cad = ref.get();
            assertTrue(cad.getPermanente());
            assertNull(cad.getDuracaoDias());
            assertNull(cad.getExpiraEm());
            assertFalse(cad.getManterAposCiencia());
            assertEquals(NUMERO_SEQUENCE, cad.getNumero());
            assertEquals(TipoAviso.VERIFICACAO, cad.getTipo());
            assertEquals(StatusAviso.ATIVO, cad.getStatus());
            assertEquals(ADMIN_ID, cad.getCriadoPorId());

            assertEquals(1, mensagens.size());
            assertEquals(MSG_TRIM, mensagens.get(0).getTexto());     // trim aplicado
            assertEquals(1, mensagens.get(0).getOrdem());
            assertEquals(CADASTRO_ID, mensagens.get(0).getCadastroId());

            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.SALA, alvos.get(0).getAlvoTipo());
            assertEquals(SALA_ID, alvos.get(0).getSalaId());
            assertEquals(CADASTRO_ID, alvos.get(0).getCadastroId());

            // Shape do resumo devolvido ao controller.
            assertEquals(CADASTRO_ID, out.get("id"));
            assertEquals(NUMERO_SEQUENCE, out.get("numero"));
            assertEquals("VERIFICACAO", out.get("tipo"));
            assertEquals("Verificação", out.get("tipo_label"));
            assertEquals("Ativo", out.get("status"));
            assertEquals(true, out.get("permanente"));
            assertEquals(false, out.get("manter_apos_ciencia"));
            assertNull(out.get("duracao_dias"));
            assertNull(out.get("expira_em"));
            assertEquals(ADMIN_NOME, out.get("criado_por"));         // resolvido no PES_ADMINISTRADOR
        }

        @Test
        @DisplayName("criar — não-permanente com 7 dias grava DURACAO_DIAS e EXPIRA_EM = agora + 7 dias; manterAposCiencia=true é preservado")
        void criar_naoPermanenteCalculaExpiraEm() {
            stubSalaExistente(SALA_ID, SALA_NOME);
            stubSalasLivres();
            stubSequenciaNumero();
            stubAdminExistente();
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            stubSaveAllAlvos();

            // SUT usa LocalDateTime.now() sem Clock: janela [antes, depois] em vez de igualdade.
            LocalDateTime antes = LocalDateTime.now();
            Map<String, Object> out = service.criar(
                    req("VERIFICACAO", false, 7, true, List.of(MSG_CRUA), "SALA", List.of(SALA_ID), null, null), ADMIN_ID);
            LocalDateTime depois = LocalDateTime.now();

            AvisoCadastro cad = ref.get();
            assertFalse(cad.getPermanente());
            assertEquals(7, cad.getDuracaoDias());
            assertTrue(cad.getManterAposCiencia());
            assertNotNull(cad.getExpiraEm());
            assertFalse(cad.getExpiraEm().isBefore(antes.plusDays(7)), "EXPIRA_EM anterior a agora+7d");
            assertFalse(cad.getExpiraEm().isAfter(depois.plusDays(7)), "EXPIRA_EM posterior a agora+7d");

            assertEquals(7, out.get("duracao_dias"));
            assertEquals(false, out.get("permanente"));
            assertEquals(true, out.get("manter_apos_ciencia"));
            assertEquals(cad.getExpiraEm().toString(), out.get("expira_em"));
        }

        @Test
        @DisplayName("criar — mensagens recebem ordem sequencial 1..N na ordem do payload")
        void criar_ordemDasMensagens() {
            stubSequenciaNumero();
            stubAdminExistente();
            stubSaveCadastro();
            List<AvisoMensagem> mensagens = stubSaveMensagens();
            stubSaveAllAlvos();

            service.criar(req("GERAL", null, null, null, List.of(" Primeiro ", "Segundo", "Terceiro"),
                    "TODOS", null, null, null), ADMIN_ID);

            assertEquals(3, mensagens.size());
            assertEquals(List.of(1, 2, 3), mensagens.stream().map(AvisoMensagem::getOrdem).toList());
            assertEquals(List.of("Primeiro", "Segundo", "Terceiro"), mensagens.stream().map(AvisoMensagem::getTexto).toList());
        }

        @Test
        @DisplayName("criar — salaIds repetidos geram um único alvo e uma única validação por sala (dedup)")
        void criar_dedupSalaIds() {
            stubSalaExistente(SALA_ID, SALA_NOME);
            stubSalaExistente(OUTRA_SALA_ID, "Plenário 3");
            stubSalasLivres();
            stubSequenciaNumero();
            stubAdminExistente();
            stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            service.criar(reqSala(List.of(SALA_ID, SALA_ID, OUTRA_SALA_ID, SALA_ID)), ADMIN_ID);

            assertEquals(2, alvos.size());
            assertEquals(List.of(SALA_ID, OUTRA_SALA_ID), alvos.stream().map(AvisoAlvo::getSalaId).toList());
            verify(salaRepo, times(1)).findById(SALA_ID);
            verify(salaRepo, times(1)).findById(OUTRA_SALA_ID);
        }

        @Test
        @DisplayName("criar — operadorIds/tecnicoIds repetidos geram um único alvo por pessoa (dedup)")
        void criar_dedupPessoas() {
            stubOperadorExistente(OPERADOR_ID);
            stubSequenciaNumero();
            stubAdminExistente();
            stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            service.criar(req("VERIFICACAO", null, null, null, List.of(MSG_CRUA), "OPERADOR",
                    null, List.of(OPERADOR_ID, OPERADOR_ID), null), ADMIN_ID);

            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.OPERADOR, alvos.get(0).getAlvoTipo());
            assertEquals(OPERADOR_ID, alvos.get(0).getOperadorId());
            assertNull(alvos.get(0).getSalaId());
            verify(operadorRepo, times(1)).findById(OPERADOR_ID);
        }

        @Test
        @DisplayName("público ADMIN (destravado na decisão 18): grava 1 alvo ADMIN com a FK do administrador")
        void criar_publicoAdminIndividual_aceito() {
            // Antes o cadastro do form recusava ADMIN ("público não suportado") por não haver produtor
            // na UI; a decisão 18 destravou (o card Pessoal passou a oferecer administradores, e parte
            // das pessoas com folha vive em PES_ADMINISTRADOR). Agora ADMIN individual grava alvo real.
            stubAdminExistente(OUTRO_ADMIN_ID);   // destinatário
            stubSequenciaNumero();
            stubAdminExistente();                 // autor (validarAutor + toResumo)
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            service.criar(reqPessoas("PESSOAL", null, null, null, "ADMIN", null, null, List.of(OUTRO_ADMIN_ID)), ADMIN_ID);

            assertEquals(TipoAviso.PESSOAL, ref.get().getTipo());
            assertNull(ref.get().getSubtipo());   // PESSOAL + ADMIN individual: sem subtipo de grupo
            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.ADMIN, alvos.get(0).getAlvoTipo());
            assertEquals(OUTRO_ADMIN_ID, alvos.get(0).getAdminId());
            assertNull(alvos.get(0).getOperadorId());
        }

        @Test
        @DisplayName("público TODOS_ADMIN (grupo Administradores): tipo GERAL grava subtipo GRUPO_ADMINISTRADORES e 1 alvo coletivo sem FK")
        void criar_grupoAdministradores_aceito() {
            // TODOS_ADMIN existe no enum, no CHECK do banco (021) e na leitura desde sempre, mas nunca
            // teve produtor — a decisão 18 lhe dá o primeiro (o card Pessoal, modo "Um grupo").
            stubSequenciaNumero();
            stubAdminExistente();                 // autor
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            service.criar(req("GERAL", null, null, null, List.of(MSG_CRUA), "TODOS_ADMIN", null, null, null), ADMIN_ID);

            assertEquals(SubtipoAviso.GRUPO_ADMINISTRADORES, ref.get().getSubtipo());
            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.TODOS_ADMIN, alvos.get(0).getAlvoTipo());
            assertNull(alvos.get(0).getAdminId());
            verifyNoInteractions(salaRepo, operadorRepo, tecnicoRepo);
        }
    }

    // ═══ criar — AGENDA, grupo (GERAL) e modo PESSOAS (card Pessoal) ═══

    @Nested
    @DisplayName("criar — AGENDA, grupo (GERAL) e modo PESSOAS do card Pessoal")
    class CriarAgendaGrupoPessoas {

        @Test
        @DisplayName("criar — AGENDA: força alvo TODOS, permanente sem expira, subtipo AGENDA, ignorando alvo_tipo/duração enviados")
        void criar_agenda_forcaTodos() {
            stubSequenciaNumero();
            stubAdminExistente();
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            // alvo_tipo="SALA", duracao=5 e sala_ids no payload são IGNORADOS — AGENDA sempre é TODOS/permanente.
            Map<String, Object> out = service.criar(
                    req("AGENDA", false, 5, true, List.of(MSG_CRUA), "SALA", List.of(SALA_ID), null, null), ADMIN_ID);

            AvisoCadastro cad = ref.get();
            assertEquals(TipoAviso.AGENDA, cad.getTipo());
            assertEquals(SubtipoAviso.AGENDA, cad.getSubtipo());
            assertTrue(cad.getPermanente());
            assertNull(cad.getExpiraEm());
            assertNull(cad.getDuracaoDias());
            assertFalse(cad.getManterAposCiencia());
            assertEquals(StatusAviso.ATIVO, cad.getStatus());

            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.TODOS, alvos.get(0).getAlvoTipo());
            assertNull(alvos.get(0).getSalaId());
            // O alvo_tipo/sala do payload nem são lidos: nenhuma validação de sala/ocupação roda.
            verifyNoInteractions(salaRepo);
            assertEquals("AGENDA", out.get("tipo"));
        }

        @Test
        @DisplayName("criar — modo grupo (GERAL): cada coletivo grava o subtipo GRUPO_* (§2) e 1 alvo coletivo sem FK, na ordem")
        void criar_modoGrupo_subtiposEAlvos() {
            stubSequenciaNumero();
            stubAdminExistente();
            List<AvisoCadastro> salvos = new ArrayList<>();
            when(cadastroRepo.save(any(AvisoCadastro.class))).thenAnswer(inv -> {
                AvisoCadastro c = inv.getArgument(0); c.setId(CADASTRO_ID); salvos.add(c); return c;
            });
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            record Caso(String alvoTipo, AlvoTipoAviso alvo, SubtipoAviso subtipo) {}
            List<Caso> casos = List.of(
                    new Caso("TODOS_OPERADORES", AlvoTipoAviso.TODOS_OPERADORES, SubtipoAviso.GRUPO_OPERADORES),
                    new Caso("TODOS_TECNICOS", AlvoTipoAviso.TODOS_TECNICOS, SubtipoAviso.GRUPO_TECNICOS),
                    new Caso("TODOS", AlvoTipoAviso.TODOS, SubtipoAviso.GRUPO_TODOS),
                    new Caso("TODOS_ADMIN", AlvoTipoAviso.TODOS_ADMIN, SubtipoAviso.GRUPO_ADMINISTRADORES));

            for (Caso caso : casos)
                service.criar(req("GERAL", null, null, null, List.of(MSG_CRUA), caso.alvoTipo(), null, null, null), ADMIN_ID);

            assertEquals(4, salvos.size());
            assertEquals(4, alvos.size());
            for (int i = 0; i < casos.size(); i++) {
                assertEquals(casos.get(i).subtipo(), salvos.get(i).getSubtipo(), "subtipo do grupo " + casos.get(i).alvoTipo());
                assertEquals(casos.get(i).alvo(), alvos.get(i).getAlvoTipo());
                assertNull(alvos.get(i).getOperadorId());
                assertNull(alvos.get(i).getTecnicoId());
                assertNull(alvos.get(i).getAdminId());
                assertNull(alvos.get(i).getSalaId());
            }
            verifyNoInteractions(salaRepo, operadorRepo, tecnicoRepo);
        }

        @Test
        @DisplayName("criar — modo PESSOAS: listas mistas (op/téc/adm) viram um cadastro PESSOAL subtipo PESSOAL com um alvo por pessoa")
        void criar_modoPessoas_alvosMistos() {
            stubOperadorExistente(OPERADOR_ID);
            stubTecnicoExistente(TECNICO_ID);
            stubAdminExistente(OUTRO_ADMIN_ID);
            stubSequenciaNumero();
            stubAdminExistente();   // autor
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            service.criar(reqPessoas("PESSOAL", null, null, true, "PESSOAS",
                    List.of(OPERADOR_ID), List.of(TECNICO_ID), List.of(OUTRO_ADMIN_ID)), ADMIN_ID);

            AvisoCadastro cad = ref.get();
            assertEquals(TipoAviso.PESSOAL, cad.getTipo());
            assertEquals(SubtipoAviso.PESSOAL, cad.getSubtipo());
            assertTrue(cad.getManterAposCiencia());
            assertTrue(cad.getPermanente());

            assertEquals(3, alvos.size());
            assertEquals(List.of(AlvoTipoAviso.OPERADOR, AlvoTipoAviso.TECNICO, AlvoTipoAviso.ADMIN),
                    alvos.stream().map(AvisoAlvo::getAlvoTipo).toList());
            assertEquals(OPERADOR_ID, alvos.get(0).getOperadorId());
            assertEquals(TECNICO_ID, alvos.get(1).getTecnicoId());
            assertEquals(OUTRO_ADMIN_ID, alvos.get(2).getAdminId());
        }

        @Test
        @DisplayName("criar — modo PESSOAS não-permanente com 10 dias grava DURACAO_DIAS/EXPIRA_EM; manter=false é preservado")
        void criar_modoPessoas_naoPermanente() {
            stubOperadorExistente(OPERADOR_ID);
            stubSequenciaNumero();
            stubAdminExistente();
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            stubSaveAllAlvos();

            service.criar(reqPessoas("PESSOAL", false, 10, false, "PESSOAS", List.of(OPERADOR_ID), null, null), ADMIN_ID);

            AvisoCadastro cad = ref.get();
            assertFalse(cad.getPermanente());
            assertEquals(10, cad.getDuracaoDias());
            assertNotNull(cad.getExpiraEm());
            assertFalse(cad.getManterAposCiencia());
        }

        @Test
        @DisplayName("criar — modo PESSOAS sem nenhum destinatário é rejeitado, sem consumir a sequence")
        void criar_modoPessoas_semDestinatarios() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(reqPessoas("PESSOAL", null, null, null, "PESSOAS", null, null, null), ADMIN_ID));
            assertEquals("Selecione ao menos um destinatário.", ex.getMessage());
            verifyNoInteractions(entityManager);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criar — modo PESSOAS com operador inexistente responde 404")
        void criar_modoPessoas_operadorInexistente() {
            when(operadorRepo.findById(OPERADOR_ID)).thenReturn(Optional.empty());
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(reqPessoas("PESSOAL", null, null, null, "PESSOAS",
                            List.of(OPERADOR_ID), null, null), ADMIN_ID));
            assertEquals("Operador inválido: " + OPERADOR_ID, ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(entityManager);
            assertNadaEscrito();
        }
    }

    // ═══ listarPessoas — fonte do multi-select do card Pessoal ═══

    @Nested
    @DisplayName("listarPessoas — pessoas dos 3 papéis para o card Pessoal")
    class ListarPessoas {

        private Operador operador(String id, String nome) {
            Operador o = new Operador(); o.setId(id); o.setNomeCompleto(nome); return o;
        }

        private Tecnico tecnico(String id, String nome) {
            Tecnico t = new Tecnico(); t.setId(id); t.setNomeCompleto(nome); return t;
        }

        private Administrador administrador(String id, String nome) {
            Administrador a = new Administrador(); a.setId(id); a.setNomeCompleto(nome); return a;
        }

        @Test
        @DisplayName("listarPessoas — junta os 3 papéis no shape {id, nome, tipo} em ordem pt-BR (caixa e acento não contam)")
        void listarPessoas_shapeEOrdem() {
            when(operadorRepo.findAll()).thenReturn(List.of(operador("op-1", "Érica")));
            when(tecnicoRepo.findAll()).thenReturn(List.of(tecnico("tec-1", "bruno")));
            when(adminRepo.findAll()).thenReturn(List.of(administrador("adm-1", "Zeca"), administrador("adm-2", "Alvaro")));

            List<Map<String, Object>> out = service.listarPessoas();

            // Ordem única pt-BR (F30): acento e caixa não pesam — "Alvaro" < "bruno" < "Érica" < "Zeca".
            assertEquals(List.of("Alvaro", "bruno", "Érica", "Zeca"),
                    out.stream().map(m -> m.get("nome")).toList());
            assertEquals(List.of("ADMINISTRADOR", "TECNICO", "OPERADOR", "ADMINISTRADOR"),
                    out.stream().map(m -> m.get("tipo")).toList());
            assertEquals("adm-2", out.get(0).get("id"));
            assertEquals(List.of("id", "nome", "tipo"), List.copyOf(out.get(0).keySet()));
        }

        @Test
        @DisplayName("listarPessoas — sem ninguém cadastrado devolve lista vazia")
        void listarPessoas_vazio() {
            when(operadorRepo.findAll()).thenReturn(List.of());
            when(tecnicoRepo.findAll()).thenReturn(List.of());
            when(adminRepo.findAll()).thenReturn(List.of());
            assertTrue(service.listarPessoas().isEmpty());
        }
    }

    // ═══ registrarVisto — visto de AGENDA (só AGENDA, idempotente) ═══

    @Nested
    @DisplayName("registrarVisto — visto persistente por usuário, exclusivo do AGENDA")
    class RegistrarVisto {

        @Test
        @DisplayName("registrarVisto — cadastro inexistente responde 404")
        void visto_cadastroInexistente() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.empty());
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrarVisto(CADASTRO_ID, OPERADOR_ID, PapelPessoa.OPERADOR));
            assertEquals("Aviso não encontrado.", ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(cienciaRepo, cienciaWriter);
        }

        @Test
        @DisplayName("registrarVisto — tipo != AGENDA é rejeitado (GERAL, PESSOAL, VERIFICACAO e tipo nulo)")
        void visto_soAgenda() {
            when(cadastroRepo.findById(CADASTRO_ID))
                    .thenReturn(Optional.of(cadastro(TipoAviso.GERAL, StatusAviso.ATIVO)))
                    .thenReturn(Optional.of(cadastro(TipoAviso.PESSOAL, StatusAviso.ATIVO)))
                    .thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.ATIVO)))
                    .thenReturn(Optional.of(cadastro(null, StatusAviso.ATIVO)));
            for (int i = 0; i < 4; i++) {
                ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                        () -> service.registrarVisto(CADASTRO_ID, OPERADOR_ID, PapelPessoa.OPERADOR));
                assertEquals("Este tipo de aviso não registra visualização.", ex.getMessage());
                assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            }
            verifyNoInteractions(cienciaRepo, cienciaWriter);
        }

        @Test
        @DisplayName("registrarVisto — AGENDA não-ATIVO (desativado entre exibição e registro) é no-op silencioso")
        void visto_naoAtivo() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.AGENDA, StatusAviso.DESATIVADO)));
            assertDoesNotThrow(() -> service.registrarVisto(CADASTRO_ID, OPERADOR_ID, PapelPessoa.OPERADOR));
            verifyNoInteractions(cienciaRepo, cienciaWriter);
        }

        @Test
        @DisplayName("registrarVisto — já visto (mesma pessoa, sem sala) é no-op: não regrava")
        void visto_jaVisto() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.AGENDA, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndOperadorId(CADASTRO_ID, OPERADOR_ID)).thenReturn(Optional.of(new AvisoCiencia()));
            service.registrarVisto(CADASTRO_ID, OPERADOR_ID, PapelPessoa.OPERADOR);
            verifyNoInteractions(cienciaWriter);
        }

        @Test
        @DisplayName("registrarVisto — grava sem sala na coluna do papel; corrida (DataIntegrityViolation) é engolida")
        void visto_gravaColunaCertaECorrida() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.AGENDA, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndTecnicoId(CADASTRO_ID, TECNICO_ID)).thenReturn(Optional.empty());
            doThrow(new DataIntegrityViolationException("UK_FRM_AVISO_CIE_TEC")).when(cienciaWriter).inserir(any(AvisoCiencia.class));

            assertDoesNotThrow(() -> service.registrarVisto(CADASTRO_ID, TECNICO_ID, PapelPessoa.TECNICO));

            verify(cienciaWriter).inserir(argThat(c ->
                    TECNICO_ID.equals(c.getTecnicoId())
                            && c.getSalaId() == null
                            && c.getOperadorId() == null
                            && c.getAdminId() == null
                            && c.getCienteEm() != null));
        }
    }

    // ═══ criarPessoalIndividual ═══

    @Nested
    @DisplayName("criarPessoalIndividual — avisos PESSOAL programáticos (publicação de folha de ponto)")
    class CriarPessoalIndividual {

        private final DestinatarioAviso operador = new DestinatarioAviso(OPERADOR_ID, PapelPessoa.OPERADOR);
        private final DestinatarioAviso tecnico = new DestinatarioAviso(TECNICO_ID, PapelPessoa.TECNICO);
        private final DestinatarioAviso admin = new DestinatarioAviso(ADMIN_ID, PapelPessoa.ADMIN);

        @Test
        @DisplayName("criarPessoalIndividual — mensagem nula ou em branco é rejeitada antes de tocar em qualquer repositório")
        void criarPessoal_mensagemObrigatoria() {
            ServiceValidationException nula = assertThrows(ServiceValidationException.class,
                    () -> service.criarPessoalIndividual(List.of(operador), null, ADMIN_ID, SubtipoAviso.FOLHA_SEMANAL));
            assertEquals("Mensagem do aviso é obrigatória.", nula.getMessage());

            ServiceValidationException branca = assertThrows(ServiceValidationException.class,
                    () -> service.criarPessoalIndividual(List.of(operador), "   ", ADMIN_ID, SubtipoAviso.FOLHA_SEMANAL));
            assertEquals("Mensagem do aviso é obrigatória.", branca.getMessage());

            verifyNoInteractions(adminRepo, entityManager);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criarPessoalIndividual — autor inexistente responde 404 e nada é gravado")
        void criarPessoal_autorInexistente() {
            when(adminRepo.findById(ADMIN_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criarPessoalIndividual(List.of(operador), MSG_CRUA, ADMIN_ID, SubtipoAviso.FOLHA_SEMANAL));

            assertEquals("Administrador inválido.", ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(entityManager);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criarPessoalIndividual — sem destinatário válido (lista nula, vazia ou só entradas incompletas) é no-op: nenhum cadastro é criado")
        void criarPessoal_semDestinatariosValidos() {
            stubAdminExistente();
            List<DestinatarioAviso> incompletos = Arrays.asList(
                    null,
                    new DestinatarioAviso(null, PapelPessoa.OPERADOR),
                    new DestinatarioAviso(OPERADOR_ID, null));

            assertDoesNotThrow(() -> service.criarPessoalIndividual(null, MSG_CRUA, ADMIN_ID, SubtipoAviso.FOLHA_SEMANAL));
            assertDoesNotThrow(() -> service.criarPessoalIndividual(List.of(), MSG_CRUA, ADMIN_ID, SubtipoAviso.FOLHA_SEMANAL));
            assertDoesNotThrow(() -> service.criarPessoalIndividual(incompletos, MSG_CRUA, ADMIN_ID, SubtipoAviso.FOLHA_SEMANAL));

            // O autor é validado antes (3 leituras), mas a sequence nunca é consumida.
            verify(adminRepo, times(3)).findById(ADMIN_ID);
            verifyNoInteractions(entityManager);
            assertNadaEscrito();
        }

        @Test
        @DisplayName("criarPessoalIndividual — dedup por (papel, pessoa): a mesma pessoa em papéis distintos vale 2 alvos; repetida no mesmo papel, 1")
        void criarPessoal_dedupPorPapelEPessoa() {
            stubAdminExistente();
            stubSequenciaNumero();
            stubSaveCadastro();
            stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            // OPERADOR_ID aparece 2× como operador (dedup) e 1× como técnico (papel distinto → entra).
            DestinatarioAviso mesmoIdOutroPapel = new DestinatarioAviso(OPERADOR_ID, PapelPessoa.TECNICO);
            service.criarPessoalIndividual(
                    Arrays.asList(operador, operador, mesmoIdOutroPapel, admin), MSG_CRUA, ADMIN_ID,
                    SubtipoAviso.FOLHA_SEMANAL);

            assertEquals(3, alvos.size());
            assertEquals(List.of(AlvoTipoAviso.OPERADOR, AlvoTipoAviso.TECNICO, AlvoTipoAviso.ADMIN),
                    alvos.stream().map(AvisoAlvo::getAlvoTipo).toList());
            assertEquals(OPERADOR_ID, alvos.get(0).getOperadorId());
            assertEquals(OPERADOR_ID, alvos.get(1).getTecnicoId());
            assertEquals(ADMIN_ID, alvos.get(2).getAdminId());
            assertNull(alvos.get(0).getTecnicoId());
            assertNull(alvos.get(2).getOperadorId());
        }

        @Test
        @DisplayName("criarPessoalIndividual — cadastro é PESSOAL, permanente, some após a ciência (manterAposCiencia=false) e a mensagem única vem trimada na ordem 1")
        void criarPessoal_shapeDoCadastro() {
            stubAdminExistente();
            stubSequenciaNumero();
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            List<AvisoMensagem> mensagens = stubSaveMensagens();
            List<AvisoAlvo> alvos = stubSaveAllAlvos();

            service.criarPessoalIndividual(List.of(tecnico), MSG_CRUA, ADMIN_ID, SubtipoAviso.FOLHA_MENSAL);

            AvisoCadastro cad = ref.get();
            assertEquals(TipoAviso.PESSOAL, cad.getTipo());
            assertEquals(SubtipoAviso.FOLHA_MENSAL, cad.getSubtipo()); // §2: o subtipo passado é gravado
            assertTrue(cad.getPermanente());
            assertFalse(cad.getManterAposCiencia());
            assertEquals(StatusAviso.ATIVO, cad.getStatus());
            assertEquals(ADMIN_ID, cad.getCriadoPorId());
            assertEquals(NUMERO_SEQUENCE, cad.getNumero());
            assertNull(cad.getExpiraEm());
            assertNull(cad.getDuracaoDias());

            assertEquals(1, mensagens.size());
            assertEquals(MSG_TRIM, mensagens.get(0).getTexto());
            assertEquals(1, mensagens.get(0).getOrdem());
            assertEquals(CADASTRO_ID, mensagens.get(0).getCadastroId());

            assertEquals(1, alvos.size());
            assertEquals(TECNICO_ID, alvos.get(0).getTecnicoId());
            assertEquals(CADASTRO_ID, alvos.get(0).getCadastroId());
            // IDs vêm de vínculo interno confiável: não revalida cada pessoa (ao contrário de criar).
            verifyNoInteractions(operadorRepo, tecnicoRepo, salaRepo);
        }

        /**
         * A proveniência. O aviso PESSOAL nasce de DOIS caminhos com o mesmo autor, o mesmo tipo
         * e textos parecidos: a publicação de folha e o desfecho de folga do banco de horas. Só o
         * primeiro grava ORIGEM_LOTE_ID — e é exatamente essa diferença que permite à exclusão de um
         * lote apagar os avisos dele sem levar junto os do outro caminho.
         */
        @Test
        @DisplayName("criarPessoalIndividual COM origem grava ORIGEM_LOTE_ID no cadastro")
        void criarPessoal_comOrigemGravaOLote() {
            stubAdminExistente();
            stubSequenciaNumero();
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            stubSaveAllAlvos();

            service.criarPessoalIndividual(List.of(operador), MSG_CRUA, ADMIN_ID, SubtipoAviso.FOLHA_SEMANAL, "lote-42");

            assertEquals("lote-42", ref.get().getOrigemLoteId());
        }

        @Test
        @DisplayName("a sobrecarga SEM origem (desfecho de folga) deixa ORIGEM_LOTE_ID NULO: a exclusão de um lote jamais a alcança")
        void criarPessoal_semOrigemDeixaNulo() {
            stubAdminExistente();
            stubSequenciaNumero();
            AtomicReference<AvisoCadastro> ref = stubSaveCadastro();
            stubSaveMensagens();
            stubSaveAllAlvos();

            service.criarPessoalIndividual(List.of(operador), MSG_CRUA, ADMIN_ID, SubtipoAviso.SOLICITACAO_APROVADA);

            assertNull(ref.get().getOrigemLoteId());
        }
    }

    // ═══ registrarCiencia — idempotência ═══

    @Nested
    @DisplayName("registrarCiencia — idempotência e desvios")
    class RegistrarCiencia {

        @Test
        @DisplayName("registrarCiencia — cadastro inexistente responde 404")
        void registrarCiencia_cadastroInexistente() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrarCiencia(CADASTRO_ID, SALA_ID, OPERADOR_ID, PapelPessoa.OPERADOR));

            assertEquals("Aviso não encontrado.", ex.getMessage());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verifyNoInteractions(cienciaRepo, cienciaWriter);
        }

        @Test
        @DisplayName("registrarCiencia — desvio 1: tipo sem ciência (GERAL/AGENDA) é rejeitado com 400; tipo nulo cai na mesma guarda")
        void registrarCiencia_tipoNaoRegistraCiencia() {
            when(cadastroRepo.findById(CADASTRO_ID))
                    .thenReturn(Optional.of(cadastro(TipoAviso.GERAL, StatusAviso.ATIVO)))
                    .thenReturn(Optional.of(cadastro(TipoAviso.AGENDA, StatusAviso.ATIVO)))
                    .thenReturn(Optional.of(cadastro(null, StatusAviso.ATIVO)));

            for (int i = 0; i < 3; i++) {
                ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                        () -> service.registrarCiencia(CADASTRO_ID, SALA_ID, OPERADOR_ID, PapelPessoa.OPERADOR));
                assertEquals("Este tipo de aviso não registra ciência.", ex.getMessage());
                assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            }
            verifyNoInteractions(cienciaRepo, cienciaWriter);
        }

        @Test
        // PESSOAL sem sala é aceito — provado em registrarCiencia_adminSemSala/_operadorPessoalSemSala.
        @DisplayName("registrarCiencia — VERIFICACAO sem salaId é rejeitada (a ciência de verificação é por sala)")
        void registrarCiencia_salaObrigatoriaSoParaVerificacao() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.ATIVO)));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrarCiencia(CADASTRO_ID, null, OPERADOR_ID, PapelPessoa.OPERADOR));

            assertEquals("Sala é obrigatória para registrar ciência.", ex.getMessage());
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            verifyNoInteractions(cienciaRepo, cienciaWriter);
        }

        @Test
        @DisplayName("registrarCiencia — desvio 2: aviso não-ATIVO (desativado/expirado entre a exibição e o clique) retorna em silêncio, sem consultar nem gravar ciência")
        void registrarCiencia_avisoNaoAtivo() {
            when(cadastroRepo.findById(CADASTRO_ID))
                    .thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.DESATIVADO)))
                    .thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.EXPIRADO)));

            assertDoesNotThrow(() -> service.registrarCiencia(CADASTRO_ID, SALA_ID, OPERADOR_ID, PapelPessoa.OPERADOR));
            assertDoesNotThrow(() -> service.registrarCiencia(CADASTRO_ID, SALA_ID, OPERADOR_ID, PapelPessoa.OPERADOR));

            verifyNoInteractions(cienciaRepo, cienciaWriter);
        }

        @Test
        @DisplayName("registrarCiencia — ciência já existente para (cadastro, sala, pessoa) é no-op: não regrava")
        void registrarCiencia_jaCiente() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndSalaIdAndOperadorId(CADASTRO_ID, SALA_ID, OPERADOR_ID))
                    .thenReturn(Optional.of(new AvisoCiencia()));

            service.registrarCiencia(CADASTRO_ID, SALA_ID, OPERADOR_ID, PapelPessoa.OPERADOR);

            verifyNoInteractions(cienciaWriter);
        }

        @Test
        @DisplayName("registrarCiencia — desvio 3 (corrida): DataIntegrityViolationException do writer é engolida — a operação é idempotente")
        void registrarCiencia_corridaNaoPropaga() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndSalaIdAndOperadorId(CADASTRO_ID, SALA_ID, OPERADOR_ID))
                    .thenReturn(Optional.empty());
            doThrow(new DataIntegrityViolationException("UK_FRM_AVISO_CIE_OP"))
                    .when(cienciaWriter).inserir(any(AvisoCiencia.class));

            assertDoesNotThrow(() -> service.registrarCiencia(CADASTRO_ID, SALA_ID, OPERADOR_ID, PapelPessoa.OPERADOR));

            verify(cienciaWriter).inserir(any(AvisoCiencia.class));
        }

        @Test
        @DisplayName("registrarCiencia — operador: grava a ciência com sala e OPERADOR_ID, deixando TECNICO_ID/ADMIN_ID nulos")
        void registrarCiencia_operadorGravaColunaCerta() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndSalaIdAndOperadorId(CADASTRO_ID, SALA_ID, OPERADOR_ID))
                    .thenReturn(Optional.empty());

            service.registrarCiencia(CADASTRO_ID, SALA_ID, OPERADOR_ID, PapelPessoa.OPERADOR);

            verify(cienciaWriter).inserir(argThat(c ->
                    CADASTRO_ID.equals(c.getCadastroId())
                            && Integer.valueOf(SALA_ID).equals(c.getSalaId())
                            && OPERADOR_ID.equals(c.getOperadorId())
                            && c.getTecnicoId() == null
                            && c.getAdminId() == null
                            && c.getCienteEm() != null));
        }

        @Test
        @DisplayName("registrarCiencia — técnico: consulta e grava pela coluna TECNICO_ID, com a sala da verificação")
        void registrarCiencia_tecnicoGravaColunaCerta() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.VERIFICACAO, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndSalaIdAndTecnicoId(CADASTRO_ID, SALA_ID, TECNICO_ID))
                    .thenReturn(Optional.empty());

            service.registrarCiencia(CADASTRO_ID, SALA_ID, TECNICO_ID, PapelPessoa.TECNICO);

            verify(cienciaWriter).inserir(argThat(c ->
                    TECNICO_ID.equals(c.getTecnicoId())
                            && c.getOperadorId() == null
                            && c.getAdminId() == null
                            && Integer.valueOf(SALA_ID).equals(c.getSalaId())));
        }

        @Test
        @DisplayName("registrarCiencia — admin em aviso PESSOAL: sem sala, a chave da ciência é (cadastro, admin)")
        void registrarCiencia_adminSemSala() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.PESSOAL, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndAdminId(CADASTRO_ID, ADMIN_ID)).thenReturn(Optional.empty());

            service.registrarCiencia(CADASTRO_ID, null, ADMIN_ID, PapelPessoa.ADMIN);

            verify(cienciaWriter).inserir(argThat(c ->
                    ADMIN_ID.equals(c.getAdminId())
                            && c.getSalaId() == null
                            && c.getOperadorId() == null
                            && c.getTecnicoId() == null));
        }

        @Test
        @DisplayName("registrarCiencia — operador em aviso PESSOAL: sem sala, a chave é (cadastro, operador) — não a variante com sala")
        void registrarCiencia_operadorPessoalSemSala() {
            when(cadastroRepo.findById(CADASTRO_ID)).thenReturn(Optional.of(cadastro(TipoAviso.PESSOAL, StatusAviso.ATIVO)));
            when(cienciaRepo.findByCadastroIdAndOperadorId(CADASTRO_ID, OPERADOR_ID)).thenReturn(Optional.empty());

            service.registrarCiencia(CADASTRO_ID, null, OPERADOR_ID, PapelPessoa.OPERADOR);

            verify(cienciaRepo).findByCadastroIdAndOperadorId(CADASTRO_ID, OPERADOR_ID);
            verify(cienciaRepo, never()).findByCadastroIdAndSalaIdAndOperadorId(anyString(), anyInt(), anyString());
            verify(cienciaWriter).inserir(argThat(c -> c.getSalaId() == null && OPERADOR_ID.equals(c.getOperadorId())));
        }
    }

    // ═══ buscarPendentes(tipos) — contrato de chamada ═══

    @Nested
    @DisplayName("buscarPendentes(tipos) — contrato do SQL e filtragem por ciência")
    class BuscarPendentes {

        /** Linha do SELECT: ID, MANTER_APOS_CIENCIA (NUMBER(1) → BigDecimal), TIPO, SUBTIPO. */
        private Object[] row(String id, int manter, TipoAviso tipo) {
            return row(id, manter, tipo, null);
        }

        private Object[] row(String id, int manter, TipoAviso tipo, SubtipoAviso subtipo) {
            return new Object[]{id, BigDecimal.valueOf(manter), tipo.name(), subtipo == null ? null : subtipo.name()};
        }

        private Query stubPendentes(String condAlvoFragmento, List<Object[]> rows) {
            Query q = mockQuery("FROM FRM_AVISO_CADASTRO c", condAlvoFragmento);
            when(q.getResultList()).thenReturn(rows);
            return q;
        }

        @Test
        @DisplayName("buscarPendentes — lista de tipos nula ou vazia devolve lista vazia sem emitir SQL")
        void buscarPendentes_semTiposNaoConsulta() {
            assertEquals(List.of(), service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR, (List<TipoAviso>) null));
            assertEquals(List.of(), service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR, List.of()));

            verifyNoInteractions(entityManager, cienciaRepo, mensagemRepo);
        }

        @Test
        @DisplayName("buscarPendentes — operador: tipos entram concatenados no IN (não como bind), pessoaId é o bind 1 e o alvo cobre o individual e os coletivos TODOS_OPERADORES/TODOS")
        void buscarPendentes_contratoSqlOperador() {
            Query q = mockQuery(
                    "c.TIPO IN ('ESCALA','PESSOAL','GERAL')",
                    "(al.ALVO_TIPO = 'OPERADOR' AND al.OPERADOR_ID = ?) OR al.ALVO_TIPO IN ('TODOS_OPERADORES','TODOS')",
                    "c.STATUS = 'Ativo'",
                    "ORDER BY c.CRIADO_EM");
            when(q.getResultList()).thenReturn(List.of());

            List<Map<String, Object>> out = service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR,
                    List.of(TipoAviso.ESCALA, TipoAviso.PESSOAL, TipoAviso.GERAL));

            assertTrue(out.isEmpty());
            verify(q).setParameter(1, OPERADOR_ID);
        }

        @Test
        @DisplayName("buscarPendentes — técnico: condição de alvo usa TECNICO_ID e os coletivos TODOS_TECNICOS/TODOS")
        void buscarPendentes_contratoSqlTecnico() {
            Query q = mockQuery("(al.ALVO_TIPO = 'TECNICO' AND al.TECNICO_ID = ?) OR al.ALVO_TIPO IN ('TODOS_TECNICOS','TODOS')");
            when(q.getResultList()).thenReturn(List.<Object[]>of(row("cad-a", 1, TipoAviso.ESCALA)));
            when(mensagemRepo.findByCadastroIdOrderByOrdem("cad-a")).thenReturn(List.of(mensagem(1, "Escala publicada")));

            List<Map<String, Object>> out = service.buscarPendentes(TECNICO_ID, PapelPessoa.TECNICO, List.of(TipoAviso.ESCALA));

            assertEquals(1, out.size());
            verify(q).setParameter(1, TECNICO_ID);
        }

        @Test
        @DisplayName("buscarPendentes — admin: condição de alvo usa ADMIN_ID e SÓ o coletivo TODOS_ADMIN (TODOS não atinge admins)")
        void buscarPendentes_contratoSqlAdmin() {
            Query q = mockQuery("(al.ALVO_TIPO = 'ADMIN' AND al.ADMIN_ID = ?) OR al.ALVO_TIPO = 'TODOS_ADMIN'");
            when(q.getResultList()).thenReturn(List.of());

            service.buscarPendentes(ADMIN_ID, PapelPessoa.ADMIN, List.of(TipoAviso.PESSOAL));

            // Se a condição do admin passasse a incluir TODOS (violando AlvoTipoAviso.atingeAdmins()),
            // o SQL não casaria o matcher acima → createNativeQuery devolve null → NPE ruidoso.
            verify(q).setParameter(1, ADMIN_ID);
        }

        @Test
        @DisplayName("buscarPendentes — tipo que exige ciência some da lista quando a pessoa já deu ciência e o aviso não é manter-após-ciência")
        void buscarPendentes_filtraJaCiente() {
            stubPendentes("al.OPERADOR_ID = ?", List.of(
                    row("cad-ciente", 0, TipoAviso.ESCALA),
                    row("cad-pendente", 0, TipoAviso.PESSOAL)));
            when(cienciaRepo.findByCadastroIdAndOperadorId("cad-ciente", OPERADOR_ID))
                    .thenReturn(Optional.of(new AvisoCiencia()));
            when(cienciaRepo.findByCadastroIdAndOperadorId("cad-pendente", OPERADOR_ID))
                    .thenReturn(Optional.empty());
            when(mensagemRepo.findByCadastroIdOrderByOrdem("cad-pendente"))
                    .thenReturn(List.of(mensagem(1, "Sua folha de ponto foi publicada.")));

            List<Map<String, Object>> out = service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR,
                    List.of(TipoAviso.ESCALA, TipoAviso.PESSOAL));

            assertEquals(1, out.size());
            assertEquals("cad-pendente", out.get(0).get("cadastro_id"));
            assertEquals("PESSOAL", out.get(0).get("tipo"));
            // Legado sem subtipo (row com SUBTIPO nulo): título cai no fallback do label do tipo (§2).
            assertEquals("Pessoal", out.get(0).get("titulo"));
            // exige_ciencia=true é o que faz o front renderizar o botão "Ciente" (o par false está
            // no caso GERAL abaixo — sem os dois, um hardcode do campo passaria despercebido).
            assertEquals(true, out.get(0).get("exige_ciencia"));
            // A ciência é consultada sem sala (chave cadastro+pessoa) para avisos sem sala.
            verify(cienciaRepo, never()).findByCadastroIdAndSalaIdAndOperadorId(anyString(), anyInt(), anyString());
        }

        @Test
        @DisplayName("buscarPendentes — manter_apos_ciencia=1 reaparece sempre: a ciência nem chega a ser consultada (curto-circuito)")
        void buscarPendentes_manterAposCienciaNaoConsultaCiencia() {
            stubPendentes("al.OPERADOR_ID = ?", List.<Object[]>of(row("cad-manter", 1, TipoAviso.ESCALA)));
            when(mensagemRepo.findByCadastroIdOrderByOrdem("cad-manter")).thenReturn(List.of(mensagem(1, "Confira sua escala")));

            List<Map<String, Object>> out = service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR, List.of(TipoAviso.ESCALA));

            assertEquals(1, out.size());
            assertEquals(true, out.get(0).get("manter_apos_ciencia"));
            verifyNoInteractions(cienciaRepo);
        }

        @Test
        @DisplayName("buscarPendentes — GERAL não exige ciência: sempre retorna e nunca consulta FRM_AVISO_CIENCIA; o payload traz tipo, exige_ciencia e as mensagens ordenadas")
        void buscarPendentes_geralSempreRetornaEShapeDoPayload() {
            stubPendentes("al.OPERADOR_ID = ?", List.<Object[]>of(row("cad-geral", 0, TipoAviso.GERAL)));
            when(mensagemRepo.findByCadastroIdOrderByOrdem("cad-geral"))
                    .thenReturn(List.of(mensagem(1, "Primeira"), mensagem(2, "Segunda")));

            List<Map<String, Object>> out = service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR, List.of(TipoAviso.GERAL));

            assertEquals(1, out.size());
            Map<String, Object> aviso = out.get(0);
            assertEquals("cad-geral", aviso.get("cadastro_id"));
            assertEquals("GERAL", aviso.get("tipo"));
            assertEquals("Geral", aviso.get("titulo")); // sem subtipo → fallback no label do tipo
            assertEquals(false, aviso.get("exige_ciencia"));
            assertEquals(false, aviso.get("manter_apos_ciencia"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mensagens = (List<Map<String, Object>>) aviso.get("mensagens");
            assertEquals(2, mensagens.size());
            assertEquals(1, mensagens.get(0).get("ordem"));
            assertEquals("Primeira", mensagens.get(0).get("texto"));
            assertEquals("Segunda", mensagens.get(1).get("texto"));

            verifyNoInteractions(cienciaRepo);
        }

        @Test
        @DisplayName("buscarPendentes — quando o cadastro tem SUBTIPO, o título do popup vem do subtipo (§2), não do label do tipo")
        void buscarPendentes_tituloVemDoSubtipo() {
            stubPendentes("al.OPERADOR_ID = ?",
                    List.<Object[]>of(row("cad-folha", 1, TipoAviso.PESSOAL, SubtipoAviso.FOLHA_SEMANAL)));
            when(mensagemRepo.findByCadastroIdOrderByOrdem("cad-folha"))
                    .thenReturn(List.of(mensagem(1, "Sua folha de ponto semanal foi publicada.")));

            List<Map<String, Object>> out = service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR, List.of(TipoAviso.PESSOAL));

            assertEquals(1, out.size());
            // FOLHA_SEMANAL → "Folha semanal disponível" (popup), distinto do label do tipo ("Pessoal").
            assertEquals("Folha semanal disponível", out.get(0).get("titulo"));
        }

        @Test
        @DisplayName("buscarPendentes — AGENDA some depois do visto do usuário (temCiencia sem sala); antes do visto, aparece")
        void buscarPendentes_agendaFiltraPorVisto() {
            // 2 avisos de AGENDA: um já visto pelo operador (some), outro ainda não (aparece). AGENDA não
            // exige ciência, mas o "visto" persistente (§6.2) reusa a mesma tabela e filtra no servidor.
            stubPendentes("al.OPERADOR_ID = ?", List.of(
                    row("cad-visto", 0, TipoAviso.AGENDA, SubtipoAviso.AGENDA),
                    row("cad-novo", 0, TipoAviso.AGENDA, SubtipoAviso.AGENDA)));
            when(cienciaRepo.findByCadastroIdAndOperadorId("cad-visto", OPERADOR_ID)).thenReturn(Optional.of(new AvisoCiencia()));
            when(cienciaRepo.findByCadastroIdAndOperadorId("cad-novo", OPERADOR_ID)).thenReturn(Optional.empty());
            when(mensagemRepo.findByCadastroIdOrderByOrdem("cad-novo")).thenReturn(List.of(mensagem(1, "Confira a agenda")));

            List<Map<String, Object>> out = service.buscarPendentes(OPERADOR_ID, PapelPessoa.OPERADOR, List.of(TipoAviso.AGENDA));

            assertEquals(1, out.size());
            assertEquals("cad-novo", out.get(0).get("cadastro_id"));
            assertEquals("AGENDA", out.get(0).get("tipo"));
            assertEquals(false, out.get(0).get("exige_ciencia"));
            // Subtipo AGENDA → título "Agenda Legislativa" (§2), não o label do tipo ("Agenda").
            assertEquals("Agenda Legislativa", out.get(0).get("titulo"));
            // O visto usa a chave sem sala (cadastro+pessoa), nunca a variante com sala.
            verify(cienciaRepo, never()).findByCadastroIdAndSalaIdAndOperadorId(anyString(), anyInt(), anyString());
        }
    }

    // ═══ Sentinela do AvisoCienciaWriter ═══

    @Nested
    @DisplayName("AvisoCienciaWriter — sentinela do REQUIRES_NEW")
    class SentinelaWriter {

        @Test
        @DisplayName("AvisoCienciaWriter.inserir — mantém @Transactional(REQUIRES_NEW) público e não-final: a idempotência de registrarCiencia depende disso")
        void inserir_mantemRequiresNew() throws Exception {
            // A semântica REAL (sub-transação que isola a violação de unicidade) só se prova em
            // banco (furaria o rollback do @DataJpaTest).
            // Esta é a única trava contra a remoção silenciosa da anotação: sem REQUIRES_NEW, a
            // DataIntegrityViolationException capturada em registrarCiencia deixaria a transação
            // chamadora marcada para rollback (UnexpectedRollbackException no commit).
            Method inserir = AvisoCienciaWriter.class.getMethod("inserir", AvisoCiencia.class);

            Transactional tx = inserir.getAnnotation(Transactional.class);
            assertNotNull(tx, "AvisoCienciaWriter.inserir perdeu o @Transactional");
            assertEquals(Propagation.REQUIRES_NEW, tx.propagation(),
                    "AvisoCienciaWriter.inserir precisa de REQUIRES_NEW (§4.5)");

            // O proxy do Spring só intercepta método público e não-final.
            assertTrue(Modifier.isPublic(inserir.getModifiers()));
            assertFalse(Modifier.isFinal(inserir.getModifiers()));
            assertFalse(Modifier.isFinal(AvisoCienciaWriter.class.getModifiers()));
        }
    }
}
