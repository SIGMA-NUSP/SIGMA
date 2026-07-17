package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.LockModeType;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unitários de {@link PontoService#publicar} — as duas guardas da publicação.
 *
 * <p><b>Concorrência:</b> aqui se prova apenas que a publicação lê o lote pelo caminho que SEGURA a
 * linha ({@code lockPorId} → {@code SELECT ... FOR UPDATE}), nunca pelo {@code findById} solto. Que
 * o lock de fato serializa duas transações só o Oracle real pode dizer — é o
 * {@code PontoPublicacaoConcorrenteIT}.
 *
 * <p><b>Folha mensal:</b> a MENSAL é única por pessoa+competência e FECHA o mês. Sobreposição entre
 * SEMANAIS continua livre — elas são cumulativas por desenho, e o que NÃO deve existir (uma
 * validação genérica de sobreposição de período) é provado pelas contraprovas.
 */
@ExtendWith(MockitoExtension.class)
class PontoServiceTest {

    @Mock private PontoLoteRepository loteRepo;
    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private AdministradorRepository administradorRepo;
    @Mock private AvisoService avisoService;
    @Mock private SaldoAberturaService saldoAberturaService;
    @Mock private RetificacaoService retificacaoService;
    /** "A pessoa existe?" é uma pergunta só — o service não faz mais o switch por repositório. */
    @Mock private PessoaCadastroLookup pessoaCadastro;

    @InjectMocks
    private PontoService service;

    /** O único {@code @Value} do service; a publicação lê o PDF de cada página daqui. */
    @TempDir
    Path filesDir;

    private static final String LOTE = "lote-1";
    private static final String ADMIN = "adm-1";
    private static final String OP = "op-1";
    private static final String NOME_OP = "Maria Silva";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "filesDir", filesDir.toString());
    }

    // ── fixtures ──

    private static PontoLote lote(String tipo, LocalDate inicio, LocalDate fim, String status) {
        PontoLote l = new PontoLote();
        l.setId(LOTE);
        l.setTipo(tipo);
        l.setDataInicio(inicio);
        l.setDataFim(fim);
        l.setTotalPaginas(1);
        l.setArquivoOriginal("ponto/originais/lote.pdf");
        l.setCriadoPorId(ADMIN);
        l.setStatus(status);
        return l;
    }

    private static PontoLote emRevisao(String tipo, LocalDate inicio, LocalDate fim) {
        return lote(tipo, inicio, fim, "REVISAO");
    }

    /** Página vinculada. O PDF não existe no {@code @TempDir}: o BANCO fica nulo (WARN), sem abortar. */
    private static PontoLotePagina pagina(int numero, String pessoaId, String pessoaTipo) {
        PontoLotePagina p = new PontoLotePagina();
        p.setId("pag-" + numero);
        p.setLoteId(LOTE);
        p.setNumeroPagina(numero);
        p.setArquivoPagina("ponto/paginas/p" + numero + ".pdf");
        p.setPessoaId(pessoaId);
        p.setPessoaTipo(pessoaTipo);
        p.setStatusMatch(pessoaId == null ? "PENDENTE" : "MANUAL");
        return p;
    }

    private static Operador operador() {
        Operador op = new Operador();
        op.setId(OP);
        op.setNomeCompleto(NOME_OP);
        return op;
    }

    /** Lote em revisão + suas páginas, com o operador no elenco (é dele o nome que a recusa cita). */
    private void cenario(PontoLote lote, PontoLotePagina... paginas) {
        when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote));
        when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(paginas));
        when(operadorRepo.findAll()).thenReturn(List.of(operador()));
    }

    /**
     * Resposta da guarda: a pessoa já tem MENSAL publicada tocando a competência do lote. O 3º campo é
     * o DATA_INICIO da mensal existente — dele sai o mês que a recusa cita.
     */
    private void mensalPublicadaDe(String pessoaId, String pessoaTipo, LocalDate inicio, LocalDate fim) {
        when(paginaRepo.findPessoasComMensalPublicadaNoPeriodo(eq(LOTE), anyCollection(), eq(inicio), eq(fim)))
                .thenReturn(List.<Object[]>of(new Object[] { pessoaId, pessoaTipo, inicio }));
    }

    private void nenhumaMensalPublicada() {
        when(paginaRepo.findPessoasComMensalPublicadaNoPeriodo(eq(LOTE), anyCollection(), any(), any()))
                .thenReturn(List.of());
    }

    private void assertNadaGravado() {
        verify(loteRepo, never()).save(any());
        verify(paginaRepo, never()).saveAll(any());
        verifyNoInteractions(avisoService, saldoAberturaService);
    }

    /** A frase que o admin lê na tela vai em {@code message} (o botão Publicar não lê {@code error}). */
    private static String mensagemDoAdmin(ServiceValidationException ex) {
        return String.valueOf(ex.getExtraFields().get("message"));
    }

    // ══════════════════════════════════════════════════════════════
    // A leitura do lote na publicação segura a linha
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("publicar lê o lote com lock de linha (lockPorId), nunca com findById solto")
    void publicarSeguraALinhaDoLote() {
        cenario(emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                pagina(1, OP, "OPERADOR"));
        nenhumaMensalPublicada();

        service.publicar(LOTE, false);

        verify(loteRepo).lockPorId(LOTE);
        // O findById NÃO é usado aqui: ele lê sem segurar a linha, e é por essa fresta que duas
        // publicações concorrentes do mesmo lote passavam juntas pela checagem de status.
        verify(loteRepo, never()).findById(any());
    }

    @Test
    @DisplayName("o SELECT do lote na publicação é FOR UPDATE: lockPorId carrega @Lock(PESSIMISTIC_WRITE)")
    void lockPorIdCarregaLockPessimista() throws NoSuchMethodException {
        // Sem esta trava, apagar a anotação (um merge malfeito, um refactor que "limpa" imports) deixaria
        // o método com o mesmo nome e a mesma assinatura: o unitário acima e a suíte inteira seguiriam
        // verdes, e o lock teria evaporado em silêncio.
        Lock lock = PontoLoteRepository.class.getMethod("lockPorId", String.class).getAnnotation(Lock.class);

        assertNotNull(lock, "lockPorId sem @Lock não segura a linha: o SELECT vira uma leitura comum");
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    @DisplayName("lote já PUBLICADO (o que a 2ª transação vê depois do lock) → 400 e nada gravado")
    void publicarLoteJaPublicado() {
        when(loteRepo.lockPorId(LOTE))
                .thenReturn(Optional.of(lote("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "PUBLICADO")));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.publicar(LOTE, true));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("Lote já está publicado.", ex.getMessage());
        // A recusa da corrida também precisa aparecer na tela do admin, não só no corpo do erro.
        assertEquals("Lote já está publicado.", mensagemDoAdmin(ex));
        assertNadaGravado();
        verifyNoInteractions(paginaRepo);   // nem chega a olhar as páginas
    }

    @Test
    @DisplayName("a re-âncora do banco só roda DEPOIS do lote virar PUBLICADO (a query da âncora filtra status)")
    void reancoraDepoisDoFlipDeStatus() {
        PontoLote lote = emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        cenario(lote, pagina(1, OP, "OPERADOR"));
        nenhumaMensalPublicada();
        // findCandidatasAncora exige l.status = 'PUBLICADO': re-ancorar antes do flip devolveria âncora
        // vazia e zeraria o saldo de abertura da pessoa — em silêncio, sem quebrar nada.
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenAnswer(inv -> {
            assertEquals("PUBLICADO", lote.getStatus(),
                    "a publicação já tem de ter virado o status quando a re-âncora consulta a folha");
            return null;
        });

        service.publicar(LOTE, false);

        InOrder ordem = inOrder(loteRepo, paginaRepo, saldoAberturaService);
        ordem.verify(loteRepo).save(lote);                       // flip do status
        ordem.verify(paginaRepo).saveAll(anyList());             // BANCO_FINAL_MIN das páginas
        ordem.verify(saldoAberturaService).reancorar(OP, "OPERADOR");
    }

    @Test
    @DisplayName("lote inexistente → 404 'Lote não encontrado.'")
    void publicarLoteInexistente() {
        when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.empty());

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.publicar(LOTE, true));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("Lote não encontrado.", ex.getMessage());
        assertNadaGravado();
    }

    // ══════════════════════════════════════════════════════════════
    // A alteração de vínculo serializa com a publicação
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("atualizarVinculo passa pelo mesmo portão da publicação")
    class VinculoSobLock {

        @Test
        @DisplayName("atualizarVinculo lê o lote com lock de linha (lockPorId), nunca com findById solto")
        void vinculoSeguraALinhaDoLote() {
            PontoLotePagina pendente = pagina(1, null, null);
            when(loteRepo.lockPorId(LOTE))
                    .thenReturn(Optional.of(emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))));
            when(paginaRepo.findById("pag-1")).thenReturn(Optional.of(pendente));
            when(pessoaCadastro.existe(OP, "OPERADOR")).thenReturn(true);
            when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(pendente));
            when(operadorRepo.findAll()).thenReturn(List.of(operador()));

            service.atualizarVinculo(LOTE, "pag-1", OP, "OPERADOR");

            verify(loteRepo).lockPorId(LOTE);
            // Sem o lock, a leitura do lote não espera a publicação em voo: o vínculo enxergava o status
            // ainda REVISAO, passava pela guarda abaixo e sobrevivia ao commit da publicação — folha
            // publicada sem aviso, sem BANCO_FINAL_MIN e sem re-âncora.
            verify(loteRepo, never()).findById(any());
            assertEquals(OP, pendente.getPessoaId());
            assertEquals("MANUAL", pendente.getStatusMatch());
        }

        @Test
        @DisplayName("lote já PUBLICADO (o que o vínculo vê depois de esperar o lock) → 400 e nada gravado")
        void vinculoEmLoteJaPublicado() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(
                    lote("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "PUBLICADO")));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarVinculo(LOTE, "pag-1", OP, "OPERADOR"));

            assertEquals("Lote já publicado não pode ser alterado.", ex.getMessage());
            // É esta a recusa que a corrida produz agora: a página nem chega a ser lida, nada é salvo.
            verify(paginaRepo, never()).save(any());
            verifyNoInteractions(avisoService, saldoAberturaService);
        }

        @Test
        @DisplayName("lote inexistente no vínculo → 404 'Lote não encontrado.'")
        void vinculoEmLoteInexistente() {
            when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarVinculo(LOTE, "pag-1", OP, "OPERADOR"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("Lote não encontrado.", ex.getMessage());
            verify(paginaRepo, never()).save(any());
        }

        /**
         * O ramo NEGATIVO do vínculo — a recusa que existia desde o início e nunca teve teste (a frase aparecia
         * zero vezes na suíte). Ele passa a ser a sentinela do consumo do lookup no {@code PontoService}:
         * quem responde "não existe" é o {@link PessoaCadastroLookup} (provado à parte, no
         * {@code PessoaCadastroLookupTest}); aqui trava-se o que o service FAZ com esse não — 400 com a
         * mensagem do módulo, e a página intocada.
         */
        @Test
        @DisplayName("pessoa inexistente (ou par trocado) no vínculo → 400 e a página não é salva")
        void vinculoComPessoaInexistente() {
            PontoLotePagina pendente = pagina(1, null, null);
            when(loteRepo.lockPorId(LOTE))
                    .thenReturn(Optional.of(emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))));
            when(paginaRepo.findById("pag-1")).thenReturn(Optional.of(pendente));
            when(pessoaCadastro.existe(OP, "TECNICO")).thenReturn(false);   // o par trocado, visto daqui

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.atualizarVinculo(LOTE, "pag-1", OP, "TECNICO"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Operador/técnico/administrador inválido.", ex.getMessage());
            verify(paginaRepo, never()).save(any());
            assertNull(pendente.getPessoaId());   // nem em memória a página foi tocada
        }
    }

    // ══════════════════════════════════════════════════════════════
    // As re-âncoras do lote acontecem em ordem determinística
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a publicação re-ancora as pessoas em ordem determinística (tipo, id), não na ordem das páginas")
    void reancoraNaOrdemDeterminista() {
        // As páginas chegam na ordem INVERSA da ordem do lock: se o laço seguisse a ordem das páginas,
        // dois lotes com as mesmas pessoas em ordens diferentes travariam as linhas de PNT_BANCO_SALDO
        // em sentidos opostos — ORA-00060 (deadlock), que chega ao admin como 500 cru.
        PontoLote lote = emRevisao("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
        when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote));
        when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(
                pagina(1, "tec-1", "TECNICO"),      // TECNICO > OPERADOR > ADMINISTRADOR na ordem do lock
                pagina(2, "op-z", "OPERADOR"),
                pagina(3, "op-a", "OPERADOR"),      // mesmo tipo: desempata pelo id
                pagina(4, "adm-9", "ADMINISTRADOR")));
        when(paginaRepo.findPessoasComMensalPublicadaNoPeriodo(eq(LOTE), anyCollection(), any(), any()))
                .thenReturn(List.of());

        service.publicar(LOTE, false);

        InOrder ordem = inOrder(saldoAberturaService);
        ordem.verify(saldoAberturaService).reancorar("adm-9", "ADMINISTRADOR");
        ordem.verify(saldoAberturaService).reancorar("op-a", "OPERADOR");
        ordem.verify(saldoAberturaService).reancorar("op-z", "OPERADOR");
        ordem.verify(saldoAberturaService).reancorar("tec-1", "TECNICO");
        verifyNoMoreInteractions(saldoAberturaService);
    }

    @Test
    @DisplayName("o backfill (reprocessarBanco) re-ancora na MESMA ordem determinística da publicação")
    void backfillReancoraNaMesmaOrdem() {
        when(paginaRepo.findPublicadasSemBancoFinal()).thenReturn(List.of());
        when(paginaRepo.findPessoasComFolhaPublicada()).thenReturn(List.<Object[]>of(
                new Object[] { "op-z", "OPERADOR" },
                new Object[] { "tec-1", "TECNICO" },
                new Object[] { "op-a", "OPERADOR" }));

        Map<String, Object> out = service.reprocessarBanco();

        // O backfill percorre TODAS as pessoas com folha: rodando junto com uma publicação, sem a ordem
        // comum os dois laços pegariam as mesmas linhas em sentidos opostos.
        InOrder ordem = inOrder(saldoAberturaService);
        ordem.verify(saldoAberturaService).reancorar("op-a", "OPERADOR");
        ordem.verify(saldoAberturaService).reancorar("op-z", "OPERADOR");
        ordem.verify(saldoAberturaService).reancorar("tec-1", "TECNICO");
        verifyNoMoreInteractions(saldoAberturaService);
        assertEquals(3, out.get("pessoas_reancoradas"));
    }

    // ══════════════════════════════════════════════════════════════
    // Guarda da mensal — recusas
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("a folha MENSAL é única por pessoa+competência e fecha o mês")
    class GuardaDaMensal {

        @Test
        @DisplayName("2ª mensal da pessoa no mês (já publicada no banco) → 400 nomeando a pessoa, nada gravado")
        void mensalDuplicadaNoBanco() {
            cenario(emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                    pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.publicar(LOTE, true));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            String msg = mensagemDoAdmin(ex);
            assertEquals(ex.getMessage(), msg, "a frase tem de estar nos dois campos do erro");
            assertTrue(msg.contains(NOME_OP), () -> "a recusa precisa NOMEAR a pessoa: " + msg);
            assertTrue(msg.contains("06/2026"), () -> "e dizer a competência já ocupada: " + msg);
            assertNadaGravado();
        }

        @Test
        @DisplayName("duas folhas mensais da mesma pessoa DENTRO do próprio lote → 400 nomeando a pessoa, nada gravado")
        void mensalDuplicadaNoProprioLote() {
            cenario(emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                    pagina(1, OP, "OPERADOR"),
                    pagina(2, OP, "OPERADOR"));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.publicar(LOTE, true));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            String msg = mensagemDoAdmin(ex);
            assertTrue(msg.contains(NOME_OP), () -> "a recusa precisa NOMEAR a pessoa: " + msg);
            assertTrue(msg.contains("mais de uma folha mensal"), msg);
            assertNadaGravado();
            // O conflito é visível no próprio lote: nem consulta o banco.
            verify(paginaRepo, never()).findPessoasComMensalPublicadaNoPeriodo(any(), anyCollection(), any(), any());
        }

        @Test
        @DisplayName("semanal atrasada de mês já fechado por mensal publicada → 400 nomeando a pessoa, nada gravado")
        void semanalDeMesFechado() {
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28)),
                    pagina(1, OP, "OPERADOR"));
            // A janela consultada é o MÊS inteiro da semanal, não o período dela: é o mês que está fechado.
            mensalPublicadaDe(OP, "OPERADOR", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.publicar(LOTE, true));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            String msg = mensagemDoAdmin(ex);
            assertTrue(msg.contains(NOME_OP), () -> "a recusa precisa NOMEAR a pessoa: " + msg);
            assertTrue(msg.contains("já foi fechado por folha mensal publicada"), msg);
            // Cita o mês da MENSAL que fechou (06/2026), não a janela consultada.
            assertTrue(msg.contains("06/2026"), msg);
            assertNadaGravado();
        }

        @Test
        @DisplayName("a recusa cita o mês REALMENTE fechado, mesmo quando o lote cruza a virada")
        void semanalQueCruzaAViradaCitaOMesFechado() {
            // Semanal 29/06–05/07: a janela consultada abrange junho E julho, mas só junho está fechado.
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 5)),
                    pagina(1, OP, "OPERADOR"));
            when(paginaRepo.findPessoasComMensalPublicadaNoPeriodo(eq(LOTE), anyCollection(),
                    eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 7, 31))))
                    .thenReturn(List.<Object[]>of(new Object[] { OP, "OPERADOR", LocalDate.of(2026, 6, 1) }));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.publicar(LOTE, true));

            String msg = mensagemDoAdmin(ex);
            assertTrue(msg.contains("06/2026"), () -> "junho é o mês fechado: " + msg);
            assertFalse(msg.contains("07/2026"),
                    () -> "julho está aberto — dizer que ele foi fechado seria mentira: " + msg);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Contraprovas: o que a guarda NÃO pode barrar
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("guarda da mensal — contraprovas")
    class ContraprovasDaMensal {

        @Test
        @DisplayName("1ª mensal da pessoa no mês publica normalmente (aviso e re-âncora, uma vez)")
        void primeiraMensalDoMesPublica() {
            PontoLote lote = emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
            cenario(lote, pagina(1, OP, "OPERADOR"));
            nenhumaMensalPublicada();

            Map<String, Object> out = service.publicar(LOTE, true);

            assertEquals("PUBLICADO", out.get("status"));
            assertEquals("PUBLICADO", lote.getStatus());
            assertNotNull(lote.getPublicadoEm());
            verify(loteRepo).save(lote);
            verify(saldoAberturaService, times(1)).reancorar(OP, "OPERADOR");
            // O 4º argumento é a PROVENIÊNCIA: o aviso nasce marcado com o lote que o criou, e é
            // só por essa marca que a exclusão daquele lote sabe reconhecê-lo depois.
            verify(avisoService, times(1)).criarPessoalIndividual(anyList(), anyString(), eq(ADMIN), eq(LOTE));
        }

        @Test
        @DisplayName("o aviso da publicação nasce com ORIGEM = o lote publicado (é a chave da exclusão)")
        void avisoDaPublicacaoLevaAOrigemDoLote() {
            PontoLote lote = emRevisao("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
            cenario(lote, pagina(1, OP, "OPERADOR"));
            nenhumaMensalPublicada();

            service.publicar(LOTE, true);

            // Sem a origem, a exclusão do lote teria de adivinhar quais avisos são dele — e apagaria,
            // por autor/tipo, os avisos PESSOAIS do desfecho de folga, que não são desta publicação.
            verify(avisoService).criarPessoalIndividual(anyList(), anyString(), eq(ADMIN), eq(LOTE));
            verify(avisoService, never()).criarPessoalIndividual(anyList(), anyString(), anyString());
        }

        @Test
        @DisplayName("semanais cumulativas com período sobreposto publicam (a guarda NÃO é de sobreposição)")
        void semanalCumulativaSobrepostaPublica() {
            // A 2ª semanal do mês cobre 01–12 e reengloba os dias da 1ª (01–05): sobrepor é o normal.
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12)),
                    pagina(1, OP, "OPERADOR"));
            nenhumaMensalPublicada();

            Map<String, Object> out = service.publicar(LOTE, true);

            assertEquals("PUBLICADO", out.get("status"));
            // A única pergunta feita ao banco é sobre a MENSAL — semanal com semanal nunca conflita.
            verify(paginaRepo).findPessoasComMensalPublicadaNoPeriodo(eq(LOTE), anyCollection(), any(), any());
        }

        @Test
        @DisplayName("mensal de OUTRO mês da mesma pessoa publica (a janela consultada é a do lote)")
        void mensalDeOutroMesPublica() {
            cenario(emRevisao("MENSAL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
                    pagina(1, OP, "OPERADOR"));
            // Junho está fechado para essa pessoa; julho é outra competência e não é consultado como junho.
            when(paginaRepo.findPessoasComMensalPublicadaNoPeriodo(eq(LOTE), anyCollection(),
                    eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)))).thenReturn(List.of());

            Map<String, Object> out = service.publicar(LOTE, true);

            assertEquals("PUBLICADO", out.get("status"));
            verify(saldoAberturaService).reancorar(OP, "OPERADOR");
        }

        @Test
        @DisplayName("duas páginas da mesma pessoa num lote SEMANAL publicam (o veto intra-lote é só da mensal)")
        void duasPaginasDaMesmaPessoaEmLoteSemanalPublicam() {
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5)),
                    pagina(1, OP, "OPERADOR"),
                    pagina(2, OP, "OPERADOR"));
            nenhumaMensalPublicada();

            Map<String, Object> out = service.publicar(LOTE, true);

            assertEquals("PUBLICADO", out.get("status"));
            // Uma re-âncora só: reancorarPessoas dedupa por (pessoa, tipo).
            verify(saldoAberturaService, times(1)).reancorar(OP, "OPERADOR");
        }

        @Test
        @DisplayName("lote sem nenhuma página vinculada não consulta a guarda e publica")
        void loteSoComPaginasPendentesPublica() {
            cenario(emRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                    pagina(1, null, null));

            Map<String, Object> out = service.publicar(LOTE, true);

            assertEquals("PUBLICADO", out.get("status"));
            verify(paginaRepo, never()).findPessoasComMensalPublicadaNoPeriodo(any(), anyCollection(), any(), any());
            // Sem pessoa, não há a quem avisar nem quem re-ancorar.
            verifyNoInteractions(avisoService, saldoAberturaService);
        }
    }
}
