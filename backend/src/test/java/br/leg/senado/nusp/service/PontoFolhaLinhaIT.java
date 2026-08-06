package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoFolhaLinha;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.it.support.Causas;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT da tabela da folha (PNT_FOLHA_LINHA) contra Oracle real: publicar uma folha passa a gravar,
 * linha a linha, o que o cartão Secullum imprime — e o que só o banco pode responder é se a
 * gravação sobrevive ao ciclo de vida da folha. O índice único (página, ordem) recusa mesmo duas
 * linhas na mesma posição, o cascade da página leva as linhas embora quando o lote ou a folha
 * morrem, e regravar a mesma folha não deixa duas gerações convivendo.
 *
 * <p>Aqui o PDF é de verdade: o teste monta um cartão sintético com OpenPDF, grava-o onde a
 * aplicação procura os arquivos e deixa a publicação lê-lo pelo caminho normal — extração de texto
 * e parser inclusive. Sem isso não haveria linha nenhuma para gravar, e o que restaria seria a
 * afirmação vazia de que zero linhas continuam zero.
 *
 * <p>{@code @SpringBootTest} porque {@code @DataJpaTest} não instancia services e faz rollback de
 * tudo, e aqui a publicação e a exclusão precisam COMMITAR de verdade (os arquivos só saem do disco
 * depois do commit); sem rollback automático, a limpeza é manual.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoFolhaLinhaIT {

    /**
     * O username do master vem da CONFIGURAÇÃO ({@code app.admin.master-username} do
     * application-test.yml) — só ele exclui publicação.
     */
    private static final String MASTER = "master.teste";

    private static final LocalDate JULHO_INI = LocalDate.of(2026, 7, 1);
    private static final LocalDate JULHO_FIM = LocalDate.of(2026, 7, 31);
    private static final LocalDate SEMANA_INI = LocalDate.of(2026, 7, 13);
    private static final LocalDate SEMANA_FIM = LocalDate.of(2026, 7, 19);

    /**
     * O texto de um cartão Secullum de julho, como o PDF o imprime: cabeçalho, seis linhas-dia e a
     * linha de TOTAIS. As seis cobrem o que a folha real tem de variado — dias de batida completa,
     * um dia de status (Falta), um feriado sem texto nas células, um dia em branco — e as duas
     * últimas NÃO trazem banco, para que a última linha com banco fique no meio da página.
     */
    private static final List<String> CARTAO_DE_JULHO = List.of(
            "Cartao Ponto - Senado Federal",
            "Funcionario: folha de teste",
            "01/07/26 - qua 08:00 12:00 13:00 17:00 +08:00 +00:00",
            "02/07/26 - qui 08:00 12:00 13:00 18:00 +09:00 +01:00",
            "03/07/26 - sex FaltaFaltaFaltaFalta -08:00 -07:00",
            "06/07/26 - seg 08:00 12:00 13:00 17:00 +08:00 +01:30",
            "09/07/26 - feri",
            "10/07/26 - sex",
            "TOTAIS +09:00 +01:30");

    /** Linhas-dia do cartão de julho (o cabeçalho e a linha de TOTAIS não são dias). */
    private static final int LINHAS_DO_CARTAO = 6;

    /** BANCO em minutos da última linha do cartão que traz banco impresso ("+01:30", em 06/07). */
    private static final int BANCO_IMPRESSO_MIN = 90;

    /**
     * Cartão de uma semana em que nenhum dia traz banco acumulado — a folha fica sem
     * BANCO_FINAL_MIN e por isso continua na fila do reprocessamento, que é o que permite rodá-lo
     * duas vezes sobre uma folha já gravada.
     */
    private static final List<String> CARTAO_SEM_BANCO = List.of(
            "Cartao Ponto - Senado Federal",
            "13/07/26 - seg 09:00 12:00",
            "14/07/26 - ter 09:00 12:00 13:00 18:00",
            "15/07/26 - qua AtestAtestAtestAtest");

    private static final int LINHAS_DO_CARTAO_SEM_BANCO = 3;

    @Autowired private PontoService pontoService;
    @Autowired private PontoExclusaoService exclusaoService;
    @Autowired private PontoFolhaLinhaRepository folhaLinhaRepo;

    /** O contexto completo liga @EnableScheduling; estes dois fazem I/O de rede a cada poucos segundos. */
    @MockitoBean private AgendaLegislativaService agendaLegislativaService;
    @MockitoBean private CessaoSheetService cessaoSheetService;

    @Autowired private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    @Value("${app.files.dir}")
    private String filesDir;

    private TransactionTemplate tx;

    /** Tudo que o teste semeou — a limpeza manual (sem rollback) apaga por estes ids. */
    private final List<String> adminIds = new ArrayList<>();
    private final List<String> pessoaIds = new ArrayList<>();
    private final List<String> loteIds = new ArrayList<>();

    private Administrador admin;
    private Operador ana;
    private Operador bruno;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            ana = novaPessoa("Ana da Linha");
            bruno = novaPessoa("Bruno da Linha");
        });
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM PNT_EXCLUSAO_LOG WHERE EXCLUIDO_POR_ID IN :admins");
            executar("DELETE FROM PNT_BANCO_SALDO WHERE PESSOA_ID IN :pessoas");
            // O cascade encadeado leva as páginas com o lote e as linhas com as páginas.
            executar("DELETE FROM PNT_LOTE WHERE ID IN :lotes");
            executar("DELETE FROM PES_OPERADOR WHERE ID IN :pessoas");
            executar("DELETE FROM PES_ADMINISTRADOR WHERE ID IN :admins");
        });
    }

    private void executar(String sql) {
        var query = em.createNativeQuery(sql);
        if (sql.contains(":admins")) query.setParameter("admins", adminIds);
        if (sql.contains(":pessoas")) query.setParameter("pessoas", pessoaIds);
        if (sql.contains(":lotes")) query.setParameter("lotes", loteIds);
        query.executeUpdate();
    }

    // ══════════════════════════════════════════════════════════════
    // Seed
    // ══════════════════════════════════════════════════════════════

    private Operador novaPessoa(String nome) {
        Operador op = CenarioFactory.novoOperador(em, nome);
        op.setCargaHoraria(40);   // o gate do banco de horas recusa carga nula
        em.merge(op);
        em.flush();
        pessoaIds.add(op.getId());
        return op;
    }

    /** Lote em REVISÃO — o estado de onde a publicação parte. */
    private PontoLote loteEmRevisao(String tipo, String categoria, LocalDate inicio, LocalDate fim) {
        PontoLote lote = tx.execute(status -> CenarioFactory.novoLotePonto(em, tipo, categoria, inicio, fim, admin));
        loteIds.add(lote.getId());
        return lote;
    }

    /** Página vinculada à pessoa COM o PDF já em disco — é ele que a publicação vai ler e parsear. */
    private PontoLotePagina folhaDe(PontoLote lote, int numeroPagina, Operador pessoa, List<String> cartao) {
        PontoLotePagina pagina = tx.execute(status ->
                CenarioFactory.novaPaginaLote(em, lote, numeroPagina, pessoa.getId(), "OPERADOR"));
        gravarPdf(pagina.getArquivoPagina(), cartao);
        return pagina;
    }

    /**
     * Grava em disco um PDF de uma página com o texto informado, uma linha por parágrafo — o mesmo
     * arquivo que o upload teria deixado ali. A extração de texto devolve exatamente essas linhas,
     * e é sobre elas que o parser da folha trabalha.
     */
    private void gravarPdf(String relPath, List<String> linhas) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, bytes);
            doc.open();
            for (String linha : linhas) doc.add(new Paragraph(linha));
            doc.close();
            Path destino = Paths.get(filesDir).resolve(relPath);
            Files.createDirectories(destino.getParent());
            Files.write(destino, bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("falha ao gravar o PDF da folha em " + relPath, e);
        }
    }

    /** Linha montada à mão, sem passar pelo parser — serve só para bater na unicidade do banco. */
    private static PontoFolhaLinha linhaAvulsa(String paginaId, int ordem) {
        PontoFolhaLinha linha = new PontoFolhaLinha();
        linha.setPaginaId(paginaId);
        linha.setOrdem(ordem);
        linha.setDiaVerbatim("31/07/26 - sex");
        return linha;
    }

    // ══════════════════════════════════════════════════════════════
    // Leitura do estado
    // ══════════════════════════════════════════════════════════════

    private List<PontoFolhaLinha> linhasDaFolha(PontoLotePagina pagina) {
        return tx.execute(status -> folhaLinhaRepo.findByPaginaIdOrderByOrdem(pagina.getId()));
    }

    private static List<Integer> ordensDe(List<PontoFolhaLinha> linhas) {
        return linhas.stream().map(PontoFolhaLinha::getOrdem).toList();
    }

    /** BANCO_FINAL_MIN relido do banco (e não da entidade em memória, que a publicação já tocou). */
    private Integer bancoDaFolha(PontoLotePagina pagina) {
        return tx.execute(status -> em.find(PontoLotePagina.class, pagina.getId()).getBancoFinalMin());
    }

    /** O snapshot que a exclusão gravou na trilha (é ele que sobra depois que as linhas somem). */
    private String trilhaDoLote(String loteId) {
        return tx.execute(status -> String.valueOf(em.createNativeQuery(
                        "SELECT TO_CHAR(RESUMO) FROM PNT_EXCLUSAO_LOG WHERE LOTE_ID = :id")
                .setParameter("id", loteId)
                .getSingleResult()));
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("a publicação transforma a folha impressa em tabela no banco")
    class PublicacaoGravaAFolha {

        private PontoLote lote;
        private PontoLotePagina folha;

        @BeforeEach
        void publicarAFolhaDeJulho() {
            lote = loteEmRevisao("MENSAL", PontoLote.CATEGORIA_PREVIA, JULHO_INI, JULHO_FIM);
            folha = folhaDe(lote, 1, ana, CARTAO_DE_JULHO);
            pontoService.publicar(lote.getId(), false);
        }

        @Test
        @DisplayName("uma linha por dia impresso, na ordem da página e começando em 1")
        void umaLinhaPorDiaNaOrdemDaPagina() {
            List<PontoFolhaLinha> linhas = linhasDaFolha(folha);

            assertEquals(LINHAS_DO_CARTAO, linhas.size(),
                    "só as linhas-dia viram linha: o cabeçalho e a linha de TOTAIS do cartão não são dias");
            assertEquals(List.of(1, 2, 3, 4, 5, 6), ordensDe(linhas),
                    "a ordem é a posição na página — é ela, e não a data, que identifica a linha");
            assertEquals(List.of(
                            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3),
                            LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10)),
                    linhas.stream().map(PontoFolhaLinha::getData).toList(),
                    "a data derivada sai do 'dd/mm/aa' impresso, e o cartão só tem dois dígitos de ano");
        }

        @Test
        @DisplayName("as 7 colunas do cartão são cópia do impresso; a célula em branco vira nulo")
        void colunasVerbatimECelulaEmBranco() {
            List<PontoFolhaLinha> linhas = linhasDaFolha(folha);

            PontoFolhaLinha diaDeBatidas = linhas.get(0);
            assertEquals("01/07/26 - qua", diaDeBatidas.getDiaVerbatim());
            assertEquals("08:00", diaDeBatidas.getEnt1());
            assertEquals("12:00", diaDeBatidas.getSai1());
            assertEquals("13:00", diaDeBatidas.getEnt2());
            assertEquals("17:00", diaDeBatidas.getSai2());
            assertEquals("+08:00", diaDeBatidas.getTotalDia(), "o total do dia entra com o sinal impresso");
            assertEquals("+00:00", diaDeBatidas.getBanco());
            assertNull(diaDeBatidas.getOcorrencia(), "dia de batidas não tem status");

            PontoFolhaLinha diaEmBranco = linhas.get(5);
            assertEquals("10/07/26 - sex", diaEmBranco.getDiaVerbatim(),
                    "o dia sem nada impresso continua sendo uma linha da folha");
            assertNull(diaEmBranco.getEnt1());
            assertNull(diaEmBranco.getSai1());
            assertNull(diaEmBranco.getEnt2());
            assertNull(diaEmBranco.getSai2());
            assertNull(diaEmBranco.getTotalDia());
            assertNull(diaEmBranco.getBanco());
            assertEquals(LocalDate.of(2026, 7, 10), diaEmBranco.getData());
        }

        @Test
        @DisplayName("o dia de status guarda o texto nas quatro células e o repete na ocorrência")
        void diaDeStatusEFeriado() {
            List<PontoFolhaLinha> linhas = linhasDaFolha(folha);

            PontoFolhaLinha falta = linhas.get(2);
            assertEquals(List.of("Falta", "Falta", "Falta", "Falta"),
                    List.of(falta.getEnt1(), falta.getSai1(), falta.getEnt2(), falta.getSai2()),
                    "o cartão repete o texto do status nas células de batida, e a folha gravada o repete também");
            assertEquals("Falta", falta.getOcorrencia(), "a ocorrência é o status do dia, sem tradução");
            assertEquals("-08:00", falta.getTotalDia());
            assertEquals("-07:00", falta.getBanco(),
                    "a falta debita a jornada e é a linha de status que carrega o acumulado reduzido");

            PontoFolhaLinha feriado = linhas.get(4);
            assertEquals("09/07/26 - qui", feriado.getDiaVerbatim(),
                    "o cartão troca o dia da semana por 'feri' no feriado; a folha gravada mostra o dia real");
            assertEquals("Feriado", feriado.getEnt1(),
                    "feriado sem texto nas células é marcado nas quatro — é o que o funcionário lê na folha");
            assertEquals("Feriado", feriado.getOcorrencia());
            assertNull(feriado.getTotalDia());
            assertNull(feriado.getBanco());
        }

        @Test
        @DisplayName("o BANCO da folha é o da ÚLTIMA linha com banco impresso, não o da última linha")
        void bancoDaFolhaVemDaUltimaLinhaComBanco() {
            List<PontoFolhaLinha> linhas = linhasDaFolha(folha);

            assertEquals("+01:30", linhas.get(3).getBanco(), "a última linha do cartão que traz acumulado");
            assertNull(linhas.get(4).getBanco(), "as duas linhas seguintes não trazem banco nenhum");
            assertNull(linhas.get(5).getBanco());

            Integer banco = bancoDaFolha(folha);
            assertNotNull(banco, "a folha traz acumulado impresso: a página tem de sair da publicação com banco");
            assertEquals(BANCO_IMPRESSO_MIN, banco.intValue(),
                    "o acumulado da pessoa é o do último dia que o imprimiu — parar na última linha da "
                            + "página deixaria a folha sem banco e a pessoa sem âncora");
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("a ordem da linha é única DENTRO da folha")
    class UnicidadeDaOrdem {

        private PontoLote lote;
        private PontoLotePagina folhaDeAna;
        private PontoLotePagina folhaDeBruno;

        @BeforeEach
        void publicarDuasFolhas() {
            lote = loteEmRevisao("MENSAL", PontoLote.CATEGORIA_PREVIA, JULHO_INI, JULHO_FIM);
            folhaDeAna = folhaDe(lote, 1, ana, CARTAO_DE_JULHO);
            folhaDeBruno = folhaDe(lote, 2, bruno, CARTAO_DE_JULHO);
            pontoService.publicar(lote.getId(), false);
        }

        @Test
        @DisplayName("repetir uma ordem na mesma folha estoura o índice único; a mesma ordem em outra folha é normal")
        void ordemRepetidaNaMesmaFolha() {
            Exception ex = assertThrows(Exception.class, () -> tx.executeWithoutResult(status ->
                    folhaLinhaRepo.saveAndFlush(linhaAvulsa(folhaDeAna.getId(), 1))));

            assertTrue(Causas.contem(ex, "ORA-00001"), ex::getMessage);
            assertTrue(Causas.contem(ex, "UK_PNT_FOLHALIN_ORDEM"),
                    () -> "a recusa tem de vir do índice da folha, não de outra constraint: " + ex.getMessage());

            assertEquals(LINHAS_DO_CARTAO, linhasDaFolha(folhaDeAna).size(),
                    "a recusa não pode ter deixado uma linha a mais na folha");
            // A chave é (página, ordem): as duas folhas do lote têm, cada uma, a sua linha de ordem 1.
            assertEquals(Integer.valueOf(1), linhasDaFolha(folhaDeBruno).get(0).getOrdem());
            assertEquals(LINHAS_DO_CARTAO, linhasDaFolha(folhaDeBruno).size());
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("morta a folha, morrem as linhas dela")
    class ExclusaoLevaAsLinhas {

        private PontoLote lote;
        private PontoLotePagina folhaDeAna;
        private PontoLotePagina folhaDeBruno;

        @BeforeEach
        void publicarOLoteDeDuasFolhas() {
            lote = loteEmRevisao("MENSAL", PontoLote.CATEGORIA_PREVIA, JULHO_INI, JULHO_FIM);
            folhaDeAna = folhaDe(lote, 1, ana, CARTAO_DE_JULHO);
            folhaDeBruno = folhaDe(lote, 2, bruno, CARTAO_DE_JULHO);
            pontoService.publicar(lote.getId(), false);
            assertEquals(LINHAS_DO_CARTAO, linhasDaFolha(folhaDeAna).size(), "a folha nasce gravada");
            assertEquals(LINHAS_DO_CARTAO, linhasDaFolha(folhaDeBruno).size());
        }

        @Test
        @DisplayName("excluir o LOTE apaga as linhas das duas folhas, e a trilha registra quantas eram")
        void exclusaoDeLoteApagaAsLinhas() {
            Map<String, Object> resumo = exclusaoService.excluirLote(lote.getId(), MASTER, admin.getId());

            assertTrue(linhasDaFolha(folhaDeAna).isEmpty(), "a folha de Ana não existe mais: as linhas dela também não");
            assertTrue(linhasDaFolha(folhaDeBruno).isEmpty());

            assertEquals(2L * LINHAS_DO_CARTAO, resumo.get("linhas_folha_excluidas"),
                    "o número é contado ANTES de apagar — depois da deleção ele não existe mais para a trilha");
            assertTrue(trilhaDoLote(lote.getId())
                            .contains("\"linhas_folha_excluidas\":" + (2 * LINHAS_DO_CARTAO)),
                    () -> "o snapshot da trilha tem de guardar a contagem: " + trilhaDoLote(lote.getId()));
        }

        @Test
        @DisplayName("excluir uma FOLHA apaga só as linhas dela; a folha do outro continua inteira")
        void exclusaoDePaginaApagaSoAsLinhasDela() {
            Map<String, Object> resumo = exclusaoService.excluirPagina(
                    lote.getId(), folhaDeAna.getId(), MASTER, admin.getId());

            assertTrue(linhasDaFolha(folhaDeAna).isEmpty());
            assertEquals(LINHAS_DO_CARTAO, linhasDaFolha(folhaDeBruno).size(),
                    "a exclusão é da folha de Ana — apagar por LOTE em vez de por PÁGINA levaria a de Bruno junto");
            assertEquals(List.of(1, 2, 3, 4, 5, 6), ordensDe(linhasDaFolha(folhaDeBruno)),
                    "e a folha que sobrou continua completa, da primeira à última linha");

            assertEquals((long) LINHAS_DO_CARTAO, resumo.get("linhas_folha_excluidas"),
                    "a trilha conta as linhas da folha excluída, não as do lote inteiro");
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("regravar a mesma folha não duplica linha")
    class ReprocessamentoNaoDuplica {

        private PontoLote lote;
        private PontoLotePagina folha;

        @BeforeEach
        void publicarFolhaSemBanco() {
            lote = loteEmRevisao("SEMANAL", null, SEMANA_INI, SEMANA_FIM);
            folha = folhaDe(lote, 1, ana, CARTAO_SEM_BANCO);
            pontoService.publicar(lote.getId(), false);
        }

        @Test
        @DisplayName("reprocessar duas vezes a folha já gravada mantém as mesmas linhas, na mesma ordem")
        void reprocessarMantemAContagem() {
            assertEquals(LINHAS_DO_CARTAO_SEM_BANCO, linhasDaFolha(folha).size(), "a folha nasce gravada");
            assertNull(bancoDaFolha(folha),
                    "nenhum dia deste cartão traz acumulado: é por ficar sem banco que a folha continua "
                            + "na fila do reprocessamento e pode ser regravada");

            Map<String, Object> primeira = pontoService.reprocessarBanco();
            assertEquals(1, primeira.get("paginas_processadas"), "a folha sem banco é a que o reprocessamento pega");
            assertEquals(LINHAS_DO_CARTAO_SEM_BANCO, linhasDaFolha(folha).size(),
                    "regravar a folha apaga a geração anterior antes de gravar a nova — sem isso a segunda "
                            + "passada esbarraria na unicidade (página, ordem) ou dobraria a folha");

            pontoService.reprocessarBanco();

            List<PontoFolhaLinha> linhas = linhasDaFolha(folha);
            assertEquals(LINHAS_DO_CARTAO_SEM_BANCO, linhas.size(), "a contagem é estável a cada nova passada");
            assertEquals(List.of(1, 2, 3), ordensDe(linhas), "e a ordem recomeça em 1, como na primeira gravação");
            assertEquals("15/07/26 - qua", linhas.get(2).getDiaVerbatim(), "o conteúdo regravado é o mesmo");
            assertEquals("Atest", linhas.get(2).getOcorrencia());
        }
    }
}
