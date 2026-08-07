package br.leg.senado.nusp.service;

import br.leg.senado.nusp.enums.SubtipoAviso;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoFolhaLinha;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.LockModeType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unitários de {@link PontoService#publicar} — as duas guardas da publicação e o texto do aviso
 * que ela dispara.
 *
 * <p><b>Concorrência:</b> aqui se prova apenas que a publicação lê o lote pelo caminho que SEGURA a
 * linha ({@code lockPorId} → {@code SELECT ... FOR UPDATE}), nunca pelo {@code findById} solto. Que
 * o lock de fato serializa duas transações só o Oracle real pode dizer — é o
 * {@code PontoPublicacaoConcorrenteIT}.
 *
 * <p><b>Folha mensal:</b> a MENSAL é única por pessoa+competência e FECHA o mês. Sobreposição entre
 * SEMANAIS continua livre — elas são cumulativas por desenho, e o que NÃO deve existir (uma
 * validação genérica de sobreposição de período) é provado pelas contraprovas.
 *
 * <p><b>Natureza da mensal:</b> a prévia abre o mês e a definitiva o fecha. A única folha que entra
 * por cima de outra é a definitiva sobre a prévia da mesma pessoa e competência; todas as outras
 * cinco combinações da matriz são recusadas.
 *
 * <p><b>Tabela da folha:</b> publicar não guarda só o PDF — a tabela impressa nele vira linha de
 * banco, e é dela que saem as consultas por dia. A folha é regravada por inteiro a cada publicação
 * ou reprocessamento, e uma folha ilegível nunca aborta a publicação.
 */
@ExtendWith(MockitoExtension.class)
class PontoServiceTest {

    @Mock private PontoLoteRepository loteRepo;
    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private PontoFolhaLinhaRepository folhaLinhaRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private AdministradorRepository administradorRepo;
    @Mock private AvisoService avisoService;
    @Mock private SaldoAberturaService saldoAberturaService;
    @Mock private RetificacaoService retificacaoService;
    /** "A pessoa existe?" é uma pergunta só — o service não faz mais o switch por repositório. */
    @Mock private PessoaCadastroLookup pessoaCadastro;

    /** A tabela da folha vai ao banco de uma vez só, em bloco: é esse bloco que se inspeciona. */
    @Captor private ArgumentCaptor<List<PontoFolhaLinha>> linhasDaFolha;

    @InjectMocks
    private PontoService service;

    /** O único {@code @Value} do service; a publicação lê o PDF de cada página daqui. */
    @TempDir
    Path filesDir;

    private static final String LOTE = "lote-1";
    private static final String ADMIN = "adm-1";
    private static final String OP = "op-1";
    private static final String NOME_OP = "Maria Silva";
    /** Segunda pessoa do elenco: a recusa que esbarra em duas naturezas ao mesmo tempo precisa de duas. */
    private static final String OP2 = "op-2";
    private static final String NOME_OP2 = "Ana Paula";
    private static final String PREVIA = PontoLote.CATEGORIA_PREVIA;
    private static final String DEFINITIVA = PontoLote.CATEGORIA_DEFINITIVA;
    /** A competência disputada pela prévia e pela definitiva nos cenários de substituição. */
    private static final LocalDate JULHO_INI = LocalDate.of(2026, 7, 1);
    private static final LocalDate JULHO_FIM = LocalDate.of(2026, 7, 31);

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

    /** Lote MENSAL em revisão com a natureza declarada no envio: a prévia abre o mês, a definitiva fecha. */
    private static PontoLote mensal(String categoria, LocalDate inicio, LocalDate fim) {
        PontoLote l = emRevisao("MENSAL", inicio, fim);
        l.setCategoria(categoria);
        return l;
    }

    /** Lote MENSAL já publicado com a natureza informada (o que a lista do dono lê do banco). */
    private static PontoLote mensalPublicado(String categoria, LocalDate inicio, LocalDate fim) {
        PontoLote l = lote("MENSAL", inicio, fim, "PUBLICADO");
        l.setCategoria(categoria);
        return l;
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
        return operador(OP, NOME_OP);
    }

    private static Operador operador(String id, String nome) {
        Operador op = new Operador();
        op.setId(id);
        op.setNomeCompleto(nome);
        return op;
    }

    /** Lote em revisão + suas páginas, com o operador no elenco (é dele o nome que a recusa cita). */
    private void cenario(PontoLote lote, PontoLotePagina... paginas) {
        cenario(List.of(operador()), lote, paginas);
    }

    /** Como o {@link #cenario(PontoLote, PontoLotePagina...)}, com o elenco escolhido. */
    private void cenario(List<Operador> elenco, PontoLote lote, PontoLotePagina... paginas) {
        when(loteRepo.lockPorId(LOTE)).thenReturn(Optional.of(lote));
        when(paginaRepo.findByLoteIdOrderByNumeroPagina(LOTE)).thenReturn(List.of(paginas));
        when(operadorRepo.findAll()).thenReturn(elenco);
    }

    /**
     * Resposta da guarda: a pessoa já tem MENSAL publicada tocando a competência do lote. O 3º campo é
     * o DATA_INICIO da mensal existente — dele sai o mês que a recusa cita; o 4º é a natureza dela, e é
     * só por ele que a guarda distingue o conflito que barra da substituição pretendida.
     */
    private void mensalPublicadaDe(String pessoaId, String pessoaTipo, LocalDate inicio, LocalDate fim,
                                   String categoria) {
        mensaisPublicadas(inicio, fim, new Object[] { pessoaId, pessoaTipo, inicio, categoria });
    }

    /**
     * Várias mensais publicadas na mesma janela consultada — pessoas e naturezas diferentes.
     *
     * <p>{@code Arrays.asList} e não {@code List.of}: com um array de arrays, a sobrecarga de UM
     * elemento de {@code List.of} vence a varargs (todo {@code Object[][]} é um {@code Object[]}) e a
     * lista sairia com uma linha só — a quádrupla inteira embrulhada dentro dela.
     */
    private void mensaisPublicadas(LocalDate inicio, LocalDate fim, Object[]... linhas) {
        when(paginaRepo.findPessoasComMensalPublicadaNoPeriodo(eq(LOTE), anyCollection(), eq(inicio), eq(fim)))
                .thenReturn(Arrays.asList(linhas));
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
            cenario(mensal(PREVIA, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                    pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), PREVIA);

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
            mensalPublicadaDe(OP, "OPERADOR", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), DEFINITIVA);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.publicar(LOTE, true));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            String msg = mensagemDoAdmin(ex);
            assertTrue(msg.contains(NOME_OP), () -> "a recusa precisa NOMEAR a pessoa: " + msg);
            assertTrue(msg.contains("já foi fechado por folha mensal"), msg);
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
            mensalPublicadaDe(OP, "OPERADOR", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31), DEFINITIVA);

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
            // O subtipo (FOLHA_MENSAL aqui) e a PROVENIÊNCIA (o lote): o aviso nasce marcado com o
            // lote que o criou, e é só por essa marca que a exclusão daquele lote sabe reconhecê-lo.
            verify(avisoService, times(1)).criarPessoalIndividual(anyList(), anyString(), eq(ADMIN),
                    eq(SubtipoAviso.FOLHA_MENSAL), eq(LOTE));
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
            verify(avisoService).criarPessoalIndividual(anyList(), anyString(), eq(ADMIN),
                    eq(SubtipoAviso.FOLHA_SEMANAL), eq(LOTE));
            // Nunca a sobrecarga SEM origem (desfecho de folga): a publicação sempre marca o lote.
            verify(avisoService, never()).criarPessoalIndividual(anyList(), anyString(), anyString(), any(SubtipoAviso.class));
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

    // ══════════════════════════════════════════════════════════════
    // O texto do aviso de publicação
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("o texto do aviso identifica a folha: mensal pela competência, semanal pelas datas")
    class TextoDoAvisoDePublicacao {

        /** O texto entregue ao aviso é contrato com quem lê a notificação — captura-o inteiro. */
        private String textoDoAviso() {
            ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
            verify(avisoService).criarPessoalIndividual(anyList(), texto.capture(), eq(ADMIN),
                    any(SubtipoAviso.class), eq(LOTE));
            return texto.getValue();
        }

        @Test
        @DisplayName("mensal prévia cita a competência por extenso (\"Junho/2026\") e o limite de retificação")
        void mensalPreviaCitaACompetencia() {
            PontoLote lote = mensal(PREVIA, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
            cenario(lote, pagina(1, OP, "OPERADOR"));
            nenhumaMensalPublicada();
            when(retificacaoService.limiteRetificacao(lote)).thenReturn(LocalDate.of(2026, 7, 10));

            service.publicar(LOTE, true);

            // A folha mensal É a competência inteira: o intervalo 01/06–30/06 em datas seria redundante
            // e ilegível — o aviso diz o MÊS; o intervalo de datas é identidade só da semanal.
            assertEquals("Folha de ponto mensal — prévia (Junho/2026) publicada. "
                    + "Acesse \"Minhas Folhas\" para visualizá-la. Retificações até 10/07/2026.",
                    textoDoAviso());
        }

        @Test
        @DisplayName("a competência preserva o acento do mês (Março/2026); sem limite, sem frase de retificação")
        void mensalPreservaAcentoDoMes() {
            PontoLote lote = mensal(PREVIA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
            cenario(lote, pagina(1, OP, "OPERADOR"));
            nenhumaMensalPublicada();
            // Sem limite de retificação, a última frase simplesmente não existe — nada de "até null".
            when(retificacaoService.limiteRetificacao(lote)).thenReturn(null);

            service.publicar(LOTE, true);

            assertEquals("Folha de ponto mensal — prévia (Março/2026) publicada. "
                    + "Acesse \"Minhas Folhas\" para visualizá-la.",
                    textoDoAviso());
        }

        @Test
        @DisplayName("mensal definitiva anuncia o fechamento do mês, sem convite a conferir e sem prazo")
        void mensalDefinitivaAnunciaOFechamento() {
            cenario(mensal(DEFINITIVA, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
                    pagina(1, OP, "OPERADOR"));
            nenhumaMensalPublicada();

            service.publicar(LOTE, true);

            assertEquals("Folha de ponto definitiva do mês Julho/2026 publicada.", textoDoAviso());
            // A definitiva encerra o mês: não há prazo de retificação a anunciar, e por isso o texto
            // nem chega a perguntar qual seria.
            verifyNoInteractions(retificacaoService);
        }

        @Test
        @DisplayName("semanal segue citando o intervalo de datas (dd/mm/aaaa a dd/mm/aaaa)")
        void semanalCitaOIntervaloDeDatas() {
            PontoLote lote = emRevisao("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
            cenario(lote, pagina(1, OP, "OPERADOR"));
            nenhumaMensalPublicada();
            when(retificacaoService.limiteRetificacao(lote)).thenReturn(LocalDate.of(2026, 6, 12));

            service.publicar(LOTE, true);

            assertEquals("Folha de ponto semanal (01/06/2026 a 05/06/2026) publicada. "
                    + "Acesse \"Minhas Folhas\" para visualizá-la. Retificações até 12/06/2026.",
                    textoDoAviso());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Prévia × definitiva: a matriz completa da guarda
    // ══════════════════════════════════════════════════════════════

    /**
     * As seis combinações de (folha sendo publicada) × (mensal já publicada da pessoa na competência).
     * Só uma delas passa — a definitiva por cima da prévia, que é como o mês fecha. As outras cinco
     * recusam, e a frase da recusa NOMEIA a natureza que ocupa o mês: é ela que diz ao admin se ainda
     * cabe uma definitiva ali ou se o mês já está encerrado.
     */
    @Nested
    @DisplayName("a definitiva é a única folha que entra por cima de outra")
    class PreviaEDefinitiva {

        /** Publica esperando a recusa do lote INTEIRO e devolve a frase que o admin lê. */
        private String recusaAoPublicar() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.publicar(LOTE, true));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ex.getMessage(), mensagemDoAdmin(ex), "a frase tem de estar nos dois campos do erro");
            assertNadaGravado();
            return ex.getMessage();
        }

        @Test
        @DisplayName("semanal atrasada de mês que a PRÉVIA já abriu → recusa citando a prévia")
        void semanalContraPrevia() {
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10)),
                    pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", JULHO_INI, JULHO_FIM, PREVIA);

            assertEquals("Publicação recusada: o mês de Maria Silva (07/2026) já foi fechado por"
                    + " folha mensal prévia publicada.",
                    recusaAoPublicar());
        }

        @Test
        @DisplayName("semanal atrasada de mês que a DEFINITIVA fechou → recusa citando a definitiva")
        void semanalContraDefinitiva() {
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10)),
                    pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", JULHO_INI, JULHO_FIM, DEFINITIVA);

            assertEquals("Publicação recusada: o mês de Maria Silva (07/2026) já foi fechado por"
                    + " folha mensal definitiva publicada.",
                    recusaAoPublicar());
        }

        @Test
        @DisplayName("segunda PRÉVIA da mesma competência → recusa: prévia não substitui prévia")
        void previaContraPrevia() {
            cenario(mensal(PREVIA, JULHO_INI, JULHO_FIM), pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", JULHO_INI, JULHO_FIM, PREVIA);

            // Corrigir a prévia é retificar, não republicar: uma segunda prévia deixaria duas folhas
            // igualmente válidas do mesmo mês para a mesma pessoa.
            assertEquals("Publicação recusada: já existe folha mensal prévia publicada de"
                    + " Maria Silva (07/2026).",
                    recusaAoPublicar());
        }

        @Test
        @DisplayName("PRÉVIA depois da definitiva do mês → recusa: mês fechado não reabre")
        void previaContraDefinitiva() {
            cenario(mensal(PREVIA, JULHO_INI, JULHO_FIM), pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", JULHO_INI, JULHO_FIM, DEFINITIVA);

            assertEquals("Publicação recusada: já existe folha mensal definitiva publicada de"
                    + " Maria Silva (07/2026).",
                    recusaAoPublicar());
        }

        @Test
        @DisplayName("segunda DEFINITIVA da mesma competência → recusa: o mês só fecha uma vez")
        void definitivaContraDefinitiva() {
            cenario(mensal(DEFINITIVA, JULHO_INI, JULHO_FIM), pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", JULHO_INI, JULHO_FIM, DEFINITIVA);

            assertEquals("Publicação recusada: já existe folha mensal definitiva publicada de"
                    + " Maria Silva (07/2026).",
                    recusaAoPublicar());
        }

        @Test
        @DisplayName("DEFINITIVA por cima da prévia da mesma pessoa e mês publica e avisa o fechamento")
        void definitivaSubstituiAPrevia() {
            PontoLote lote = mensal(DEFINITIVA, JULHO_INI, JULHO_FIM);
            cenario(lote, pagina(1, OP, "OPERADOR"));
            mensalPublicadaDe(OP, "OPERADOR", JULHO_INI, JULHO_FIM, PREVIA);

            Map<String, Object> out = service.publicar(LOTE, true);

            // É a única passagem da matriz: a prévia é ignorada porque a substituição é o objetivo.
            assertEquals("PUBLICADO", out.get("status"));
            assertEquals("PUBLICADO", lote.getStatus());
            assertEquals(DEFINITIVA, out.get("categoria"));
            verify(loteRepo).save(lote);
            verify(saldoAberturaService, times(1)).reancorar(OP, "OPERADOR");
            verify(avisoService).criarPessoalIndividual(anyList(),
                    eq("Folha de ponto definitiva do mês Julho/2026 publicada."),
                    eq(ADMIN), eq(SubtipoAviso.FOLHA_MENSAL), eq(LOTE));
        }

        /**
         * Depois da substituição a pessoa tem as DUAS mensais publicadas — a prévia continua no
         * histórico. A recusa fala de um mês, não de arquivos: cita a pessoa uma vez e nomeia a
         * folha que fechou aquele mês.
         */
        @Test
        @DisplayName("pessoa com prévia substituída e definitiva: nome citado UMA vez, pela definitiva")
        void previaSubstituidaNaoDuplicaONome() {
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10)),
                    pagina(1, OP, "OPERADOR"));
            mensaisPublicadas(JULHO_INI, JULHO_FIM,
                    new Object[] { OP, "OPERADOR", JULHO_INI, PREVIA },
                    new Object[] { OP, "OPERADOR", JULHO_INI, DEFINITIVA });

            assertEquals("Publicação recusada: o mês de Maria Silva (07/2026) já foi fechado por"
                    + " folha mensal definitiva publicada.",
                    recusaAoPublicar());
        }

        @Test
        @DisplayName("conflitos de naturezas diferentes na mesma recusa: a frase sai sem a palavra da natureza")
        void misturaDeNaturezasOmiteAPalavra() {
            cenario(List.of(operador(), operador(OP2, NOME_OP2)),
                    mensal(PREVIA, JULHO_INI, JULHO_FIM),
                    pagina(1, OP, "OPERADOR"), pagina(2, OP2, "OPERADOR"));
            mensaisPublicadas(JULHO_INI, JULHO_FIM,
                    new Object[] { OP, "OPERADOR", JULHO_INI, PREVIA },
                    new Object[] { OP2, "OPERADOR", JULHO_INI, DEFINITIVA });

            // Nenhuma das duas naturezas representa o conjunto: dizer "prévia" seria falso para quem já
            // teve o mês fechado, e "definitiva" seria falso para quem ainda o tem aberto.
            assertEquals("Publicação recusada: já existe folha mensal publicada de"
                    + " Ana Paula (07/2026), Maria Silva (07/2026).",
                    recusaAoPublicar());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // A natureza declarada no envio do PDF
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("a natureza é escolhida no envio e só existe na folha mensal")
    class NaturezaNoUpload {

        /** PDF de verdade, de 1 página: sem ele o envio para antes de criar o lote. */
        private MockMultipartFile pdfDeUmaPagina() {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, buf);
            doc.open();
            doc.add(new Paragraph("folha de ponto"));
            doc.close();
            return new MockMultipartFile("arquivo", "cartao-ponto.pdf", "application/pdf", buf.toByteArray());
        }

        /** Os lotes que chegaram ao banco — é neles que a natureza normalizada tem de estar. */
        private List<PontoLote> lotesGravados(int vezes) {
            ArgumentCaptor<PontoLote> captor = ArgumentCaptor.forClass(PontoLote.class);
            verify(loteRepo, times(vezes)).save(captor.capture());
            return captor.getAllValues();
        }

        @Test
        @DisplayName("mensal sem natureza → 400 pedindo a escolha, antes mesmo de abrir o arquivo")
        void mensalSemCategoria() {
            // O arquivo nem chega a ser lido: a natureza é validada junto do tipo, no início do envio.
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.upload(null, "MENSAL", null, "2026-07-01", "2026-07-31", ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Informe se a folha mensal é prévia ou definitiva.", ex.getMessage());
            verify(loteRepo, never()).save(any());
        }

        @Test
        @DisplayName("mensal com natureza fora do domínio → 400 dizendo os dois valores aceitos")
        void mensalComCategoriaInvalida() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.upload(null, "MENSAL", "PARCIAL", "2026-07-01", "2026-07-31", ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Categoria inválida (use PREVIA ou DEFINITIVA).", ex.getMessage());
            verify(loteRepo, never()).save(any());
        }

        @Test
        @DisplayName("semanal com natureza preenchida → 400: ela é cumulativa e não fecha mês nenhum")
        void semanalComCategoria() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.upload(null, "SEMANAL", "PREVIA", "2026-07-06", "2026-07-10", ADMIN));

            // Recusar, e não ignorar em silêncio: quem marcou a natureza numa semanal errou o tipo.
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Folha semanal não é prévia nem definitiva.", ex.getMessage());
            verify(loteRepo, never()).save(any());
        }

        @Test
        @DisplayName("a natureza da mensal é normalizada: \"  previa \" chega ao banco como PREVIA")
        void categoriaDaMensalNormalizada() {
            Map<String, Object> out = service.upload(pdfDeUmaPagina(), "MENSAL", "  previa ",
                    "2026-07-01", "2026-07-31", ADMIN);

            // O CHECK do banco só aceita o domínio em caixa alta — normalizar aqui é o que evita o
            // erro de constraint virar 500 na cara do admin.
            assertEquals(PREVIA, lotesGravados(1).get(0).getCategoria());
            assertEquals(PREVIA, out.get("categoria"));
        }

        @Test
        @DisplayName("semanal sem natureza (vazia ou nula) grava CATEGORIA nula no lote")
        void semanalGravaCategoriaNula() {
            service.upload(pdfDeUmaPagina(), "SEMANAL", "", "2026-07-06", "2026-07-10", ADMIN);
            Map<String, Object> out = service.upload(pdfDeUmaPagina(), "SEMANAL", null,
                    "2026-07-13", "2026-07-17", ADMIN);

            for (PontoLote l : lotesGravados(2)) {
                assertNull(l.getCategoria(), "a semanal não tem natureza: o banco exige nulo nela");
            }
            assertNull(out.get("categoria"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // A lista do dono ("Minhas Folhas")
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("publicada a definitiva, a prévia do mesmo mês deixa de ser a folha do dono")
    class MinhasFolhas {

        /** Pares [página, lote] que o banco devolve — {@code Arrays.asList} pelo mesmo motivo da guarda. */
        private void folhasPublicadas(Object[]... pares) {
            when(paginaRepo.findFolhasPublicadasByPessoa(OP)).thenReturn(Arrays.asList(pares));
        }

        @Test
        @DisplayName("a prévia some da lista quando existe definitiva publicada da pessoa na competência")
        void previaSomeQuandoADefinitivaChega() {
            folhasPublicadas(
                    new Object[] { pagina(2, OP, "OPERADOR"), mensalPublicado(DEFINITIVA, JULHO_INI, JULHO_FIM) },
                    new Object[] { pagina(1, OP, "OPERADOR"), mensalPublicado(PREVIA, JULHO_INI, JULHO_FIM) });
            when(paginaRepo.contarDefinitivasPublicadas(OP, "OPERADOR", JULHO_INI, JULHO_FIM)).thenReturn(1L);

            List<Map<String, Object>> folhas = service.minhasFolhas(OP);

            // Duas folhas do mesmo mês na lista seriam duas versões da mesma coisa, e a prévia já não
            // vale — para o admin, porém, nada some: o histórico de lotes continua inteiro.
            assertEquals(1, folhas.size());
            assertEquals("pag-2", folhas.get(0).get("id"));
            assertEquals(DEFINITIVA, folhas.get(0).get("categoria"));
        }

        @Test
        @DisplayName("a prévia continua na lista enquanto a definitiva do mês não chega")
        void previaFicaEnquantoNaoHaDefinitiva() {
            folhasPublicadas(new Object[] { pagina(1, OP, "OPERADOR"),
                    mensalPublicado(PREVIA, JULHO_INI, JULHO_FIM) });
            when(paginaRepo.contarDefinitivasPublicadas(OP, "OPERADOR", JULHO_INI, JULHO_FIM)).thenReturn(0L);

            List<Map<String, Object>> folhas = service.minhasFolhas(OP);

            assertEquals(1, folhas.size());
            assertEquals("pag-1", folhas.get(0).get("id"));
            // A natureza vai no payload: é dela que a lista tira a etiqueta ao lado do período.
            assertEquals(PREVIA, folhas.get(0).get("categoria"));
        }

        @Test
        @DisplayName("semanal nunca é substituída: fica na lista e nem chega a perguntar pela definitiva")
        void semanalNuncaSome() {
            folhasPublicadas(new Object[] { pagina(1, OP, "OPERADOR"),
                    lote("SEMANAL", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10), "PUBLICADO") });

            List<Map<String, Object>> folhas = service.minhasFolhas(OP);

            assertEquals(1, folhas.size());
            // A chave existe em toda folha; na semanal ela vem nula, que é a ausência de natureza.
            assertTrue(folhas.get(0).containsKey("categoria"));
            assertNull(folhas.get(0).get("categoria"));
            verify(paginaRepo, never()).contarDefinitivasPublicadas(any(), any(), any(), any());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // A tabela da folha, gravada linha a linha na publicação
    // ══════════════════════════════════════════════════════════════

    /**
     * Publicar transcreve para o banco a tabela impressa no PDF: uma linha por linha-dia, com as 7
     * colunas do cartão VERBATIM e mais duas derivadas delas (a data-calendário e a ocorrência do
     * dia). O PDF é a fonte, mas deixa de ser a única — as consultas por dia passam a sair daqui.
     *
     * <p>As folhas destes testes são PDFs de verdade, escritos no {@code @TempDir} de onde a
     * publicação lê: o que se prova é o caminho inteiro (ler o arquivo, parsear, gravar), não um
     * parser dublê.
     */
    @Nested
    @DisplayName("publicar grava a tabela da folha, linha a linha")
    class TabelaDaFolha {

        /**
         * Folha de 3 dias como o cartão a imprime — um dia com os quatro horários, um de status e um
         * com só o 1º turno —, cercada pelo cabeçalho e pela linha de totais, que não são linhas-dia.
         */
        private static final String[] FOLHA = {
                "Cartao Ponto - Secullum",
                "01/07/26 - qua06:0012:0013:0017:00+02:00+10:50",
                "02/07/26 - quiFERNCFERNCFERNCFERNC+10:50",
                "03/07/26 - sex06:0012:00+00:00+09:20",
                "TOTAIS 02:00 +09:20"};

        /** Outra folha, com outros dias e outro acumulado — serve para flagrar uma segunda leitura. */
        private static final String[] OUTRA_FOLHA = {
                "10/07/26 - sexDISPOSIDISPOSIDISPOSIDISPOSI-01:00-02:15",
                "11/07/26 - sáb06:0012:00+00:00-02:15"};

        /** O BANCO acumulado no fim da {@link #FOLHA} (+09:20), em minutos. */
        private static final int BANCO_DA_FOLHA = 9 * 60 + 20;

        // ── fixtures da folha em disco ──

        /** Escreve no {@code @TempDir} o arquivo que a página aponta: é de lá que a publicação lê. */
        private void gravarArquivo(PontoLotePagina p, byte[] conteudo) throws Exception {
            Path destino = filesDir.resolve(p.getArquivoPagina());
            Files.createDirectories(destino.getParent());
            Files.write(destino, conteudo);
        }

        /** PDF de uma página com as linhas informadas, uma por linha impressa. */
        private void gravarFolha(PontoLotePagina p, String... linhas) throws Exception {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, buf);
            doc.open();
            for (String linha : linhas) doc.add(new Paragraph(linha));
            doc.close();
            gravarArquivo(p, buf.toByteArray());
        }

        /** Lote semanal em revisão com uma folha vinculada, pronto para publicar. */
        private PontoLotePagina folhaVinculada() {
            PontoLotePagina pg = pagina(1, OP, "OPERADOR");
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5)), pg);
            nenhumaMensalPublicada();
            return pg;
        }

        /** As linhas entregues ao banco na (única) gravação da folha. */
        private List<PontoFolhaLinha> linhasGravadas() {
            verify(folhaLinhaRepo).saveAll(linhasDaFolha.capture());
            return linhasDaFolha.getValue();
        }

        // ── o que é gravado ──

        @Test
        @DisplayName("cada linha-dia do PDF vira uma linha da folha, na ordem da página a partir de 1")
        void gravaUmaLinhaPorDiaNaOrdemDaPagina() throws Exception {
            PontoLotePagina pg = folhaVinculada();
            gravarFolha(pg, FOLHA);

            service.publicar(LOTE, false);

            List<PontoFolhaLinha> linhas = linhasGravadas();
            // Cabeçalho e TOTAIS não são dias: só as três linhas-dia entram.
            assertEquals(3, linhas.size());
            // A ordem é a da página, e é ela que identifica a linha — a data pode faltar, repetir-se
            // (folha que cruza a virada) ou vir ilegível, e a folha continua tendo de ser remontada
            // exatamente como foi impressa.
            assertEquals(List.of(1, 2, 3), linhas.stream().map(PontoFolhaLinha::getOrdem).toList());
            assertTrue(linhas.stream().allMatch(l -> "pag-1".equals(l.getPaginaId())));

            PontoFolhaLinha comBatidas = linhas.get(0);
            assertEquals("01/07/26 - qua", comBatidas.getDiaVerbatim());
            assertEquals("06:00", comBatidas.getEnt1());
            assertEquals("12:00", comBatidas.getSai1());
            assertEquals("13:00", comBatidas.getEnt2());
            assertEquals("17:00", comBatidas.getSai2());
            assertEquals("+02:00", comBatidas.getTotalDia());
            assertEquals("+10:50", comBatidas.getBanco());
            // Derivadas do verbatim: a data-calendário do dia e a ocorrência, ausente no dia de batidas.
            assertEquals(LocalDate.of(2026, 7, 1), comBatidas.getData());
            assertNull(comBatidas.getOcorrencia());

            PontoFolhaLinha deStatus = linhas.get(1);
            assertEquals("02/07/26 - qui", deStatus.getDiaVerbatim());
            assertEquals("FERNC", deStatus.getEnt1());
            assertEquals("FERNC", deStatus.getSai2());
            assertEquals("+10:50", deStatus.getBanco());
            assertEquals(LocalDate.of(2026, 7, 2), deStatus.getData());
            // O status do dia é o que a tela e os relatórios leem sem reinterpretar o texto do cartão.
            assertEquals("FERNC", deStatus.getOcorrencia());
        }

        @Test
        @DisplayName("célula em branco na folha é gravada como nulo, nunca como string vazia")
        void celulaEmBrancoViraNulo() throws Exception {
            PontoLotePagina pg = folhaVinculada();
            gravarFolha(pg, FOLHA);

            service.publicar(LOTE, false);

            List<PontoFolhaLinha> linhas = linhasGravadas();
            // No Oracle, string vazia JÁ é nulo: gravar "" faria a coluna divergir do que a leitura
            // devolve, e toda comparação com vazio no código passaria a mentir.
            PontoFolhaLinha soUmTurno = linhas.get(2);
            assertNull(soUmTurno.getEnt2());
            assertNull(soUmTurno.getSai2());
            assertNull(linhas.get(1).getTotalDia(), "o dia de status não tem TOTALDIA impresso");
        }

        @Test
        @DisplayName("o BANCO e as linhas saem do MESMO parse: a folha é lida uma vez, antes de gravar")
        void bancoELinhasSaemDoMesmoParse() throws Exception {
            PontoLotePagina pg = folhaVinculada();
            gravarFolha(pg, FOLHA);
            // A folha é TROCADA no disco no instante em que a publicação limpa as linhas antigas: o que
            // for gravado depois só pode ter vindo da leitura feita antes. Com uma leitura para o saldo
            // e outra para a tabela, cada uma veria uma folha, e o BANCO da página deixaria de bater
            // com o da última linha gravada — o saldo do banco de horas divergindo da folha que o
            // explica, em silêncio.
            doAnswer(inv -> {
                gravarFolha(pg, OUTRA_FOLHA);
                return null;
            }).when(folhaLinhaRepo).deleteByPaginaId("pag-1");

            service.publicar(LOTE, false);

            List<PontoFolhaLinha> linhas = linhasGravadas();
            assertEquals(3, linhas.size(), "as linhas são as da folha lida no início, não as da trocada");
            assertEquals("03/07/26 - sex", linhas.get(2).getDiaVerbatim());
            assertEquals("+09:20", linhas.get(2).getBanco());
            assertEquals(BANCO_DA_FOLHA, pg.getBancoFinalMin());
        }

        @Test
        @DisplayName("página sem pessoa não gera linha nenhuma, mesmo com o PDF no servidor")
        void paginaPendenteNaoGeraLinhas() throws Exception {
            PontoLotePagina pendente = pagina(1, null, null);
            cenario(emRevisao("SEMANAL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5)), pendente);
            gravarFolha(pendente, FOLHA);

            service.publicar(LOTE, false);

            // Folha sem dono não é folha de ninguém: não há o que gravar nem o que limpar.
            verifyNoInteractions(folhaLinhaRepo);
            assertNull(pendente.getBancoFinalMin());
        }

        // ── folha que não pode ser lida ──

        @Test
        @DisplayName("PDF ausente não aborta a publicação: sem banco, sem linhas — e a limpeza acontece")
        void folhaAusenteNaoAbortaAPublicacao() {
            // O arquivo nunca foi gravado no @TempDir.
            PontoLotePagina pg = folhaVinculada();

            Map<String, Object> out = service.publicar(LOTE, false);

            assertEquals("PUBLICADO", out.get("status"));
            assertNull(pg.getBancoFinalMin());
            verify(folhaLinhaRepo, never()).saveAll(any());
            // A limpeza vale mesmo sem nada a gravar: uma folha que ficou ilegível não pode continuar
            // exibindo a tabela da geração anterior.
            verify(folhaLinhaRepo).deleteByPaginaId("pag-1");
            verify(folhaLinhaRepo).flush();
        }

        @Test
        @DisplayName("arquivo que não abre como PDF tem o mesmo desfecho: publica sem banco e sem linhas")
        void folhaIlegivelNaoAbortaAPublicacao() throws Exception {
            PontoLotePagina pg = folhaVinculada();
            gravarArquivo(pg, "isto nao e um PDF".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> out = service.publicar(LOTE, false);

            // Uma folha ilegível é problema de uma pessoa; abortar puniria o lote inteiro, e o admin
            // ainda republica ou reprocessa depois.
            assertEquals("PUBLICADO", out.get("status"));
            assertNull(pg.getBancoFinalMin());
            verify(folhaLinhaRepo, never()).saveAll(any());
            verify(folhaLinhaRepo).deleteByPaginaId("pag-1");
        }

        // ── regravação ──

        @Test
        @DisplayName("regravar a folha apaga as linhas anteriores antes de inserir as novas")
        void regravarApagaAsLinhasAnteriores() throws Exception {
            PontoLotePagina pg = folhaVinculada();
            gravarFolha(pg, FOLHA);

            service.publicar(LOTE, false);

            // A folha é regravada por inteiro, não completada: a unicidade (página, ordem) não admite
            // duas gerações convivendo, e o flush é o que garante que o DELETE chegue antes do INSERT.
            InOrder ordem = inOrder(folhaLinhaRepo);
            ordem.verify(folhaLinhaRepo).deleteByPaginaId("pag-1");
            ordem.verify(folhaLinhaRepo).flush();
            ordem.verify(folhaLinhaRepo).saveAll(any());
        }

        @Test
        @DisplayName("o reprocessamento do banco regrava a tabela das folhas que percorre")
        void reprocessarBancoRegravaAsLinhas() throws Exception {
            PontoLotePagina pg = pagina(1, OP, "OPERADOR");
            gravarFolha(pg, FOLHA);
            when(paginaRepo.findPublicadasSemBancoFinal()).thenReturn(List.of(pg));
            when(paginaRepo.findPessoasComFolhaPublicada()).thenReturn(List.of());

            Map<String, Object> out = service.reprocessarBanco();

            // O reprocessamento existe para as folhas que ficaram sem banco; a tabela delas ficou
            // faltando junto, e é a mesma leitura que devolve as duas coisas.
            assertEquals(1, out.get("gravadas"));
            assertEquals(BANCO_DA_FOLHA, pg.getBancoFinalMin());
            assertEquals(3, linhasGravadas().size());
            verify(folhaLinhaRepo).deleteByPaginaId("pag-1");
        }
    }
}
