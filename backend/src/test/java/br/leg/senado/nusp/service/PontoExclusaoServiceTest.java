package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoExclusaoLog;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AvisoAlvoRepository;
import br.leg.senado.nusp.repository.AvisoCadastroRepository;
import br.leg.senado.nusp.repository.AvisoCienciaRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import br.leg.senado.nusp.repository.PontoExclusaoLogRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unitários da exclusão de publicações do Ponto (F59) — o que se prova SEM banco:
 *
 * <ul>
 *   <li>a PERMISSÃO: só o master (a propriedade {@code app.admin.master-username}, nunca um nome
 *       hardcoded) passa; para todos os outros é 403 e <b>nada é tocado</b> — nem lido;</li>
 *   <li>os alvos inexistentes (404) e a página de outro lote (400);</li>
 *   <li>a ORDEM das deleções, que é o estágio inteiro: retificações → flush → avisos → páginas →
 *       flush → re-âncora. Trocar dois passos aqui é ORA-02292 ou uma âncora que volta para a folha
 *       que acabou de ser excluída;</li>
 *   <li>a montagem do preview e do RESUMO da auditoria a partir de fatos mockados.</li>
 * </ul>
 *
 * <p>Que o lock SERIALIZA de fato, que o cascade limpa os filhos do aviso e que a re-âncora acha a
 * folha anterior — isso só o Oracle real diz ({@code PontoExclusaoIT} e
 * {@code PontoExclusaoConcorrenciaIT}).
 */
@ExtendWith(MockitoExtension.class)
class PontoExclusaoServiceTest {

    private static final String MASTER = "master.teste";     // = application-test.yml (nunca "douglas.antunes")
    private static final String OUTRO_ADMIN = "chefe.teste";
    private static final String CALLER_ID = "adm-1";

    private static final String LOTE = "lote-1";
    private static final String PAGINA = "pag-1";
    private static final String OP = "op-1";
    private static final String NOME_OP = "Maria Silva";
    private static final String CADASTRO = "cad-1";

    @Mock private PontoLoteRepository loteRepo;
    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private PontoRetificacaoRepository retificacaoRepo;
    @Mock private PontoBancoSaldoRepository saldoRepo;
    @Mock private PontoExclusaoLogRepository exclusaoLogRepo;
    @Mock private AvisoCadastroRepository cadastroRepo;
    @Mock private AvisoAlvoRepository alvoRepo;
    @Mock private AvisoCienciaRepository cienciaRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private AdministradorRepository administradorRepo;
    @Mock private SaldoAberturaService saldoAberturaService;

    private PontoExclusaoService service;

    /** Os PDFs vivem aqui; a deleção é best-effort e nunca derruba a exclusão. */
    @TempDir
    Path filesDir;

    @BeforeEach
    void setUp() {
        // ObjectMapper REAL: o RESUMO é JSON de verdade, e é ele que a auditoria grava.
        service = new PontoExclusaoService(loteRepo, paginaRepo, retificacaoRepo, saldoRepo, exclusaoLogRepo,
                cadastroRepo, alvoRepo, cienciaRepo, operadorRepo, tecnicoRepo, administradorRepo,
                saldoAberturaService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "masterUsername", MASTER);
        ReflectionTestUtils.setField(service, "filesDir", filesDir.toString());
    }

    // ── fixtures ──

    private static PontoLote lote(String tipo, String status) {
        PontoLote l = new PontoLote();
        l.setId(LOTE);
        l.setTipo(tipo);
        l.setDataInicio(LocalDate.of(2026, 6, 1));
        l.setDataFim(LocalDate.of(2026, 6, 30));
        l.setStatus(status);
        l.setTotalPaginas(1);
        l.setArquivoOriginal("ponto/originais/lote.pdf");
        l.setCriadoPorId(CALLER_ID);
        return l;
    }

