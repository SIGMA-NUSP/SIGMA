package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.it.support.VigiaDeFacetas;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import jakarta.persistence.EntityManager;

/**
 * IT das 2 listagens de {@link BancoHorasService} contra Oracle real (§3.3 do plano): o SQL delas é
 * literal (`FROM PNT_SOLICITACAO_FOLGA s …`, executado pelo {@code executePagedQuery}) e **escapa de
 * qualquer grep** — só a integração prova o ownership, o CASE dos 3 tipos de pessoa, os LEFT JOINs de
 * deliberador e de saldo, a ordenação default composta e os filtros.
 *
 * <p>Service construído à mão (padrão da FASE C, {@code ChecklistServiceIT}): {@code EntityManager} e
 * {@link AdministradorRepository} REAIS — é o único repositório que as listagens tocam (o
 * {@code servidorPublico} do caller, em {@code anotarDeliberacao}) — e mocks Mockito nas dependências
 * que elas não exercitam. O {@link Clock} é FIXO (15/07/2026, zona explícita — gotcha 13): é ele que
 * decide a flag {@code atrasada}, e sem ele o teste dependeria do dia da execução.
 *
 * <p>Formas do Oracle nas asserções (gotchas 4/5): {@code saldo_min} chega como {@code Number} (o
 * {@code convertValue} do motor devolve {@code Long}), {@code data_folga} como texto
 * {@code "yyyy-MM-dd 00:00:00.0"}, e {@code deliberado_por}/{@code motivo} existem no Map com valor
 * nulo quando o LEFT JOIN não casa. Nada é semeado fora do teste: o NUSP_TEST é clone vazio e o
 * rollback do {@code @DataJpaTest} isola cada caso.
 */
@OracleIT
class BancoHorasServiceIT {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private AdministradorRepository administradorRepo;

