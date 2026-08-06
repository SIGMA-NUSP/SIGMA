package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT do ciclo completo da folha mensal contra Oracle real: a PRÉVIA abre o mês, a DEFINITIVA o
 * fecha. Publicada a definitiva, a prévia da mesma pessoa deixa de existir para o dono (some de
 * "Minhas Folhas" e o acesso direto vira 404) enquanto o admin continua alcançando as duas; o banco
 * de horas passa a ancorar na definitiva e volta sozinho para a prévia quando ela é excluída; e
 * nenhum dia da competência aceita mais retificação, por folha nenhuma.
 *
 * <p>Nada disso se decide em memória: quem responde "esta pessoa já tem definitiva publicada neste
 * mês?" é uma consulta que cruza PNT_LOTE_PAGINA com PNT_LOTE, e a âncora sai de um ORDER BY que,
 * entre duas folhas do MESMO período, desempata pela ordem de criação — por isso a prévia é ancorada
 * no relógio do banco uma hora antes, como no mundo real, em vez de contar com a sorte dos
 * microssegundos.
 *
 * <p>{@code @SpringBootTest} porque {@code @DataJpaTest} não instancia services e faz rollback de
 * tudo, e aqui as publicações e a exclusão precisam COMMITAR de verdade; sem rollback automático, a
 * limpeza é manual. Os PDFs das folhas são gravados em disco quando o teste vai BAIXAR ou LER a
 * folha — o BANCO, quando importa, é injetado na fixture (a folha sintética não tem coluna nenhuma
 * para o parser achar).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoSubstituicaoDefinitivaIT {

    /**
     * O username do master vem da CONFIGURAÇÃO ({@code app.admin.master-username} do
     * application-test.yml) — só ele exclui publicação.
     */
    private static final String MASTER = "master.teste";

    private static final LocalDate JULHO_INI = LocalDate.of(2026, 7, 1);
    private static final LocalDate JULHO_FIM = LocalDate.of(2026, 7, 31);
    private static final LocalDate SEMANA_INI = LocalDate.of(2026, 7, 1);
    private static final LocalDate SEMANA_FIM = LocalDate.of(2026, 7, 5);

    /** Dia retificado enquanto o mês ainda estava aberto. */
    private static final LocalDate DIA_RETIFICADO = LocalDate.of(2026, 7, 1);
    /** Dia inédito, tentado depois que a definitiva fechou o mês. */
    private static final LocalDate DIA_RECUSADO = LocalDate.of(2026, 7, 2);

    /** BANCO impresso em cada uma das duas folhas do mês (minutos). */
    private static final int BANCO_DA_PREVIA = 600;
    private static final int BANCO_DA_DEFINITIVA = 900;

    /** Distância, em segundos, entre a publicação da prévia e a da definitiva. */
    private static final int UMA_HORA = 3600;

    @Autowired private PontoService pontoService;
    @Autowired private PontoExclusaoService exclusaoService;
    @Autowired private RetificacaoService retificacaoService;
    @Autowired private SaldoAberturaService saldoAberturaService;
    @Autowired private PontoBancoSaldoRepository saldoRepo;

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

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            ana = CenarioFactory.novoOperador(em, "Ana da Competencia");
            ana.setCargaHoraria(40);   // o gate do banco de horas recusa carga nula
            em.merge(ana);
            em.flush();
            pessoaIds.add(ana.getId());
        });
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM PNT_EXCLUSAO_LOG WHERE EXCLUIDO_POR_ID IN :admins");
            executar("DELETE FROM PNT_RETIFICACAO WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_BANCO_SALDO WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_LOTE WHERE ID IN :lotes");          // ON DELETE CASCADE leva as páginas
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

    /** Lote em REVISÃO com a natureza informada — o estado de onde a publicação parte. */
    private PontoLote loteEmRevisao(String tipo, String categoria, LocalDate inicio, LocalDate fim) {
        PontoLote lote = CenarioFactory.novoLotePonto(em, tipo, categoria, inicio, fim, admin);
        loteIds.add(lote.getId());
        return lote;
    }

    /** Lote JÁ publicado, para os cenários que partem do estado final e não da publicação em si. */
    private PontoLote lotePublicado(String tipo, String categoria, LocalDate inicio, LocalDate fim) {
        PontoLote lote = CenarioFactory.novoLotePontoPublicado(em, tipo, categoria, inicio, fim, admin);
        loteIds.add(lote.getId());
        return lote;
    }

    private PontoLotePagina folhaDeAna(PontoLote lote) {
        return CenarioFactory.novaPaginaLote(em, lote, 1, ana.getId(), "OPERADOR");
    }

    private PontoLotePagina folhaDeAna(PontoLote lote, int bancoFinalMin) {
        return CenarioFactory.novaPaginaLote(em, lote, 1, ana.getId(), "OPERADOR", bancoFinalMin);
    }

    /**
     * Grava em disco um PDF de uma página, como o upload faria. O conteúdo não é um cartão Secullum:
     * o parser não encontra linha-dia nenhuma e a folha fica sem BANCO — o que basta para os
     * cenários de acesso, que só precisam do arquivo existir e abrir.
     */
    private void gravarPdf(String relPath) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, bytes);
            doc.open();
            doc.add(new Paragraph("Folha de ponto de teste"));
            doc.close();
            Path destino = Paths.get(filesDir).resolve(relPath);
            Files.createDirectories(destino.getParent());
            Files.write(destino, bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("falha ao gravar o PDF da folha em " + relPath, e);
        }
    }

    /** Retificação real do dono pela folha indicada (o mesmo caminho da tela). */
    private Map<String, Object> retificar(PontoLotePagina pagina, LocalDate dia) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", dia.toString());
        item.put("ent1", "08:00");
        item.put("sai1", "12:00");
        return retificacaoService.criarRetificacoes(pagina.getId(), ana.getId(), Map.of("dias", List.of(item)));
    }

    // ══════════════════════════════════════════════════════════════
    // Leitura do estado
    // ══════════════════════════════════════════════════════════════

    private List<String> idsDasFolhasDeAna() {
        return pontoService.minhasFolhas(ana.getId()).stream()
                .map(f -> String.valueOf(f.get("id"))).toList();
    }

    private PontoBancoSaldo saldoDeAna() {
        return tx.execute(status -> saldoRepo.findByPessoaIdAndPessoaTipo(ana.getId(), "OPERADOR").orElse(null));
    }

    private Map<String, Object> retificacoesNaFolha(PontoLotePagina pagina) {
        return tx.execute(status -> retificacaoService.listarRetificacoes(pagina.getId(), ana.getId()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itens(Map<String, Object> listagem) {
        return (List<Map<String, Object>>) listagem.get("retificacoes");
    }

    /** A recusa que o usuário recebe — o tipo é sempre o mesmo; o que varia é status e frase. */
    private static ServiceValidationException recusaDe(Executable acao) {
        return assertThrows(ServiceValidationException.class, acao);
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("a definitiva entra por cima da prévia — que, para o dono, deixa de existir")
    class PreviaSubstituida {

        private PontoLote previa;
        private PontoLote definitiva;
        private PontoLotePagina folhaPrevia;
        private PontoLotePagina folhaDefinitiva;

        @BeforeEach
        void publicarAsDuasFolhasDoMes() {
            tx.executeWithoutResult(status -> {
                previa = loteEmRevisao("MENSAL", PontoLote.CATEGORIA_PREVIA, JULHO_INI, JULHO_FIM);
                folhaPrevia = folhaDeAna(previa);
                definitiva = loteEmRevisao("MENSAL", PontoLote.CATEGORIA_DEFINITIVA, JULHO_INI, JULHO_FIM);
                folhaDefinitiva = folhaDeAna(definitiva);
            });
            gravarPdf(folhaPrevia.getArquivoPagina());
            gravarPdf(folhaDefinitiva.getArquivoPagina());

            pontoService.publicar(previa.getId(), false);
            // A definitiva é a ÚNICA folha que passa pela guarda com uma mensal da pessoa já publicada
            // no mês: ela não colide com a prévia, substitui.
            Map<String, Object> publicada = pontoService.publicar(definitiva.getId(), false);
            assertEquals("PUBLICADO", publicada.get("status"));
        }

        @Test
        @DisplayName("some de Minhas Folhas: o dono passa a ver só a definitiva do mês")
        void previaSaiDaListaDoDono() {
            assertEquals(List.of(folhaDefinitiva.getId()), idsDasFolhasDeAna(),
                    "com a definitiva publicada, a prévia deixou de ser a folha do mês — duas folhas do "
                            + "mesmo mês na lista fariam o dono escolher qual conferir");

            Map<String, Object> folha = pontoService.minhasFolhas(ana.getId()).get(0);
            assertEquals("MENSAL", folha.get("tipo"));
            assertEquals(PontoLote.CATEGORIA_DEFINITIVA, folha.get("categoria"),
                    "a natureza vai na lista: é ela que o dono vê ao lado do período");
        }

        @Test
        @DisplayName("o acesso direto do dono à prévia vira 404; a definitiva continua abrindo")
        void donoNaoAlcancaMaisAPrevia() {
            ServiceValidationException download = recusaDe(
                    () -> pontoService.baixarFolha(folhaPrevia.getId(), ana.getId(), "operador"));
            assertEquals(HttpStatus.NOT_FOUND, download.getStatus(),
                    "sumir da lista sem fechar o link deixaria a folha velha viva para quem guardou a URL");
            assertEquals("Folha indisponível.", download.getMessage());

            ServiceValidationException dados = recusaDe(
                    () -> pontoService.dadosFolha(folhaPrevia.getId(), ana.getId(), "operador"));
            assertEquals(HttpStatus.NOT_FOUND, dados.getStatus());
            assertEquals("Folha indisponível.", dados.getMessage());

            // E a folha que passou a valer segue acessível pelos dois caminhos.
            assertTrue(pontoService.baixarFolha(folhaDefinitiva.getId(), ana.getId(), "operador")
                    .conteudo().length > 0);
            assertEquals(PontoLote.CATEGORIA_DEFINITIVA,
                    pontoService.dadosFolha(folhaDefinitiva.getId(), ana.getId(), "operador").get("categoria"));
        }

        @Test
        @DisplayName("o admin continua baixando e lendo as DUAS folhas do mês")
        void adminEnxergaAsDuas() {
            // O histórico de lotes é do admin: a substituição é o que o DONO vê, não uma exclusão.
            assertTrue(pontoService.baixarFolha(folhaPrevia.getId(), admin.getId(), "administrador")
                    .conteudo().length > 0, "a prévia continua baixável pelo admin");
            assertTrue(pontoService.baixarFolha(folhaDefinitiva.getId(), admin.getId(), "administrador")
                    .conteudo().length > 0);

            assertEquals(PontoLote.CATEGORIA_PREVIA,
                    pontoService.dadosFolha(folhaPrevia.getId(), admin.getId(), "administrador").get("categoria"));
            assertEquals(PontoLote.CATEGORIA_DEFINITIVA,
                    pontoService.dadosFolha(folhaDefinitiva.getId(), admin.getId(), "administrador").get("categoria"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("a âncora do banco de horas entre as duas folhas do mesmo mês")
    class AncoraEntrePreviaEDefinitiva {

        private PontoLote previa;
        private PontoLote definitiva;
        private PontoLotePagina folhaPrevia;
        private PontoLotePagina folhaDefinitiva;

        @BeforeEach
        void duasFolhasPublicadasComBanco() {
            tx.executeWithoutResult(status -> {
                previa = lotePublicado("MENSAL", PontoLote.CATEGORIA_PREVIA, JULHO_INI, JULHO_FIM);
                folhaPrevia = folhaDeAna(previa, BANCO_DA_PREVIA);
                // A prévia é do começo do mês e a definitiva vem depois: as duas cobrem o MESMO período,
                // e é a ordem de criação que decide qual delas o banco de horas toma como oficial.
                CenarioFactory.fixarTimestamp(em, "PNT_LOTE", "CRIADO_EM", previa.getId(), UMA_HORA);

                definitiva = lotePublicado("MENSAL", PontoLote.CATEGORIA_DEFINITIVA, JULHO_INI, JULHO_FIM);
                folhaDefinitiva = folhaDeAna(definitiva, BANCO_DA_DEFINITIVA);
                saldoAberturaService.reancorar(ana.getId(), "OPERADOR");
            });
        }

        @Test
        @DisplayName("com as duas publicadas a âncora é a DEFINITIVA; excluída ela, a prévia volta a ancorar sozinha")
        void ancoraSegueADefinitivaEVoltaParaAPrevia() {
            PontoBancoSaldo comAsDuas = saldoDeAna();
            assertEquals(folhaDefinitiva.getId(), comAsDuas.getAncoraPaginaId(),
                    "as duas folhas cobrem julho e as duas têm BANCO: a oficial é a que chegou por último");
            assertEquals(BANCO_DA_DEFINITIVA, comAsDuas.getSaldoAberturaMin());
            assertEquals(JULHO_FIM, comAsDuas.getAncoraData());

            exclusaoService.excluirLote(definitiva.getId(), MASTER, admin.getId());

            PontoBancoSaldo semADefinitiva = saldoDeAna();
            assertEquals(folhaPrevia.getId(), semADefinitiva.getAncoraPaginaId(),
                    "morta a definitiva, a prévia do mesmo mês continua publicada e volta a ser a folha oficial");
            assertEquals(BANCO_DA_PREVIA, semADefinitiva.getSaldoAberturaMin());
            assertEquals(JULHO_FIM, semADefinitiva.getAncoraData());

            // E o dono reencontra a prévia: a substituição é uma leitura do estado atual, não uma marca
            // gravada na folha — sem definitiva no mês, não há o que substituir.
            assertEquals(List.of(folhaPrevia.getId()), idsDasFolhasDeAna());
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("retificação com o mês encerrado pela definitiva")
    class MesFechadoParaRetificacao {

        private PontoLote semanal;
        private PontoLote definitiva;
        private PontoLotePagina folhaSemanal;
        private PontoLotePagina folhaDefinitiva;
        private String retificacaoDoDiaAberto;

        @BeforeEach
        void semanalPublicadaComUmDiaRetificado() {
            tx.executeWithoutResult(status -> {
                semanal = loteEmRevisao("SEMANAL", null, SEMANA_INI, SEMANA_FIM);
                folhaSemanal = folhaDeAna(semanal);
            });
            pontoService.publicar(semanal.getId(), false);
            retificacaoDoDiaAberto = String.valueOf(
                    itens(retificar(folhaSemanal, DIA_RETIFICADO)).get(0).get("id"));
        }

        /** Fecha julho: a mensal definitiva entra mesmo com a semanal do mesmo mês já publicada. */
        private void publicarADefinitivaDeJulho() {
            tx.executeWithoutResult(status -> {
                definitiva = loteEmRevisao("MENSAL", PontoLote.CATEGORIA_DEFINITIVA, JULHO_INI, JULHO_FIM);
                folhaDefinitiva = folhaDeAna(definitiva);
            });
            pontoService.publicar(definitiva.getId(), false);
        }

        @Test
        @DisplayName("o dia é recusado tanto pela folha semanal quanto pela própria mensal")
        void nenhumaFolhaRetificaODiaDoMesFechado() {
            publicarADefinitivaDeJulho();

            String recusaEsperada = "O dia 02/07/2026 não pode ser retificado: "
                    + "a folha de ponto definitiva de Julho/2026 já foi publicada.";

            // A semanal continua dentro do prazo dela e o dia continua dentro do período dela: o que
            // fecha a porta é a competência, não a folha por onde o usuário entra.
            ServiceValidationException pelaSemanal = recusaDe(() -> retificar(folhaSemanal, DIA_RECUSADO));
            assertEquals(HttpStatus.BAD_REQUEST, pelaSemanal.getStatus());
            assertEquals(recusaEsperada, pelaSemanal.getMessage());

            // Nem a própria definitiva retifica: ela fecha o mês, não o reabre para si mesma.
            ServiceValidationException pelaMensal = recusaDe(() -> retificar(folhaDefinitiva, DIA_RECUSADO));
            assertEquals(HttpStatus.BAD_REQUEST, pelaMensal.getStatus());
            assertEquals(recusaEsperada, pelaMensal.getMessage());

            List<Map<String, Object>> gravadas = itens(retificacoesNaFolha(folhaSemanal));
            assertEquals(List.of(DIA_RETIFICADO.toString()), gravadas.stream().map(r -> r.get("data")).toList(),
                    "as duas recusas não podem ter deixado nada para trás: só o dia retificado com o mês aberto");
        }

        @Test
        @DisplayName("a folha passa a se declarar fechada e a retificação já gravada não aceita mais edição")
        void mesFechadoBloqueiaAEdicaoDaRetificacaoJaGravada() {
            assertFalse((Boolean) retificacoesNaFolha(folhaSemanal).get("mes_fechado"),
                    "antes da definitiva o mês está aberto e a tela libera a folha inteira");

            publicarADefinitivaDeJulho();

            assertTrue((Boolean) retificacoesNaFolha(folhaSemanal).get("mes_fechado"),
                    "todo o período da semanal cai em julho, que a definitiva encerrou");

            ServiceValidationException edicao = recusaDe(() -> retificacaoService.editarRetificacao(
                    folhaSemanal.getId(), retificacaoDoDiaAberto, ana.getId(),
                    Map.of("ent1", "09:00", "sai1", "13:00")));
            assertEquals(HttpStatus.BAD_REQUEST, edicao.getStatus());
            assertEquals("O dia 01/07/2026 não pode ser retificado: "
                    + "a folha de ponto definitiva de Julho/2026 já foi publicada.", edicao.getMessage());

            Map<String, Object> mantida = itens(retificacoesNaFolha(folhaSemanal)).get(0);
            assertEquals("08:00", mantida.get("ent1"), "a recusa não pode ter gravado a edição pela metade");
            assertEquals("12:00", mantida.get("sai1"));
        }
    }
}