    private static PontoLotePagina pagina(String id, String pessoaId) {
        PontoLotePagina p = new PontoLotePagina();
        p.setId(id);
        p.setLoteId(LOTE);
        p.setNumeroPagina(1);
        p.setArquivoPagina("ponto/paginas/" + id + ".pdf");
        if (pessoaId != null) {
            p.setPessoaId(pessoaId);
            p.setPessoaTipo("OPERADOR");
            p.setStatusMatch("MANUAL");
        } else {
            p.setStatusMatch("PENDENTE");
        }
        return p;
    }

    private static PontoRetificacao retificacao(String paginaId, LocalDate data) {
        PontoRetificacao r = new PontoRetificacao();
        r.setId("ret-" + data);
        r.setPaginaId(paginaId);
        r.setPessoaId(OP);
        r.setPessoaTipo("OPERADOR");
        r.setData(data);
        return r;
    }

    private static AvisoCadastro cadastro() {
        AvisoCadastro c = new AvisoCadastro();
        c.setId(CADASTRO);
        c.setOrigemLoteId(LOTE);
        return c;
    }

    private static AvisoAlvo alvoOperador(String operadorId) {
        AvisoAlvo a = new AvisoAlvo();
        a.setId("alvo-" + operadorId);
        a.setCadastroId(CADASTRO);
        a.setAlvoTipo(AlvoTipoAviso.OPERADOR);
        a.setOperadorId(operadorId);
        return a;
    }

    private void stubOperador(String id, String nome) {
        Operador o = new Operador();
        o.setId(id);
        o.setNomeCompleto(nome);
        lenient().when(operadorRepo.findById(id)).thenReturn(Optional.of(o));
    }

