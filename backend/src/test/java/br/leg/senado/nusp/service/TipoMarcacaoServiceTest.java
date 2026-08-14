package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacaoExclusaoLog;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoExclusaoLogRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Catálogo de tipos de ocorrência: quem mexe, o que é um nome/badge repetido e o
 * que morre junto quando um tipo é excluído.
 */
@ExtendWith(MockitoExtension.class)
class TipoMarcacaoServiceTest {

    private static final String MASTER = "master.teste";
    private static final String OUTRO_ADMIN = "outro.admin";
    private static final String CALLER_ID = "adm-1";

    @Mock private PontoTipoMarcacaoRepository tipoRepo;
    @Mock private PontoDiaMarcacaoRepository diaRepo;
    @Mock private PontoPessoaMarcacaoRepository pessoaRepo;
    @Mock private PontoRetificacaoRepository retificacaoRepo;
    @Mock private PontoTipoMarcacaoExclusaoLogRepository trilhaRepo;
    @Mock private PessoaCadastroLookup pessoaCadastro;

    @Captor private ArgumentCaptor<List<PontoTipoMarcacao>> tiposSalvos;
    @Captor private ArgumentCaptor<PontoTipoMarcacaoExclusaoLog> trilhaGravada;

    private TipoMarcacaoService service;

    @BeforeEach
    void setUp() {
        // ObjectMapper REAL: o RESUMO da trilha é JSON de verdade, e é ele que a auditoria guarda.
        service = new TipoMarcacaoService(tipoRepo, diaRepo, pessoaRepo, retificacaoRepo, trilhaRepo,
                pessoaCadastro, new ObjectMapper());
        ReflectionTestUtils.setField(service, "masterUsername", MASTER);
    }

    // ── fixtures ─────────────────────────────────────────────────

    private static PontoTipoMarcacao tipo(String id, String nome, String badge, String escopo) {
        PontoTipoMarcacao t = new PontoTipoMarcacao();
        t.setId(id);
        t.setNome(nome);
        t.setNomeNorm(TipoMarcacaoService.normalizar(nome));
        t.setBadge(badge);
        t.setBadgeNorm(TipoMarcacaoService.normalizar(badge));
        t.setEscopo(escopo);
        return t;
    }

    /** O tipo que o funcionário escolhe ao retificar o próprio dia — está no catálogo, mas não é do admin. */
    private static PontoTipoMarcacao tipoDoFuncionario(String id, String nome, String badge, String escopo) {
        PontoTipoMarcacao t = tipo(id, nome, badge, escopo);
        t.setVisivelFuncionario(true);
        return t;
    }

    private static PontoDiaMarcacao diaMarcado(LocalDate data, String tipoId) {
        PontoDiaMarcacao m = new PontoDiaMarcacao();
        m.setData(data);
        m.setTipoId(tipoId);
        m.setCriadoPorId(CALLER_ID);
        return m;
    }

    private static PontoPessoaMarcacao pessoaMarcada(String pessoaId, String pessoaTipo,
                                                     LocalDate data, String tipoId) {
        PontoPessoaMarcacao m = new PontoPessoaMarcacao();
        m.setPessoaId(pessoaId);
        m.setPessoaTipo(pessoaTipo);
        m.setData(data);
        m.setTipoId(tipoId);
        m.setCriadoPorId(CALLER_ID);
        return m;
    }

    /** O dia que o próprio funcionário declarou com aquele tipo na retificação da folha. */
    private static PontoRetificacao retificado(String pessoaId, String pessoaTipo,
                                               LocalDate data, String tipoId) {
        PontoRetificacao r = new PontoRetificacao();
        r.setPessoaId(pessoaId);
        r.setPessoaTipo(pessoaTipo);
        r.setData(data);
        r.setTipoId(tipoId);
        return r;
    }

    private static Map<String, Object> pedido(String nome, String badge, String escopo) {
        return Map.of("nome", nome, "badge", badge, "escopo", escopo);
    }

