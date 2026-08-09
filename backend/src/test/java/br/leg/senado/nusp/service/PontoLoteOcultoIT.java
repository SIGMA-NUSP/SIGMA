package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT do lote OCULTO (acervo histórico) contra Oracle real: as folhas dele alimentam o sumário de
 * ocorrências como as de qualquer lote publicado, mas não existem para o dono nem para o admin
 * comum — só o master as vê —, não ancoram o banco de horas e a publicação delas não gera
 * comunicação nenhuma, nem mesmo o alerta de dia incompleto.
 *
 * <p>O PDF é de verdade: a publicação lê o cartão sintético pelo caminho normal (extração de
 * texto, parser, gravação da tabela da folha e do BANCO), e é dessa tabela que o sumário e a
 * âncora saem — o que este IT exercita é exatamente a fronteira entre o que o oculto alimenta
 * (sumário, BANCO extraído) e o que ele nunca toca (listas, avisos, âncora do saldo).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoLoteOcultoIT {

    /** O username do master vem da configuração ({@code app.admin.master-username}). */
    private static final String MASTER = "master.teste";
    /** Qualquer username que não seja o master — a guarda compara texto, não cadastro. */
    private static final String ADMIN_COMUM = "admin.comum";

    private static final LocalDate MAIO_2025_INI = LocalDate.of(2025, 5, 1);
    private static final LocalDate MAIO_2025_FIM = LocalDate.of(2025, 5, 31);
    private static final LocalDate SEMANA_2026_INI = LocalDate.of(2026, 6, 15);
    private static final LocalDate SEMANA_2026_FIM = LocalDate.of(2026, 6, 21);

    /**
     * Cartão do acervo: um dia completo, um dia com TRÊS batidas (registro pela metade — é ele que
     * provaria o alerta de dia incompleto se o lote não fosse oculto) e uma Falta para o sumário.
     * O BANCO acumulado fecha em -06:00.
     */
    private static final List<String> CARTAO_OCULTO = List.of(
            "Cartao Ponto - Senado Federal",
            "05/05/25 - seg 08:00 12:00 13:00 17:00 +08:00 -06:00",
            "06/05/25 - ter 08:00 12:00 13:00 +04:00 -06:00",
            "07/05/25 - qua FaltaFaltaFaltaFalta -08:00 -06:00");

    /** Cartão de folha visível comum, com BANCO +01:00 — é ela que deve ancorar o saldo. */
    private static final List<String> CARTAO_VISIVEL = List.of(
            "Cartao Ponto - Senado Federal",
            "15/06/26 - seg 08:00 12:00 13:00 17:00 +08:00 +01:00");

    @Autowired private PontoService pontoService;
    @Autowired private RetificacaoService retificacaoService;
    @Autowired private SumarioOcorrenciasService sumarioService;
    @Autowired private PontoLotePaginaRepository paginaRepo;

    /** O contexto completo liga @EnableScheduling; estes dois fazem I/O de rede a cada poucos segundos. */
    @MockitoBean private AgendaLegislativaService agendaLegislativaService;
    @MockitoBean private CessaoSheetService cessaoSheetService;

    @Autowired private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    @Value("${app.files.dir}")
    private String filesDir;

    private TransactionTemplate tx;

    private final List<String> adminIds = new ArrayList<>();
    private final List<String> pessoaIds = new ArrayList<>();
    private final List<String> loteIds = new ArrayList<>();
    private final List<String> arquivos = new ArrayList<>();

    private Administrador admin;
    private Operador diana;
    private PontoLote loteOculto;
    private PontoLotePagina paginaOculta;

    /** Uma operadora com uma única folha, a do lote oculto de maio/2025, ainda em revisão. */
    @BeforeEach
    void semearOAcervoOculto() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            diana = CenarioFactory.novoOperador(em, "Diana do Acervo");
            diana.setCargaHoraria(40);
            em.merge(diana);
            em.flush();
            pessoaIds.add(diana.getId());
        });
        loteOculto = loteEmRevisao("MENSAL", PontoLote.CATEGORIA_PREVIA, MAIO_2025_INI, MAIO_2025_FIM, true);
        paginaOculta = folhaDe(loteOculto, 1, diana, CARTAO_OCULTO);
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM FRM_AVISO_CIENCIA WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins)");
            executar("DELETE FROM FRM_AVISO_ALVO WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins)");
            executar("DELETE FROM FRM_AVISO_MENSAGEM WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins)");
            executar("DELETE FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins");
            executar("DELETE FROM PNT_BANCO_SALDO WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_LOTE WHERE ID IN :lotes");
            executar("DELETE FROM PES_OPERADOR WHERE ID IN :pessoas");
            executar("DELETE FROM PES_ADMINISTRADOR WHERE ID IN :admins");
        });
        for (String relPath : arquivos) {
            try {
                Files.deleteIfExists(Paths.get(filesDir).resolve(relPath));
            } catch (Exception e) {
                throw new IllegalStateException("falha ao apagar o PDF de teste " + relPath, e);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Publicação silenciosa
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("publicar o lote oculto não cria comunicação nenhuma — nem com Emitir aviso, nem o alerta de dia incompleto")
    void publicacaoOcultaEmSilencioAbsoluto() {
        Map<String, Object> publicado = publicarOculto();

        assertEquals("PUBLICADO", publicado.get("status"));
        assertEquals(Boolean.TRUE, publicado.get("oculto"));
        // A prévia com dia de três batidas dispararia o alerta em qualquer lote visível — e o aviso
        // da folha foi pedido (emitir_aviso=true). O silêncio só se explica pelo oculto.
        assertEquals(0, contar("SELECT COUNT(*) FROM FRM_AVISO_CADASTRO WHERE ORIGEM_LOTE_ID = :id"));
    }

    // ══════════════════════════════════════════════════════════════
    // Visibilidade
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a lista de lotes traz o oculto (com o selo) só para o master")
    void listaSoParaOMaster() {
        publicarOculto();

        List<Map<String, Object>> doMaster = tx.execute(status -> pontoService.listarLotes(MASTER));
        Map<String, Object> naLista = doMaster.stream()
                .filter(l -> loteOculto.getId().equals(l.get("id"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, naLista.get("oculto"));

        List<Map<String, Object>> doComum = tx.execute(status -> pontoService.listarLotes(ADMIN_COMUM));
        assertTrue(doComum.stream().noneMatch(l -> loteOculto.getId().equals(l.get("id"))),
                "o lote oculto vazou para a lista do admin comum");
    }

    @Test
    @DisplayName("acesso por id: 404 para o admin comum, detalhe normal para o master")
    void acessoPorIdSoParaOMaster() {
        publicarOculto();

        ServiceValidationException recusa = assertThrows(ServiceValidationException.class,
                () -> tx.execute(status -> pontoService.obterLote(loteOculto.getId(), ADMIN_COMUM)));
        assertEquals(HttpStatus.NOT_FOUND, recusa.getStatus());

        assertEquals(loteOculto.getId(),
                tx.execute(status -> pontoService.obterLote(loteOculto.getId(), MASTER)).get("id"));
    }

    @Test
    @DisplayName("para a dona a folha oculta não existe: fora de minhas-folhas e download indisponível")
    void folhaOcultaNaoExisteParaADona() {
        publicarOculto();

        assertTrue(tx.execute(status -> pontoService.minhasFolhas(diana.getId())).isEmpty(),
                "a folha oculta apareceu na lista da dona");

        ServiceValidationException recusa = assertThrows(ServiceValidationException.class,
                () -> tx.execute(status ->
                        pontoService.baixarFolha(paginaOculta.getId(), diana.getId(), "operador", "diana.acervo")));
        assertEquals(HttpStatus.NOT_FOUND, recusa.getStatus());
        assertEquals("Folha indisponível.", recusa.getMessage());
    }

    @Test
    @DisplayName("a retificação responde o mesmo que o download: a folha oculta está indisponível para a dona")
    void retificacaoDaFolhaOcultaNaoExisteParaADona() {
        publicarOculto();

        ServiceValidationException recusa = assertThrows(ServiceValidationException.class,
                () -> tx.execute(status ->
                        retificacaoService.listarRetificacoes(paginaOculta.getId(), diana.getId())));
        assertEquals(HttpStatus.NOT_FOUND, recusa.getStatus());
        assertEquals("Folha indisponível.", recusa.getMessage());
    }

    @Test
    @DisplayName("a definitiva oculta não fecha o mês na visão da dona — a pergunta do mês fechado a ignora")
    void definitivaOcultaNaoFechaOMesParaADona() {
        PontoLote definitivaOculta = tx.execute(status -> {
            PontoLote lote = CenarioFactory.novoLotePontoPublicado(em, "MENSAL", PontoLote.CATEGORIA_DEFINITIVA,
                    LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), admin);
            lote.setOculto(true);
            em.merge(lote);
            CenarioFactory.novaPaginaLote(em, lote, 1, diana.getId(), "OPERADOR");
            em.flush();
            return lote;
        });
        loteIds.add(definitivaOculta.getId());

        assertEquals(Long.valueOf(0L), tx.execute(status -> paginaRepo.contarDefinitivasPublicadas(
                        diana.getId(), "OPERADOR", LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30))),
                "a definitiva oculta fechou o mês para a dona");
    }

    // ══════════════════════════════════════════════════════════════
    // O que o oculto ALIMENTA e o que ele NUNCA toca
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("o sumário de ocorrências conta a Falta da folha oculta como a de qualquer folha publicada")
    void sumarioContaAFaltaDaOculta() {
        publicarOculto();

        Map<String, Object> resposta = tx.execute(status -> sumarioService.sumario("2025-05", "2025-05"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> funcionarios = (List<Map<String, Object>>) resposta.get("funcionarios");
        Map<String, Object> contagens = funcionarios.stream()
                .filter(f -> diana.getNomeCompleto().equals(f.get("nome")))
                .map(f -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> c = (Map<String, Object>) f.get("contagens");
                    return c;
                })
                .findFirst().orElseThrow(() -> new AssertionError("a dona da folha oculta não entrou no sumário"));
        assertEquals(1, contagens.get("Falta"));
    }

    @Test
    @DisplayName("a folha oculta nunca ancora o saldo: ele nasce zerado e só a primeira folha visível o ancora")
    void ocultaNaoAncoraOBanco() {
        publicarOculto();

        // O BANCO da folha oculta foi extraído (é acervo completo) — e mesmo assim não virou âncora.
        assertEquals(Integer.valueOf(-360),
                valorDe("SELECT BANCO_FINAL_MIN FROM PNT_LOTE_PAGINA WHERE ID = :id", paginaOculta.getId()),
                "o BANCO da folha oculta não foi extraído na publicação");
        assertNull(ancoraDe(diana.getId()), "a folha oculta ancorou o saldo");
        assertEquals(Integer.valueOf(0),
                valorDe("SELECT SALDO_ABERTURA_MIN FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :id", diana.getId()));

        // A primeira folha VISÍVEL publicada assume a âncora, ainda que cubra período mais recente.
        PontoLote visivel = loteEmRevisao("SEMANAL", null, SEMANA_2026_INI, SEMANA_2026_FIM, false);
        PontoLotePagina paginaVisivel = folhaDe(visivel, 1, diana, CARTAO_VISIVEL);
        tx.execute(status -> pontoService.publicar(visivel.getId(), false, false, MASTER));

        assertEquals(paginaVisivel.getId(), ancoraDe(diana.getId()));
        assertEquals(Integer.valueOf(60),
                valorDe("SELECT SALDO_ABERTURA_MIN FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :id", diana.getId()));
    }

    // ══════════════════════════════════════════════════════════════
    // Criação
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("upload oculto é gesto do master: qualquer outro admin leva 403 e nada é gravado")
    void uploadOcultoExigeMaster() {
        MockMultipartFile pdf = new MockMultipartFile("arquivo", "acervo.pdf", "application/pdf",
                pdfBytes(CARTAO_OCULTO));

        // Sem template em volta: a transação é a do próprio service, e o que ela deixar no banco
        // fica — é isso que permite provar que a recusa não gravou nada.
        ServiceValidationException recusa = assertThrows(ServiceValidationException.class,
                () -> pontoService.upload(pdf, "MENSAL", "DEFINITIVA",
                        "2025-05-01", "2025-05-31", admin.getId(), true, ADMIN_COMUM));
        assertEquals(HttpStatus.FORBIDDEN, recusa.getStatus());
        // O cenário nasce com exatamente um lote (o do seed): o upload recusado não pode ter criado outro.
        assertEquals(Integer.valueOf(1), valorDe("SELECT COUNT(*) FROM PNT_LOTE WHERE CRIADO_POR_ID = :id",
                admin.getId()), "o upload recusado deixou lote para trás");
    }

    // ══════════════════════════════════════════════════════════════
    // Seed e leitura
    // ══════════════════════════════════════════════════════════════

    private Map<String, Object> publicarOculto() {
        return tx.execute(status -> pontoService.publicar(loteOculto.getId(), true, false, MASTER));
    }

    private PontoLote loteEmRevisao(String tipo, String categoria, LocalDate ini, LocalDate fim, boolean oculto) {
        PontoLote novo = tx.execute(status -> {
            PontoLote lote = CenarioFactory.novoLotePonto(em, tipo, categoria, ini, fim, admin);
            lote.setOculto(oculto);
            em.merge(lote);
            em.flush();
            return lote;
        });
        loteIds.add(novo.getId());
        return novo;
    }

    private PontoLotePagina folhaDe(PontoLote lote, int numeroPagina, Operador pessoa, List<String> cartao) {
        PontoLotePagina pagina = tx.execute(status ->
                CenarioFactory.novaPaginaLote(em, lote, numeroPagina, pessoa.getId(), "OPERADOR"));
        gravarPdf(pagina.getArquivoPagina(), cartao);
        return pagina;
    }

    private void gravarPdf(String relPath, List<String> linhas) {
        try {
            Path destino = Paths.get(filesDir).resolve(relPath);
            Files.createDirectories(destino.getParent());
            Files.write(destino, pdfBytes(linhas));
            arquivos.add(relPath);
        } catch (Exception e) {
            throw new IllegalStateException("falha ao gravar o PDF da folha em " + relPath, e);
        }
    }

    private byte[] pdfBytes(List<String> linhas) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, bytes);
            doc.open();
            for (String linha : linhas) doc.add(new Paragraph(linha));
            doc.close();
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("falha ao montar o PDF sintético", e);
        }
    }

    private void executar(String sql) {
        var query = em.createNativeQuery(sql);
        if (sql.contains(":admins")) query.setParameter("admins", adminIds);
        if (sql.contains(":pessoas")) query.setParameter("pessoas", pessoaIds);
        if (sql.contains(":lotes")) query.setParameter("lotes", loteIds);
        query.executeUpdate();
    }

    /** ANCORA_PAGINA_ID do saldo da pessoa; nulo quando não há âncora (ou nem linha de saldo). */
    private String ancoraDe(String pessoaId) {
        List<?> linhas = tx.execute(status -> em.createNativeQuery(
                        "SELECT ANCORA_PAGINA_ID FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :id")
                .setParameter("id", pessoaId)
                .getResultList());
        return linhas.isEmpty() || linhas.get(0) == null ? null : String.valueOf(linhas.get(0));
    }

    private int contar(String sql) {
        return tx.execute(status -> ((Number) em.createNativeQuery(sql)
                .setParameter("id", loteOculto.getId())
                .getSingleResult()).intValue());
    }

    /** Valor numérico de uma consulta por id; nulo vira nulo (o assert nomeia o que faltou). */
    private Integer valorDe(String sql, String id) {
        Object v = tx.execute(status -> em.createNativeQuery(sql)
                .setParameter("id", id)
                .getSingleResult());
        return v == null ? null : ((Number) v).intValue();
    }
}