    /** Lote publicado + 1 página do OP, sem retificação, sem aviso, sem saldo — o cenário mais simples. */
    private PontoLote cenarioMinimo(String tipo) {
        PontoLote l = lote(tipo, "PUBLICADO");
        when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(l));
        when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(pagina(PAGINA, OP)));
        when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
        when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of());
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
        stubOperador(OP, NOME_OP);
        return l;
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("permissão — só o master, e o resto nem lê o banco")
    class Permissao {

        /**
         * A recusa vem ANTES de qualquer leitura. Não é preciosismo: o preview conta retificações e
         * NOMEIA os destinatários dos avisos de outras pessoas — um admin comum não pode extrair isso
         * nem "de leve", e um DELETE que já leu o lote antes de recusar é um DELETE a um refactor de
         * distância de acontecer.
         */
        @Test
        @DisplayName("corrige F59 — admin comum: 403 nas QUATRO rotas, e nenhum repositório é tocado")
        void naoMasterRecebe403SemTocarNada() {
            List<ServiceValidationException> recusas = List.of(
                    assertThrows(ServiceValidationException.class,
                            () -> service.previewLote(LOTE, OUTRO_ADMIN)),
                    assertThrows(ServiceValidationException.class,
                            () -> service.previewPagina(LOTE, PAGINA, OUTRO_ADMIN)),
                    assertThrows(ServiceValidationException.class,
                            () -> service.excluirLote(LOTE, OUTRO_ADMIN, CALLER_ID)),
                    assertThrows(ServiceValidationException.class,
                            () -> service.excluirPagina(LOTE, PAGINA, OUTRO_ADMIN, CALLER_ID)));

            for (ServiceValidationException ex : recusas) {
                assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
                assertEquals("forbidden", ex.getMessage());
            }
            verifyNoInteractions(loteRepo, paginaRepo, retificacaoRepo, saldoRepo, exclusaoLogRepo,
                    cadastroRepo, alvoRepo, cienciaRepo, saldoAberturaService);
        }

        @Test
        @DisplayName("corrige F59 — usuário nulo/anônimo também leva 403 (nada de master por omissão)")
        void usuarioNuloRecebe403() {
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ServiceValidationException.class,
                            () -> service.excluirLote(LOTE, null, CALLER_ID)).getStatus());
            verifyNoInteractions(loteRepo);
        }

        @Test
        @DisplayName("podeExcluir — a flag do front sai da MESMA regra do 403 (case-insensitive, como o AdminCrudService)")
        void flagPodeExcluir() {
            assertTrue(service.podeExcluir(MASTER));
            assertTrue(service.podeExcluir("MASTER.TESTE"));
            assertFalse(service.podeExcluir(OUTRO_ADMIN));
            assertFalse(service.podeExcluir(null));
        }

        @Test
        @DisplayName("o master passa: a exclusão do lote acontece")
        void masterPassa() {
            cenarioMinimo("SEMANAL");

            service.excluirLote(LOTE, MASTER, CALLER_ID);

            verify(loteRepo).delete(any(PontoLote.class));
            verify(exclusaoLogRepo).save(any(PontoExclusaoLog.class));
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("alvos inválidos")
    class AlvosInvalidos {

        @Test
        @DisplayName("lote inexistente: 404 (no preview e no delete)")
        void loteInexistente() {
            when(loteRepo.findById(LOTE)).thenReturn(Optional.empty());
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.empty());

            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ServiceValidationException.class,
                    () -> service.previewLote(LOTE, MASTER)).getStatus());
            ServiceValidationException delete = assertThrows(ServiceValidationException.class,
                    () -> service.excluirLote(LOTE, MASTER, CALLER_ID));
            assertEquals(HttpStatus.NOT_FOUND, delete.getStatus());
            assertEquals("Lote não encontrado.", delete.getMessage());
        }

        @Test
        @DisplayName("página inexistente: 404")
        void paginaInexistente() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote("MENSAL", "PUBLICADO")));
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.excluirPagina(LOTE, PAGINA, MASTER, CALLER_ID));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("Página não encontrada.", ex.getMessage());
            verify(paginaRepo, never()).deleteAll(anyList());
        }

        @Test
        @DisplayName("página de OUTRO lote: 400 — e nada é excluído (o id do lote não é decoração da URL)")
        void paginaDeOutroLote() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote("MENSAL", "PUBLICADO")));
            PontoLotePagina intrusa = pagina(PAGINA, OP);
            intrusa.setLoteId("outro-lote");
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.of(intrusa));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.excluirPagina(LOTE, PAGINA, MASTER, CALLER_ID));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Página não pertence ao lote informado.", ex.getMessage());
            verify(paginaRepo, never()).deleteAll(anyList());
            verify(retificacaoRepo, never()).deleteAll(anyList());
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("a ordem das deleções (é ela que faz a exclusão funcionar)")
    class OrdemDasDelecoes {

        /**
         * As retificações têm de morrer ANTES das páginas — e com flush, porque a FK_PNT_RETIF_PAGINA
         * não tem cascade: o DELETE da página com uma retificação viva morre em ORA-02292. E a
         * re-âncora só pode rodar DEPOIS do flush das páginas: enquanto a página morta ainda estiver
         * no banco, {@code findCandidatasAncora} a devolve e o saldo re-ancora exatamente na folha
         * que se acabou de excluir.
         */
        @Test
        @DisplayName("corrige F59 — retificações → flush → páginas → flush → re-âncora, nesta ordem")
        void ordemDeDelecaoEFlush() {
            cenarioMinimo("MENSAL");
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA)))
                    .thenReturn(List.of(retificacao(PAGINA, LocalDate.of(2026, 6, 3))));

            service.excluirLote(LOTE, MASTER, CALLER_ID);

            InOrder ordem = inOrder(retificacaoRepo, paginaRepo, loteRepo, saldoAberturaService, exclusaoLogRepo);
            ordem.verify(retificacaoRepo).deleteAll(anyList());
            ordem.verify(retificacaoRepo).flush();
            ordem.verify(paginaRepo).deleteAll(anyList());
            ordem.verify(loteRepo).delete(any(PontoLote.class));
            ordem.verify(paginaRepo).flush();
            ordem.verify(saldoAberturaService).reancorar(OP, "OPERADOR");
            ordem.verify(exclusaoLogRepo).save(any(PontoExclusaoLog.class));
        }

        @Test
        @DisplayName("corrige F59 — a chave das retificações é a PÁGINA (proveniência), nunca a pessoa")
        void retificacoesSaemPelaPagina() {
            cenarioMinimo("SEMANAL");

            service.excluirLote(LOTE, MASTER, CALLER_ID);

            // Se um refactor trocasse isto por "as retificações da pessoa", as retificações que ela
            // registrou por OUTRA folha publicada morreriam junto — o IT prova o dano; aqui a chave.
            verify(retificacaoRepo).findByPaginaIdIn(List.of(PAGINA));
        }

        @Test
        @DisplayName("exclusão de PÁGINA: o lote NÃO é apagado (ele pode ficar vazio — quem o apaga é o X dele)")
        void excluirPaginaNaoApagaOLote() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote("SEMANAL", "PUBLICADO")));
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of());
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
            stubOperador(OP, NOME_OP);

            service.excluirPagina(LOTE, PAGINA, MASTER, CALLER_ID);

            verify(paginaRepo).deleteAll(anyList());
            verify(loteRepo, never()).delete(any(PontoLote.class));
            verify(saldoAberturaService).reancorar(OP, "OPERADOR");
        }

        @Test
        @DisplayName("corrige F59 — o lote é lido pelo caminho que SEGURA a linha (lockPorId), nunca pelo findById solto")
        void exclusaoTravaOLote() {
            cenarioMinimo("SEMANAL");

            service.excluirLote(LOTE, MASTER, CALLER_ID);

            // O mesmo portão da publicação (F49) e do vínculo (F58): sem ele, excluir corre por fora
            // da serialização e a publicação em voo publica um lote que está sendo apagado.
            verify(loteRepo).lockPorId(LOTE);
            verify(loteRepo, never()).findById(anyString());
        }

        /**
         * A página PENDENTE não tem dono: não avisou ninguém e não ancora saldo nenhum. O teste trava
         * o extremo perigoso — "sem pessoa" NÃO pode colapsar com "escopo lote" (onde TODOS os alvos
         * morrem). Se colapsasse, excluir uma página que ninguém reclamou apagaria o aviso de folha de
         * todo mundo do lote.
         */
        @Test
        @DisplayName("corrige F59 — página PENDENTE (sem pessoa): nenhuma re-âncora, e os avisos do lote NEM SÃO CONSULTADOS")
        void paginaPendenteNaoReancoraNemMexeEmAviso() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote("SEMANAL", "PUBLICADO")));
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.of(pagina(PAGINA, null)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());

            service.excluirPagina(LOTE, PAGINA, MASTER, CALLER_ID);

            verify(saldoAberturaService, never()).reancorar(anyString(), anyString());
            verify(cadastroRepo, never()).findByOrigemLoteId(anyString());
            verify(alvoRepo, never()).deleteAll(anyList());
            verify(cadastroRepo, never()).delete(any(AvisoCadastro.class));
            verify(paginaRepo).deleteAll(anyList());   // mas a página, sim, morre
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("avisos — a ORIGEM é a única chave")
    class Avisos {

        @Test
        @DisplayName("corrige F59 — exclusão de LOTE: só os cadastros com ORIGEM_LOTE_ID daquele lote morrem")
        void loteApagaOsCadastrosDaOrigem() {
            cenarioMinimo("MENSAL");
            AvisoCadastro cad = cadastro();
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of(cad));
            when(alvoRepo.findByCadastroId(CADASTRO)).thenReturn(List.of(alvoOperador(OP)));

            service.excluirLote(LOTE, MASTER, CALLER_ID);

            // A pergunta ao banco é pela ORIGEM — nunca por criador/tipo/texto, que levariam junto o
            // aviso do desfecho de folga (outra origem, mesmo autor, mesmo tipo PESSOAL).
            verify(cadastroRepo).findByOrigemLoteId(LOTE);
            verify(cadastroRepo).delete(cad);   // o cascade do banco limpa mensagem/alvo/ciência
        }

        @Test
        @DisplayName("corrige F59 — exclusão de PÁGINA: sai o ALVO e a CIÊNCIA daquela pessoa; o cadastro SOBREVIVE para os demais")
        void paginaApagaSoOAlvoDaPessoa() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote("SEMANAL", "PUBLICADO")));
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of(cadastro()));
            AvisoAlvo alvoDoOp = alvoOperador(OP);
            when(alvoRepo.findByCadastroId(CADASTRO)).thenReturn(List.of(alvoDoOp, alvoOperador("op-2")));
            AvisoCiencia ciencia = new AvisoCiencia();
            when(cienciaRepo.findByCadastroIdAndOperadorId(CADASTRO, OP)).thenReturn(Optional.of(ciencia));
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
            stubOperador(OP, NOME_OP);

            service.excluirPagina(LOTE, PAGINA, MASTER, CALLER_ID);

            verify(alvoRepo).deleteAll(List.of(alvoDoOp));       // só o dele
            verify(cienciaRepo).delete(ciencia);
            verify(cadastroRepo, never()).delete(any(AvisoCadastro.class));   // op-2 ainda precisa do aviso
        }

        @Test
        @DisplayName("corrige F59 — cadastro que fica com ZERO alvos morre junto (aviso ativo sem destinatário é aviso invisível)")
        void cadastroSemAlvosMorre() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote("SEMANAL", "PUBLICADO")));
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
            AvisoCadastro cad = cadastro();
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of(cad));
            when(alvoRepo.findByCadastroId(CADASTRO)).thenReturn(List.of(alvoOperador(OP)));   // ÚNICO alvo
            when(cienciaRepo.findByCadastroIdAndOperadorId(CADASTRO, OP)).thenReturn(Optional.empty());
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
            stubOperador(OP, NOME_OP);

            service.excluirPagina(LOTE, PAGINA, MASTER, CALLER_ID);

            verify(cadastroRepo).delete(cad);
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("preview — as consequências reais daquele item")
    class Preview {

        @BeforeEach
        void loteNoBanco() {
            when(loteRepo.findById(LOTE)).thenReturn(Optional.of(lote("MENSAL", "PUBLICADO")));
        }

        @Test
        @DisplayName("corrige F59 — o preview conta o que morre e diz para onde a âncora volta")
        @SuppressWarnings("unchecked")
        void previewDeLotePublicado() {
            when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of(
                    retificacao(PAGINA, LocalDate.of(2026, 6, 3)),
                    retificacao(PAGINA, LocalDate.of(2026, 6, 4))));
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of(cadastro()));
            when(alvoRepo.findByCadastroId(CADASTRO)).thenReturn(List.of(alvoOperador(OP)));
            stubOperador(OP, NOME_OP);

            // A âncora ATUAL da pessoa é a página que vai morrer → a folha anterior assume.
            PontoBancoSaldo saldo = new PontoBancoSaldo();
            saldo.setAncoraPaginaId(PAGINA);
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.of(saldo));
            PontoLote anterior = lote("SEMANAL", "PUBLICADO");
            anterior.setDataInicio(LocalDate.of(2026, 5, 1));
            anterior.setDataFim(LocalDate.of(2026, 5, 31));
            // List.<Object[]>of: sem o tipo explícito o varargs faria disto um List<Object> de dois itens.
            when(paginaRepo.findCandidatasAncoraExcluindo(eq(OP), eq("OPERADOR"), eq(List.of(PAGINA)), any(Limit.class)))
                    .thenReturn(List.<Object[]>of(new Object[]{pagina("pag-antiga", OP), anterior}));

            Map<String, Object> pv = service.previewLote(LOTE, MASTER);

            assertEquals("LOTE", pv.get("escopo"));
            assertNull(pv.get("pagina"), "o escopo é o lote: não há UMA página alvo");
            assertEquals(1, pv.get("paginas_excluidas"));
            assertEquals(2, pv.get("retificacoes_excluidas"));
            assertEquals(1, pv.get("avisos_removidos"));
            assertEquals(List.of(NOME_OP), pv.get("avisos_destinatarios"));
            assertEquals("06/2026", pv.get("reabre_competencia"), "mensal publicada: o mês reabre");
            assertEquals(2, pv.get("arquivos"), "o PDF da página + o PDF original do lote");

            Map<String, Object> lote = (Map<String, Object>) pv.get("lote");
            assertEquals(Boolean.TRUE, lote.get("publicado"));

            List<Map<String, Object>> pessoas = (List<Map<String, Object>>) pv.get("pessoas");
            assertEquals(1, pessoas.size());
            assertEquals(NOME_OP, pessoas.get(0).get("nome"));
            assertEquals(2, pessoas.get(0).get("retificacoes_excluidas"));
            assertEquals("volta para a folha 01/05/2026 a 31/05/2026", pessoas.get(0).get("reancora"));
        }

        @Test
        @DisplayName("corrige F59 — pessoa sem folha anterior: o preview avisa que ela fica sem folha oficial (abertura 0)")
        void previewSemFolhaAnterior() {
            when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of());
            stubOperador(OP, NOME_OP);

            PontoBancoSaldo saldo = new PontoBancoSaldo();
            saldo.setAncoraPaginaId(PAGINA);
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.of(saldo));
            when(paginaRepo.findCandidatasAncoraExcluindo(eq(OP), eq("OPERADOR"), eq(List.of(PAGINA)), any(Limit.class)))
                    .thenReturn(List.of());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pessoas =
                    (List<Map<String, Object>>) service.previewLote(LOTE, MASTER).get("pessoas");

            assertEquals("fica sem folha oficial — abertura 0", pessoas.get(0).get("reancora"));
        }

        @Test
        @DisplayName("corrige F59 — a âncora que NÃO está entre as páginas excluídas não muda (e a query da candidata nem roda)")
        void previewAncoraDeOutraFolhaNaoMuda() {
            when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of());
            stubOperador(OP, NOME_OP);

            PontoBancoSaldo saldo = new PontoBancoSaldo();
            saldo.setAncoraPaginaId("outra-pagina");   // a folha mais recente da pessoa é outra
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.of(saldo));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pessoas =
                    (List<Map<String, Object>>) service.previewLote(LOTE, MASTER).get("pessoas");

            assertEquals("não muda", pessoas.get(0).get("reancora"));
            verify(paginaRepo, never()).findCandidatasAncoraExcluindo(anyString(), anyString(), anyList(), any(Limit.class));
        }

        @Test
        @DisplayName("lote em REVISÃO: contagens zeradas e nenhuma competência reaberta (não há o que desfazer)")
        void previewDeLoteEmRevisao() {
            when(loteRepo.findById(LOTE)).thenReturn(Optional.of(lote("MENSAL", "REVISAO")));
            when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of());
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
            stubOperador(OP, NOME_OP);

            Map<String, Object> pv = service.previewLote(LOTE, MASTER);

            assertEquals(0, pv.get("retificacoes_excluidas"));
            assertEquals(0, pv.get("avisos_removidos"));
            assertNull(pv.get("reabre_competencia"), "lote não publicado nunca fechou mês nenhum");
            assertEquals(2, pv.get("arquivos"), "os PDFs existem desde o upload — e vão embora");
        }

        @Test
        @DisplayName("preview de PÁGINA: nomeia a folha alvo e conta só o que é dela")
        @SuppressWarnings("unchecked")
        void previewDePagina() {
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA)))
                    .thenReturn(List.of(retificacao(PAGINA, LocalDate.of(2026, 6, 3))));
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of());
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
            stubOperador(OP, NOME_OP);

            Map<String, Object> pv = service.previewPagina(LOTE, PAGINA, MASTER);

            assertEquals("PAGINA", pv.get("escopo"));
            Map<String, Object> pagina = (Map<String, Object>) pv.get("pagina");
            assertEquals(PAGINA, pagina.get("id"));
            assertEquals(NOME_OP, pagina.get("pessoa_nome"));
            assertEquals(1, pv.get("arquivos"), "o PDF original é do LOTE: não sai com uma página");
            assertEquals(1, pv.get("retificacoes_excluidas"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("auditoria — o snapshot do que morreu")
    class Auditoria {

        @Test
        @DisplayName("corrige F59 — a trilha grava quem/quando/o quê, com as contagens REAIS e a âncora resultante")
        void trilhaComSnapshotReal() {
            cenarioMinimo("MENSAL");
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of(
                    retificacao(PAGINA, LocalDate.of(2026, 6, 3)),
                    retificacao(PAGINA, LocalDate.of(2026, 6, 4))));
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of(cadastro()));
            when(alvoRepo.findByCadastroId(CADASTRO)).thenReturn(List.of(alvoOperador(OP)));
            // Depois da re-âncora a pessoa ficou ancorada na folha de maio.
            PontoBancoSaldo saldo = new PontoBancoSaldo();
            saldo.setAncoraData(LocalDate.of(2026, 5, 31));
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.of(saldo));

            service.excluirLote(LOTE, MASTER, CALLER_ID);

            ArgumentCaptor<PontoExclusaoLog> captor = ArgumentCaptor.forClass(PontoExclusaoLog.class);
            verify(exclusaoLogRepo).save(captor.capture());
            PontoExclusaoLog trilha = captor.getValue();

            assertEquals("LOTE", trilha.getEscopo());
            assertEquals(LOTE, trilha.getLoteId());
            assertNull(trilha.getPaginaId(), "escopo LOTE não nomeia página (CK_PNT_EXCLUSAO_PAGINA)");
            assertEquals(CALLER_ID, trilha.getExcluidoPorId());
            assertTrue(trilha.getExcluidoEm() != null, "o carimbo é do relógio da aplicação (nunca SYSTIMESTAMP)");

            // O RESUMO é o SNAPSHOT: as linhas que ele descreve já não existem para quem for lê-lo.
            String resumo = trilha.getResumo();
            assertTrue(resumo.contains("\"retificacoes_excluidas\":2"), () -> resumo);
            assertTrue(resumo.contains("\"avisos_removidos\":1"), () -> resumo);
            assertTrue(resumo.contains("\"avisos_cadastros_removidos\":1"), () -> resumo);
            assertTrue(resumo.contains("\"reabre_competencia\":\"06/2026\""), () -> resumo);
            assertTrue(resumo.contains(NOME_OP), () -> "o nome de quem perdeu a folha: " + resumo);
            assertTrue(resumo.contains("ancorado em 31/05/2026"),
                    () -> "a âncora RESULTANTE, lida depois da re-âncora: " + resumo);
        }

        @Test
        @DisplayName("exclusão de PÁGINA: a trilha nomeia a página (escopo PAGINA)")
        void trilhaDePagina() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote("SEMANAL", "PUBLICADO")));
            when(paginaRepo.findById(PAGINA)).thenReturn(Optional.of(pagina(PAGINA, OP)));
            when(retificacaoRepo.findByPaginaIdIn(List.of(PAGINA))).thenReturn(List.of());
            when(cadastroRepo.findByOrigemLoteId(LOTE)).thenReturn(List.of());
            when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
            stubOperador(OP, NOME_OP);

            service.excluirPagina(LOTE, PAGINA, MASTER, CALLER_ID);

            ArgumentCaptor<PontoExclusaoLog> captor = ArgumentCaptor.forClass(PontoExclusaoLog.class);
            verify(exclusaoLogRepo).save(captor.capture());
            assertEquals("PAGINA", captor.getValue().getEscopo());
            assertEquals(PAGINA, captor.getValue().getPaginaId());
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("arquivos")
    class Arquivos {

        /**
         * Sem transação ativa (é o caso deste unitário) a deleção acontece na hora; com transação, ela
         * é registrada para DEPOIS do commit. Em nenhum dos dois um arquivo some antes do banco decidir.
         */
        @Test
        @DisplayName("os PDFs da página e do lote são apagados — e um arquivo que não existe não derruba a exclusão")
        void apagaOsPdfs() throws Exception {
            cenarioMinimo("SEMANAL");
            Path pagina = filesDir.resolve("ponto/paginas/" + PAGINA + ".pdf");
            java.nio.file.Files.createDirectories(pagina.getParent());
            java.nio.file.Files.writeString(pagina, "%PDF");
            // O PDF original NÃO existe em disco (deleteIfExists engole) — a exclusão segue.

            service.excluirLote(LOTE, MASTER, CALLER_ID);

            assertFalse(java.nio.file.Files.exists(pagina), "o PDF da folha excluída tem de sair do disco");
            verify(exclusaoLogRepo).save(any(PontoExclusaoLog.class));
        }
    }
}
