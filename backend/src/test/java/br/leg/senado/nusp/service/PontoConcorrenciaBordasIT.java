package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT de CONCORRÊNCIA das BORDAS da publicação de lote, contra Oracle real: a alteração de vínculo
 * e a re-âncora do banco de horas têm de SERIALIZAR com a publicação (lock do lote e da pessoa).
 * A corrida é determinística: uma das threads é SEGURA no meio da sua transação (espiando
 * {@link SaldoAberturaService#reancorar}, o único colaborador-classe do caminho — spy de interface
 * não sabe chamar o método real) e é ela quem abre o {@link CountDownLatch} que dá a largada à
 * rival; cada caso asserta a INVARIANTE, não só o desfecho.
 *
 * <p>{@code @SpringBootTest} porque {@code @DataJpaTest} não instancia services e as threads não
 * enxergariam o seed; sem rollback automático, a limpeza é manual. O {@link Clock} é FIXO porque
 * {@code solicitar} só aceita dia útil FUTURO do mês corrente. Toda pessoa dos cenários já tem
 * linha em PNT_BANCO_SALDO: pessoa sem linha não bloqueia ninguém, e duas publicações simultâneas
 * da PRIMEIRA folha dela colidem na UK UQ_PNT_SALDO_PESSOA em vez de esperar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoConcorrenciaBordasIT {

    /** Quarta-feira: 16/07 é dia útil futuro do mesmo mês — o dia que a folga dos cenários pede. */
    private static final LocalDate HOJE = LocalDate.of(2026, 7, 15);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA_DA_FOLGA = LocalDate.of(2026, 7, 16);

    /** Tempo que a thread lenta segura a transação aberta: folga de sobra para a rival commitar. */
    private static final long JANELA_MS = 2500;
    /** A thread segurada no meio da transação (é ela quem abre a janela). */
    private static final String THREAD_LENTA = "corrida-lenta";
    private static final String THREAD_RIVAL = "corrida-rival";

    /** Âncora do banco: BANCO impresso na folha oficial já publicada (minutos). */
    private static final int BANCO_DA_FOLHA_OFICIAL = 1000;
    /** Débito de 1 dia de folga com carga 40h (Q3). */
    private static final int DEBITO_DA_FOLGA = 480;

    @Autowired
    private PontoService pontoService;

    @Autowired
    private BancoHorasService bancoHorasService;

    /** Espiado para SEGURAR a transação no meio (e para contar re-âncoras). */
    @MockitoSpyBean
    private SaldoAberturaService saldoAberturaService;

    /** O contexto completo liga @EnableScheduling; estes dois fazem I/O de rede a cada poucos segundos. */
    @MockitoBean
    private AgendaLegislativaService agendaLegislativaService;

    @MockitoBean
    private CessaoSheetService cessaoSheetService;

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(HOJE.atTime(9, 0).atZone(ZONA).toInstant(), ZONA);
    }

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;

    /** Tudo que o teste semeou — a limpeza manual (sem rollback) apaga por estes ids. */
    private final List<String> adminIds = new ArrayList<>();
    private final List<String> pessoaIds = new ArrayList<>();
    private final List<String> loteIds = new ArrayList<>();

    /** Aberto pela thread lenta quando ela entra na janela; é a largada da rival. */
    private final CountDownLatch janelaAberta = new CountDownLatch(1);

    @BeforeEach
    void preparar() {
        tx = new TransactionTemplate(txManager);
        // Sem timeout, uma transação órfã da corrida faria o DELETE da limpeza esperar o row lock PARA
        // SEMPRE: o build penduraria em vez de ficar vermelho, que é bem pior.
        tx.setTimeout(60);
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
    // Seed + leitura do estado final
    // ══════════════════════════════════════════════════════════════

    private Administrador novoAdmin() {
        Administrador admin = CenarioFactory.novoAdministrador(em);
        adminIds.add(admin.getId());
        return admin;
    }

    /**
     * Operador com CARGA_HORARIA 40 (o gate Q17 recusa carga nula) e já COM linha de saldo — como
     * qualquer pessoa que já teve uma folha publicada. É a linha que os locks de saldo travam.
     */
    private Operador novaPessoaComSaldo(String nome, int saldoMin) {
        Operador op = CenarioFactory.novoOperador(em, nome);
        op.setCargaHoraria(40);
        em.merge(op);
        em.flush();
        pessoaIds.add(op.getId());
        CenarioFactory.novoSaldoBanco(em, op.getId(), "OPERADOR", saldoMin);
        return op;
    }

    private PontoLote novoLoteEmRevisao(String tipo, LocalDate inicio, LocalDate fim, Administrador admin) {
        PontoLote lote = CenarioFactory.novoLotePonto(em, tipo, inicio, fim, admin);
        loteIds.add(lote.getId());
        return lote;
    }

    /** A folha oficial que ancora o banco da pessoa: lote publicado + página com BANCO extraído. */
    private void folhaOficialPublicada(Administrador admin, Operador pessoa, LocalDate fim, int banco) {
        PontoLote antigo = CenarioFactory.novoLotePontoPublicado(em, "MENSAL", fim.withDayOfMonth(1), fim, admin);
        loteIds.add(antigo.getId());
        CenarioFactory.novaPaginaLote(em, antigo, 1, pessoa.getId(), "OPERADOR", banco);
    }

    private Map<String, String> paginaNoBanco(String paginaId) {
        return tx.execute(status -> {
            PontoLotePagina p = em.find(PontoLotePagina.class, paginaId);
            em.refresh(p);   // a corrida commitou por fora deste EntityManager
            return Map.of("pessoa", String.valueOf(p.getPessoaId()),
                    "match", String.valueOf(p.getStatusMatch()));
        });
    }

    private int avisosPessoaisDe(String pessoaId) {
        return numero("SELECT COUNT(*) FROM FRM_AVISO_ALVO a JOIN FRM_AVISO_CADASTRO c ON c.ID = a.CADASTRO_ID "
                + "WHERE c.TIPO = 'PESSOAL' AND a.OPERADOR_ID = :pessoa", pessoaId);
    }

    private int saldoBancoDe(String pessoaId) {
        return numero("SELECT SALDO_BANCO_MIN FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :pessoa", pessoaId);
    }

    private int linhasDeSaldoDe(String pessoaId) {
        return numero("SELECT COUNT(*) FROM PNT_BANCO_SALDO WHERE PESSOA_ID = :pessoa", pessoaId);
    }

    private int numero(String sql, String pessoaId) {
        return tx.execute(status -> {
            var query = em.createNativeQuery(sql);
            query.setParameter("pessoa", pessoaId);
            return ((Number) query.getSingleResult()).intValue();
        });
    }

    private String statusDoLote(String loteId) {
        return tx.execute(status -> {
            PontoLote l = em.find(PontoLote.class, loteId);
            em.refresh(l);
            return l.getStatus();
        });
    }

    // ══════════════════════════════════════════════════════════════
    // A corrida: a thread lenta abre a janela; a rival larga dentro dela
    // ══════════════════════════════════════════════════════════════

    /**
     * Segura a thread lenta DENTRO da transação, com a janela do defeito escancarada. Só a primeira
     * passagem abre (a re-âncora é chamada mais de uma vez em alguns fluxos) e só na thread lenta.
     */
    private void abrirJanelaESegurar() throws InterruptedException {
        if (!THREAD_LENTA.equals(Thread.currentThread().getName()) || janelaAberta.getCount() == 0) return;
        janelaAberta.countDown();
        Thread.sleep(JANELA_MS);
    }

    private Callable<String> publicar(String loteId, boolean emitirAviso) {
        return () -> {
            pontoService.publicar(loteId, emitirAviso);
            return "PUBLICOU";
        };
    }

    private Callable<String> solicitarFolga(String pessoaId) {
        return () -> {
            bancoHorasService.solicitar(pessoaId, "operador", Map.of("dias", List.of(DIA_DA_FOLGA.toString())));
            return "SOLICITOU";
        };
    }

    private Callable<String> vincular(String loteId, String paginaId, String pessoaId) {
        return () -> {
            pontoService.atualizarVinculo(loteId, paginaId, pessoaId, "OPERADOR");
            return "VINCULOU";
        };
    }

    /** A rival só larga quando a lenta já está no meio da transação — é ali que mora o defeito. */
    private Callable<String> naJanela(Callable<String> acao) {
        return () -> {
            if (!janelaAberta.await(30, TimeUnit.SECONDS)) return "TIMEOUT: a janela nunca abriu";
            return desfecho(acao).call();
        };
    }

    /** Traduz o resultado da chamada no que o usuário veria: sucesso, recusa 400 ou erro cru. */
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

    /** Roda as duas em paralelo. O nome da thread é o que o espião usa para achar a lenta. */
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

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("o vínculo alterado com a publicação em voo")
    class VinculoDuranteAPublicacao {

        private Administrador admin;
        private Operador joao;
        private Operador maria;
        private PontoLote lote;
        private PontoLotePagina paginaDeJoao;
        private PontoLotePagina paginaPendente;

        @BeforeEach
        void semear() {
            tx.executeWithoutResult(status -> {
                admin = novoAdmin();
                joao = novaPessoaComSaldo("Joao da Folha", 0);
                maria = novaPessoaComSaldo("Maria da Folha", 0);
                lote = novoLoteEmRevisao("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin);
                // BANCO já extraído: é o que faz a publicação EMITIR UPDATE nesta página (o PDF não existe
                // em disco, então o valor volta a NULL) — sem UPDATE não haveria lost update a provar.
                paginaDeJoao = CenarioFactory.novaPaginaLote(em, lote, 1, joao.getId(), "OPERADOR", 120);
                paginaPendente = CenarioFactory.novaPaginaPendente(em, lote, 2);
            });
            // A publicação é segurada ANTES de re-ancorar: nesse ponto ela já tem o lote travado e o
            // snapshot das páginas em mãos, e ainda não commitou — a janela exata da corrida.
            doAnswer(inv -> {
                abrirJanelaESegurar();
                return inv.callRealMethod();
            }).when(saldoAberturaService).reancorar(anyString(), anyString());
        }

        @Test
        @DisplayName("vincular página PENDENTE durante a publicação: nenhum vínculo sobrevive sem passar por ela")
        void vinculoFantasmaNaoSobrevive() throws Exception {
            List<String> r = correr(
                    publicar(lote.getId(), true),
                    vincular(lote.getId(), paginaPendente.getId(), maria.getId()));

            assertEquals("PUBLICOU", r.get(0), () -> "resultados: " + r);

            Map<String, String> pagina = paginaNoBanco(paginaPendente.getId());
            // A INVARIANTE, asserida ANTES do desfecho porque é ela que descreve o dano: um vínculo
            // que sobrevive à publicação TEM de ter passado pelo pipeline dela (aviso pessoal + re-âncora).
            // Sem o lock, Maria ficava com folha publicada e NENHUM dos dois — a folha órfã.
            if (maria.getId().equals(pagina.get("pessoa"))) {
                assertEquals(1, avisosPessoaisDe(maria.getId()),
                        "vínculo sobrevivente à publicação, e Maria não foi avisada da folha dela");
                verify(saldoAberturaService).reancorar(maria.getId(), "OPERADOR");
            } else {
                assertEquals("null", pagina.get("pessoa"), "a página recusada continua sem dono");
                assertEquals("PENDENTE", pagina.get("match"));
                assertEquals(0, avisosPessoaisDe(maria.getId()), "Maria não tem folha neste lote: nada a avisar");
                verify(saldoAberturaService, never()).reancorar(maria.getId(), "OPERADOR");
            }
            assertEquals("RECUSOU: Lote já publicado não pode ser alterado.", r.get(1),
                    () -> "o vínculo espera o commit da publicação, relê o status e é recusado: " + r);
            // E o que a publicação de fato publicou continua íntegro.
            assertEquals("PUBLICADO", statusDoLote(lote.getId()));
            assertEquals(1, avisosPessoaisDe(joao.getId()));
        }

        @Test
        @DisplayName("re-vincular página VINCULADA durante a publicação: nenhum 200 com a mudança desfeita")
        void revinculoNaoEDesfeitoEmSilencio() throws Exception {
            List<String> r = correr(
                    publicar(lote.getId(), true),
                    vincular(lote.getId(), paginaDeJoao.getId(), maria.getId()));

            assertEquals("PUBLICOU", r.get(0), () -> "resultados: " + r);

            Map<String, String> pagina = paginaNoBanco(paginaDeJoao.getId());
            // A INVARIANTE: o que o admin VIU tem de bater com o banco. Antes do lock ele recebia 200, e o
            // UPDATE de todas as colunas da publicação (a página não tem @Version nem @DynamicUpdate, e o
            // snapshot dela é o antigo) devolvia a página a João — a mudança sumia sem um único erro.
            if ("VINCULOU".equals(r.get(1))) {
                assertEquals(maria.getId(), pagina.get("pessoa"),
                        "o admin recebeu 200: a página TEM de estar com Maria");
            } else {
                assertEquals("RECUSOU: Lote já publicado não pode ser alterado.", r.get(1),
                        () -> "além do 200 honesto, só há um desfecho: a recusa: " + r);
                assertEquals(joao.getId(), pagina.get("pessoa"),
                        "recusado o vínculo, a página segue de João — que foi quem a publicação avisou");
                assertEquals(1, avisosPessoaisDe(joao.getId()));
                assertEquals(0, avisosPessoaisDe(maria.getId()), "Maria não tem folha neste lote");
            }
            assertEquals("PUBLICADO", statusDoLote(lote.getId()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("a re-âncora do saldo com um evento da pessoa em voo")
    class ReancoraSobLockDaPessoa {

        private Administrador admin;
        private Operador ana;
        private Operador bruno;

        @BeforeEach
        void semear() {
            tx.executeWithoutResult(status -> {
                admin = novoAdmin();
                ana = novaPessoaComSaldo("Ana do Banco", BANCO_DA_FOLHA_OFICIAL);
                bruno = novaPessoaComSaldo("Bruno do Banco", 0);
            });
        }

        @Test
        @DisplayName("publicar × solicitar folga da mesma pessoa: o saldo gravado desconta o débito commitado")
        void publicacaoESolicitacaoNaoIntercalam() throws Exception {
            PontoLote novo = tx.execute(status -> {
                // Folha oficial de junho (âncora 30/06, BANCO 1000) + lote de julho a publicar. A página
                // nova não tem BANCO (sem PDF) → a âncora segue a de junho, e a folga de 16/07 desconta.
                folhaOficialPublicada(admin, ana, LocalDate.of(2026, 6, 30), BANCO_DA_FOLHA_OFICIAL);
                PontoLote lote = novoLoteEmRevisao("SEMANAL",
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5), admin);
                CenarioFactory.novaPaginaLote(em, lote, 1, ana.getId(), "OPERADOR");
                return lote;
            });
            // A LENTA aqui é a solicitação: ela é segurada logo depois de recalcular o saldo — com a
            // pessoa travada e o débito ainda NÃO commitado. É a janela em que a publicação somava os
            // débitos vivos (sem ver este) e gravava o saldo depois — inflado.
            doAnswer(inv -> {
                Object saldo = inv.callRealMethod();
                abrirJanelaESegurar();
                return saldo;
            }).when(saldoAberturaService).reancorar(anyString(), anyString());

            List<String> r = correr(
                    solicitarFolga(ana.getId()),
                    publicar(novo.getId(), false));

            assertEquals("SOLICITOU", r.get(0), () -> "a folga cabe no saldo (1000 min > 480): " + r);
            assertEquals("PUBLICOU", r.get(1), () -> "resultados: " + r);

            // Serializadas, as duas ordens dão o MESMO número — e é o único honesto: quem grava por último
            // enxergou o commit do outro. Sem o lock, a publicação gravava 1000 (o débito vivo sumia da
            // conta) e o card do operador mentia até o próximo evento dele.
            assertEquals(BANCO_DA_FOLHA_OFICIAL - DEBITO_DA_FOLGA, saldoBancoDe(ana.getId()),
                    "o SALDO_BANCO_MIN gravado tem de descontar a folga já commitada");
        }

        @Test
        @DisplayName("duas publicações com as MESMAS pessoas em ordens opostas: nenhuma morre de deadlock (ORA-00060)")
        void duasPublicacoesConcorrentesNaoDaoDeadlock() throws Exception {
            List<PontoLote> lotes = tx.execute(status -> {
                PontoLote l1 = novoLoteEmRevisao("SEMANAL",
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5), admin);
                PontoLote l2 = novoLoteEmRevisao("SEMANAL",
                        LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 12), admin);
                // ORDENS OPOSTAS nas páginas: é a inversão que, sem ordem canônica de lock, faz as duas
                // transações travarem as mesmas duas linhas de PNT_BANCO_SALDO em sentidos contrários.
                CenarioFactory.novaPaginaLote(em, l1, 1, ana.getId(), "OPERADOR");
                CenarioFactory.novaPaginaLote(em, l1, 2, bruno.getId(), "OPERADOR");
                CenarioFactory.novaPaginaLote(em, l2, 1, bruno.getId(), "OPERADOR");
                CenarioFactory.novaPaginaLote(em, l2, 2, ana.getId(), "OPERADOR");
                return List.of(l1, l2);
            });
            // Segura CADA re-âncora depois de ela travar a pessoa — nas DUAS threads: é a janela em que a
            // outra publicação trava a SUA primeira pessoa. Ordenando igual, ninguém segura o que o outro
            // quer; sem ordenar, cada uma espera a linha que a outra já tem → ORA-00060.
            doAnswer(inv -> {
                Object saldo = inv.callRealMethod();
                Thread.sleep(JANELA_MS / 2);
                return saldo;
            }).when(saldoAberturaService).reancorar(anyString(), anyString());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<String> r = new ArrayList<>();
            try {
                List<Future<String>> corrida = List.of(
                        pool.submit(desfecho(publicar(lotes.get(0).getId(), false))),
                        pool.submit(desfecho(publicar(lotes.get(1).getId(), false))));
                for (Future<String> f : corrida) r.add(f.get(90, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(10, TimeUnit.SECONDS);
            }

            assertEquals(List.of("PUBLICOU", "PUBLICOU"), r,
                    () -> "os lotes são DIFERENTES: as duas publicações têm de terminar — um ORA-00060 "
                            + "aqui é a assinatura do deadlock de re-âncora, que o admin recebe como 500: " + r);
            assertTrue(r.stream().noneMatch(x -> x.contains("ORA-00060")), () -> "deadlock: " + r);
            assertEquals("PUBLICADO", statusDoLote(lotes.get(0).getId()));
            assertEquals("PUBLICADO", statusDoLote(lotes.get(1).getId()));
            // E as duas pessoas seguem com UMA linha de saldo cada (a UNIQUE por pessoa).
            assertEquals(1, linhasDeSaldoDe(ana.getId()));
            assertEquals(1, linhasDeSaldoDe(bruno.getId()));
        }
    }
}