    /** Roda a ação com o log da classe capturado — a linha registrada faz parte do que a exclusão entrega. */
    private static List<ILoggingEvent> capturandoOLog(Runnable acao) {
        Logger logger = (Logger) LoggerFactory.getLogger(TipoMarcacaoService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            acao.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itens(Map<String, Object> resposta, String chave) {
        return (List<Map<String, Object>>) resposta.get(chave);
    }

    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Permissão")
    class Permissao {

        @Test
        @DisplayName("admin comum: 403 em cadastrar, prever e excluir — e nenhum repositório é tocado")
        void naoMasterRecebe403SemTocarNada() {
            List<ServiceValidationException> recusas = List.of(
                    assertThrows(ServiceValidationException.class,
                            () -> service.criar(Map.of("tipos", List.of(pedido("Luto", "Lut", "GLOBAL"))),
                                    OUTRO_ADMIN, CALLER_ID)),
                    assertThrows(ServiceValidationException.class,
                            () -> service.previewExclusao("tipo-1", OUTRO_ADMIN)),
                    assertThrows(ServiceValidationException.class,
                            () -> service.excluir("tipo-1", OUTRO_ADMIN, CALLER_ID)));

            for (ServiceValidationException ex : recusas) {
                assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
                assertEquals("forbidden", ex.getMessage());
            }
            verifyNoInteractions(tipoRepo, diaRepo, pessoaRepo, retificacaoRepo, trilhaRepo, pessoaCadastro);
        }

        @Test
        @DisplayName("ler o catálogo não exige master — é o que alimenta os chips de qualquer admin")
        void listagemNaoExigeMaster() {
            when(tipoRepo.findAll()).thenReturn(List.of(tipo("t1", "Feriado", "Fer", "GLOBAL")));

            Map<String, Object> out = service.listar(null);

            assertEquals(1, itens(out, "tipos").size());
        }

        @Test
        @DisplayName("podeConfigurar — a mesma regra do 403, case-insensitive")
        void flagPodeConfigurar() {
            assertTrue(service.podeConfigurar(MASTER));
            assertTrue(service.podeConfigurar("MASTER.TESTE"));
            assertFalse(service.podeConfigurar(OUTRO_ADMIN));
            assertFalse(service.podeConfigurar(null));
        }
    }

    @Nested
    @DisplayName("Listagem")
    class Listagem {

        @Test
        @DisplayName("sem escopo vêm todos os tipos, ordenados pelo nome em pt-BR (acento não joga para o fim)")
        void listaTodosOrdenados() {
            when(tipoRepo.findAll()).thenReturn(List.of(
                    tipo("t3", "Recesso", "Rec", "INDIVIDUAL"),
                    tipo("t2", "Férias", "Fé", "INDIVIDUAL"),
                    tipo("t1", "Feriado", "Fer", "GLOBAL")));

            List<Map<String, Object>> tipos = itens(service.listar(null), "tipos");

            assertEquals(List.of("Feriado", "Férias", "Recesso"),
                    tipos.stream().map(t -> t.get("nome")).toList());
            assertEquals(Map.of("id", "t1", "nome", "Feriado", "badge", "Fer", "escopo", "GLOBAL",
                            "visivel_funcionario", false),
                    tipos.get(0));
        }

        @Test
        @DisplayName("o resumo diz quais tipos são do funcionário — os que a tela não oferece ao administrador")
        void resumoMarcaOTipoDoFuncionario() {
            when(tipoRepo.findAll()).thenReturn(List.of(
                    tipo("t1", "Feriado", "Fer", "GLOBAL"),
                    tipoDoFuncionario("t9", "Banco de horas", "Ban", "INDIVIDUAL")));

            List<Map<String, Object>> tipos = itens(service.listar(null), "tipos");

            assertEquals("Banco de horas", tipos.get(0).get("nome"));
            assertEquals(true, tipos.get(0).get("visivel_funcionario"));
            assertEquals("Feriado", tipos.get(1).get("nome"));
            assertEquals(false, tipos.get(1).get("visivel_funcionario"));
        }

        @Test
        @DisplayName("com escopo, só os daquele escopo são consultados")
        void listaPorEscopo() {
            when(tipoRepo.findByEscopo("INDIVIDUAL"))
                    .thenReturn(List.of(tipo("t2", "Atestado", "Ate", "INDIVIDUAL")));

            List<Map<String, Object>> tipos = itens(service.listar("individual"), "tipos");

            assertEquals(1, tipos.size());
            assertEquals("Atestado", tipos.get(0).get("nome"));
            verify(tipoRepo, never()).findAll();
        }

        @Test
        @DisplayName("escopo desconhecido é recusado nomeando os válidos")
        void escopoInvalido() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.listar("REGIONAL"));

            assertEquals("Escopo inválido: REGIONAL. Use GLOBAL ou INDIVIDUAL.", ex.getMessage());
            verifyNoInteractions(tipoRepo);
        }
    }

