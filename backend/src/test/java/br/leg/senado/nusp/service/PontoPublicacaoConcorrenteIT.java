package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
 * IT de CONCORRÊNCIA da publicação de lote (F49) contra Oracle real.
 *
 * <p>O caso é o da tela: com dois lotes em revisão, a resposta de um destrava o botão "Publicar" do
 * outro (slot único {@code publicandoId} — a trava de frontend fica para o estágio dela) e o admin
 * reclica, disparando um SEGUNDO POST do MESMO lote com o primeiro ainda em voo. Sem lock, as duas
 * transações liam o status REVISAO no mesmo instante, ambas passavam da guarda e o lote era publicado
 * duas vezes: aviso PESSOAL duplicado para cada pessoa da folha e re-âncora dupla.
 *
 * <p>A invariante provada aqui: <b>exatamente uma</b> das duas transações vence; a outra recebe o 400
 * honesto ("Lote já está publicado."), e o efeito colateral acontece UMA vez (o aviso da pessoa é
 * contado no banco; a re-âncora, no colaborador espionado — {@code PNT_BANCO_SALDO} tem UNIQUE por
 * pessoa, então contar linhas lá não distinguiria uma re-âncora de dez).
 *
 * <p>É o PRIMEIRO {@code @SpringBootTest} do repositório, e não por capricho: o {@code @OracleIT}
 * ({@code @DataJpaTest}) abre uma transação com rollback e não instancia beans de service — as
 * threads não enxergariam o seed e não haveria proxy transacional para {@code publicar} abrir uma
 * transação por thread. Sem rollback automático, a limpeza é manual ({@code @AfterEach}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoPublicacaoConcorrenteIT {

    @Autowired
    private PontoService pontoService;

    /** Espiado para contar as re-âncoras: a UNIQUE de PNT_BANCO_SALDO esconde a duplicação. */
    @MockitoSpyBean
    private SaldoAberturaService saldoAberturaService;

    /** O contexto completo liga @EnableScheduling; estes dois fazem I/O de rede a cada poucos segundos. */
    @MockitoBean
    private AgendaLegislativaService agendaLegislativaService;

    @MockitoBean
    private CessaoSheetService cessaoSheetService;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private String loteId;
    private String operadorId;
    private String adminId;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        // Sem timeout, uma transação órfã da corrida (thread interrompida no meio de um SELECT ... FOR
        // UPDATE) faria o DELETE da limpeza esperar o row lock PARA SEMPRE: o build penduraria em vez de
        // ficar vermelho, que é bem pior.
        tx.setTimeout(30);
        tx.executeWithoutResult(status -> {
            Administrador admin = CenarioFactory.novoAdministrador(em);
            Operador operador = CenarioFactory.novoOperador(em);
            PontoLote lote = CenarioFactory.novoLotePonto(em, "MENSAL",
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin);
            CenarioFactory.novaPaginaLote(em, lote, 1, operador.getId(), "OPERADOR");
            adminId = admin.getId();
            operadorId = operador.getId();
            loteId = lote.getId();
        });
    }

    /** Sem @Transactional na classe não há rollback: o que as threads commitaram fica no NUSP_TEST. */
    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM FRM_AVISO_CIENCIA WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID = :admin)");
            executar("DELETE FROM FRM_AVISO_ALVO WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID = :admin)");
            executar("DELETE FROM FRM_AVISO_MENSAGEM WHERE CADASTRO_ID IN "
                    + "(SELECT ID FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID = :admin)");
            executar("DELETE FROM FRM_AVISO_CADASTRO WHERE CRIADO_POR_ID = :admin");
            executar("DELETE FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :operador");
            executar("DELETE FROM PNT_LOTE WHERE ID = :lote");          // ON DELETE CASCADE leva as páginas
            executar("DELETE FROM PES_OPERADOR WHERE ID = :operador");
            executar("DELETE FROM PES_ADMINISTRADOR WHERE ID = :admin");
        });
    }

    private void executar(String sql) {
        var query = em.createNativeQuery(sql);
        if (sql.contains(":admin")) query.setParameter("admin", adminId);
        if (sql.contains(":operador")) query.setParameter("operador", operadorId);
        if (sql.contains(":lote")) query.setParameter("lote", loteId);
        query.executeUpdate();
    }

    @Test
    @DisplayName("corrige F49 — publicação concorrente do mesmo lote: exatamente uma vence, e o aviso/re-âncora acontecem UMA vez")
    void publicacaoConcorrenteDoMesmoLote() throws Exception {
        // Barreira: as duas threads entram em publicar() no mesmo instante — é a janela do F49.
        CyclicBarrier largada = new CyclicBarrier(2);
        Callable<String> publicar = () -> {
            largada.await(10, TimeUnit.SECONDS);
            try {
                pontoService.publicar(loteId, true);   // proxy @Transactional: uma transação por thread
                return "PUBLICOU";
            } catch (ServiceValidationException e) {
                return "RECUSOU: " + e.getMessage();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> resultados = new ArrayList<>();
        try {
            List<Future<String>> corrida = List.of(pool.submit(publicar), pool.submit(publicar));
            for (Future<String> f : corrida) resultados.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(1, resultados.stream().filter("PUBLICOU"::equals).count(),
                () -> "exatamente uma transação pode publicar o lote — resultados: " + resultados);
        assertTrue(resultados.stream().anyMatch(r -> r.equals("RECUSOU: Lote já está publicado.")),
                () -> "a perdedora tem de receber o 400 honesto de lote já publicado — resultados: " + resultados);

        tx.executeWithoutResult(status -> {
            PontoLote lote = em.find(PontoLote.class, loteId);
            assertEquals("PUBLICADO", lote.getStatus());
            assertNotNull(lote.getPublicadoEm());

            // O dano real do F49: um aviso PESSOAL por publicação → a pessoa receberia dois.
            assertEquals(1, contar("SELECT COUNT(*) FROM FRM_AVISO_ALVO a JOIN FRM_AVISO_CADASTRO c "
                    + "ON c.ID = a.CADASTRO_ID WHERE c.TIPO = 'PESSOAL' AND a.OPERADOR_ID = :operador"),
                    "a pessoa da folha só pode ter sido avisada UMA vez");
            assertEquals(1, contar("SELECT COUNT(*) FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :operador"),
                    "uma linha de saldo por pessoa (a UNIQUE já garantiria — a contagem forte é a do spy)");
        });

        verify(saldoAberturaService, times(1)).reancorar(operadorId, "OPERADOR");
    }

    private int contar(String sql) {
        var query = em.createNativeQuery(sql);
        query.setParameter("operador", operadorId);
        return ((Number) query.getSingleResult()).intValue();
    }
}
