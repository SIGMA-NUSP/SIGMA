package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.TipoAviso;
import br.leg.senado.nusp.it.support.CenarioFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT do aviso de dia com registro incompleto contra Oracle real: publicar uma folha em que algum dia
 * ficou com entrada ou saída faltando cria DUAS comunicações — a de sempre, para quem está em dia, e
 * a do alerta, para quem precisa corrigir — e o que só o banco pode responder é se elas nascem com a
 * proveniência certa, com destinatários disjuntos e se morrem juntas quando o lote morre.
 *
 * <p>É aqui também que o domínio do SUBTIPO é exercido de verdade: a coluna tem CHECK, e gravar o
 * subtipo novo só passa se a constraint tiver sido recriada com ele.
 *
 * <p>O PDF é de verdade: o teste monta um cartão sintético com OpenPDF e deixa a publicação lê-lo
 * pelo caminho normal — extração de texto, parser e gravação da tabela da folha, que é de onde a
 * conferência dos dias sai.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoAvisoIncompletoIT {

    /** O username do master vem da configuração ({@code app.admin.master-username}) — só ele exclui publicação. */
    private static final String MASTER = "master.teste";

    private static final LocalDate SEMANA_INI = LocalDate.of(2026, 7, 13);
    private static final LocalDate SEMANA_FIM = LocalDate.of(2026, 7, 19);

    private static final String SUBTIPO_FOLHA = "FOLHA_SEMANAL";
    private static final String SUBTIPO_ALERTA = "FOLHA_REGISTRO_INCOMPLETO";

    /** O dia que ficou pela metade no cartão de Ana, dito a ela como o aviso o diz. */
    private static final String O_DIA_DE_ANA =
            "O dia 14/07 da sua folha está sem registro de entrada ou de saída.";

    /**
     * Cartão de quem esqueceu a saída: o segundo dia tem três batidas — entrou, saiu para o almoço,
     * voltou, e a última batida nunca aconteceu.
     */
    private static final List<String> CARTAO_COM_DIA_PELA_METADE = List.of(
            "Cartao Ponto - Senado Federal",
            "13/07/26 - seg 08:00 12:00 13:00 17:00 +08:00 +00:00",
            "14/07/26 - ter 08:00 12:00 13:00 +04:00 -04:00",
            "15/07/26 - qua FaltaFaltaFaltaFalta -08:00 -12:00");

    /** Cartão em dia: um dia completo, um turno único fechado e um feriado. */
    private static final List<String> CARTAO_EM_DIA = List.of(
            "Cartao Ponto - Senado Federal",
            "13/07/26 - seg 08:00 12:00 13:00 17:00 +08:00 +00:00",
            "14/07/26 - ter 08:00 12:00 +04:00 +04:00",
            "15/07/26 - feri");

    @Autowired private PontoService pontoService;
    @Autowired private PontoExclusaoService exclusaoService;
    @Autowired private AvisoService avisoService;

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
    /** Os PDFs escritos em disco: quem não excluiu o lote precisa recolhê-los na saída. */
    private final List<String> arquivos = new ArrayList<>();

    private Administrador admin;
    private Operador ana;
    private Operador bruno;
    private Operador carla;
    private PontoLote lote;
    private PontoLotePagina paginaAna;

    /**
     * Um lote de três folhas em que só a de Ana tem dia pela metade. São TRÊS pessoas para DOIS
     * cadastros de propósito: com uma pessoa em cada grupo, contagem de gente e contagem de
     * comunicação dariam o mesmo número e nenhum assert saberia distinguir uma da outra.
     */
    @BeforeEach
    void semearOLoteComAsTresFolhas() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            ana = novaPessoa("Ana do Registro");
            bruno = novaPessoa("Bruno do Registro");
            carla = novaPessoa("Carla do Registro");
        });
        lote = loteEmRevisao();
        paginaAna = folhaDe(1, ana, CARTAO_COM_DIA_PELA_METADE);
        folhaDe(2, bruno, CARTAO_EM_DIA);
        folhaDe(3, carla, CARTAO_EM_DIA);
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM PNT_EXCLUSAO_LOG WHERE EXCLUIDO_POR_ID IN :admins");
            executar("DELETE FROM FRM_AVISO_CIENCIA WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins)");
            executar("DELETE FROM FRM_AVISO_ALVO WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins)");
            executar("DELETE FROM FRM_AVISO_MENSAGEM WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins)");
            executar("DELETE FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID IN :admins");
            executar("DELETE FROM PNT_BANCO_SALDO WHERE PESSOA_ID IN :pessoas");
            // A FK da retificação com a página não tem cascade: ela sai antes do lote.
            executar("DELETE FROM PNT_RETIFICACAO WHERE PESSOA_ID IN :pessoas");
            // O cascade encadeado leva as páginas com o lote e as linhas da folha com as páginas.
            executar("DELETE FROM PNT_LOTE WHERE ID IN :lotes");
            executar("DELETE FROM PES_OPERADOR WHERE ID IN :pessoas");
            executar("DELETE FROM PES_ADMINISTRADOR WHERE ID IN :admins");
        });
        // O teste que exclui o lote já viu o service apagar os PDFs; os demais deixariam o arquivo
        // para trás, e o diretório de arquivos é compartilhado por toda a suíte.
        for (String relPath : arquivos) {
            try {
                Files.deleteIfExists(Paths.get(filesDir).resolve(relPath));
            } catch (Exception e) {
                throw new IllegalStateException("falha ao apagar o PDF de teste " + relPath, e);
            }
        }
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

    private PontoLote loteEmRevisao() {
        PontoLote novo = tx.execute(status ->
                CenarioFactory.novoLotePonto(em, "SEMANAL", SEMANA_INI, SEMANA_FIM, admin));
        loteIds.add(novo.getId());
        return novo;
    }

    /** Página vinculada à pessoa COM o PDF já em disco — é ele que a publicação vai ler e parsear. */
    private PontoLotePagina folhaDe(int numeroPagina, Operador pessoa, List<String> cartao) {
        PontoLotePagina pagina = tx.execute(status ->
                CenarioFactory.novaPaginaLote(em, lote, numeroPagina, pessoa.getId(), "OPERADOR"));
        gravarPdf(pagina.getArquivoPagina(), cartao);
        return pagina;
    }

    /** Ana completa a batida que faltava do dia 14/07 — a correção nasce antes da publicação. */
    private void completarODiaDeAna() {
        tx.executeWithoutResult(status -> {
            PontoRetificacao r = new PontoRetificacao();
            r.setPessoaId(ana.getId());
            r.setPessoaTipo("OPERADOR");
            r.setPaginaId(paginaAna.getId());
            r.setData(LocalDate.of(2026, 7, 14));
            r.setSai2("17:00");
            em.persist(r);
        });
    }

    /** Grava em disco o PDF de uma página, uma linha impressa por parágrafo — como o upload deixaria. */
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
            arquivos.add(relPath);
        } catch (Exception e) {
            throw new IllegalStateException("falha ao gravar o PDF da folha em " + relPath, e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Leitura do estado
    // ══════════════════════════════════════════════════════════════

    /** Os subtipos das comunicações que esta publicação criou, na ordem em que nasceram. */
    private List<String> subtiposDaPublicacao() {
        return textos("SELECT SUBTIPO FROM FRM_AVISO_CADASTRO WHERE ORIGEM_LOTE_ID = :id ORDER BY NUMERO");
    }

    /** Quem recebeu a comunicação daquele subtipo (nomes, para o assert dizer de quem fala). */
    private List<String> destinatariosDe(String subtipo) {
        return textos("SELECT o.NOME_COMPLETO FROM FRM_AVISO_ALVO a "
                + "JOIN FRM_AVISO_CADASTRO c ON c.ID = a.CADASTRO_ID "
                + "JOIN PES_OPERADOR o ON o.ID = a.OPERADOR_ID "
                + "WHERE c.ORIGEM_LOTE_ID = :id AND c.SUBTIPO = '" + subtipo + "' ORDER BY o.NOME_COMPLETO");
    }

    /** Os textos daquela comunicação, na ordem em que a pessoa os lê ({@code TEXTO} é CLOB). */
    private List<String> textosDe(String subtipo) {
        return textos("SELECT TO_CHAR(m.TEXTO) FROM FRM_AVISO_MENSAGEM m "
                + "JOIN FRM_AVISO_CADASTRO c ON c.ID = m.CADASTRO_ID "
                + "WHERE c.ORIGEM_LOTE_ID = :id AND c.SUBTIPO = '" + subtipo + "' ORDER BY m.ORDEM");
    }

    /** O complemento gravado no alvo de cada destinatário, na ordem do nome ("—" quando não há nenhum). */
    private List<String> complementosDe(String subtipo) {
        return textos("SELECT NVL(a.COMPLEMENTO, '—') FROM FRM_AVISO_ALVO a "
                + "JOIN FRM_AVISO_CADASTRO c ON c.ID = a.CADASTRO_ID "
                + "JOIN PES_OPERADOR o ON o.ID = a.OPERADOR_ID "
                + "WHERE c.ORIGEM_LOTE_ID = :id AND c.SUBTIPO = '" + subtipo + "' ORDER BY o.NOME_COMPLETO");
    }

    private String idDoCadastro(String subtipo) {
        return textos("SELECT ID FROM FRM_AVISO_CADASTRO "
                + "WHERE ORIGEM_LOTE_ID = :id AND SUBTIPO = '" + subtipo + "'").get(0);
    }

    /** O que a pessoa lê na janela daquela comunicação — mensagens comuns e o que for só dela. */
    @SuppressWarnings("unchecked")
    private List<String> janelaDe(Operador pessoa, String subtipo) {
        String cadastroId = idDoCadastro(subtipo);
        return tx.execute(status ->
                avisoService.buscarPendentes(pessoa.getId(), PapelPessoa.OPERADOR, List.of(TipoAviso.PESSOAL)).stream()
                        .filter(p -> cadastroId.equals(p.get("cadastro_id")))
                        .flatMap(p -> ((List<Map<String, Object>>) p.get("mensagens")).stream())
                        .map(m -> String.valueOf(m.get("texto")))
                        .toList());
    }

    /** Os destinatários como o administrador os vê no detalhe da comunicação. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> destinatariosNoDetalhe(String subtipo) {
        return tx.execute(status ->
                (List<Map<String, Object>>) avisoService.obterDetalhe(idDoCadastro(subtipo)).get("destinatarios"));
    }

    private List<String> textos(String sql) {
        return tx.execute(status -> em.createNativeQuery(sql)
                .setParameter("id", lote.getId())
                .getResultList().stream().map(String::valueOf).toList());
    }

    private int contar(String sql) {
        return tx.execute(status -> ((Number) em.createNativeQuery(sql)
                .setParameter("id", lote.getId())
                .getSingleResult()).intValue());
    }

    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a publicação cria DUAS comunicações do mesmo lote: a da folha e a do alerta, com destinatários disjuntos")
    void publicacaoSeparaQuemTemDiaIncompleto() {
        pontoService.publicar(lote.getId(), true, false, "master.teste");

        // O subtipo novo só chega ao banco se o domínio do CHECK tiver sido recriado com ele.
        assertEquals(List.of(SUBTIPO_FOLHA, SUBTIPO_ALERTA), subtiposDaPublicacao());

        assertEquals(List.of(bruno.getNomeCompleto(), carla.getNomeCompleto()), destinatariosDe(SUBTIPO_FOLHA),
                "quem está com a folha em dia recebe o aviso de sempre");
        assertEquals(List.of(ana.getNomeCompleto()), destinatariosDe(SUBTIPO_ALERTA),
                "quem tem dia com uma batida faltando é avisado à parte — e em nenhum outro lugar");

        // A mensagem dos dois cadastros é a mesma: o que o alerta acrescenta não está nela.
        assertEquals(textosDe(SUBTIPO_FOLHA), textosDe(SUBTIPO_ALERTA));
        assertEquals(1, textosDe(SUBTIPO_ALERTA).size(), "os dias não são mensagem do cadastro");
    }

    @Test
    @DisplayName("os dias que faltaram ficam no complemento do alvo de quem os tem, e em mais lugar nenhum")
    void diasFicamNoComplementoDoAlvo() {
        pontoService.publicar(lote.getId(), true, false, "master.teste");

        assertEquals(List.of(O_DIA_DE_ANA), complementosDe(SUBTIPO_ALERTA),
                "o dia é dito à pessoa que o tem, com a data que a folha dela traz");
        assertEquals(List.of("—", "—"), complementosDe(SUBTIPO_FOLHA),
                "quem está em dia não tem nada a corrigir e nada a mais para ler");
    }

    @Test
    @DisplayName("na janela, só o dono lê os dias dele: quem está em dia recebe a mensagem comum e nada mais")
    void aJanelaMostraOsDiasSoAoDono() {
        pontoService.publicar(lote.getId(), true, false, "master.teste");
        String avisoDaFolha = textosDe(SUBTIPO_FOLHA).get(0);

        // Uma janela só, com o aviso da folha e, no fim, o que é dela.
        assertEquals(List.of(avisoDaFolha, O_DIA_DE_ANA), janelaDe(ana, SUBTIPO_ALERTA));
        // Bruno recebe o outro cadastro — e nele não há complemento de ninguém para ele ler.
        assertEquals(List.of(avisoDaFolha), janelaDe(bruno, SUBTIPO_FOLHA));
        assertTrue(janelaDe(bruno, SUBTIPO_ALERTA).isEmpty(), "o alerta não é dirigido a quem está em dia");
    }

    @Test
    @DisplayName("o detalhe da comunicação mostra ao administrador o que cada destinatário leu")
    void detalheMostraOComplementoPorDestinatario() {
        pontoService.publicar(lote.getId(), true, false, "master.teste");

        List<Map<String, Object>> doAlerta = destinatariosNoDetalhe(SUBTIPO_ALERTA);
        assertEquals(1, doAlerta.size());
        assertEquals(ana.getNomeCompleto(), doAlerta.get(0).get("nome"));
        // Sem isto o administrador teria de abrir a folha para saber de que dia a pessoa foi avisada.
        assertEquals(O_DIA_DE_ANA, doAlerta.get(0).get("complemento"));

        for (Map<String, Object> destinatario : destinatariosNoDetalhe(SUBTIPO_FOLHA))
            assertFalse(destinatario.containsKey("complemento"),
                    "a comunicação sem personalização não ganha coluna nenhuma");
    }

    @Test
    @DisplayName("publicar em silêncio avisa SÓ quem tem dia incompleto — e com o alerta inteiro")
    void publicacaoSilenciosaAvisaSoOAlerta() {
        pontoService.publicar(lote.getId(), false, false, "master.teste");

        assertEquals(List.of(SUBTIPO_ALERTA), subtiposDaPublicacao(),
                "sem 'Emitir aviso' o lote não anuncia a folha, mas o pedido de correção continua saindo");
        assertEquals(List.of(ana.getNomeCompleto()), destinatariosDe(SUBTIPO_ALERTA));
        assertEquals(List.of(O_DIA_DE_ANA), complementosDe(SUBTIPO_ALERTA),
                "o pedido de correção continua nomeando os dias");
    }

    @Test
    @DisplayName("o dia já retificado não é cobrado: quem corrigiu antes da publicação recebe só o aviso da folha")
    void retificacaoFeitaAntesCalaOAlerta() {
        completarODiaDeAna();

        pontoService.publicar(lote.getId(), true, false, "master.teste");

        assertEquals(List.of(SUBTIPO_FOLHA), subtiposDaPublicacao(),
                "com o dia resolvido não nasce alerta nenhum — a folha nova não cobra quem já corrigiu");
        assertEquals(List.of(ana.getNomeCompleto(), bruno.getNomeCompleto(), carla.getNomeCompleto()),
                destinatariosDe(SUBTIPO_FOLHA), "quem corrigiu volta ao grupo de quem está em dia");
    }

    @Test
    @DisplayName("excluir o lote leva as DUAS comunicações, com mensagens e destinatários")
    void exclusaoDoLoteLevaAsDuas() {
        pontoService.publicar(lote.getId(), true, false, "master.teste");
        assertEquals(2, subtiposDaPublicacao().size(), "as duas nascem com a publicação");

        exclusaoService.excluirLote(lote.getId(), MASTER, admin.getId());

        assertTrue(subtiposDaPublicacao().isEmpty(),
                "a proveniência do lote é a única chave da exclusão, e ela alcança os dois cadastros");
        assertEquals(0, contar("SELECT COUNT(*) FROM FRM_AVISO_MENSAGEM m "
                + "JOIN FRM_AVISO_CADASTRO c ON c.ID = m.CADASTRO_ID WHERE c.ORIGEM_LOTE_ID = :id"));
        assertEquals(0, contar("SELECT COUNT(*) FROM FRM_AVISO_ALVO a "
                + "JOIN FRM_AVISO_CADASTRO c ON c.ID = a.CADASTRO_ID WHERE c.ORIGEM_LOTE_ID = :id"));
    }

    @Test
    @DisplayName("a exclusão conta as pessoas avisadas uma vez cada: os dois cadastros não somam a mesma pessoa duas vezes")
    void exclusaoContaCadaPessoaUmaVez() {
        pontoService.publicar(lote.getId(), true, false, "master.teste");

        Map<String, Object> resumo = exclusaoService.excluirLote(lote.getId(), MASTER, admin.getId());

        // São TRÊS pessoas em DUAS comunicações: o número que o administrador lê antes de excluir é o
        // de gente avisada, e ninguém aparece duas vezes porque ninguém recebe as duas.
        assertEquals(3, resumo.get("avisos_removidos"));
        assertEquals(2, resumo.get("avisos_cadastros_removidos"), "as duas comunicações da publicação");
        @SuppressWarnings("unchecked")
        List<String> nomes = (List<String>) resumo.get("avisos_destinatarios");
        assertEquals(List.of(ana.getNomeCompleto(), bruno.getNomeCompleto(), carla.getNomeCompleto()), nomes);
    }
}
