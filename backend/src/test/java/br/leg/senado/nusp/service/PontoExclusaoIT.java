package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT da exclusão de publicações do Ponto (F59) contra Oracle real — o coração do estágio.
 *
 * <p>Aqui se prova o que nenhum mock alcança: que o <b>cascade</b> do banco limpa os filhos do aviso,
 * que a <b>FK sem cascade</b> das retificações obriga a ordem certa (ORA-02292 é o preço de errá-la),
 * que a <b>re-âncora pós-flush</b> encontra a folha ANTERIOR (e não a que acabou de morrer), que o
 * <b>mês reabre sozinho</b> pela guarda do C6 e que a exclusão é <b>cirúrgica</b>: o que não é daquela
 * publicação — a retificação ancorada em outra folha, o aviso do desfecho de folga — sobrevive.
 *
 * <p><b>Por que {@code @SpringBootTest} e não o slice {@code @OracleIT}:</b> o {@code @DataJpaTest} não
 * instancia services e faz rollback de tudo; aqui as transações dos services precisam COMMITAR de
 * verdade (a exclusão só é honesta se o commit acontece — os arquivos, inclusive, são apagados
 * <i>afterCommit</i>). Sem rollback automático, a limpeza é manual.
 *
 * <p><b>O BANCO da folha é injetado depois de publicar</b> ({@link #darBancoAFolha}): a publicação real
 * extrai o BANCO do PDF, e neste ambiente não há PDF — o parser falha com WARN e grava NULL (por
 * desenho). Injetar o valor e re-ancorar é exatamente o que o backfill (E2) faria; sem isso a página
 * não seria elegível a âncora e metade dos cenários não existiria.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoExclusaoIT {

    /**
     * O username do master vem da CONFIGURAÇÃO ({@code app.admin.master-username} do
     * application-test.yml) — nunca de um nome hardcoded. O admin semeado é quem ASSINA a exclusão
     * (a FK EXCLUIDO_POR_ID); o username é o que o controller passaria do principal.
     */
    private static final String MASTER = "master.teste";
    private static final String ADMIN_COMUM = "outro.admin";

    private static final LocalDate MAIO_INI = LocalDate.of(2026, 5, 1);
    private static final LocalDate MAIO_FIM = LocalDate.of(2026, 5, 31);
    private static final LocalDate JUNHO_INI = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUNHO_FIM = LocalDate.of(2026, 6, 30);

    private static final int BANCO_DE_MAIO = 600;
    private static final int BANCO_DE_JUNHO = 900;
    /** Débito de 1 dia de folga com carga 40h (o que o CenarioFactory grava). */
    private static final int DEBITO_DA_FOLGA = 480;

    @Autowired private PontoExclusaoService service;
    @Autowired private PontoService pontoService;
    @Autowired private RetificacaoService retificacaoService;
    @Autowired private AvisoService avisoService;
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
    private Operador bruno;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            ana = novaPessoa("Ana da Folha");
            bruno = novaPessoa("Bruno da Folha");
        });
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
            executar("DELETE FROM PNT_RETIFICACAO WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_SOLICITACAO_FOLGA WHERE PESSOA_ID IN :pessoas");
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

    private Operador novaPessoa(String nome) {
        Operador op = CenarioFactory.novoOperador(em, nome);
        op.setCargaHoraria(40);   // o gate Q17 do banco de horas recusa carga nula
        em.merge(op);
        em.flush();
        pessoaIds.add(op.getId());
        return op;
    }

    /** Lote em REVISÃO — o estado de onde a publicação (e a exclusão) parte. */
    private PontoLote loteEmRevisao(String tipo, LocalDate inicio, LocalDate fim) {
        PontoLote lote = CenarioFactory.novoLotePonto(em, tipo, inicio, fim, admin);
        loteIds.add(lote.getId());
        return lote;
    }

    /**
     * Folha oficial JÁ publicada, com BANCO — a "folha anterior" dos cenários de re-âncora. Não passa
     * por {@code publicar}: não tem aviso e não é alvo de exclusão; é só o passado da pessoa.
     */
    private PontoLotePagina folhaAnteriorPublicada(Operador pessoa, LocalDate inicio, LocalDate fim, int banco) {
        PontoLote lote = CenarioFactory.novoLotePontoPublicado(em, "MENSAL", inicio, fim, admin);
        loteIds.add(lote.getId());
        PontoLotePagina pg = CenarioFactory.novaPaginaLote(em, lote, 1, pessoa.getId(), "OPERADOR", banco);
        saldoAberturaService.reancorar(pessoa.getId(), "OPERADOR");
        return pg;
    }

    /**
     * O que o parser gravaria se houvesse PDF: o BANCO impresso na folha. Depois de injetá-lo, a
     * página vira candidata a âncora e a pessoa é re-ancorada nela — que é o estado real de qualquer
     * pessoa com folha publicada.
     */
    private void darBancoAFolha(PontoLotePagina pagina, Operador pessoa, int banco) {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("UPDATE PNT_LOTE_PAGINA SET BANCO_FINAL_MIN = :banco WHERE ID = :id")
                    .setParameter("banco", banco)
                    .setParameter("id", pagina.getId())
                    .executeUpdate();
        });
        saldoAberturaService.reancorar(pessoa.getId(), "OPERADOR");
    }

    /** Retificação real do dono, pela folha indicada (mesmo caminho do usuário — C10/C11/C12). */
    private void retificar(PontoLotePagina pagina, Operador pessoa, LocalDate dia) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", dia.toString());
        item.put("ent1", "08:00");
        item.put("sai1", "12:00");
        retificacaoService.criarRetificacoes(pagina.getId(), pessoa.getId(), Map.of("dias", List.of(item)));
    }

    /** Cria os PDFs em disco (o upload real os cria; o CenarioFactory só grava os caminhos). */
    private void criarArquivos(PontoLote lote, PontoLotePagina... paginas) {
        try {
            escrever(lote.getArquivoOriginal());
            for (PontoLotePagina p : paginas) escrever(p.getArquivoPagina());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void escrever(String relPath) throws Exception {
        Path destino = Paths.get(filesDir).resolve(relPath);
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, "%PDF-1.4 folha de teste");
    }

    private boolean existeArquivo(String relPath) {
        return Files.exists(Paths.get(filesDir).resolve(relPath));
    }

    // ══════════════════════════════════════════════════════════════
    // Leitura do estado
    // ══════════════════════════════════════════════════════════════

    private int contar(String sql, Map<String, Object> params) {
        return tx.execute(status -> {
            var query = em.createNativeQuery(sql);
            params.forEach(query::setParameter);
            return ((Number) query.getSingleResult()).intValue();
        });
    }

    private int lotesComId(String loteId) {
        return contar("SELECT COUNT(*) FROM PNT_LOTE WHERE ID = :id", Map.of("id", loteId));
    }

    private int paginasDoLote(String loteId) {
        return contar("SELECT COUNT(*) FROM PNT_LOTE_PAGINA WHERE LOTE_ID = :id", Map.of("id", loteId));
    }

    private List<String> diasRetificadosDe(Operador pessoa) {
        return tx.execute(status -> em.createNativeQuery(
                        "SELECT TO_CHAR(DATA, 'YYYY-MM-DD') FROM PNT_RETIFICACAO "
                                + "WHERE PESSOA_ID = :p ORDER BY DATA")
                .setParameter("p", pessoa.getId())
                .getResultList().stream().map(String::valueOf).toList());
    }

    /** Avisos PESSOAIS vivos da pessoa (alvos) — o que ela ainda veria na tela. */
    private int avisosDe(Operador pessoa) {
        return contar("SELECT COUNT(*) FROM FRM_AVISO_ALVO a JOIN FRM_AVISO_CADASTRO c ON c.ID = a.CADASTRO_ID "
                + "WHERE c.TIPO = 'PESSOAL' AND a.OPERADOR_ID = :p", Map.of("p", pessoa.getId()));
    }

    /** Cadastros de aviso com aquela ORIGEM — o que a publicação do lote criou. */
    private int cadastrosDaOrigem(String loteId) {
        return contar("SELECT COUNT(*) FROM FRM_AVISO_CADASTRO WHERE ORIGEM_LOTE_ID = :id", Map.of("id", loteId));
    }

    private PontoBancoSaldo saldoDe(Operador pessoa) {
        return tx.execute(status -> saldoRepo.findByPessoaIdAndPessoaTipo(pessoa.getId(), "OPERADOR").orElse(null));
    }

    private List<Object[]> trilhaDoLote(String loteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> linhas = tx.execute(status -> em.createNativeQuery(
                        "SELECT ESCOPO, EXCLUIDO_POR_ID, PAGINA_ID, TO_CHAR(RESUMO) FROM PNT_EXCLUSAO_LOG "
                                + "WHERE LOTE_ID = :id")
                .setParameter("id", loteId)
                .getResultList());
        return linhas;
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("exclusão de LOTE publicado — profunda, e só do que é dele")
    class ExclusaoDeLote {

        private PontoLote alvo;
        private PontoLotePagina folhaDeAna;
        private PontoLotePagina folhaDeBruno;
        private PontoLotePagina folhaAnteriorDeAna;

        @BeforeEach
        void publicarOLoteAlvo() {
            tx.executeWithoutResult(status -> {
                // O passado de Ana: uma folha de maio, publicada e com BANCO — é para cá que a âncora
                // dela tem de voltar quando a folha de junho morrer.
                folhaAnteriorDeAna = folhaAnteriorPublicada(ana, MAIO_INI, MAIO_FIM, BANCO_DE_MAIO);

                alvo = loteEmRevisao("MENSAL", JUNHO_INI, JUNHO_FIM);
                folhaDeAna = CenarioFactory.novaPaginaLote(em, alvo, 1, ana.getId(), "OPERADOR");
                folhaDeBruno = CenarioFactory.novaPaginaLote(em, alvo, 2, bruno.getId(), "OPERADOR");
            });
            criarArquivos(alvo, folhaDeAna, folhaDeBruno);

            pontoService.publicar(alvo.getId(), true);          // publicação REAL: aviso com ORIGEM + re-âncora
            // As duas passam a ancorar na folha de junho — mas só Ana tem um passado (maio) para onde
            // voltar. É o par que exercita os dois ramos da re-âncora numa exclusão só.
            darBancoAFolha(folhaDeAna, ana, BANCO_DE_JUNHO);
            darBancoAFolha(folhaDeBruno, bruno, BANCO_DE_JUNHO);

            // Retificações: uma DENTRO da folha alvo (morre) e uma na folha ANTERIOR (sobrevive).
            retificar(folhaDeAna, ana, LocalDate.of(2026, 6, 10));
            retificar(folhaAnteriorDeAna, ana, LocalDate.of(2026, 5, 20));
            retificar(folhaDeBruno, bruno, LocalDate.of(2026, 6, 12));

            // Um aviso PESSOAL de OUTRA origem (o desfecho de folga do banco de horas): mesmo autor,
            // mesmo tipo, e NENHUMA relação com esta publicação.
            tx.executeWithoutResult(status -> avisoService.criarPessoalIndividual(
                    List.of(new AvisoService.DestinatarioAviso(ana.getId(), PapelPessoa.OPERADOR)),
                    "Sua folga de 20/06 foi aprovada.", admin.getId()));
        }

        @Test
        @DisplayName("corrige F59 — o lote publicado morre inteiro: páginas, retificações DELE, o aviso DELE, os PDFs e a âncora")
        void exclusaoProfundaDeLotePublicado() {
            assertEquals(2, avisosDe(ana), "antes: o aviso da publicação + o do desfecho de folga");

            Map<String, Object> resumo = service.excluirLote(alvo.getId(), MASTER, admin.getId());

            // ── o lote e as folhas ──
            assertEquals(0, lotesComId(alvo.getId()), "o lote tem de sumir");
            assertEquals(0, paginasDoLote(alvo.getId()), "e as páginas com ele (cascade)");

            // ── retificações: morre a da folha excluída; SOBREVIVE a ancorada em outra folha ──
            assertEquals(List.of("2026-05-20"), diasRetificadosDe(ana),
                    "a retificação ancorada na folha de MAIO (que continua publicada) não é desta exclusão — "
                            + "apagar por PESSOA em vez de por PÁGINA a levaria junto");
            assertTrue(diasRetificadosDe(bruno).isEmpty(), "a de Bruno era da folha excluída");

            // ── avisos: morre o da ORIGEM (com os filhos, por cascade); sobrevive o de outra origem ──
            assertEquals(0, cadastrosDaOrigem(alvo.getId()), "o cadastro criado pela publicação morreu");
            assertEquals(1, avisosDe(ana), "o aviso do desfecho de folga NÃO é desta publicação e fica");
            assertEquals(0, avisosDe(bruno), "o único aviso de Bruno era o da folha publicada");

            // ── re-âncora: Ana volta para maio; Bruno fica sem folha oficial ──
            PontoBancoSaldo saldoAna = saldoDe(ana);
            assertEquals(BANCO_DE_MAIO, saldoAna.getSaldoAberturaMin(),
                    "morta a folha de junho, a abertura volta a ser o BANCO da folha de maio");
            assertEquals(MAIO_FIM, saldoAna.getAncoraData());
            assertEquals(folhaAnteriorDeAna.getId(), saldoAna.getAncoraPaginaId());

            PontoBancoSaldo saldoBruno = saldoDe(bruno);
            assertEquals(0, saldoBruno.getSaldoAberturaMin(), "Bruno não tem outra folha: abertura 0");
            assertNull(saldoBruno.getAncoraData());
            assertNull(saldoBruno.getAncoraPaginaId());

            // ── arquivos: fora do banco, DEPOIS do commit ──
            assertFalse(existeArquivo(folhaDeAna.getArquivoPagina()), "o PDF da folha de Ana sai do disco");
            assertFalse(existeArquivo(folhaDeBruno.getArquivoPagina()));
            assertFalse(existeArquivo(alvo.getArquivoOriginal()), "e o PDF original do lote também");

            // ── trilha ──
            List<Object[]> trilha = trilhaDoLote(alvo.getId());
            assertEquals(1, trilha.size(), "uma linha de auditoria por exclusão");
            assertEquals("LOTE", trilha.get(0)[0]);
            assertEquals(admin.getId(), trilha.get(0)[1], "quem excluiu");
            assertNull(trilha.get(0)[2], "escopo LOTE não nomeia página");
            String snapshot = String.valueOf(trilha.get(0)[3]);
            assertTrue(snapshot.contains("Ana da Folha") && snapshot.contains("Bruno da Folha"),
                    () -> "o snapshot nomeia quem perdeu a folha: " + snapshot);
            assertTrue(snapshot.contains("\"retificacoes_excluidas\":2"), () -> snapshot);
            assertTrue(snapshot.contains("\"avisos_removidos\":2"), () -> snapshot);

            // O retorno da API é o mesmo snapshot (é o que o front recebe).
            assertEquals(2, resumo.get("retificacoes_excluidas"));
            assertEquals("06/2026", resumo.get("reabre_competencia"));
        }

        @Test
        @DisplayName("corrige F59 — o preview PROMETE o que a exclusão cumpre (mesmo levantamento, contado no banco)")
        void previewBateComAExclusao() {
            Map<String, Object> preview = service.previewLote(alvo.getId(), MASTER);

            assertEquals(2, preview.get("paginas_excluidas"));
            assertEquals(2, preview.get("retificacoes_excluidas"));
            assertEquals(2, preview.get("avisos_removidos"), "os 2 alvos do aviso da publicação (Ana e Bruno)");
            assertEquals(3, preview.get("arquivos"), "2 páginas + o PDF original");
            assertEquals("06/2026", preview.get("reabre_competencia"));

            // ⚠️ O CenarioFactory sufixa o nome ("Ana da Folha 7") para não colidir entre execuções —
            // a chave do mapa é o nome REAL da entidade, não o rótulo do seed.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pessoas = (List<Map<String, Object>>) preview.get("pessoas");
            Map<String, String> reancoraPorNome = new LinkedHashMap<>();
            pessoas.forEach(p -> reancoraPorNome.put((String) p.get("nome"), (String) p.get("reancora")));
            assertEquals("volta para a folha 01/05/2026 a 31/05/2026",
                    reancoraPorNome.get(ana.getNomeCompleto()));
            assertEquals("fica sem folha oficial — abertura 0",
                    reancoraPorNome.get(bruno.getNomeCompleto()));

            // E o que ele prometeu é o que acontece.
            Map<String, Object> resumo = service.excluirLote(alvo.getId(), MASTER, admin.getId());
            assertEquals(preview.get("retificacoes_excluidas"), resumo.get("retificacoes_excluidas"));
            assertEquals(preview.get("avisos_removidos"), resumo.get("avisos_removidos"));
        }

        @Test
        @DisplayName("corrige F59 — admin comum: 403 na cadeia real, e o lote continua lá com tudo (nada foi tocado)")
        void adminComumNaoExclui() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.excluirLote(alvo.getId(), ADMIN_COMUM, admin.getId()));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            assertEquals(1, lotesComId(alvo.getId()));
            assertEquals(2, paginasDoLote(alvo.getId()));
            // As 3 do cenário: Ana (10/06 pela folha alvo + 20/05 pela folha anterior) e Bruno (12/06).
            assertEquals(3, diasRetificadosDe(ana).size() + diasRetificadosDe(bruno).size());
            assertEquals(1, cadastrosDaOrigem(alvo.getId()));
            assertTrue(existeArquivo(alvo.getArquivoOriginal()), "nem os arquivos");
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("exclusão de PÁGINA — cirúrgica: só aquela pessoa")
    class ExclusaoDePagina {

        private PontoLote lote;
        private PontoLotePagina folhaDeAna;
        private PontoLotePagina folhaDeBruno;

        @BeforeEach
        void publicar() {
            tx.executeWithoutResult(status -> {
                lote = loteEmRevisao("MENSAL", JUNHO_INI, JUNHO_FIM);
                folhaDeAna = CenarioFactory.novaPaginaLote(em, lote, 1, ana.getId(), "OPERADOR");
                folhaDeBruno = CenarioFactory.novaPaginaLote(em, lote, 2, bruno.getId(), "OPERADOR");
            });
            criarArquivos(lote, folhaDeAna, folhaDeBruno);
            pontoService.publicar(lote.getId(), true);
            darBancoAFolha(folhaDeAna, ana, BANCO_DE_JUNHO);
            darBancoAFolha(folhaDeBruno, bruno, BANCO_DE_JUNHO);
            retificar(folhaDeAna, ana, LocalDate.of(2026, 6, 10));
            retificar(folhaDeBruno, bruno, LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("corrige F59 — some a folha de Ana e só o que é dela; Bruno, o cadastro do aviso e o lote ficam intactos")
        void exclusaoDeUmaFolha() {
            service.excluirPagina(lote.getId(), folhaDeAna.getId(), MASTER, admin.getId());

            // ── o lote e a folha do outro sobrevivem ──
            assertEquals(1, lotesComId(lote.getId()), "excluir uma folha NÃO apaga o lote");
            assertEquals(1, paginasDoLote(lote.getId()), "restou a folha de Bruno");

            // ── retificações e avisos: só os de Ana ──
            assertTrue(diasRetificadosDe(ana).isEmpty());
            assertEquals(List.of("2026-06-12"), diasRetificadosDe(bruno), "a retificação de Bruno é dele");
            assertEquals(0, avisosDe(ana), "o ALVO de Ana no aviso saiu");
            assertEquals(1, avisosDe(bruno), "o cadastro do aviso SOBREVIVE — Bruno ainda precisa vê-lo");
            assertEquals(1, cadastrosDaOrigem(lote.getId()));

            // ── saldo: Ana perde a âncora; Bruno mantém a dele ──
            PontoBancoSaldo saldoAna = saldoDe(ana);
            assertEquals(0, saldoAna.getSaldoAberturaMin());
            assertNull(saldoAna.getAncoraPaginaId());
            PontoBancoSaldo saldoBruno = saldoDe(bruno);
            assertEquals(BANCO_DE_JUNHO, saldoBruno.getSaldoAberturaMin(), "Bruno não foi tocado");
            assertEquals(folhaDeBruno.getId(), saldoBruno.getAncoraPaginaId());

            // ── arquivos: só o PDF dela; o original do lote FICA (o lote está vivo) ──
            assertFalse(existeArquivo(folhaDeAna.getArquivoPagina()));
            assertTrue(existeArquivo(folhaDeBruno.getArquivoPagina()));
            assertTrue(existeArquivo(lote.getArquivoOriginal()),
                    "o PDF original é do LOTE: não pode sair com uma página");

            // ── trilha: escopo PAGINA, nomeando a folha ──
            List<Object[]> trilha = trilhaDoLote(lote.getId());
            assertEquals(1, trilha.size());
            assertEquals("PAGINA", trilha.get(0)[0]);
            assertEquals(folhaDeAna.getId(), trilha.get(0)[2]);
        }

        @Test
        @DisplayName("corrige F59 — excluída a ÚLTIMA folha, o lote vazio sobrevive (e o cadastro do aviso, sem ninguém, morre)")
        void loteVazioSobrevive() {
            service.excluirPagina(lote.getId(), folhaDeAna.getId(), MASTER, admin.getId());
            service.excluirPagina(lote.getId(), folhaDeBruno.getId(), MASTER, admin.getId());

            assertEquals(1, lotesComId(lote.getId()), "o lote fica — quem o apaga é o X dele (§4.2)");
            assertEquals(0, paginasDoLote(lote.getId()));
            assertEquals(0, cadastrosDaOrigem(lote.getId()),
                    "sem nenhum alvo restante, o cadastro do aviso vira um aviso invisível: morre junto");
            assertEquals(2, trilhaDoLote(lote.getId()).size(), "duas exclusões, duas linhas de trilha");
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("a mensal excluída REABRE o mês (nenhum mecanismo novo — a guarda do C6 pergunta ao banco)")
    class MensalReabreOMes {

        @Test
        @DisplayName("corrige F59 — mensal publicada por engano: a 2ª é recusada; excluída a 1ª, a 2ª PUBLICA")
        void mensalExcluidaLiberaACompetencia() {
            PontoLote errada = tx.execute(status -> {
                PontoLote l = loteEmRevisao("MENSAL", JUNHO_INI, JUNHO_FIM);
                CenarioFactory.novaPaginaLote(em, l, 1, ana.getId(), "OPERADOR");
                return l;
            });
            PontoLote correta = tx.execute(status -> {
                PontoLote l = loteEmRevisao("MENSAL", JUNHO_INI, JUNHO_FIM);
                CenarioFactory.novaPaginaLote(em, l, 1, ana.getId(), "OPERADOR");
                return l;
            });

            pontoService.publicar(errada.getId(), true);

            // A guarda do C6 fecha o mês: a mensal CERTA não entra mais (era exatamente o beco do F59).
            ServiceValidationException recusa = assertThrows(ServiceValidationException.class,
                    () -> pontoService.publicar(correta.getId(), true));
            assertTrue(recusa.getMessage().contains("já existe folha mensal publicada"),
                    () -> "a recusa do C6: " + recusa.getMessage());

            service.excluirLote(errada.getId(), MASTER, admin.getId());

            // Sem uma linha de código de "reabertura": morta a mensal, a guarda não acha mais conflito.
            Map<String, Object> publicado = pontoService.publicar(correta.getId(), true);
            assertEquals("PUBLICADO", publicado.get("status"));
            assertEquals(1, avisosDe(ana), "a pessoa é avisada pela folha CERTA (a da errada morreu com ela)");
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("re-âncora do saldo (o flush antes dela é o gotcha do estágio)")
    class ReancoraDoSaldo {

        @Test
        @DisplayName("corrige F59 — excluída a folha MAIS RECENTE, a âncora volta para a imediatamente anterior")
        void ancoraVoltaParaAAnterior() {
            PontoLotePagina maio = tx.execute(status ->
                    folhaAnteriorPublicada(ana, MAIO_INI, MAIO_FIM, BANCO_DE_MAIO));
            PontoLote junho = tx.execute(status -> loteEmRevisao("MENSAL", JUNHO_INI, JUNHO_FIM));
            PontoLotePagina folhaDeJunho = tx.execute(status ->
                    CenarioFactory.novaPaginaLote(em, junho, 1, ana.getId(), "OPERADOR"));
            pontoService.publicar(junho.getId(), false);
            darBancoAFolha(folhaDeJunho, ana, BANCO_DE_JUNHO);
            assertEquals(BANCO_DE_JUNHO, saldoDe(ana).getSaldoAberturaMin(), "antes: ancorada em junho");

            service.excluirLote(junho.getId(), MASTER, admin.getId());

            PontoBancoSaldo saldo = saldoDe(ana);
            assertEquals(BANCO_DE_MAIO, saldo.getSaldoAberturaMin(),
                    "sem o flush antes da re-âncora, findCandidatasAncora ainda veria a página morta e "
                            + "o saldo voltaria a ancorar na folha que acabou de ser excluída");
            assertEquals(maio.getId(), saldo.getAncoraPaginaId());
            assertEquals(MAIO_FIM, saldo.getAncoraData());
        }

        @Test
        @DisplayName("corrige F59 — folha ÚNICA: abertura 0, âncora NULL, e o cache passa a descontar TODOS os débitos vivos")
        void folhaUnicaExcluidaZeraAAbertura() {
            PontoLote junho = tx.execute(status -> loteEmRevisao("MENSAL", JUNHO_INI, JUNHO_FIM));
            PontoLotePagina folha = tx.execute(status ->
                    CenarioFactory.novaPaginaLote(em, junho, 1, ana.getId(), "OPERADOR"));
            pontoService.publicar(junho.getId(), false);
            darBancoAFolha(folha, ana, BANCO_DE_JUNHO);

            // Uma folga APROVADA depois da âncora: hoje ela já desconta do cache.
            tx.executeWithoutResult(status -> CenarioFactory.novaSolicitacaoFolga(em, ana.getId(), "OPERADOR",
                    LocalDate.of(2026, 7, 10), StatusSolicitacaoFolga.APROVADO, admin, null));
            saldoAberturaService.reancorar(ana.getId(), "OPERADOR");
            assertEquals(BANCO_DE_JUNHO - DEBITO_DA_FOLGA, saldoDe(ana).getSaldoBancoMin());

            service.excluirLote(junho.getId(), MASTER, admin.getId());

            PontoBancoSaldo saldo = saldoDe(ana);
            assertEquals(0, saldo.getSaldoAberturaMin());
            assertNull(saldo.getAncoraData(), "sem folha oficial, não há âncora");
            assertNull(saldo.getAncoraPaginaId());
            assertEquals(-DEBITO_DA_FOLGA, saldo.getSaldoBancoMin(),
                    "sem âncora, TODO débito vivo desconta (o BANCO do PDF não existe mais para embutir nada)");
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("interação com o prazo do C12 — excluir a folha LIBERTA o dia")
    class InteracaoComOPrazo {

        /**
         * A retificação é única por (pessoa, dia) — UK. Enquanto ela existe, o dia está travado em
         * TODAS as folhas da pessoa que o cobrem (é o F32/C12). Excluir a folha que a ancorou mata a
         * retificação: o dia volta a ficar livre, e a folha que continua publicada (com a janela dela
         * aberta) volta a aceitá-lo.
         */
        @Test
        @DisplayName("corrige F59 — morta a retificação com a folha A, o dia some da listagem de B e volta a GRAVAR por ela")
        void diaVoltaAFicarLivre() {
            LocalDate dia = LocalDate.of(2026, 6, 5);
            PontoLote loteA = tx.execute(status -> loteEmRevisao("SEMANAL", JUNHO_INI, LocalDate.of(2026, 6, 12)));
            PontoLotePagina folhaA = tx.execute(status ->
                    CenarioFactory.novaPaginaLote(em, loteA, 1, ana.getId(), "OPERADOR"));
            PontoLote loteB = tx.execute(status -> loteEmRevisao("SEMANAL", JUNHO_INI, LocalDate.of(2026, 6, 19)));
            PontoLotePagina folhaB = tx.execute(status ->
                    CenarioFactory.novaPaginaLote(em, loteB, 1, ana.getId(), "OPERADOR"));
            pontoService.publicar(loteA.getId(), false);
            pontoService.publicar(loteB.getId(), false);

            retificar(folhaA, ana, dia);   // o dia entra pela folha A (semanais cumulativas cobrem os dois)

            assertEquals(List.of("2026-06-05"), diasListadosNaFolha(folhaB, ana),
                    "C12: o dia retificado por A aparece travado na folha B (a chave é pessoa+dia)");
            assertThrows(ServiceValidationException.class, () -> retificar(folhaB, ana, dia),
                    "e a UK recusa regravá-lo por B");

            service.excluirLote(loteA.getId(), MASTER, admin.getId());

            assertTrue(diasListadosNaFolha(folhaB, ana).isEmpty(),
                    "morta a folha que ancorava a retificação, o dia deixa de aparecer retificado");
            retificar(folhaB, ana, dia);   // e agora GRAVA — dentro da janela DE B
            assertEquals(List.of("2026-06-05"), diasListadosNaFolha(folhaB, ana));
        }

        @SuppressWarnings("unchecked")
        private List<String> diasListadosNaFolha(PontoLotePagina pagina, Operador pessoa) {
            Map<String, Object> out = tx.execute(status ->
                    retificacaoService.listarRetificacoes(pagina.getId(), pessoa.getId()));
            return ((List<Map<String, Object>>) out.get("retificacoes")).stream()
                    .map(m -> String.valueOf(m.get("data"))).toList();
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("lote em REVISÃO — exclusão rasa por natureza")
    class LoteEmRevisao {

        @Test
        @DisplayName("corrige F59 — o preview de um lote não publicado vem zerado, e a exclusão leva só os PDFs")
        void exclusaoDeLoteEmRevisao() {
            PontoLote lote = tx.execute(status -> loteEmRevisao("MENSAL", JUNHO_INI, JUNHO_FIM));
            PontoLotePagina folha = tx.execute(status ->
                    CenarioFactory.novaPaginaLote(em, lote, 1, ana.getId(), "OPERADOR"));
            criarArquivos(lote, folha);

            Map<String, Object> preview = service.previewLote(lote.getId(), MASTER);
            assertEquals(0, preview.get("retificacoes_excluidas"), "lote não publicado não tem retificação");
            assertEquals(0, preview.get("avisos_removidos"), "nem aviso: a publicação é que os cria");
            assertNull(preview.get("reabre_competencia"), "e não fechou mês nenhum");
            assertEquals(2, preview.get("arquivos"), "os PDFs existem desde o upload");

            service.excluirLote(lote.getId(), MASTER, admin.getId());

            assertEquals(0, lotesComId(lote.getId()));
            assertFalse(existeArquivo(folha.getArquivoPagina()));
            assertFalse(existeArquivo(lote.getArquivoOriginal()));
            // A pessoa nunca teve folha publicada: a re-âncora só confirma o que já era verdade.
            PontoBancoSaldo saldo = saldoDe(ana);
            assertNotNull(saldo, "a re-âncora cria a linha de saldo (upsert), zerada — inócuo");
            assertEquals(0, saldo.getSaldoAberturaMin());
            assertNull(saldo.getAncoraPaginaId());
            assertEquals(1, trilhaDoLote(lote.getId()).size(), "a trilha existe mesmo na exclusão rasa");
        }
    }
}
