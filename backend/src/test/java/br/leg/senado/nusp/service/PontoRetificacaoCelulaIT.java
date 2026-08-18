package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoFolhaLinha;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IT da retificação por célula contra Oracle real: o que cada gravação deixa no banco, o que a
 * janela de publicação recusa e como duas folhas da mesma pessoa se comportam sobre o mesmo dia.
 *
 * <p>É aqui que o modelo novo se prova contra o banco de verdade — um horário SOZINHO grava (o
 * antigo CHECK de pares o recusaria), a ocorrência declarada zera os horários pelo CHECK de
 * conteúdo, e apagar é apagar a linha.
 *
 * <p>Harness de {@code @SpringBootTest} (não o slice {@code @OracleIT}) para que o
 * {@code @Transactional} do service commite de verdade; sem rollback automático, a limpeza é
 * manual. O cenário: uma pessoa com DUAS folhas semanais cumulativas — A (01–12/jun, publicada
 * antes) e B (01–19/jun, publicada depois). Todos os dias das duas seguem abertos: acumular folhas
 * não fecha dia nenhum — só a mensal definitiva fecha.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PontoRetificacaoCelulaIT {

    /** Folha A — SEMANAL 01–12/jun, publicada primeiro. */
    private static final LocalDate A_INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate A_FIM = LocalDate.of(2026, 6, 12);
    /** Folha B — SEMANAL 01–19/jun: cobre A inteira e vai além (cumulativa). */
    private static final LocalDate B_INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate B_FIM = LocalDate.of(2026, 6, 19);
    /** Competência inteira, para a folha mensal dos casos de prévia/definitiva. */
    private static final LocalDate MES_INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate MES_FIM = LocalDate.of(2026, 6, 30);
    /** Fim de uma folha mensal que imprime só o começo do mês — a que cobre parte da competência. */
    private static final LocalDate MES_FIM_PARCIAL = LocalDate.of(2026, 6, 10);

    /** Segunda-feira que só B trouxe: dia aberto. */
    private static final LocalDate DIA_15 = LocalDate.of(2026, 6, 15);
    /** Terça-feira idem. */
    private static final LocalDate DIA_16 = LocalDate.of(2026, 6, 16);
    /** Sexta-feira dentro de A e de B: aberta — vir em duas folhas não fecha o dia. */
    private static final LocalDate DIA_05 = LocalDate.of(2026, 6, 5);
    /** Sábado que só B trouxe: dentro da janela, mas fora do dia útil. */
    private static final LocalDate SABADO_13 = LocalDate.of(2026, 6, 13);

    @Autowired
    private RetificacaoService service;

    /**
     * Espião do repositório — só para reproduzir a CORRIDA da chave do dia (a busca que não vê a
     * linha que já existe). Fora desse caso, todos os métodos são os reais.
     */
    @MockitoSpyBean
    private PontoRetificacaoRepository retificacaoRepo;

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

    /** Tudo que o teste semeou — a limpeza manual (sem rollback) apaga por estes ids. */
    private final List<String> adminIds = new ArrayList<>();
    private final List<String> pessoaIds = new ArrayList<>();
    private final List<String> loteIds = new ArrayList<>();
    private final List<String> tipoIds = new ArrayList<>();

    private Administrador admin;
    private Operador operador;
    private PontoLotePagina paginaA;
    private PontoLotePagina paginaB;
    private PontoTipoMarcacao tipoBanco;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            operador = CenarioFactory.novoOperador(em);
            pessoaIds.add(operador.getId());

            PontoLote loteA = CenarioFactory.novoLotePontoPublicado(em, "SEMANAL", A_INICIO, A_FIM, admin);
            loteIds.add(loteA.getId());
            paginaA = CenarioFactory.novaPaginaLote(em, loteA, 1, operador.getId(), "OPERADOR");

            PontoLote loteB = CenarioFactory.novoLotePontoPublicado(em, "SEMANAL", B_INICIO, B_FIM, admin);
            loteIds.add(loteB.getId());
            paginaB = CenarioFactory.novaPaginaLote(em, loteB, 1, operador.getId(), "OPERADOR");

            // A publicação de A recua uma hora: a ordem entre as folhas decide qual delas
            // REPRESENTA o dia (de onde saem os horários impressos) e qual reenvio substitui qual.
            CenarioFactory.fixarTimestamp(em, "PNT_LOTE", "PUBLICADO_EM", loteA.getId(), 3600);

            // O catálogo do schema de teste nasce vazio — o tipo que o funcionário declara é semeado aqui.
            tipoBanco = CenarioFactory.novoTipoMarcacao(em, "Banco", "Bnc", PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
            tipoBanco.setVisivelFuncionario(true);
            tipoBanco.setContaFolga(true);
            em.merge(tipoBanco);
            em.flush();
            tipoIds.add(tipoBanco.getId());
        });
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM PNT_FOLHA_LINHA WHERE PAGINA_ID IN "
                    + "(SELECT ID FROM PNT_LOTE_PAGINA WHERE LOTE_ID IN :lotes)");
            executar("DELETE FROM PNT_RETIFICACAO WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_LOTE WHERE ID IN :lotes");          // ON DELETE CASCADE leva as páginas
            executar("DELETE FROM PNT_DIA_MARCACAO WHERE TIPO_ID IN :tipos");   // a FK do tipo não tem cascade
            executar("DELETE FROM PNT_PESSOA_MARCACAO WHERE TIPO_ID IN :tipos");
            executar("DELETE FROM PNT_TIPO_MARCACAO WHERE ID IN :tipos");
            executar("DELETE FROM PES_OPERADOR WHERE ID IN :pessoas");
            executar("DELETE FROM PES_ADMINISTRADOR WHERE ID IN :admins");
        });
    }

    private void executar(String sql) {
        var query = em.createNativeQuery(sql);
        if (sql.contains(":admins")) query.setParameter("admins", adminIds);
        if (sql.contains(":pessoas")) query.setParameter("pessoas", pessoaIds);
        if (sql.contains(":lotes")) query.setParameter("lotes", loteIds);
        if (sql.contains(":tipos")) query.setParameter("tipos", tipoIds);
        query.executeUpdate();
    }

    // ══ Chamadas ao service ═══════════════════════════════════════

    private Map<String, Object> celula(PontoLotePagina pagina, LocalDate data, String campo, String valor) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("data", data.toString());
        corpo.put("campo", campo);
        corpo.put("valor", valor);
        return service.salvarCelula(pagina.getId(), operador.getId(), corpo);
    }

    private Map<String, Object> declarar(PontoLotePagina pagina, LocalDate data, String tipoId) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("data", data.toString());
        corpo.put("tipo_id", tipoId);
        return service.salvarTipo(pagina.getId(), operador.getId(), corpo);
    }

    private Map<String, Object> listar(PontoLotePagina pagina) {
        return tx.execute(status -> service.listarRetificacoes(pagina.getId(), operador.getId()));
    }

    // ══ Leitura do que o BANCO guardou (transação nova) ═══════════

    /** ENT1, SAI1, ENT2, SAI2, TIPO_ID e PAGINA_ID do dia, como o banco os guardou. */
    private Object[] noBanco(LocalDate data) {
        return tx.execute(status -> (Object[]) em.createNativeQuery(
                        "SELECT ENT1, SAI1, ENT2, SAI2, TIPO_ID, PAGINA_ID FROM PNT_RETIFICACAO "
                                + "WHERE PESSOA_ID = :pessoa AND DATA = :data")
                .setParameter("pessoa", operador.getId())
                .setParameter("data", data)
                .getSingleResult());
    }

    private long linhasNoBanco() {
        return tx.execute(status -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM PNT_RETIFICACAO WHERE PESSOA_ID = :pessoa")
                .setParameter("pessoa", operador.getId())
                .getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    private List<String> diasListados(PontoLotePagina pagina) {
        List<Map<String, Object>> rs = (List<Map<String, Object>>) listar(pagina).get("retificacoes");
        return rs.stream().map(m -> String.valueOf(m.get("data"))).toList();
    }

    @SuppressWarnings("unchecked")
    private boolean diaAberto(PontoLotePagina pagina, LocalDate data) {
        List<Map<String, Object>> dias = (List<Map<String, Object>>) listar(pagina).get("dias");
        return dias.stream()
                .filter(d -> data.toString().equals(d.get("data")))
                .map(d -> Boolean.TRUE.equals(d.get("aberto")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("o dia " + data + " não veio na folha"));
    }

    /** Publica uma folha MENSAL da pessoa cobrindo a competência inteira; devolve a página dela. */
    private PontoLotePagina publicarMensal(String categoria) {
        return publicarMensal(categoria, MES_INICIO, MES_FIM);
    }

    /** Folha MENSAL de um período escolhido — a que imprime só parte da competência. */
    private PontoLotePagina publicarMensal(String categoria, LocalDate inicio, LocalDate fim) {
        return tx.execute(status -> {
            PontoLote lote = CenarioFactory.novoLotePontoPublicado(em, "MENSAL", categoria,
                    inicio, fim, admin);
            loteIds.add(lote.getId());
            return CenarioFactory.novaPaginaLote(em, lote, 1, operador.getId(), "OPERADOR");
        });
    }

    /** Publica mais uma folha SEMANAL da pessoa; devolve a página dela. */
    private PontoLotePagina publicarSemanal(LocalDate inicio, LocalDate fim) {
        return tx.execute(status -> {
            PontoLote lote = CenarioFactory.novoLotePontoPublicado(em, "SEMANAL", inicio, fim, admin);
            loteIds.add(lote.getId());
            return CenarioFactory.novaPaginaLote(em, lote, 1, operador.getId(), "OPERADOR");
        });
    }

    /** Recua a publicação da folha, para que a próxima publicada seja inequivocamente posterior. */
    private void recuarPublicacao(PontoLotePagina pagina, int segundos) {
        tx.executeWithoutResult(status ->
                CenarioFactory.fixarTimestamp(em, "PNT_LOTE", "PUBLICADO_EM", pagina.getLoteId(), segundos));
    }

    /** Marca o dia com uma ocorrência que vale para todos; devolve o nome que a mensagem exibe. */
    private String marcarDiaParaTodos(LocalDate data) {
        return tx.execute(status -> {
            PontoTipoMarcacao tipo = CenarioFactory.novoTipoMarcacao(em, "Feriado", "Fer",
                    PontoTipoMarcacao.ESCOPO_GLOBAL);
            tipoIds.add(tipo.getId());
            PontoDiaMarcacao marcacao = new PontoDiaMarcacao();
            marcacao.setData(data);
            marcacao.setTipoId(tipo.getId());
            marcacao.setCriadoPorId(admin.getId());
            em.persist(marcacao);
            em.flush();
            return tipo.getNome();
        });
    }

    /** Grava a linha-dia da folha como o PDF a imprimiu. */
    private void imprimirNaFolha(PontoLotePagina pagina, LocalDate data, String ent1, String sai1) {
        tx.executeWithoutResult(status -> {
            PontoFolhaLinha linha = new PontoFolhaLinha();
            linha.setPaginaId(pagina.getId());
            linha.setOrdem(1);
            linha.setDiaVerbatim("15/06/26 - seg");
            linha.setData(data);
            linha.setEnt1(ent1);
            linha.setSai1(sai1);
            em.persist(linha);
            em.flush();
        });
    }

    // ══════════════════════════════════════════════════════════════

    @Nested
    class Gravacao {

        @Test
        void umHorarioSozinhoGrava() {
            celula(paginaB, DIA_15, "sai1", "12:30");

            assertArrayEquals(new Object[] {null, "12:30", null, null, null, paginaB.getId()},
                    noBanco(DIA_15));
        }

        @Test
        void aSegundaCelulaDoDiaPreservaAPrimeira() {
            celula(paginaB, DIA_15, "ent1", "08:00");
            celula(paginaB, DIA_15, "sai1", "12:30");

            assertArrayEquals(new Object[] {"08:00", "12:30", null, null, null, paginaB.getId()},
                    noBanco(DIA_15));
            assertEquals(1, linhasNoBanco(), "o dia continua sendo uma linha só");
        }

        @Test
        void editarDeNovoTrocaOValor() {
            celula(paginaB, DIA_15, "ent1", "08:00");
            celula(paginaB, DIA_15, "ent1", "09:15");

            assertEquals("09:15", noBanco(DIA_15)[0]);
        }

        @Test
        void declararOcorrenciaZeraOsHorarios() {
            celula(paginaB, DIA_15, "ent1", "08:00");
            celula(paginaB, DIA_15, "sai1", "12:00");

            Map<String, Object> resposta = declarar(paginaB, DIA_15, tipoBanco.getId());

            assertArrayEquals(new Object[] {null, null, null, null, tipoBanco.getId(), paginaB.getId()},
                    noBanco(DIA_15));
            assertEquals(tipoBanco.getNome(), resposta.get("tipo_nome"));
            assertEquals(true, resposta.get("conta_folga"));
        }

        @Test
        void digitarHorarioDesfazAOcorrencia() {
            declarar(paginaB, DIA_15, tipoBanco.getId());

            celula(paginaB, DIA_15, "ent1", "08:00");

            assertArrayEquals(new Object[] {"08:00", null, null, null, null, paginaB.getId()},
                    noBanco(DIA_15));
        }

        @Test
        void limparApagaALinha() {
            celula(paginaB, DIA_15, "ent1", "08:00");

            service.limpar(paginaB.getId(), operador.getId(), DIA_15.toString());

            assertEquals(0, linhasNoBanco());
        }

        @Test
        void limparDiaSemRetificacaoNaoEncontra() {
            ServiceValidationException e = assertThrows(ServiceValidationException.class,
                    () -> service.limpar(paginaB.getId(), operador.getId(), DIA_15.toString()));

            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }

        @Test
        void tipoDeOutroFuncionarioNaoSeDeclara() {
            PontoTipoMarcacao escondido = tx.execute(status -> {
                PontoTipoMarcacao t = CenarioFactory.novoTipoMarcacao(em, "Atestado", "Ate",
                        PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
                tipoIds.add(t.getId());
                return t;
            });

            assertEquals("Tipo de ocorrência não disponível.",
                    assertThrows(ServiceValidationException.class,
                            () -> declarar(paginaB, DIA_15, escondido.getId())).getMessage());
            assertEquals(0, linhasNoBanco());
        }

        @Test
        void aCorridaNaChaveDoDiaViraRecusaAmigavel() {
            celula(paginaB, DIA_15, "ent1", "08:00");
            // O segundo pedido não enxerga a linha que já existe: quem desempata é a UK do banco.
            doReturn(Optional.empty()).when(retificacaoRepo)
                    .findByPessoaIdAndPessoaTipoAndData(anyString(), anyString(), any());

            assertEquals("Não foi possível concluir a operação.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, DIA_15, "sai1", "12:00")).getMessage());
        }
    }

    /**
     * O dia que não recebe conteúdo — o fim de semana e a ocorrência que o administrador marcou para
     * todos — continua aceitando que a correção anterior seja apagada: a proibição pode ter chegado
     * DEPOIS do que a pessoa escreveu, e desfazer não pode ficar refém disso.
     */
    @Nested
    class DiaBloqueadoParaEscrita {

        @Test
        void oDiaMarcadoParaTodosNaoRecebeCorrecao() {
            String ocorrencia = marcarDiaParaTodos(DIA_15);

            assertEquals("O dia 15/06/2026 está marcado como " + ocorrencia + ".",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, DIA_15, "ent1", "08:00")).getMessage());
            assertEquals(0, linhasNoBanco());
        }

        @Test
        void oDiaMarcadoParaTodosAindaPodeSerApagado() {
            celula(paginaB, DIA_15, "ent1", "08:00");
            marcarDiaParaTodos(DIA_15);

            service.limpar(paginaB.getId(), operador.getId(), DIA_15.toString());

            assertEquals(0, linhasNoBanco());
        }

        @Test
        void oFimDeSemanaNaoRecebeCorrecao() {
            assertEquals("O dia 13/06/2026 não é dia útil.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, SABADO_13, "ent1", "08:00")).getMessage());
            assertEquals(0, linhasNoBanco());
        }

        @Test
        void aMarcacaoIndividualVisivelApareceNaFolhaEACorrecaoAssumeODia() {
            marcarPeloAdmin(DIA_15);

            assertEquals(tipoBanco.getNome(), marcacaoPessoalDoDia(paginaB, DIA_15));

            celula(paginaB, DIA_15, "ent1", "08:00");
            assertEquals("08:00", noBanco(DIA_15)[0],
                    "a marcação individual não fecha o dia: quem discorda dela retifica por cima");
            assertEquals(0, marcacoesPessoaisNoBanco(DIA_15),
                    "o gesto do funcionário assume o dia: a marcação do administrador sai junto");
        }

        @Test
        void aMarcacaoIndividualVisivelSeLimpaComoUmaCorrecao() {
            marcarPeloAdmin(DIA_15);

            service.limpar(paginaB.getId(), operador.getId(), DIA_15.toString());

            assertEquals(0, marcacoesPessoaisNoBanco(DIA_15));
            assertEquals(0, linhasNoBanco(), "limpar a marcação não cria retificação nenhuma");
            assertNull(marcacaoPessoalDoDia(paginaB, DIA_15));
        }

        private void marcarPeloAdmin(LocalDate data) {
            tx.executeWithoutResult(status -> {
                PontoPessoaMarcacao m = new PontoPessoaMarcacao();
                m.setPessoaId(operador.getId());
                m.setPessoaTipo("OPERADOR");
                m.setData(data);
                m.setTipoId(tipoBanco.getId());
                m.setCriadoPorId(admin.getId());
                em.persist(m);
            });
        }

        private long marcacoesPessoaisNoBanco(LocalDate data) {
            return tx.execute(status -> ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM PNT_PESSOA_MARCACAO WHERE PESSOA_ID = :pessoa AND DATA = :data")
                    .setParameter("pessoa", operador.getId())
                    .setParameter("data", data)
                    .getSingleResult()).longValue());
        }

        @SuppressWarnings("unchecked")
        private Object marcacaoPessoalDoDia(PontoLotePagina pagina, LocalDate data) {
            List<Map<String, Object>> dias = (List<Map<String, Object>>) listar(pagina).get("dias");
            return dias.stream()
                    .filter(d -> data.toString().equals(d.get("data")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("o dia " + data + " não veio na folha"))
                    .get("marcacao_pessoal");
        }
    }

    @Nested
    class Janela {

        @Test
        void oDiaQueVeioEmDuasFolhasSegueAberto() {
            celula(paginaB, DIA_05, "ent1", "08:00");

            assertEquals("08:00", noBanco(DIA_05)[0]);
            assertTrue(diaAberto(paginaB, DIA_05));
        }

        @Test
        void aFolhaAntigaSegueAceitandoRetificacao() {
            assertTrue(diaAberto(paginaA, DIA_05));

            celula(paginaA, DIA_05, "ent1", "08:00");

            assertEquals("08:00", noBanco(DIA_05)[0]);
        }

        @Test
        void aPreviaConviveComAsSemanaisSemFecharNada() {
            PontoLotePagina previa = publicarMensal(PontoLote.CATEGORIA_PREVIA);

            celula(previa, DIA_05, "ent1", "08:00");

            assertEquals("08:00", noBanco(DIA_05)[0]);
            assertTrue(diaAberto(paginaA, DIA_05));
            assertTrue(diaAberto(paginaB, DIA_15));
        }

        @Test
        void aDefinitivaFechaOsDiasQueImprime() {
            publicarMensal(PontoLote.CATEGORIA_DEFINITIVA);

            assertEquals("Não é possível retificar. Folha definitiva já publicada.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, DIA_15, "ent1", "08:00")).getMessage());
            assertEquals(false, diaAberto(paginaB, DIA_15));
        }

        @Test
        void aDefinitivaNaoAlcancaODiaQueNaoImprimiu() {
            publicarMensal(PontoLote.CATEGORIA_DEFINITIVA, MES_INICIO, MES_FIM_PARCIAL);

            celula(paginaB, DIA_15, "ent1", "08:00");

            assertEquals("08:00", noBanco(DIA_15)[0], "o dia fora da cobertura segue aberto");
            assertEquals("Não é possível retificar. Folha definitiva já publicada.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, DIA_05, "ent1", "08:00")).getMessage());
        }

        @Test
        void aDefinitivaSubstituidaNaoDevolveOsDiasQueFechou() {
            PontoLotePagina definitiva = publicarMensal(PontoLote.CATEGORIA_DEFINITIVA);
            recuarPublicacao(definitiva, 60);
            // A definitiva é reenviada cobrindo menos dias — o que ela já fechara não regride.
            publicarMensal(PontoLote.CATEGORIA_DEFINITIVA, MES_INICIO, MES_FIM_PARCIAL);

            assertEquals("Não é possível retificar. Folha definitiva já publicada.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, DIA_15, "ent1", "08:00")).getMessage());
            assertEquals(false, diaAberto(paginaB, DIA_15));
        }

        @Test
        void aSemanaAntigaReenviadaNaoMudaOAbertoEFechado() {
            recuarPublicacao(paginaB, 1800);
            // A semana antiga volta corrigida: é a última publicação da pessoa, mas cobre o mesmo
            // período de antes — e acumular publicações não fecha dia nenhum.
            publicarSemanal(A_INICIO, A_FIM);

            celula(paginaB, DIA_15, "ent1", "08:00");

            assertEquals("08:00", noBanco(DIA_15)[0]);
            assertTrue(diaAberto(paginaB, DIA_15));
            assertTrue(diaAberto(paginaB, DIA_05));
        }

        @Test
        void aFolhaSubstituidaDeixaDeAbrir() {
            celula(paginaB, DIA_15, "ent1", "08:00");
            PontoLotePagina previa = publicarMensal(PontoLote.CATEGORIA_PREVIA);
            publicarMensal(PontoLote.CATEGORIA_DEFINITIVA);

            assertEquals(HttpStatus.NOT_FOUND,
                    assertThrows(ServiceValidationException.class,
                            () -> listar(previa)).getStatus());
        }
    }

    @Nested
    class DuasFolhasSobreOMesmoDia {

        @Test
        void oQueFoiRetificadoPorUmaFolhaApareceNaOutra() {
            PontoLotePagina previa = publicarMensal(PontoLote.CATEGORIA_PREVIA);
            celula(previa, DIA_05, "ent1", "08:00");

            assertEquals(List.of(DIA_05.toString()), diasListados(paginaA));
            assertEquals(List.of(DIA_05.toString()), diasListados(paginaB));
        }

        @Test
        void oDiaForaDoPeriodoDaFolhaNaoAparece() {
            celula(paginaB, DIA_15, "ent1", "08:00");

            assertEquals(List.of(), diasListados(paginaA), "A termina no dia 12");
            assertEquals(List.of(DIA_15.toString()), diasListados(paginaB));
        }

        @Test
        void oDiaForaDoPeriodoDaFolhaNaoGrava() {
            assertEquals("O dia 15/06/2026 está fora do período da folha.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaA, DIA_15, "ent1", "08:00")).getMessage());
        }
    }

    @Nested
    class OrdemComAFolhaImpressa {

        @Test
        void oHorarioImpressoEntraNaConferencia() {
            // A folha já traz o turno que atravessa a meia-noite: o relógio dela voltou uma vez.
            imprimirNaFolha(paginaB, DIA_15, "19:00", "02:00");

            assertEquals("Os horários do dia 15/06/2026 estão fora de ordem.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, DIA_15, "ent2", "01:00")).getMessage());

            celula(paginaB, DIA_15, "ent2", "03:00");
            assertEquals("03:00", noBanco(DIA_15)[2]);
        }

        @Test
        void oDiaDeStatusNaoAtrapalha() {
            imprimirNaFolha(paginaB, DIA_15, "Falta", "Falta");

            celula(paginaB, DIA_15, "ent1", "08:00");

            assertEquals("08:00", noBanco(DIA_15)[0]);
        }

        @Test
        void semLinhaImpressaODiaSoConfereConsigoMesmo() {
            celula(paginaB, DIA_15, "ent1", "08:00");
            celula(paginaB, DIA_15, "sai1", "12:00");

            assertNull(noBanco(DIA_15)[2]);
            assertEquals("12:00", noBanco(DIA_15)[1]);
        }
    }

    /**
     * Quem cobre sessão noturna entra num dia e sai no outro: o relógio do dia pode voltar UMA vez.
     * A segunda volta é horário trocado, e o dia não fecha assim.
     */
    @Nested
    class ODiaQueViraAMeiaNoite {

        @Test
        void oTurnoQueAtravessaAMeiaNoiteGrava() {
            celula(paginaB, DIA_15, "ent1", "19:03");
            celula(paginaB, DIA_15, "sai1", "02:28");

            assertArrayEquals(new Object[] {"19:03", "02:28", null, null, null, paginaB.getId()},
                    noBanco(DIA_15));
        }

        @Test
        void aSegundaVoltaDoRelogioERecusada() {
            celula(paginaB, DIA_15, "ent1", "19:00");
            celula(paginaB, DIA_15, "sai1", "02:28");

            assertEquals("Os horários do dia 15/06/2026 estão fora de ordem.",
                    assertThrows(ServiceValidationException.class,
                            () -> celula(paginaB, DIA_15, "ent2", "01:00")).getMessage());
            assertNull(noBanco(DIA_15)[2], "o horário recusado não chega ao banco");
        }
    }
}