    @Nested
    @DisplayName("Cadastro")
    class Cadastro {

        @Test
        @DisplayName("grava o lote inteiro com a forma normalizada e o autor de cada tipo")
        void cadastraLote() {
            when(tipoRepo.findByNomeNormInOrBadgeNormIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of());

            Map<String, Object> out = service.criar(Map.of("tipos", List.of(
                    pedido("Luto", "Lut", "GLOBAL"),
                    pedido("  Licença  paternidade ", "Lpa", "individual"))), MASTER, CALLER_ID);

            verify(tipoRepo).saveAllAndFlush(tiposSalvos.capture());
            List<PontoTipoMarcacao> salvos = tiposSalvos.getValue();
            assertEquals(2, salvos.size());
            assertEquals("Luto", salvos.get(0).getNome());
            assertEquals("LUTO", salvos.get(0).getNomeNorm());
            assertEquals("LUT", salvos.get(0).getBadgeNorm());
            assertEquals("GLOBAL", salvos.get(0).getEscopo());
            assertEquals(CALLER_ID, salvos.get(0).getCriadoPorId());
            // Espaços das pontas e do meio colapsam no texto exibido; o escopo vem em maiúsculas.
            assertEquals("Licença paternidade", salvos.get(1).getNome());
            assertEquals("LICENCA PATERNIDADE", salvos.get(1).getNomeNorm());
            assertEquals("INDIVIDUAL", salvos.get(1).getEscopo());
            assertEquals(2, itens(out, "criados").size());
        }