    /** Mesmo dia canônico do unitário (quarta-feira): 14/07 é "ontem", 16/07 é "amanhã". */
    private static final LocalDate HOJE = LocalDate.of(2026, 7, 15);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private BancoHorasService service;
    private final VigiaDeFacetas vigiaDeFacetas = new VigiaDeFacetas();

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        service = criarService(administradorRepo);
        // As listagens do Ponto trazem a faceta mais exótica do sistema (o CASE do nome, sobre 3
        // LEFT JOINs): se a consolidação GROUPING SETS falhar nela, o fallback devolve o MESMO
        // resultado e só o WARN denuncia (gotcha 16).
        vigiaDeFacetas.instalar();
    }

    @AfterEach
    void nenhumWarnDeFaceta() {
        vigiaDeFacetas.exigirZeroWarns();
    }

    /** As listagens só usam o EM (SQL nativo) e o administradorRepo (anotação de deliberação). */
    private BancoHorasService criarService(AdministradorRepository adminRepo) {
        return new BancoHorasService(emReal(),
                mock(PontoBancoSaldoRepository.class),
                mock(PontoSolicitacaoFolgaRepository.class),
                mock(PontoDiaMarcacaoRepository.class),
                mock(PontoPessoaMarcacaoRepository.class),
                mock(SaldoAberturaService.class),
                mock(AvisoService.class),
                mock(OperadorRepository.class),
                mock(TecnicoRepository.class),
                adminRepo,
                Clock.fixed(HOJE.atTime(12, 0).atZone(ZONA).toInstant(), ZONA));
    }

    // ── helpers de leitura das linhas ──

    private static List<String> ids(PagedResult r) {
        return r.data().stream().map(linha -> (String) linha.get("id")).toList();
    }

    /** {@code "2026-07-14 00:00:00.0"} → {@code 2026-07-14} (o DATE do Oracle vem com hora). */
    private static LocalDate dia(Map<String, Object> linha) {
        return LocalDate.parse(String.valueOf(linha.get("data_folga")).substring(0, 10));
    }

    private static List<LocalDate> dias(PagedResult r) {
        return r.data().stream().map(BancoHorasServiceIT::dia).toList();
    }

    private static List<String> valores(PagedResult r, String coluna) {
        return r.data().stream().map(linha -> (String) linha.get(coluna)).toList();
    }

    private static Map<String, Object> linhaDe(PagedResult r, PontoSolicitacaoFolga s) {
        return r.data().stream()
                .filter(linha -> s.getId().equals(linha.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a solicitação " + s.getId() + " não veio na listagem"));
    }

    /** Valores (não os rótulos) de uma faceta do {@code meta.distinct} — a ordem é a que o motor devolve. */
    private static List<String> valoresDaFaceta(PagedResult r, String faceta) {
        return r.distinct().get(faceta).stream().map(item -> item.get("value")).toList();
    }

    private static Map<String, Object> filtroValores(String coluna, Object... valores) {
        return Map.of(coluna, Map.of("values", List.of(valores)));
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("listMinhasSolicitacoes — ownership do dono no WHERE (binds do baseParams)")
    class MinhasSolicitacoes {

        @Test
        @DisplayName("só as linhas do dono: pedido de OUTRA pessoa e do MESMO id em outro PESSOA_TIPO ficam de fora")
        void ownership_soODono() {
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador outro = CenarioFactory.novoOperador(emReal());
            PontoSolicitacaoFolga meu = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            CenarioFactory.novaSolicitacaoFolga(emReal(), outro.getId(), "OPERADOR",
                    HOJE.plusDays(2), StatusSolicitacaoFolga.PENDENTE);
            // MESMO id, tipo diferente: só o AND do PESSOA_TIPO exclui esta linha (a tabela é polimórfica, sem FK)
            CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "TECNICO",
                    HOJE.plusDays(3), StatusSolicitacaoFolga.PENDENTE);
            emReal().flush();

            PagedResult r = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10, null, null, null);

            assertEquals(1, r.total(), "o COUNT também filtra pelo dono — não é a tabela inteira");
            assertEquals(List.of(meu.getId()), ids(r));
        }

        @Test
        @DisplayName("deliberado_por: nulo em PENDENTE e o NOME do admin quando deliberada (LEFT JOIN PES_ADMINISTRADOR)")
        void deliberadoPor_vemDoLeftJoin() {
            Operador dono = CenarioFactory.novoOperador(emReal());
            Administrador chefe = CenarioFactory.novoAdministrador(emReal());
            PontoSolicitacaoFolga pendente = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga rejeitada = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(2), StatusSolicitacaoFolga.REJEITADO, chefe, "Sem cobertura na sala");
            emReal().flush();

            PagedResult r = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10, null, null, null);

            Map<String, Object> linhaPendente = linhaDe(r, pendente);
            assertNull(linhaPendente.get("deliberado_por"), "pendente não tem deliberador — o LEFT JOIN não casa");
            assertNull(linhaPendente.get("motivo"));

            Map<String, Object> linhaRejeitada = linhaDe(r, rejeitada);
            assertEquals(chefe.getNomeCompleto(), linhaRejeitada.get("deliberado_por"));
            assertEquals("Sem cobertura na sala", linhaRejeitada.get("motivo"));
            assertEquals("REJEITADO", linhaRejeitada.get("status"));
        }

        @Test
        @DisplayName("ordenação default: DATA_FOLGA ASC com tiebreaker CRIADO_EM DESC no empate de dia")
        void ordenacaoDefault_dataFolgaComTiebreaker() {
            Operador dono = CenarioFactory.novoOperador(emReal());
            LocalDate empate = HOJE.plusDays(5);
            // Semeio FORA da ordem esperada: sem ORDER BY, viria na ordem de inserção (falso-verde).
            PontoSolicitacaoFolga tarde = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(9), StatusSolicitacaoFolga.PENDENTE);
            // Mesmo dia, 2 linhas: a FBI só admite UMA viva por (pessoa, dia) — a outra é CANCELADA.
            PontoSolicitacaoFolga antigaNoEmpate = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(),
                    "OPERADOR", empate, StatusSolicitacaoFolga.CANCELADO);
            PontoSolicitacaoFolga novaNoEmpate = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(),
                    "OPERADOR", empate, StatusSolicitacaoFolga.PENDENTE);
            // A cancelada nasce 1h ANTES — o tiebreaker CRIADO_EM DESC tem de pôr a nova na frente.
            // As DUAS âncoras vêm do relógio do BANCO (SYSTIMESTAMP): o @PrePersist carimba pela JVM, e
            // comparar os dois relógios só funcionaria enquanto ambos estivessem em UTC (F7).
            CenarioFactory.fixarTimestamp(emReal(), "PNT_SOLICITACAO_FOLGA", "CRIADO_EM",
                    antigaNoEmpate.getId(), 7200);
            CenarioFactory.fixarTimestamp(emReal(), "PNT_SOLICITACAO_FOLGA", "CRIADO_EM",
                    novaNoEmpate.getId(), 3600);
            emReal().flush();

            PagedResult r = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10, null, null, null);

            assertEquals(List.of(novaNoEmpate.getId(), antigaNoEmpate.getId(), tarde.getId()), ids(r),
                    "dia mais próximo primeiro; no empate de dia, a criada há menos tempo vem antes");
            assertEquals(List.of(empate, empate, HOJE.plusDays(9)), dias(r));
        }

        @Test
        @DisplayName("paginação: OFFSET/FETCH fatia os dados e o COUNT enxerga o conjunto inteiro")
        void paginacao_fatiaComTotalCompleto() {
            Operador dono = CenarioFactory.novoOperador(emReal());
            for (int i = 1; i <= 5; i++) {
                CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                        HOJE.plusDays(i), StatusSolicitacaoFolga.PENDENTE);
            }
            emReal().flush();

            PagedResult pagina2 = service.listMinhasSolicitacoes(dono.getId(), "operador", 2, 2, null, null, null);

            assertEquals(5, pagina2.total(), "o COUNT não pagina");
            assertEquals(List.of(HOJE.plusDays(3), HOJE.plusDays(4)), dias(pagina2),
                    "página 2 de 2 em 2 = o 3º e o 4º dias");
        }

        @Test
        @DisplayName("filtros: status (text, IN) e data_folga (date, range sargável) recortam dados E total")
        void filtros_statusEData() {
            Operador dono = CenarioFactory.novoOperador(emReal());
            Administrador chefe = CenarioFactory.novoAdministrador(emReal());
            PontoSolicitacaoFolga pendente = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga aprovada = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(2), StatusSolicitacaoFolga.APROVADO, chefe, null);
            CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(3), StatusSolicitacaoFolga.CANCELADO);
            emReal().flush();

            PagedResult porStatus = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10, null, null,
                    filtroValores("status", "PENDENTE", "APROVADO"));
            assertEquals(2, porStatus.total());
            assertEquals(List.of(pendente.getId(), aprovada.getId()), ids(porStatus));

            PagedResult porDia = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10, null, null,
                    Map.of("data_folga", Map.of("values", List.of(HOJE.plusDays(2).toString()))));
            assertEquals(1, porDia.total(), "o filtro de data casa o dia exato (range [d, d+1))");
            assertEquals(List.of(aprovada.getId()), ids(porDia));
        }

        @Test
        @DisplayName("ordenação explícita: sort/dir do controller (data_folga DESC), status e deliberado_por (LEFT JOIN)")
        void ordenacaoExplicita_pelasColunasDoMS_SORT() {
            // O PontoController SEMPRE manda sort/dir (default data_folga/desc) — nenhum caso os exercitava,
            // e as demais entradas do MS_SORT são SQL literal que só a integração alcança.
            Operador dono = CenarioFactory.novoOperador(emReal());
            Administrador zulu = CenarioFactory.novoAdministrador(emReal(), "Zulu Deliberador");
            Administrador alfa = CenarioFactory.novoAdministrador(emReal(), "Alfa Deliberador");
            PontoSolicitacaoFolga pendente = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga aprovadaPorZulu = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(),
                    "OPERADOR", HOJE.plusDays(2), StatusSolicitacaoFolga.APROVADO, zulu, null);
            PontoSolicitacaoFolga rejeitadaPorAlfa = CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(),
                    "OPERADOR", HOJE.plusDays(3), StatusSolicitacaoFolga.REJEITADO, alfa, "não");
            emReal().flush();

            PagedResult porDiaDesc = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10,
                    "data_folga", "desc", null);
            assertEquals(List.of(rejeitadaPorAlfa.getId(), aprovadaPorZulu.getId(), pendente.getId()),
                    ids(porDiaDesc), "data_folga DESC — o par (sort, dir) que o controller manda de verdade");

            PagedResult porDeliberador = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10,
                    "deliberado_por", "asc", null);
            assertEquals(List.of(rejeitadaPorAlfa.getId(), aprovadaPorZulu.getId(), pendente.getId()),
                    ids(porDeliberador),
                    "ORDER BY adm.NOME_COMPLETO ASC sobre o LEFT JOIN: Alfa, Zulu e a PENDENTE (NULL) por último");

            PagedResult porStatus = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 10,
                    "status", "asc", null);
            assertEquals(List.of("APROVADO", "PENDENTE", "REJEITADO"), valores(porStatus, "status"),
                    "ORDER BY s.STATUS ASC — ordem alfabética do literal, não a do enum");
        }

        @Test
        @DisplayName("sobrecarga curta ≡ longa(somenteDados=false); somenteDados=true pula COUNT e facetas (Q5)")
        void somenteDados_pulaCountEFacetas() {
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador estranho = CenarioFactory.novoOperador(emReal());
            for (int i = 1; i <= 3; i++) {
                CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                        HOJE.plusDays(i), StatusSolicitacaoFolga.PENDENTE);
            }
            CenarioFactory.novaSolicitacaoFolga(emReal(), dono.getId(), "OPERADOR",
                    HOJE.plusDays(4), StatusSolicitacaoFolga.CANCELADO);
            // A faceta é calculada com os MESMOS binds da listagem: o status do estranho não pode vazar nela.
            CenarioFactory.novaSolicitacaoFolga(emReal(), estranho.getId(), "OPERADOR",
                    HOJE.plusDays(5), StatusSolicitacaoFolga.REJEITADO,
                    CenarioFactory.novoAdministrador(emReal()), "não");
            emReal().flush();

            PagedResult curta = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 2, null, null, null);
            PagedResult longa = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 2, null, null, null, false);
            assertEquals(ids(curta), ids(longa), "a sobrecarga curta delega com somenteDados=false");
            assertEquals(curta.total(), longa.total());
            assertEquals(4, curta.total(), "a sobrecarga curta faz o COUNT de TODAS as linhas do dono");
            assertEquals(List.of("CANCELADO", "PENDENTE"), valoresDaFaceta(curta, "status"),
                    "a faceta enxerga só os status DO DONO — o REJEITADO do estranho ficaria de fora "
                            + "apenas se os binds do ownership chegarem também à query de facetas");

            PagedResult relatorio = service.listMinhasSolicitacoes(dono.getId(), "operador", 1, 2, null, null, null, true);
            assertEquals(2, relatorio.data().size(), "o LIMIT continua valendo no modo relatório");
            assertEquals(2, relatorio.total(), "sem COUNT: o total vira o tamanho da página devolvida");
            assertTrue(relatorio.distinct().isEmpty(), "sem facetas no modo relatório");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("listSolicitacoesAdmin — fila de todos, com nome polimórfico, saldo e anotação")
    class SolicitacoesAdmin {

        @Test
        @DisplayName("nome pelo CASE dos 3 LEFT JOINs (operador/técnico/administrador) e saldo_min do PNT_BANCO_SALDO")
        void nomeDosTresTipos_eSaldoDoJoin() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal(), "Zeta Operadora");
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal(), "Tecnico Titular");
            Administrador adminComFolha = CenarioFactory.novoAdministrador(emReal());
            // 2º técnico SEM pedido: se o ON do join do técnico perdesse o `pt.ID = s.PESSOA_ID`, a linha
            // do técnico sofreria fan-out (1 por técnico da tabela) e o nome poderia vir do estranho.
            CenarioFactory.novoTecnico(emReal(), "Tecnico Estranho");

            PontoSolicitacaoFolga doOperador = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doTecnico = CenarioFactory.novaSolicitacaoFolga(emReal(), tecnico.getId(),
                    "TECNICO", HOJE.plusDays(2), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doAdmin = CenarioFactory.novaSolicitacaoFolga(emReal(), adminComFolha.getId(),
                    "ADMINISTRADOR", HOJE.plusDays(3), StatusSolicitacaoFolga.PENDENTE);
            CenarioFactory.novoSaldoBanco(emReal(), operador.getId(), "OPERADOR", 1570);
            // o técnico NÃO tem linha de saldo — o LEFT JOIN deve devolver nulo, não zero nem erro
            CenarioFactory.novoSaldoBanco(emReal(), adminComFolha.getId(), "ADMINISTRADOR", -123);
            emReal().flush();

            PagedResult r = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null);

            assertEquals(3, r.total(), "o admin vê a fila inteira — e 1 linha por pedido, sem fan-out dos joins");
            assertEquals(3, r.data().size());
            assertEquals(operador.getNomeCompleto(), linhaDe(r, doOperador).get("nome"));
            assertEquals(tecnico.getNomeCompleto(), linhaDe(r, doTecnico).get("nome"),
                    "o nome vem do técnico DONO do pedido, não de qualquer técnico");
            assertEquals(adminComFolha.getNomeCompleto(), linhaDe(r, doAdmin).get("nome"));

            assertEquals(1570L, linhaDe(r, doOperador).get("saldo_min"));
            assertEquals(-123L, linhaDe(r, doAdmin).get("saldo_min"), "saldo negativo atravessa o join com o sinal");
            assertNull(linhaDe(r, doTecnico).get("saldo_min"), "pessoa sem linha em PNT_BANCO_SALDO → saldo nulo");
        }

        @Test
        @DisplayName("saldo_min casa PESSOA_ID E PESSOA_TIPO: nem o homônimo de outro tipo, nem o saldo do colega")
        void saldo_casaPessoaETipo() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Operador colega = CenarioFactory.novoOperador(emReal());
            PontoSolicitacaoFolga pedido = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            // (1) saldo com o MESMO id, mas como TECNICO → só o AND do TIPO o exclui
            CenarioFactory.novoSaldoBanco(emReal(), operador.getId(), "TECNICO", 999);
            // (2) saldo de OUTRO operador → só o AND do ID o exclui (sem ele, o join casaria e ainda duplicaria)
            CenarioFactory.novoSaldoBanco(emReal(), colega.getId(), "OPERADOR", 777);
            emReal().flush();

            PagedResult r = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null);

            assertEquals(1, r.total(), "1 pedido = 1 linha: um ON frouxo faria fan-out pelos saldos");
            assertEquals(1, r.data().size());
            assertNull(linhaDe(r, pedido).get("saldo_min"),
                    "o dono não tem saldo OPERADOR: nem o do técnico homônimo nem o do colega podem aparecer");
        }

        @Test
        @DisplayName("ordenação default composta (D-4.1): PENDENTEs primeiro, depois por DATA_FOLGA")
        void ordenacaoDefault_pendentesPrimeiro() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Administrador chefe = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());

            // A pendente é a de dia MAIS DISTANTE: só a ordenação composta a põe na frente.
            PontoSolicitacaoFolga aprovadaCedo = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(1), StatusSolicitacaoFolga.APROVADO, chefe, null);
            PontoSolicitacaoFolga rejeitadaDepois = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(2), StatusSolicitacaoFolga.REJEITADO, chefe, "não");
            PontoSolicitacaoFolga pendenteTarde = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(9), StatusSolicitacaoFolga.PENDENTE);
            emReal().flush();

            PagedResult r = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null);

            assertEquals(List.of(pendenteTarde.getId(), aprovadaCedo.getId(), rejeitadaDepois.getId()), ids(r),
                    "CASE STATUS WHEN 'PENDENTE' THEN 0 ELSE 1 END, DATA_FOLGA — a pendente distante vem primeiro");

            // A fila do admin tem DOIS joins na MESMA tabela (pa = solicitante admin, adm = deliberador):
            // sem esta asserção, trocar um alias pelo outro passaria verde e o relatório imprimiria o nome errado.
            assertEquals(chefe.getNomeCompleto(), linhaDe(r, rejeitadaDepois).get("deliberado_por"));
            assertEquals("não", linhaDe(r, rejeitadaDepois).get("motivo"));
            assertNull(linhaDe(r, pendenteTarde).get("deliberado_por"), "pendente não tem deliberador");
            assertNull(linhaDe(r, pendenteTarde).get("motivo"));
        }

        @Test
        @DisplayName("tiebreaker s.ID: empate total (mesmo status e mesmo dia) tem ordem estável entre páginas")
        void tiebreaker_mantemPaginacaoEstavel() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            LocalDate mesmoDia = HOJE.plusDays(2);
            // A FBI UQ_PNT_SOLF_VIVA proíbe 2 vivas da MESMA pessoa no mesmo dia → 4 pessoas distintas.
            for (int i = 0; i < 4; i++) {
                Operador pessoa = CenarioFactory.novoOperador(emReal());
                CenarioFactory.novaSolicitacaoFolga(emReal(), pessoa.getId(), "OPERADOR",
                        mesmoDia, StatusSolicitacaoFolga.PENDENTE);
            }
            emReal().flush();

            // ⚠️ A ordem esperada vem do BANCO, não de String.compareTo: a sessão JDBC ordena VARCHAR2 por
            // colação LINGUÍSTICA (WEST_EUROPEAN — o driver herda o locale pt-BR da JVM), onde LETRA vem
            // antes de DÍGITO. Comparar com o sort do Java daria falha fantasma (achado F30 da §5).
            @SuppressWarnings("unchecked")
            List<String> ordemDoBanco = emReal()
                    .createNativeQuery("SELECT s.ID FROM PNT_SOLICITACAO_FOLGA s ORDER BY s.ID")
                    .getResultList();
            assertEquals(4, ordemDoBanco.size());

            PagedResult tudo = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null);
            assertEquals(ordemDoBanco, ids(tudo),
                    "empatadas no CASE e em DATA_FOLGA → quem decide é o tiebreaker s.ID");

            // Paginar 1 a 1: sem desempate estável, o Oracle poderia repetir (ou pular) linhas entre páginas.
            List<String> paginando = new ArrayList<>();
            for (int pagina = 1; pagina <= 4; pagina++) {
                paginando.addAll(ids(service.listSolicitacoesAdmin(caller.getId(), pagina, 1, null, null, null, null)));
            }
            assertEquals(ordemDoBanco, paginando, "as 4 páginas de 1 cobrem as 4 linhas, sem repetição");
        }

        @Test
        @DisplayName("ordenação explícita: sort=nome varre o CASE dos 3 tipos; dir=desc na composta só inverte o DIA")
        void ordenacaoExplicita_nomeEDirecaoNaComposta() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Administrador chefe = CenarioFactory.novoAdministrador(emReal(), "Zzz Chefe");
            Operador operador = CenarioFactory.novoOperador(emReal(), "Bravo Operador");
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal(), "Alfa Tecnico");
            Administrador adminComFolha = CenarioFactory.novoAdministrador(emReal(), "Charlie Admin");

            PontoSolicitacaoFolga doOperador = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doTecnico = CenarioFactory.novaSolicitacaoFolga(emReal(), tecnico.getId(),
                    "TECNICO", HOJE.plusDays(2), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doAdmin = CenarioFactory.novaSolicitacaoFolga(emReal(), adminComFolha.getId(),
                    "ADMINISTRADOR", HOJE.plusDays(3), StatusSolicitacaoFolga.APROVADO, chefe, null);
            emReal().flush();

            // ORDER BY sobre a EXPRESSÃO (o CASE), não sobre uma coluna: Alfa (téc) < Bravo (op) < Charlie (adm)
            PagedResult porNome = service.listSolicitacoesAdmin(caller.getId(), 1, 10, "nome", "asc", null, null);
            assertEquals(List.of(doTecnico.getId(), doOperador.getId(), doAdmin.getId()), ids(porNome),
                    "o sort por Nome atravessa os 3 tipos — uma coluna crua deixaria 2 deles nulos");

            // dir=desc com o sort default COMPOSTO: o DESC cola só no último termo (s.DATA_FOLGA), então os
            // PENDENTEs continuam na frente. Comportamento surpreendente, mas é o contrato atual.
            PagedResult padraoDesc = service.listSolicitacoesAdmin(caller.getId(), 1, 10, "padrao", "desc", null, null);
            assertEquals(List.of(doTecnico.getId(), doOperador.getId(), doAdmin.getId()), ids(padraoDesc),
                    "PENDENTEs primeiro (dia DESC entre eles), a deliberada por último");
        }

        @Test
        @DisplayName("search casa o NOME do solicitante nos 3 tipos (é a expressão CASE, não a coluna do operador)")
        void search_peloNomeDoSolicitante() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Operador alvoOperador = CenarioFactory.novoOperador(emReal(), "Buscavel Operador", "alfa");
            Tecnico alvoTecnico = CenarioFactory.novoTecnico(emReal(), "Buscavel Tecnico");
            Administrador alvoAdmin = CenarioFactory.novoAdministrador(emReal(), "Buscavel Admin");
            Operador ruido = CenarioFactory.novoOperador(emReal(), "Outra Pessoa Qualquer", "bravo");

            PontoSolicitacaoFolga doOperador = CenarioFactory.novaSolicitacaoFolga(emReal(), alvoOperador.getId(),
                    "OPERADOR", HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doTecnico = CenarioFactory.novaSolicitacaoFolga(emReal(), alvoTecnico.getId(),
                    "TECNICO", HOJE.plusDays(2), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doAdmin = CenarioFactory.novaSolicitacaoFolga(emReal(), alvoAdmin.getId(),
                    "ADMINISTRADOR", HOJE.plusDays(3), StatusSolicitacaoFolga.PENDENTE);
            CenarioFactory.novaSolicitacaoFolga(emReal(), ruido.getId(), "OPERADOR",
                    HOJE.plusDays(4), StatusSolicitacaoFolga.PENDENTE);
            emReal().flush();

            PagedResult r = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, "buscavel", null);

            assertEquals(3, r.total(), "o COUNT reflete a busca (UPPER LIKE sobre o CASE dos 3 tipos)");
            assertEquals(List.of(doOperador.getId(), doTecnico.getId(), doAdmin.getId()), ids(r),
                    "buscar por uma coluna crua (po.NOME_COMPLETO) acharia só o operador");
            assertEquals(alvoTecnico.getNomeCompleto(), linhaDe(r, doTecnico).get("nome"));
        }

        @Test
        @DisplayName("filtro de coluna 'nome' (text sobre o CASE) e facetas: as 3 chaves, com os nomes dos 3 tipos")
        void filtroEFacetaDoNome() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal(), "Delta Operador", "delta");
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal(), "Echo Tecnico");
            PontoSolicitacaoFolga doOperador = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            CenarioFactory.novaSolicitacaoFolga(emReal(), tecnico.getId(), "TECNICO",
                    HOJE.plusDays(2), StatusSolicitacaoFolga.PENDENTE);
            emReal().flush();

            // O filtro de coluna é OUTRO caminho de SQL que a busca (UPPER(CAST(expr AS VARCHAR2)) LIKE):
            // tirar "nome" do colMap faz o helper IGNORAR o filtro em silêncio — a fila voltaria inteira.
            PagedResult filtrado = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null,
                    Map.of("nome", Map.of("text", "delta")));
            assertEquals(1, filtrado.total(), "o filtro de coluna Nome recorta dados E total");
            assertEquals(List.of(doOperador.getId()), ids(filtrado));

            PagedResult tudo = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null);
            assertEquals(Set.of("nome", "data_folga", "status"), tudo.distinct().keySet());
            assertEquals(List.of(operador.getNomeCompleto(), tecnico.getNomeCompleto()),
                    valoresDaFaceta(tudo, "nome"),
                    "a faceta do Nome é uma EXPRESSÃO dentro do GROUPING SETS — e enxerga os 2 tipos");
            assertEquals(List.of("PENDENTE"), valoresDaFaceta(tudo, "status"));
        }

        @Test
        @DisplayName("paginação + filtro de status na fila do admin (sem ownership, o recorte é só do filtro)")
        void paginacaoEFiltro() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Administrador chefe = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            for (int i = 1; i <= 4; i++) {
                CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(), "OPERADOR",
                        HOJE.plusDays(i), StatusSolicitacaoFolga.PENDENTE);
            }
            CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(), "OPERADOR",
                    HOJE.plusDays(8), StatusSolicitacaoFolga.REJEITADO, chefe, "não");
            emReal().flush();

            PagedResult pendentes = service.listSolicitacoesAdmin(caller.getId(), 2, 3, null, null, null,
                    filtroValores("status", "PENDENTE"));

            assertEquals(4, pendentes.total(), "4 pendentes no filtro (a rejeitada fica fora do COUNT)");
            assertEquals(List.of(HOJE.plusDays(4)), dias(pendentes), "página 2 de 3 em 3 = a 4ª pendente");
        }

        @Test
        @DisplayName("anotação 'atrasada' (Q11) vem do Clock fixo: só PENDENTE com dia ANTERIOR a hoje")
        void anotacao_atrasadaSegueOClock() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Administrador chefe = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());

            PontoSolicitacaoFolga ontem = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(), "OPERADOR",
                    HOJE.minusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga hoje = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(), "OPERADOR",
                    HOJE, StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga amanha = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(), "OPERADOR",
                    HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga aprovadaOntem = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.minusDays(3), StatusSolicitacaoFolga.APROVADO, chefe, null);
            emReal().flush();

            PagedResult r = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null);

            assertEquals(Boolean.TRUE, linhaDe(r, ontem).get("atrasada"), "pendente de ontem está atrasada");
            assertEquals(Boolean.FALSE, linhaDe(r, hoje).get("atrasada"), "o dia de hoje ainda não passou");
            assertEquals(Boolean.FALSE, linhaDe(r, amanha).get("atrasada"));
            assertEquals(Boolean.FALSE, linhaDe(r, aprovadaOntem).get("atrasada"),
                    "atraso é sobre pedido PENDENTE — já deliberado não atrasa");
        }

        @Test
        @DisplayName("anotação 'pode_deliberar': T-1.2 (próprio pedido) e T-1.3 (pedido de admin × caller sem SP)")
        void anotacao_podeDeliberar() {
            Administrador callerSemSp = CenarioFactory.novoAdministrador(emReal());
            callerSemSp.setServidorPublico(false);   // admin COM folha de ponto
            Administrador outroAdmin = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Tecnico tecnico = CenarioFactory.novoTecnico(emReal());

            PontoSolicitacaoFolga proprio = CenarioFactory.novaSolicitacaoFolga(emReal(), callerSemSp.getId(),
                    "ADMINISTRADOR", HOJE.plusDays(1), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga deOutroAdmin = CenarioFactory.novaSolicitacaoFolga(emReal(), outroAdmin.getId(),
                    "ADMINISTRADOR", HOJE.plusDays(2), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doOperador = CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(),
                    "OPERADOR", HOJE.plusDays(3), StatusSolicitacaoFolga.PENDENTE);
            PontoSolicitacaoFolga doTecnico = CenarioFactory.novaSolicitacaoFolga(emReal(), tecnico.getId(),
                    "TECNICO", HOJE.plusDays(4), StatusSolicitacaoFolga.PENDENTE);
            emReal().flush();

            // caller SP=0: não delibera o próprio pedido (T-1.2) nem o de outro admin (T-1.3); op/téc, sim (Q33)
            PagedResult semSp = service.listSolicitacoesAdmin(callerSemSp.getId(), 1, 10, null, null, null, null);
            assertEquals(Boolean.FALSE, linhaDe(semSp, proprio).get("pode_deliberar"), "T-1.2");
            assertEquals(Boolean.FALSE, linhaDe(semSp, deOutroAdmin).get("pode_deliberar"), "T-1.3");
            assertEquals(Boolean.TRUE, linhaDe(semSp, doOperador).get("pode_deliberar"));
            assertEquals(Boolean.TRUE, linhaDe(semSp, doTecnico).get("pode_deliberar"));

            // o MESMO pedido de admin, visto por um admin servidor público (o default da entidade), libera
            PagedResult comSp = service.listSolicitacoesAdmin(outroAdmin.getId(), 1, 10, null, null, null, null);
            assertEquals(Boolean.TRUE, linhaDe(comSp, proprio).get("pode_deliberar"),
                    "admin SP=1 delibera pedido de OUTRO admin (T-1.3 satisfeito)");
            assertEquals(Boolean.FALSE, linhaDe(comSp, deOutroAdmin).get("pode_deliberar"),
                    "mas continua sem poder deliberar o PRÓPRIO pedido (T-1.2 vence)");
            assertEquals(Boolean.TRUE, linhaDe(comSp, doOperador).get("pode_deliberar"), "Q33");
            assertEquals(Boolean.TRUE, linhaDe(comSp, doTecnico).get("pode_deliberar"), "Q33");

            // caller que não existe mais em PES_ADMINISTRADOR: o servidorPublico cai no default fail-closed
            PagedResult sumido = service.listSolicitacoesAdmin("caller-que-nao-existe", 1, 10, null, null, null, null);
            assertEquals(Boolean.FALSE, linhaDe(sumido, deOutroAdmin).get("pode_deliberar"),
                    "sem linha de admin, o servidorPublico é FALSE (orElse fail-closed) — não delibera pedido de admin");
            assertEquals(Boolean.TRUE, linhaDe(sumido, doOperador).get("pode_deliberar"),
                    "mas o pedido de operador segue deliberável por qualquer admin (T-1.3 não se aplica)");
        }

        @Test
        @DisplayName("somenteDados=true (relatório): sem anotação, sem facetas e SEM tocar o administradorRepo")
        void somenteDados_naoAnotaENaoLeOCaller() {
            Administrador caller = CenarioFactory.novoAdministrador(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            CenarioFactory.novaSolicitacaoFolga(emReal(), operador.getId(), "OPERADOR",
                    HOJE.minusDays(1), StatusSolicitacaoFolga.PENDENTE);   // atrasada, se fosse anotar
            CenarioFactory.novoSaldoBanco(emReal(), operador.getId(), "OPERADOR", 600);
            emReal().flush();

            // repositório MOCKADO só neste caso: é a prova de que o caminho do relatório nem lê o caller
            AdministradorRepository adminRepoMock = mock(AdministradorRepository.class);
            PagedResult relatorio = criarService(adminRepoMock)
                    .listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null, true);

            assertEquals(1, relatorio.data().size());
            Map<String, Object> linha = relatorio.data().get(0);
            assertFalse(linha.containsKey("pode_deliberar"), "o relatório não anota deliberação");
            assertFalse(linha.containsKey("atrasada"));
            assertEquals(600L, linha.get("saldo_min"), "mas os dados da SQL (inclusive o join de saldo) vêm iguais");
            assertTrue(relatorio.distinct().isEmpty(), "sem facetas (Q5)");
            verifyNoInteractions(adminRepoMock);

            // e a sobrecarga curta (somenteDados=false) ANOTA — contraprova de que a diferença é o flag
            PagedResult listagem = service.listSolicitacoesAdmin(caller.getId(), 1, 10, null, null, null, null);
            assertTrue(listagem.data().get(0).containsKey("pode_deliberar"));
            assertEquals(Boolean.TRUE, listagem.data().get(0).get("atrasada"));
        }
    }
}
