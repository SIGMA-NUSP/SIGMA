package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT de CONCORRÊNCIA da exclusão de publicações contra Oracle real: excluir toma o MESMO lock de
 * linha do lote que publicar e vincular, então os três caminhos SERIALIZAM. Sem o lock, uma
 * exclusão simultânea a uma publicação ainda não commitada leria o lote em REVISÃO — apagaria as
 * páginas sem enxergar o aviso PESSOAL em criação (aviso vivo de folha inexistente, saldo ancorado
 * em página morta); na ordem inversa, a publicação ressuscitaria em silêncio um lote recém-excluído.
 *
 * <p>A corrida é determinística (mesmo idioma do {@code PontoConcorrenciaBordasIT}): a thread LENTA
 * é segurada DENTRO da sua transação — espiando {@link SaldoAberturaService#reancorar}, o
 * colaborador-classe que os dois caminhos atravessam (spy de interface não saberia chamar o método
 * real) — e é ela quem abre o latch que dá a largada à rival. As duas ordens são provadas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoExclusaoConcorrenciaIT {

    /** O username do master vem da configuração de teste (app.admin.master-username). */
    private static final String MASTER = "master.teste";

    private static final LocalDate INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate FIM = LocalDate.of(2026, 6, 30);

    /** Tempo que a thread lenta segura a transação aberta: folga de sobra para a rival chegar ao lock. */
    private static final long JANELA_MS = 2500;
    private static final String THREAD_LENTA = "corrida-lenta";
    private static final String THREAD_RIVAL = "corrida-rival";

    @Autowired private PontoService pontoService;
    @Autowired private PontoExclusaoService exclusaoService;

    /** Espiado para SEGURAR a transação da thread lenta no meio — a janela do defeito. */
    @MockitoSpyBean private SaldoAberturaService saldoAberturaService;

    /** O contexto completo liga @EnableScheduling; estes dois fazem I/O de rede a cada poucos segundos. */
    @MockitoBean private AgendaLegislativaService agendaLegislativaService;
    @MockitoBean private CessaoSheetService cessaoSheetService;

    @Autowired private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;

    private final List<String> adminIds = new ArrayList<>();
    private final List<String> pessoaIds = new ArrayList<>();
    private final List<String> loteIds = new ArrayList<>();

    /** Aberto pela thread lenta quando ela entra na janela; é a largada da rival. */
    private final CountDownLatch janelaAberta = new CountDownLatch(1);

    private Administrador admin;
    private Operador ana;
    private PontoLote lote;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        // Sem timeout, uma transação órfã da corrida faria o DELETE da limpeza esperar o row lock PARA
        // SEMPRE: o build penduraria em vez de ficar vermelho, que é bem pior.
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            ana = CenarioFactory.novoOperador(em, "Ana da Corrida");
            ana.setCargaHoraria(40);
            em.merge(ana);
            em.flush();
            pessoaIds.add(ana.getId());
            lote = CenarioFactory.novoLotePonto(em, "MENSAL", INICIO, FIM, admin);
            loteIds.add(lote.getId());
            CenarioFactory.novaPaginaLote(em, lote, 1, ana.getId(), "OPERADOR");
        });

        // Segura a LENTA depois que ela já travou o lote e antes de commitar — a janela exata.
        doAnswer(inv -> {
            Object resultado = inv.callRealMethod();
            abrirJanelaESegurar();
            return resultado;
        }).when(saldoAberturaService).reancorar(anyString(), anyString());
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
            executar("DELETE FROM PNT_BANCO_SALDO WHERE PESSOA_ID IN :pessoas");
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
    // A corrida
    // ══════════════════════════════════════════════════════════════

    private void abrirJanelaESegurar() throws InterruptedException {
        if (!THREAD_LENTA.equals(Thread.currentThread().getName()) || janelaAberta.getCount() == 0) return;
        janelaAberta.countDown();
        Thread.sleep(JANELA_MS);
    }

    private Callable<String> publicar() {
        return () -> {
            pontoService.publicar(lote.getId(), true);
            return "PUBLICOU";
        };
    }

    private Callable<String> excluir() {
        return () -> {
            exclusaoService.excluirLote(lote.getId(), MASTER, admin.getId());
            return "EXCLUIU";
        };
    }

    /** A rival só larga quando a lenta já está no meio da transação — é ali que mora o defeito. */
    private Callable<String> naJanela(Callable<String> acao) {
        return () -> {
            if (!janelaAberta.await(30, TimeUnit.SECONDS)) return "TIMEOUT: a janela nunca abriu";
            return desfecho(acao).call();
        };
    }

    private Callable<String> desfecho(Callable<String> acao) {
        return () -> {
            try {
                return acao.call();
            } catch (ServiceValidationException e) {
                return "RECUSOU: " + e.getMessage();
            } catch (Exception e) {
                return "ERRO: " + e.getClass().getSimpleName() + " — " + e.getMessage();
            }
        };
    }

    private List<String> correr(Callable<String> lenta, Callable<String> rival) throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Future<String> l = pool.submit(nomeada(desfecho(lenta), THREAD_LENTA));
            Future<String> r = pool.submit(nomeada(naJanela(rival), THREAD_RIVAL));
            return List.of(l.get(90, TimeUnit.SECONDS), r.get(90, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Callable<String> nomeada(Callable<String> tarefa, String nome) {
        return () -> {
            Thread.currentThread().setName(nome);
            return tarefa.call();
        };
    }

    // ── leitura do estado final ──

    private int numero(String sql, Map<String, Object> params) {
        return tx.execute(status -> {
            var query = em.createNativeQuery(sql);
            params.forEach(query::setParameter);
            return ((Number) query.getSingleResult()).intValue();
        });
    }

    private int lotesVivos() {
        return numero("SELECT COUNT(*) FROM PNT_LOTE WHERE ID = :id", Map.of("id", lote.getId()));
    }

    private int avisosDeAna() {
        return numero("SELECT COUNT(*) FROM FRM_AVISO_ALVO a JOIN FRM_AVISO_CADASTRO c ON c.ID = a.CADASTRO_ID "
                + "WHERE c.TIPO = 'PESSOAL' AND a.OPERADOR_ID = :p", Map.of("p", ana.getId()));
    }

    private int trilhas() {
        return numero("SELECT COUNT(*) FROM PNT_EXCLUSAO_LOG WHERE LOTE_ID = :id", Map.of("id", lote.getId()));
    }

    private String ancoraDeAna() {
        return tx.execute(status -> {
            List<?> r = em.createNativeQuery("SELECT ANCORA_PAGINA_ID FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :p")
                    .setParameter("p", ana.getId()).getResultList();
            return r.isEmpty() ? null : (String) r.get(0);
        });
    }

    // ══════════════════════════════════════════════════════════════

    /**
     * ORDEM A — a publicação segura o lote; a exclusão larga na janela e ESPERA o lock.
     *
     * <p>A invariante: quando a exclusão finalmente entra, ela vê o lote JÁ PUBLICADO e age sobre o que
     * a publicação criou — o aviso PESSOAL some junto. Sem o lock, ela leria o lote em revisão, não
     * acharia aviso nenhum (ele ainda não commitou) e o deixaria vivo, apontando para um lote morto.
     */
    @Test
    @DisplayName("excluir DURANTE a publicação: a exclusão espera o lock e age sobre o lote já publicado (nenhum aviso órfão)")
    void exclusaoEsperaAPublicacao() throws Exception {
        List<String> r = correr(publicar(), excluir());

        assertEquals("PUBLICOU", r.get(0), () -> "resultados: " + r);
        assertEquals("EXCLUIU", r.get(1), () -> "a exclusão espera o commit e prossegue — profunda: " + r);

        assertEquals(0, lotesVivos(), "o lote foi excluído");
        assertEquals(0, avisosDeAna(),
                "o aviso que a publicação criou tem de ter morrido com o lote — um aviso vivo aqui é a "
                        + "assinatura de uma exclusão que rodou sem enxergar o commit da publicação");
        assertNull(ancoraDeAna(), "e o saldo não pode ficar ancorado numa página que já não existe");
        assertEquals(1, trilhas(), "a trilha registra a exclusão do lote publicado");
    }

    /**
     * ORDEM B — a exclusão segura o lote; a publicação larga na janela e ESPERA.
     *
     * <p>Quando a publicação entra, o lote já não existe: o {@code lockPorId} não acha a linha e ela
     * responde 404 honesto. O que NÃO pode acontecer é ela publicar assim mesmo — gravando aviso e
     * re-âncora para um lote apagado (escrita órfã).
     */
    @Test
    @DisplayName("publicar DURANTE a exclusão: a publicação espera, relê e responde 404 — sem escrita órfã")
    void publicacaoEsperaAExclusao() throws Exception {
        List<String> r = correr(excluir(), publicar());

        assertEquals("EXCLUIU", r.get(0), () -> "resultados: " + r);
        assertEquals("RECUSOU: Lote não encontrado.", r.get(1),
                () -> "a publicação espera o lock, relê o lote (morto) e recusa: " + r);

        assertEquals(0, lotesVivos());
        assertEquals(0, avisosDeAna(), "publicação recusada não avisa ninguém");
        assertEquals(1, trilhas());
        assertTrue(r.stream().noneMatch(x -> x.startsWith("ERRO:")), () -> "nenhum erro cru: " + r);
    }
}
