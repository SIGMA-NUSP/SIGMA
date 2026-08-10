package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.enums.SubtipoAviso;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.repository.AvisoAlvoRepository;
import br.leg.senado.nusp.repository.AvisoCadastroRepository;
import br.leg.senado.nusp.repository.AvisoCienciaRepository;
import br.leg.senado.nusp.repository.AvisoMensagemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT do encerramento das comunicações cuja folha foi substituída. Publicar a folha corrigida por
 * cima da anterior deixa o aviso da publicação antiga apontando para um documento que o dono não
 * alcança mais — e é isso que o serviço desfaz, atingindo só quem foi substituído.
 *
 * <p>{@code @SpringBootTest} porque as publicações precisam COMMITAR (a limpeza lê o estado
 * consolidado das folhas, como a lista do dono faz); sem rollback automático, a limpeza dos dados
 * é manual.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoAvisoSubstituidoIT {

    private static final String MASTER = "master.teste";
    private static final LocalDate JULHO_INI = LocalDate.of(2026, 7, 1);
    private static final LocalDate JULHO_FIM = LocalDate.of(2026, 7, 31);
    private static final LocalDate AGOSTO_INI = LocalDate.of(2026, 8, 1);
    private static final LocalDate AGOSTO_FIM = LocalDate.of(2026, 8, 31);

    @Autowired private PontoService pontoService;
    @Autowired private PontoExclusaoService exclusaoService;
    @Autowired private AvisoService avisoService;
    @Autowired private AvisoCadastroRepository cadastroRepo;
    @Autowired private AvisoAlvoRepository alvoRepo;
    @Autowired private AvisoMensagemRepository mensagemRepo;
    @Autowired private AvisoCienciaRepository cienciaRepo;

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
            ana = CenarioFactory.novoOperador(em, "Ana da Substituicao");
            bruno = CenarioFactory.novoOperador(em, "Bruno da Substituicao");
            pessoaIds.add(ana.getId());
            pessoaIds.add(bruno.getId());
        });
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM FRM_AVISO_CADASTRO WHERE ORIGEM_LOTE_ID IN :lotes");
            executar("DELETE FROM PNT_EXCLUSAO_LOG WHERE EXCLUIDO_POR_ID IN :admins");
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

    /** Lote mensal prévio já publicado, com uma folha por pessoa e o aviso da publicação criado. */
    private PontoLote publicarMensal(LocalDate inicio, LocalDate fim, boolean emitirAviso, Operador... donos) {
        PontoLote lote = tx.execute(status -> {
            PontoLote novo = CenarioFactory.novoLotePonto(em, "MENSAL", PontoLote.CATEGORIA_PREVIA, inicio, fim, admin);
            loteIds.add(novo.getId());
            int pagina = 1;
            for (Operador dono : donos) {
                gravarPdf(CenarioFactory.novaPaginaLote(em, novo, pagina++, dono.getId(), "OPERADOR").getArquivoPagina());
            }
            return novo;
        });
        // Substituição é gesto confirmado: a 1ª chamada só conta as pessoas atingidas.
        pontoService.publicar(lote.getId(), emitirAviso, true, MASTER);
        return lote;
    }

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

    // ══════════════════════════════════════════════════════════════
    // Leitura do estado
    // ══════════════════════════════════════════════════════════════

    private List<AvisoCadastro> cadastrosDoLote(PontoLote lote) {
        return tx.execute(status -> cadastroRepo.findByOrigemLoteId(lote.getId()));
    }

    private AvisoCadastro cadastroUnico(PontoLote lote) {
        List<AvisoCadastro> cadastros = cadastrosDoLote(lote);
        assertEquals(1, cadastros.size(), "a publicação cria um cadastro por lote");
        return cadastros.get(0);
    }

    private List<String> pessoasDoCadastro(String cadastroId) {
        return tx.execute(status -> alvoRepo.findByCadastroId(cadastroId).stream()
                .map(AvisoAlvo::getOperadorId).toList());
    }

    private boolean vePendente(Operador dono, String cadastroId) {
        return tx.execute(status -> avisoService.buscarPendentes(dono.getId(), PapelPessoa.OPERADOR, "geral")
                .stream().anyMatch(m -> cadastroId.equals(m.get("cadastro_id"))));
    }

    // ══════════════════════════════════════════════════════════════
    // Testes
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("todos os destinatários substituídos: o cadastro antigo é desativado e não sobra remanescente")
    void substituicaoTotal() {
        PontoLote antigo = publicarMensal(JULHO_INI, JULHO_FIM, true, ana, bruno);
        AvisoCadastro cadastro = cadastroUnico(antigo);
        assertEquals(StatusAviso.ATIVO, cadastro.getStatus());

        publicarMensal(JULHO_INI, JULHO_FIM, true, ana, bruno);   // a correção do mesmo mês

        List<AvisoCadastro> depois = cadastrosDoLote(antigo);
        assertEquals(1, depois.size(), "nada de remanescente: ninguém sobrou");
        assertEquals(StatusAviso.DESATIVADO, depois.get(0).getStatus());
        assertNotNull(depois.get(0).getDesativadoEm());
        assertFalse(vePendente(ana, cadastro.getId()));
        assertFalse(vePendente(bruno, cadastro.getId()));
    }

    @Test
    @DisplayName("parte substituída: o cadastro antigo sai e um remanescente nasce com quem não foi atingido")
    void substituicaoParcial() {
        PontoLote antigo = publicarMensal(JULHO_INI, JULHO_FIM, true, ana, bruno);
        AvisoCadastro cadastro = cadastroUnico(antigo);

        publicarMensal(JULHO_INI, JULHO_FIM, true, ana);   // só a folha da Ana foi corrigida

        List<AvisoCadastro> depois = cadastrosDoLote(antigo);
        assertEquals(2, depois.size(), "o antigo desativado + o remanescente");
        AvisoCadastro desativado = depois.stream().filter(c -> c.getId().equals(cadastro.getId())).findFirst().orElseThrow();
        AvisoCadastro remanescente = depois.stream().filter(c -> !c.getId().equals(cadastro.getId())).findFirst().orElseThrow();

        assertEquals(StatusAviso.DESATIVADO, desativado.getStatus());
        assertEquals(StatusAviso.ATIVO, remanescente.getStatus());
        assertEquals(List.of(bruno.getId()), pessoasDoCadastro(remanescente.getId()));
        assertEquals(cadastro.getSubtipo(), remanescente.getSubtipo());
        assertEquals(antigo.getId(), remanescente.getOrigemLoteId());
        assertEquals(1, (int) tx.execute(status -> mensagemRepo.findByCadastroIdOrderByOrdem(remanescente.getId()).size()));

        // Bruno continua devendo ciência — agora pelo cadastro novo; Ana não vê mais nada daquele lote.
        assertTrue(vePendente(bruno, remanescente.getId()));
        assertFalse(vePendente(ana, remanescente.getId()));
        assertFalse(vePendente(bruno, cadastro.getId()));
    }

    @Test
    @DisplayName("parcial com ciência já dada: o remanescente herda a ciência e o complemento de cada um")
    void remanescenteHerdaCienciaEComplemento() {
        PontoLote antigo = publicarMensal(JULHO_INI, JULHO_FIM, false, ana, bruno);
        // Cadastro com complemento por destinatário — o mesmo shape do alerta de registro incompleto.
        tx.executeWithoutResult(status -> avisoService.criarPessoalIndividual(List.of(
                        new AvisoService.DestinatarioAviso(ana.getId(), PapelPessoa.OPERADOR, "Dias: 01/07."),
                        new AvisoService.DestinatarioAviso(bruno.getId(), PapelPessoa.OPERADOR, "Dias: 02/07.")),
                "Folha publicada.", admin.getId(), SubtipoAviso.FOLHA_REGISTRO_INCOMPLETO, antigo.getId()));
        AvisoCadastro comComplemento = cadastrosDoLote(antigo).get(0);
        tx.executeWithoutResult(status ->
                avisoService.registrarCiencia(comComplemento.getId(), null, bruno.getId(), PapelPessoa.OPERADOR));

        publicarMensal(JULHO_INI, JULHO_FIM, false, ana);   // só a Ana é substituída

        AvisoCadastro remanescente = cadastrosDoLote(antigo).stream()
                .filter(c -> !c.getId().equals(comComplemento.getId())).findFirst().orElseThrow();
        AvisoAlvo alvo = tx.execute(status -> alvoRepo.findByCadastroId(remanescente.getId()).get(0));
        assertEquals(bruno.getId(), alvo.getOperadorId());
        assertEquals("Dias: 02/07.", alvo.getComplemento(), "o complemento acompanha a pessoa");

        assertEquals(1, (int) tx.execute(status -> cienciaRepo.findByCadastroIdOrderByCienteEm(remanescente.getId()).size()));
        // Bruno já tinha dado ciência: o remanescente nasce cumprido e não volta a incomodá-lo.
        assertEquals(StatusAviso.DESATIVADO, remanescente.getStatus());
        assertFalse(vePendente(bruno, remanescente.getId()));
    }

    @Test
    @DisplayName("cadastro de folha não substituída fica intocado — inclusive o de outro mês da mesma pessoa")
    void cadastroDeOutraFolhaIntocado() {
        PontoLote agosto = publicarMensal(AGOSTO_INI, AGOSTO_FIM, true, ana);
        PontoLote julho = publicarMensal(JULHO_INI, JULHO_FIM, true, ana);

        publicarMensal(JULHO_INI, JULHO_FIM, true, ana);   // substitui só a folha de julho

        assertEquals(StatusAviso.ATIVO, cadastroUnico(agosto).getStatus());
        assertEquals(StatusAviso.DESATIVADO, cadastroUnico(julho).getStatus());
    }

    @Test
    @DisplayName("sem emitir aviso, a limpeza acontece do mesmo jeito")
    void limpezaSemEmitirAviso() {
        PontoLote antigo = publicarMensal(JULHO_INI, JULHO_FIM, true, ana);
        AvisoCadastro cadastro = cadastroUnico(antigo);

        PontoLote novo = publicarMensal(JULHO_INI, JULHO_FIM, false, ana);

        assertEquals(StatusAviso.DESATIVADO, cadastrosDoLote(antigo).get(0).getStatus());
        assertTrue(cadastrosDoLote(novo).isEmpty(), "o lote novo não anunciou nada");
        assertFalse(vePendente(ana, cadastro.getId()));
    }

    @Test
    @DisplayName("excluir o lote antigo leva junto o remanescente — o vínculo de origem é o mesmo")
    void exclusaoDoLoteAntigoLevaORemanescente() {
        PontoLote antigo = publicarMensal(JULHO_INI, JULHO_FIM, true, ana, bruno);
        publicarMensal(JULHO_INI, JULHO_FIM, true, ana);
        assertEquals(2, cadastrosDoLote(antigo).size());

        exclusaoService.excluirLote(antigo.getId(), MASTER, admin.getId());

        assertTrue(cadastrosDoLote(antigo).isEmpty());
    }
}