        @Test
        @DisplayName("lista vazia (ou ausente) é recusada — o cadastro precisa de ao menos um tipo")
        void loteVazio() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of(), MASTER, CALLER_ID));

            assertEquals("Informe ao menos um tipo em 'tipos'.", ex.getMessage());
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("nome e badge são obrigatórios e têm teto de 20 e 3 caracteres")
        void camposObrigatoriosELimites() {
            assertEquals("Informe o nome do tipo.", erroDoCadastro(pedido("   ", "Lut", "GLOBAL")));
            assertEquals("Informe a badge do tipo.", erroDoCadastro(pedido("Luto", "", "GLOBAL")));
            assertEquals("O nome do tipo deve ter no máximo 20 caracteres.",
                    erroDoCadastro(pedido("Licença para tratar de assuntos", "Lut", "GLOBAL")));
            assertEquals("A badge deve ter no máximo 3 caracteres.",
                    erroDoCadastro(pedido("Luto", "Luto", "GLOBAL")));
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("dentro do mesmo lote, nome repetido por acento ou caixa é repetição")
        void repetidoNoLotePorNome() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of("tipos", List.of(
                            pedido("Férias", "Fé", "INDIVIDUAL"),
                            pedido("ferias", "Fer", "INDIVIDUAL"))), MASTER, CALLER_ID));

            assertEquals("O nome \"ferias\" está repetido na lista.", ex.getMessage());
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("dentro do mesmo lote, badge repetida por acento ou caixa é repetição")
        void repetidoNoLotePorBadge() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of("tipos", List.of(
                            pedido("Feriado", "Fer", "GLOBAL"),
                            pedido("Férias", "Fér", "INDIVIDUAL"))), MASTER, CALLER_ID));

            assertEquals("A badge \"Fér\" está repetida na lista.", ex.getMessage());
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("nome já cadastrado — mesmo em OUTRO escopo — derruba o lote nomeando o conflito")
        void conflitoDeNomeComOCatalogo() {
            when(tipoRepo.findByNomeNormInOrBadgeNormIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of(tipo("t1", "Feriado", "Fer", "GLOBAL")));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of("tipos", List.of(
                            pedido("feriado", "Frd", "INDIVIDUAL"))), MASTER, CALLER_ID));

            assertEquals("Já existe um tipo com o nome \"Feriado\".", ex.getMessage());
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("badge já cadastrada derruba o lote nomeando a badge em uso")
        void conflitoDeBadgeComOCatalogo() {
            when(tipoRepo.findByNomeNormInOrBadgeNormIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of(tipo("t1", "Feriado", "Fer", "GLOBAL")));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of("tipos", List.of(
                            pedido("Férias", "fer", "INDIVIDUAL"))), MASTER, CALLER_ID));

            assertEquals("Já existe um tipo com a badge \"Fer\".", ex.getMessage());
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("o nome que a planilha usa para contar folgas é reservado — cadastrá-lo inflaria a linha \"Folgas\"")
        void nomeReservadoDaFolga() {
            for (String grafia : List.of("Banco de horas", "banco de  horas", "BANCO DE HORAS")) {
                ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                        () -> service.criar(Map.of("tipos", List.of(pedido(grafia, "BH", "GLOBAL"))),
                                MASTER, CALLER_ID));
                assertTrue(ex.getMessage().contains("reservado pelo sistema"), ex.getMessage());
            }
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("caracteres que crescem em maiúscula são recusados antes de estourar a coluna")
        void formaNormalizadaTambemTemTeto() {
            // "ß" vira "SS": a badge de 2 caracteres normaliza para 4 e não caberia em BADGE_NORM.
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of("tipos", List.of(pedido("Luto", "ßß", "GLOBAL"))),
                            MASTER, CALLER_ID));

            assertTrue(ex.getMessage().contains("Limite de caracteres excedido"), ex.getMessage());
            verify(tipoRepo, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("corrida com outro cadastro: a constraint decide e a recusa continua legível")
        void corridaNaUnicidade() {
            when(tipoRepo.findByNomeNormInOrBadgeNormIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of());
            when(tipoRepo.saveAllAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("ORA-00001"));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of("tipos", List.of(pedido("Luto", "Lut", "GLOBAL"))),
                            MASTER, CALLER_ID));

            assertEquals("Não foi possível concluir a operação.", ex.getMessage());
        }

        private String erroDoCadastro(Map<String, Object> item) {
            return assertThrows(ServiceValidationException.class,
                    () -> service.criar(Map.of("tipos", List.of(item)), MASTER, CALLER_ID)).getMessage();
        }
    }

    @Nested
    @DisplayName("Exclusão")
    class Exclusao {

        @Test
        @DisplayName("preview de tipo geral conta as marcações de dia e não fala em funcionários")
        void previewGlobal() {
            when(tipoRepo.findById("t1")).thenReturn(java.util.Optional.of(
                    tipo("t1", "Feriado", "Fer", "GLOBAL")));
            when(diaRepo.findByTipoIdOrderByData("t1")).thenReturn(List.of(
                    diaMarcado(LocalDate.of(2026, 7, 9), "t1"),
                    diaMarcado(LocalDate.of(2026, 9, 7), "t1")));

            Map<String, Object> pv = service.previewExclusao("t1", MASTER);

            assertEquals(2, pv.get("marcacoes"));
            assertEquals(0, pv.get("pessoas_afetadas"));
            assertEquals(List.of(), pv.get("pessoas"));
            verify(pessoaRepo, never()).findByTipoIdOrderByData(anyString());
        }

        @Test
        @DisplayName("preview de tipo individual nomeia os funcionários afetados, sem repetir quem tem vários dias")
        void previewIndividual() {
            when(tipoRepo.findById("t2")).thenReturn(java.util.Optional.of(
                    tipo("t2", "Férias", "Fé", "INDIVIDUAL")));
            when(pessoaRepo.findByTipoIdOrderByData("t2")).thenReturn(List.of(
                    pessoaMarcada("op-1", "OPERADOR", LocalDate.of(2026, 7, 6), "t2"),
                    pessoaMarcada("op-1", "OPERADOR", LocalDate.of(2026, 7, 7), "t2"),
                    pessoaMarcada("tec-2", "TECNICO", LocalDate.of(2026, 7, 8), "t2")));
            when(pessoaCadastro.nome("op-1", "OPERADOR")).thenReturn("Bruno Alves");
            when(pessoaCadastro.nome("tec-2", "TECNICO")).thenReturn("Ana Prado");

            Map<String, Object> pv = service.previewExclusao("t2", MASTER);

            assertEquals(3, pv.get("marcacoes"));
            assertEquals(2, pv.get("pessoas_afetadas"));
            assertEquals(List.of("Ana Prado", "Bruno Alves"), pv.get("pessoas"));
        }

        @Test
        @DisplayName("preview separa o que o admin marcou do que o funcionário declarou, e une as pessoas das duas fontes")
        void previewSeparaMarcacoesDeRetificacoes() {
            when(tipoRepo.findById("t2")).thenReturn(java.util.Optional.of(
                    tipo("t2", "Férias", "Fé", "INDIVIDUAL")));
            when(pessoaRepo.findByTipoIdOrderByData("t2")).thenReturn(List.of(
                    pessoaMarcada("op-1", "OPERADOR", LocalDate.of(2026, 7, 6), "t2"),
                    pessoaMarcada("op-1", "OPERADOR", LocalDate.of(2026, 7, 7), "t2")));
            when(retificacaoRepo.findByTipoIdOrderByData("t2")).thenReturn(List.of(
                    retificado("op-1", "OPERADOR", LocalDate.of(2026, 7, 8), "t2"),
                    retificado("tec-2", "TECNICO", LocalDate.of(2026, 7, 9), "t2"),
                    retificado("tec-2", "TECNICO", LocalDate.of(2026, 7, 10), "t2")));
            when(pessoaCadastro.nome("op-1", "OPERADOR")).thenReturn("Bruno Alves");
            when(pessoaCadastro.nome("tec-2", "TECNICO")).thenReturn("Ana Prado");

            Map<String, Object> pv = service.previewExclusao("t2", MASTER);

            // A tela nomeia as duas coisas: marcação é o que o administrador pôs no calendário,
            // retificação é o que o funcionário declarou na própria folha — as cinco linhas nunca
            // viram um número só.
            assertEquals(2, pv.get("marcacoes"));
            assertEquals(3, pv.get("retificacoes"));
            // quem tem marcação E declaração aparece uma vez
            assertEquals(2, pv.get("pessoas_afetadas"));
            assertEquals(List.of("Ana Prado", "Bruno Alves"), pv.get("pessoas"));
        }

        @Test
        @DisplayName("as declarações entram no preview do tipo geral também — elas não olham o escopo")
        void previewGlobalContaAsRetificacoes() {
            when(tipoRepo.findById("t1")).thenReturn(java.util.Optional.of(
                    tipo("t1", "Feriado", "Fer", "GLOBAL")));
            when(diaRepo.findByTipoIdOrderByData("t1")).thenReturn(List.of(
                    diaMarcado(LocalDate.of(2026, 7, 9), "t1")));
            when(retificacaoRepo.findByTipoIdOrderByData("t1")).thenReturn(List.of(
                    retificado("op-1", "OPERADOR", LocalDate.of(2026, 7, 10), "t1")));
            when(pessoaCadastro.nome("op-1", "OPERADOR")).thenReturn("Bruno Alves");

            Map<String, Object> pv = service.previewExclusao("t1", MASTER);

            assertEquals(1, pv.get("marcacoes"));
            assertEquals(1, pv.get("retificacoes"));
            // o tipo geral não tem marcação de pessoa: quem aparece na lista veio da declaração
            assertEquals(List.of("Bruno Alves"), pv.get("pessoas"));
            verify(pessoaRepo, never()).findByTipoIdOrderByData(anyString());
        }

        @Test
        @DisplayName("tipo inexistente: 404 no preview e na exclusão")
        void tipoInexistente() {
            when(tipoRepo.findById("sumiu")).thenReturn(java.util.Optional.empty());
            when(tipoRepo.lockPorId("sumiu")).thenReturn(java.util.Optional.empty());

            for (ServiceValidationException ex : List.of(
                    assertThrows(ServiceValidationException.class,
                            () -> service.previewExclusao("sumiu", MASTER)),
                    assertThrows(ServiceValidationException.class,
                            () -> service.excluir("sumiu", MASTER, CALLER_ID)))) {
                assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
                assertEquals("Tipo de ocorrência não encontrado.", ex.getMessage());
            }
            verifyNoInteractions(trilhaRepo);
        }

        @Test
        @DisplayName("excluir trava o tipo, apaga as marcações ANTES dele e grava a trilha com as contagens reais")
        void excluiEmCascataComTrilha() {
            PontoTipoMarcacao alvo = tipo("t2", "Férias", "Fé", "INDIVIDUAL");
            when(tipoRepo.lockPorId("t2")).thenReturn(java.util.Optional.of(alvo));
            when(pessoaRepo.findByTipoIdOrderByData("t2")).thenReturn(List.of(
                    pessoaMarcada("op-1", "OPERADOR", LocalDate.of(2026, 7, 6), "t2"),
                    pessoaMarcada("op-1", "OPERADOR", LocalDate.of(2026, 7, 7), "t2")));
            when(pessoaCadastro.nome("op-1", "OPERADOR")).thenReturn("Bruno Alves");

            Map<String, Object> resumo = service.excluir("t2", MASTER, CALLER_ID);

            // A FK não tem cascade: as marcações precisam sair antes do tipo, e o flush garante a ordem.
            InOrder ordem = inOrder(pessoaRepo, tipoRepo, trilhaRepo);
            ordem.verify(pessoaRepo).deleteAll(any());
            ordem.verify(pessoaRepo).flush();
            ordem.verify(tipoRepo).delete(alvo);
            ordem.verify(trilhaRepo).save(any());

            assertEquals(2, resumo.get("marcacoes"));
            assertEquals(1, resumo.get("pessoas_afetadas"));

            verify(trilhaRepo).save(trilhaGravada.capture());
            PontoTipoMarcacaoExclusaoLog trilha = trilhaGravada.getValue();
            assertEquals("t2", trilha.getTipoId());
            assertEquals("Férias", trilha.getNome());
            assertEquals("Fé", trilha.getBadge());
            assertEquals("INDIVIDUAL", trilha.getEscopo());
            assertEquals(CALLER_ID, trilha.getExcluidoPorId());
            assertTrue(trilha.getExcluidoEm() != null);
            // O snapshot guarda o que morreu: quem for ler a trilha não tem mais as linhas.
            assertTrue(trilha.getResumo().contains("\"marcacoes\":2"));
            assertTrue(trilha.getResumo().contains("Bruno Alves"));
        }

        @Test
        @DisplayName("excluir leva junto as declarações feitas com o tipo, todas antes dele")
        void excluiTambemAsRetificacoes() {
            PontoTipoMarcacao alvo = tipo("t1", "Luto", "Lut", "GLOBAL");
            List<PontoDiaMarcacao> dias = List.of(diaMarcado(LocalDate.of(2026, 7, 9), "t1"));
            List<PontoRetificacao> declaracoes = List.of(
                    retificado("op-1", "OPERADOR", LocalDate.of(2026, 7, 10), "t1"),
                    retificado("op-1", "OPERADOR", LocalDate.of(2026, 7, 11), "t1"));
            when(tipoRepo.lockPorId("t1")).thenReturn(java.util.Optional.of(alvo));
            when(diaRepo.findByTipoIdOrderByData("t1")).thenReturn(dias);
            when(retificacaoRepo.findByTipoIdOrderByData("t1")).thenReturn(declaracoes);
            when(pessoaCadastro.nome("op-1", "OPERADOR")).thenReturn("Bruno Alves");

            Map<String, Object> resumo = service.excluir("t1", MASTER, CALLER_ID);

            // Nenhuma dessas FKs tem cascade: o tipo é o último a sair, depois de tudo o que aponta
            // para ele — as declarações do funcionário inclusive.
            InOrder ordem = inOrder(diaRepo, retificacaoRepo, tipoRepo);
            ordem.verify(diaRepo).deleteAll(dias);
            ordem.verify(diaRepo).flush();
            ordem.verify(retificacaoRepo).deleteAll(declaracoes);
            ordem.verify(retificacaoRepo).flush();
            ordem.verify(tipoRepo).delete(alvo);

            assertEquals(1, resumo.get("marcacoes"));
            assertEquals(2, resumo.get("retificacoes"));
            assertEquals(1, resumo.get("pessoas_afetadas"));
        }

        @Test
        @DisplayName("a trilha e a linha de log repetem os números do resumo, cada contagem no seu lugar")
        void trilhaELogRepetemAsContagens() {
            PontoTipoMarcacao alvo = tipo("t2", "Férias", "Fé", "INDIVIDUAL");
            when(tipoRepo.lockPorId("t2")).thenReturn(java.util.Optional.of(alvo));
            when(pessoaRepo.findByTipoIdOrderByData("t2")).thenReturn(List.of(
                    pessoaMarcada("op-1", "OPERADOR", LocalDate.of(2026, 7, 6), "t2")));
            when(retificacaoRepo.findByTipoIdOrderByData("t2")).thenReturn(List.of(
                    retificado("tec-2", "TECNICO", LocalDate.of(2026, 7, 8), "t2"),
                    retificado("tec-2", "TECNICO", LocalDate.of(2026, 7, 9), "t2")));
            when(pessoaCadastro.nome("op-1", "OPERADOR")).thenReturn("Bruno Alves");
            when(pessoaCadastro.nome("tec-2", "TECNICO")).thenReturn("Ana Prado");

            List<ILoggingEvent> registrado =
                    capturandoOLog(() -> service.excluir("t2", MASTER, CALLER_ID));

            // O snapshot é o que sobra depois que as linhas somem: as duas contagens vão separadas.
            verify(trilhaRepo).save(trilhaGravada.capture());
            String snapshot = trilhaGravada.getValue().getResumo();
            assertTrue(snapshot.contains("\"marcacoes\":1"), snapshot);
            assertTrue(snapshot.contains("\"retificacoes\":2"), snapshot);
            assertTrue(snapshot.contains("\"pessoas_afetadas\":2"), snapshot);

            assertEquals(1, registrado.size());
            String linha = registrado.get(0).getFormattedMessage();
            assertTrue(linha.contains("1 marcação(ões), 2 retificação(ões)"), linha);
        }

        @Test
        @DisplayName("o tipo que o funcionário declara não é excluível, e a recusa não apaga nada")
        void tipoVisivelAoFuncionarioNaoEhExcluivel() {
            PontoTipoMarcacao alvo = tipoDoFuncionario("t9", "Banco de horas", "Ban", "INDIVIDUAL");
            when(tipoRepo.lockPorId("t9")).thenReturn(java.util.Optional.of(alvo));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.excluir("t9", MASTER, CALLER_ID));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Este tipo não pode ser excluído.", ex.getMessage());
            verify(tipoRepo, never()).delete(any());
            verify(diaRepo, never()).deleteAll(any());
            verify(pessoaRepo, never()).deleteAll(any());
            verify(retificacaoRepo, never()).deleteAll(any());
            // A recusa vem antes do levantamento: nem as linhas são lidas, e não há o que registrar.
            verify(retificacaoRepo, never()).findByTipoIdOrderByData(anyString());
            verifyNoInteractions(trilhaRepo, pessoaCadastro);
        }

        @Test
        @DisplayName("tipo sem nenhuma marcação some sozinho, e a trilha registra o zero")
        void excluiTipoSemMarcacoes() {
            PontoTipoMarcacao alvo = tipo("t1", "Luto", "Lut", "GLOBAL");
            when(tipoRepo.lockPorId("t1")).thenReturn(java.util.Optional.of(alvo));
            when(diaRepo.findByTipoIdOrderByData("t1")).thenReturn(List.of());

            Map<String, Object> resumo = service.excluir("t1", MASTER, CALLER_ID);

            assertEquals(0, resumo.get("marcacoes"));
            verify(tipoRepo).delete(alvo);
            verify(trilhaRepo).save(trilhaGravada.capture());
            assertTrue(trilhaGravada.getValue().getResumo().contains("\"marcacoes\":0"));
        }
    }

    @Nested
    @DisplayName("Normalização")
    class Normalizacao {

        @Test
        @DisplayName("a forma normalizada ignora acento, caixa e espaços de sobra")
        void formaNormalizada() {
            assertEquals("FERIAS", TipoMarcacaoService.normalizar("  férias "));
            assertEquals("FERIAS", TipoMarcacaoService.normalizar("FÉRIAS"));
            assertEquals("P. FACULTATIVO", TipoMarcacaoService.normalizar("P.  facultativo"));
            assertEquals("LIC. MEDICA", TipoMarcacaoService.normalizar("Lic. médica"));
            assertEquals("A DISPOSICAO", TipoMarcacaoService.normalizar("À disposição"));
            assertEquals("", TipoMarcacaoService.normalizar(null));
        }
    }
}
