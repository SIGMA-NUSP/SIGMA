package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.ChecklistItemTipo;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.StatusResposta;
import br.leg.senado.nusp.enums.Turno;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;

/**
 * IT do contrato completo de {@link DashboardQueryHelper#executePagedQuery} — o motor único de
 * TODAS as listagens paginadas do sistema — contra Oracle real.
 *
 * <p>Veículos: {@code AdminDashboardService.listOperadores} (sem período/tiebreaker) e
 * {@code listChecklists} (dateCol, tiebreaker, 5 facetas, expressão derivada no colMap),
 * construídos à mão com EM real + ObjectMapper. Assim os binds saem na composição REAL:
 * {@code baseParams → search → período → filtros → OFFSET/FETCH}. A perna {@code baseParams}
 * (hoje só usada por OperadorDashboardService e BancoHorasService) é exercitada chamando o motor
 * diretamente — ele é público e estático.
 *
 * <p><b>Anti-falso-verde das facetas:</b> a consolidação {@code GROUPING SETS} e o fallback
 * por-coluna produzem o MESMO resultado; só um WARN distingue os caminhos. Sem vigiar o log, todo
 * teste de faceta passaria mesmo com a consolidação quebrada (o fallback a substituiria em
 * silêncio). Por isso {@link #nenhumWarnDeFaceta()} falha o teste se o motor logar qualquer WARN —
 * é a verificação "zero WARN de faceta" da validação do T12, tornada executável.
 */
@OracleIT
class DashboardQueryHelperIT {

    @Autowired
    private TestEntityManager em;

    private AdminDashboardService admin;
    private Logger loggerDoMotor;
    private ListAppender<ILoggingEvent> logDoMotor;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        admin = new AdminDashboardService(emReal(), new ObjectMapper().findAndRegisterModules());
        loggerDoMotor = (Logger) LoggerFactory.getLogger(DashboardQueryHelper.class);
        logDoMotor = new ListAppender<>();
        logDoMotor.start();
        loggerDoMotor.addAppender(logDoMotor);
    }

    @AfterEach
    void nenhumWarnDeFaceta() {
        loggerDoMotor.detachAppender(logDoMotor);
        List<String> warns = logDoMotor.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertTrue(warns.isEmpty(),
                "o motor caiu no fallback por-coluna (a consolidação GROUPING SETS falhou): " + warns);
    }

    // ── Helpers de payload (mesmo shape que o controller entrega ao service) ──

    private static Map<String, Object> periodo(String... startEnd) {
        List<Map<String, String>> ranges = new ArrayList<>();
        for (int i = 0; i < startEnd.length; i += 2) {
            ranges.add(Map.of("start", startEnd[i], "end", startEnd[i + 1]));
        }
        return Map.of("ranges", ranges);
    }

    private static Map<String, Object> comValores(Object... valores) {
        return Map.of("values", List.of(valores));
    }

    private static Map<String, Object> comTexto(String texto) {
        return Map.of("text", texto);
    }

    private static Map<String, Object> comIntervalo(String de, String ate) {
        return Map.of("range", Map.of("from", de, "to", ate));
    }

    // ── Helpers de leitura do PagedResult ──

    private static List<String> textos(PagedResult r, String coluna) {
        return r.data().stream().map(linha -> (String) linha.get(coluna)).toList();
    }

    private static List<Long> ids(PagedResult r) {
        return r.data().stream().map(linha -> (Long) linha.get("id")).toList();
    }

    private static List<String> valoresDaFaceta(PagedResult r, String faceta) {
        return r.distinct().get(faceta).stream().map(item -> item.get("value")).toList();
    }

    private static List<String> rotulosDaFaceta(PagedResult r, String faceta) {
        return r.distinct().get(faceta).stream().map(item -> item.get("label")).toList();
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("paginação — OFFSET/FETCH e COUNT independentes")
    class Paginacao {

        /** 25 operadores "Operador IT 01".."25": o número zero-padded fixa a ordem por NOME_COMPLETO. */
        private List<Operador> semear25() {
            List<Operador> ops = new ArrayList<>();
            for (int i = 1; i <= 25; i++) {
                ops.add(CenarioFactory.novoOperador(emReal(), String.format("Operador IT %02d", i)));
            }
            return ops;
        }

        @Test
        @DisplayName("executePagedQuery — page 2/limit 10 devolve as 10 linhas certas, na ordem, com total da tabela inteira")
        void paginacao_pageDoisDevolveAFatiaCertaComTotalCompleto() {
            List<Operador> ops = semear25();

            PagedResult r = admin.listOperadores(2, 10, "", "nome", "asc", null);

            assertEquals(25, r.total(), "o COUNT não pagina — deve ver as 25 linhas");
            assertEquals(10, r.data().size());
            assertEquals(ops.subList(10, 20).stream().map(Operador::getNomeCompleto).toList(),
                    textos(r, "nome_completo"),
                    "OFFSET 10 FETCH NEXT 10 deveria devolver o 11º ao 20º nome");

            // page 2 tem offset == limit: trocar os dois binds finais daria o MESMO resultado.
            // page 3/limit 5 (offset 10, fetch 5) desfaz a simetria e prova a ordem OFFSET→FETCH.
            PagedResult assimetrica = admin.listOperadores(3, 5, "", "nome", "asc", null);
            assertEquals(ops.subList(10, 15).stream().map(Operador::getNomeCompleto).toList(),
                    textos(assimetrica, "nome_completo"),
                    "OFFSET 10 FETCH NEXT 5 — se os binds trocassem, viriam 5 linhas a partir da 6ª");
        }

        @Test
        @DisplayName("executePagedQuery — última página parcial e página além do fim preservam o total")
        void paginacao_ultimaPaginaParcialEAlemDoFim() {
            semear25();

            PagedResult ultima = admin.listOperadores(3, 10, "", "nome", "asc", null);
            assertEquals(5, ultima.data().size(), "a 3ª página de 25 linhas tem 5");
            assertEquals(25, ultima.total());

            PagedResult vazia = admin.listOperadores(4, 10, "", "nome", "asc", null);
            assertEquals(0, vazia.data().size(), "além do fim não há linhas");
            assertEquals(25, vazia.total(), "o COUNT não depende do OFFSET");
        }

        @Test
        @DisplayName("executePagedQuery — direction=desc (case-insensitive) inverte a ordenação")
        void paginacao_direcaoDescInverteAOrdem() {
            List<Operador> ops = semear25();

            PagedResult r = admin.listOperadores(1, 3, "", "nome", "DeSc", null);

            assertEquals(List.of(ops.get(24).getNomeCompleto(), ops.get(23).getNomeCompleto(),
                    ops.get(22).getNomeCompleto()), textos(r, "nome_completo"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("search — (col COLLATE BINARY_AI) LIKE ? sobre searchCols")
    class Busca {

        /**
         * corrige F30. Contrato novo da busca: <b>acento e caixa não contam</b> — quem digita "jose"
         * acha "José", e quem digita "José" acha "Jose". Antes, o motor usava {@code UPPER(col) LIKE
         * UPPER(?)}: neutralizava a caixa e mantinha o acento, então quem procurasse a operadora
         * "Kátia" por "katia" (o jeito natural de digitar) não a encontrava.
         *
         * <p>A insensibilidade vem do {@code COLLATE BINARY_AI} <b>na expressão do LIKE</b>, não da
         * sessão — ver {@code identidade_naoAfrouxouForaDaBusca}: nenhuma outra igualdade de texto
         * do sistema mudou junto.
         */
        @Test
        @DisplayName("corrige F30 — a busca ignora caixa E acento: Jose/jose/José/josé/JOSE acham 'José'")
        void busca_ignoraCaixaEAcento() {
            // logins neutros: o termo buscado precisa existir só no NOME (a busca varre nome E e-mail)
            Operador jose = CenarioFactory.novoOperador(emReal(), "José Antônio", "alfa");
            // "Josafá" contém "josa", não "jose" — a busca ficou tolerante, não cega
            Operador josafa = CenarioFactory.novoOperador(emReal(), "Josafá Bento", "bravo");

            for (String termo : List.of("Jose", "jose", "José", "josé", "JOSE", "JOSÉ")) {
                PagedResult r = admin.listOperadores(1, 10, termo, "nome", "asc", null);
                assertEquals(1, r.total(), "o termo '" + termo + "' deveria achar 'José Antônio'");
                assertEquals(jose.getNomeCompleto(), textos(r, "nome_completo").get(0),
                        "o termo '" + termo + "' deveria achar 'José Antônio'");
            }

            PagedResult outro = admin.listOperadores(1, 10, "josa", "nome", "asc", null);
            assertEquals(1, outro.total(), "'josa' é outro nome — a busca não virou peneira");
            assertEquals(josafa.getNomeCompleto(), textos(outro, "nome_completo").get(0));
        }

        @Test
        @DisplayName("search — varre TODAS as searchCols: um termo que só existe no e-mail encontra a linha")
        void busca_cobreAColunaEmailAlemDoNome() {
            Operador alvo = CenarioFactory.novoOperador(emReal(), "Nome Neutro Um", "zulu");
            CenarioFactory.novoOperador(emReal(), "Nome Neutro Dois", "yankee");

            PagedResult r = admin.listOperadores(1, 10, "zulu", "nome", "asc", null);

            assertEquals(1, r.total(), "o OR entre as searchCols deveria alcançar o e-mail");
            assertEquals(alvo.getNomeCompleto(), textos(r, "nome_completo").get(0));
        }

        @Test
        @DisplayName("search — termo é stripado nas bordas e o COUNT reflete a busca")
        void busca_stripaEspacosEOTotalRefleteOFiltro() {
            CenarioFactory.novoOperador(emReal(), "Nome Neutro Um", "zulu");
            CenarioFactory.novoOperador(emReal(), "Nome Neutro Dois", "yankee");
            CenarioFactory.novoOperador(emReal(), "Nome Neutro Tres", "xray");

            PagedResult r = admin.listOperadores(1, 10, "   zulu   ", "nome", "asc", null);

            assertEquals(1, r.total(), "o total é o COUNT da busca, não da tabela");
            assertEquals(1, r.data().size());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("período — dateCol BETWEEN TO_DATE(?) AND TO_DATE(?)")
    class Periodo {

        private Sala sala;
        private Operador operador;
        private Checklist marcoUm;
        private Checklist marcoQuinze;
        private Checklist abrilDois;

        @BeforeEach
        void semearTresDatas() {
            sala = CenarioFactory.novaSala(emReal());
            operador = CenarioFactory.novoOperador(emReal());
            marcoUm = CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 3, 1), Turno.MATUTINO);
            marcoQuinze = CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 3, 15), Turno.MATUTINO);
            abrilDois = CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 4, 2), Turno.MATUTINO);
        }

        @Test
        @DisplayName("período — BETWEEN inclui as duas bordas")
        void periodo_bordasInclusivas() {
            PagedResult mes = admin.listChecklists(1, 10, "", "data", "asc",
                    periodo("2026-03-01", "2026-03-15"), null);
            assertEquals(2, mes.total());
            assertEquals(List.of(marcoUm.getId(), marcoQuinze.getId()), ids(mes));

            PagedResult umDia = admin.listChecklists(1, 10, "", "data", "asc",
                    periodo("2026-03-15", "2026-03-15"), null);
            assertEquals(1, umDia.total(), "start=end deve trazer o próprio dia (BETWEEN inclusivo)");
            assertEquals(marcoQuinze.getId(), ids(umDia).get(0));
        }

        @Test
        @DisplayName("período — múltiplos ranges são unidos por OR")
        void periodo_multiplosRangesSaoUnidosPorOr() {
            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc",
                    periodo("2026-03-01", "2026-03-01", "2026-04-02", "2026-04-02"), null);

            assertEquals(2, r.total());
            assertEquals(List.of(marcoUm.getId(), abrilDois.getId()), ids(r),
                    "os dois ranges disjuntos deveriam somar, sem trazer 15/03");
        }

        @Test
        @DisplayName("período — nulo ou sem ranges não filtra nada")
        void periodo_nuloOuVazioNaoFiltra() {
            assertEquals(3, admin.listChecklists(1, 10, "", "data", "asc", null, null).total());
            assertEquals(3, admin.listChecklists(1, 10, "", "data", "asc", Map.of(), null).total());
            assertEquals(3, admin.listChecklists(1, 10, "", "data", "asc",
                    Map.of("ranges", List.of()), null).total());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("filtros de coluna — text/values/range por colType")
    class FiltrosDeColuna {

        private Sala salaAlfa;
        private Sala salaBravo;
        private Operador operador;
        private Checklist alfaMatutinoQuinzeMarco;
        private Checklist alfaVespertinoDezesseisMarco;
        private Checklist bravoMatutinoDoisAbril;

        @BeforeEach
        void semearGrade() {
            salaAlfa = CenarioFactory.novaSala(emReal(), "SALA_ALFA");
            salaBravo = CenarioFactory.novaSala(emReal(), "SALA_BRAVO");
            operador = CenarioFactory.novoOperador(emReal());
            alfaMatutinoQuinzeMarco = CenarioFactory.novoChecklist(emReal(), salaAlfa, operador,
                    LocalDate.of(2026, 3, 15), Turno.MATUTINO);
            alfaVespertinoDezesseisMarco = CenarioFactory.novoChecklist(emReal(), salaAlfa, operador,
                    LocalDate.of(2026, 3, 16), Turno.VESPERTINO);
            bravoMatutinoDoisAbril = CenarioFactory.novoChecklist(emReal(), salaBravo, operador,
                    LocalDate.of(2026, 4, 2), Turno.MATUTINO);
        }

        /** Grava uma hora-do-dia real em DATA_OPERACAO (a entidade só produz meia-noite). */
        private void darHoraDoDia(Long checklistId, String dataHora) {
            int linhas = emReal().createNativeQuery(
                    "UPDATE FRM_CHECKLIST SET DATA_OPERACAO = TO_DATE(:dh, 'YYYY-MM-DD HH24:MI') WHERE ID = :id")
                    .setParameter("dh", dataHora)
                    .setParameter("id", checklistId)
                    .executeUpdate();
            assertEquals(1, linhas, "o UPDATE de hora não encontrou o checklist — INSERT não materializado?");
            emReal().clear();
        }

        @Test
        @DisplayName("filtro text — UPPER(CAST(col)) LIKE, insensível a caixa")
        void filtroTexto_emColunaDeTexto() {
            Map<String, Object> filtros = Map.of("sala", comTexto("sala_alfa"));

            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null, filtros);

            assertEquals(2, r.total());
            assertEquals(List.of(alfaMatutinoQuinzeMarco.getId(), alfaVespertinoDezesseisMarco.getId()), ids(r));
        }

        @Test
        @DisplayName("filtro values — IN (?) em coluna de texto")
        void filtroValues_inEmColunaDeTexto() {
            Map<String, Object> filtros = Map.of("turno", comValores("Vespertino"));

            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null, filtros);

            assertEquals(1, r.total());
            assertEquals(alfaVespertinoDezesseisMarco.getId(), ids(r).get(0));
        }

        @Test
        @DisplayName("filtro values — funciona sobre EXPRESSÃO derivada do colMap (o CASE/EXISTS do status)")
        void filtroValues_emExpressaoDerivadaDoColMap() {
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(emReal());
            CenarioFactory.novaResposta(emReal(), alfaMatutinoQuinzeMarco, tipo, StatusResposta.FALHA, "falhou");
            CenarioFactory.novaResposta(emReal(), alfaVespertinoDezesseisMarco, tipo, StatusResposta.OK, null);

            PagedResult falha = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("status", comValores("Falha")));
            assertEquals(1, falha.total());
            assertEquals(alfaMatutinoQuinzeMarco.getId(), ids(falha).get(0));

            PagedResult semRespostas = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("status", comValores("--")));
            assertEquals(1, semRespostas.total(), "checklist sem nenhuma resposta tem status '--'");
            assertEquals(bravoMatutinoDoisAbril.getId(), ids(semRespostas).get(0));
        }

        // A prova de que o limite superior é col < to+1 (e não col <= to) exige uma linha com
        // hora-do-dia e está em filtroData_horaDoDiaProvaOMaisUm; aqui, com dados à meia-noite,
        // só se cobre a inclusão do dia 'to' e a inclusividade da borda INFERIOR.
        @Test
        @DisplayName("filtro range (date) — inclui o dia 'to' e é inclusivo na borda inferior 'from'")
        void filtroData_rangeIncluiODiaFinalEExcluiOSeguinte() {
            PagedResult ate15 = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comIntervalo("2026-03-01", "2026-03-15")));
            assertEquals(1, ate15.total(), "o próprio dia 'to' entra");
            assertEquals(alfaMatutinoQuinzeMarco.getId(), ids(ate15).get(0));

            PagedResult ate16 = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comIntervalo("2026-03-01", "2026-03-16")));
            assertEquals(2, ate16.total(), "16/03 entra quando é o 'to'");

            // borda INFERIOR inclusiva: from = exatamente 15/03 (dia de alfaMatutino) ainda o traz.
            // A mutação col > TO_DATE(from) (perder o '=') o excluiria.
            PagedResult desde15 = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comIntervalo("2026-03-15", "2026-03-31")));
            assertTrue(ids(desde15).contains(alfaMatutinoQuinzeMarco.getId()),
                    "col >= TO_DATE('2026-03-15') é inclusivo na borda inferior");
            assertFalse(ids(desde15).contains(bravoMatutinoDoisAbril.getId()),
                    "02/04 fica fora do range que termina em 31/03");
        }

        @Test
        @DisplayName("filtro range/values (date) — hora-do-dia no 'to' distingue col < to+1 de col <= to")
        void filtroData_horaDoDiaProvaOMaisUm() {
            // Todos os checklists da fixture nascem à meia-noite (LocalDate → DATE 00:00), onde
            // (col < to+1) e (col <= to) são indistinguíveis. Uma linha às 14h de 16/03 só entra
            // pelo '+1' — a mutação "col <= TO_DATE(to)" a perderia. Força-se a hora por UPDATE
            // nativo porque a entidade Checklist mapeia LocalDate e sempre trunca para 00:00.
            Checklist tarde = CenarioFactory.novoChecklist(emReal(), salaAlfa, operador,
                    LocalDate.of(2026, 3, 16), Turno.MATUTINO);
            darHoraDoDia(tarde.getId(), "2026-03-16 14:00");

            // range: to=15/03 exclui a de 16/03-14h; to=16/03 a inclui (só via col < to+1)
            PagedResult ate15 = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comIntervalo("2026-03-01", "2026-03-15")));
            assertFalse(ids(ate15).contains(tarde.getId()), "16/03 14h não pode entrar num range que termina em 15/03");

            PagedResult ate16 = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comIntervalo("2026-03-01", "2026-03-16")));
            assertTrue(ids(ate16).contains(tarde.getId()),
                    "16/03 14h só entra porque o limite é col < TO_DATE('2026-03-16')+1, não col <= TO_DATE('2026-03-16')");

            // values: o dia 16/03 também cobre [16/03 00:00, 17/03 00:00) — pega a de 14h
            PagedResult valor16 = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comValores("2026-03-16")));
            assertTrue(ids(valor16).contains(tarde.getId()),
                    "o range [d, d+1) do filtro values também engloba a hora-do-dia");
        }

        @Test
        @DisplayName("filtro values (date) — cada data vira um par de binds [d, d+1)")
        void filtroData_valuesGeraUmRangePorDia() {
            PagedResult umDia = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comValores("2026-03-16")));
            assertEquals(1, umDia.total());
            assertEquals(alfaVespertinoDezesseisMarco.getId(), ids(umDia).get(0));

            PagedResult doisDias = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comValores("2026-03-16", "2026-04-02")));
            assertEquals(2, doisDias.total(), "condições e binds crescem juntos, na ordem dos valores");
            assertEquals(List.of(alfaVespertinoDezesseisMarco.getId(), bravoMatutinoDoisAbril.getId()),
                    ids(doisDias));
        }

        @Test
        @DisplayName("filtro text (date) — TO_CHAR(col,'DD/MM/YYYY') LIKE, não UPPER")
        void filtroData_textoUsaToCharDdMmYyyy() {
            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("data", comTexto("16/03")));

            assertEquals(1, r.total());
            assertEquals(alfaVespertinoDezesseisMarco.getId(), ids(r).get(0));
        }

        @Test
        @DisplayName("filtros — chave fora do colMap e spec que não é Map são ignorados em silêncio")
        void filtros_chaveDesconhecidaOuSpecInvalidaSaoIgnorados() {
            Map<String, Object> desconhecida = Map.of("coluna_inexistente", comValores("qualquer"));
            assertEquals(3, admin.listChecklists(1, 10, "", "data", "asc", null, desconhecida).total(),
                    "chave fora do colMap não pode virar SQL (nem quebrar)");

            Map<String, Object> specCrua = Map.of("turno", "Vespertino");
            assertEquals(3, admin.listChecklists(1, 10, "", "data", "asc", null, specCrua).total(),
                    "spec precisa ser Map {text|values|range} — string crua é ignorada");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("composição — a ORDEM dos binds é contratual (search → período → filtros → OFFSET/FETCH)")
    class ComposicaoDeBinds {

        private Sala salaAlfa;
        private Operador operador;
        private Checklist alvo;

        @BeforeEach
        void semearRuidoEAlvo() {
            salaAlfa = CenarioFactory.novaSala(emReal(), "SALA_ALFA");
            Sala salaBravo = CenarioFactory.novaSala(emReal(), "SALA_BRAVO");
            operador = CenarioFactory.novoOperador(emReal(), "Operador Zulu");

            alvo = CenarioFactory.novoChecklist(emReal(), salaAlfa, operador,
                    LocalDate.of(2026, 3, 15), Turno.MATUTINO);
            // ruídos: cada um é eliminado por exatamente UMA das três condições
            CenarioFactory.novoChecklist(emReal(), salaAlfa, operador,
                    LocalDate.of(2026, 3, 15), Turno.VESPERTINO);   // morre no filtro de turno
            CenarioFactory.novoChecklist(emReal(), salaBravo, operador,
                    LocalDate.of(2026, 3, 15), Turno.MATUTINO);     // morre no search
            CenarioFactory.novoChecklist(emReal(), salaAlfa, operador,
                    LocalDate.of(2026, 4, 2), Turno.MATUTINO);      // morre no período
        }

        @Test
        @DisplayName("composição — search + período + 2 filtros simultâneos selecionam a única linha que satisfaz tudo")
        void composicao_searchPeriodoEFiltrosSelecionamAUnicaLinha() {
            // 7 binds antes do OFFSET/FETCH: search(2: s.NOME, o.NOME_COMPLETO) + período(2)
            // + turno(1) + data(2). Se a ordem regredisse, TO_DATE(?) receberia '%SALA_ALFA%'
            // (ORA-01858) ou o LIKE receberia uma data — o resultado nunca seria este.
            Map<String, Object> filtros = new LinkedHashMap<>();
            filtros.put("turno", comValores("Matutino"));
            filtros.put("data", comValores("2026-03-15"));

            PagedResult r = admin.listChecklists(1, 10, "SALA_ALFA", "data", "asc",
                    periodo("2026-03-01", "2026-03-31"), filtros);

            assertEquals(1, r.total());
            assertEquals(alvo.getId(), ids(r).get(0));
        }

        @Test
        @DisplayName("composição — OFFSET/FETCH são os ÚLTIMOS binds (paginar não desloca os do WHERE)")
        void composicao_offsetELimitVemDepoisDosBindsDoWhere() {
            Checklist segundoMatutino = CenarioFactory.novoChecklist(emReal(), salaAlfa, operador,
                    LocalDate.of(2026, 3, 20), Turno.MATUTINO);
            Map<String, Object> filtros = Map.of("turno", comValores("Matutino"));

            PagedResult pagina1 = admin.listChecklists(1, 1, "SALA_ALFA", "data", "asc",
                    periodo("2026-03-01", "2026-03-31"), filtros);
            PagedResult pagina2 = admin.listChecklists(2, 1, "SALA_ALFA", "data", "asc",
                    periodo("2026-03-01", "2026-03-31"), filtros);

            assertEquals(2, pagina1.total(), "o COUNT vê as duas linhas filtradas");
            assertEquals(List.of(alvo.getId()), ids(pagina1));
            assertEquals(List.of(segundoMatutino.getId()), ids(pagina2),
                    "OFFSET 1 sobre o MESMO WHERE — os binds do filtro não podem ter escorregado");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("facetas — GROUPING SETS consolidado (fallback por-coluna NÃO acionado)")
    class Facetas {

        @Test
        @DisplayName("facetas — as 5 chaves do colMap, date DESC (value ISO, label dd/MM/yyyy) e as demais ASC")
        void facetas_cincoChavesComOrdenacaoPorTipo() {
            Sala alfa = CenarioFactory.novaSala(emReal(), "SALA_ALFA");
            Sala bravo = CenarioFactory.novaSala(emReal(), "SALA_BRAVO");
            Operador operador = CenarioFactory.novoOperador(emReal(), "Operador Zulu");
            CenarioFactory.novoChecklist(emReal(), alfa, operador, LocalDate.of(2026, 3, 1), Turno.MATUTINO);
            CenarioFactory.novoChecklist(emReal(), bravo, operador, LocalDate.of(2026, 3, 15), Turno.VESPERTINO);
            CenarioFactory.novoChecklist(emReal(), alfa, operador, LocalDate.of(2026, 4, 2), Turno.MATUTINO);

            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null, null);

            // a ORDEM das chaves segue a iteração do colMap (Map.of) — só o conteúdo é contratual
            assertEquals(Set.of("data", "sala", "turno", "nome", "status"), r.distinct().keySet(),
                    "as 5 facetas do colMap devem estar presentes");

            assertEquals(List.of("2026-04-02", "2026-03-15", "2026-03-01"), valoresDaFaceta(r, "data"),
                    "faceta date ordena DESC pelo value ISO");
            assertEquals(List.of("02/04/2026", "15/03/2026", "01/03/2026"), rotulosDaFaceta(r, "data"),
                    "label da faceta date é dd/MM/yyyy");
            assertEquals(List.of(alfa.getNome(), bravo.getNome()), valoresDaFaceta(r, "sala"),
                    "facetas de texto ordenam ASC");
            assertEquals(List.of("Matutino", "Vespertino"), valoresDaFaceta(r, "turno"));
            assertEquals(List.of("--"), valoresDaFaceta(r, "status"),
                    "sem respostas, a expressão derivada rende '--' para os 3");
            assertEquals(List.of(operador.getNomeCompleto()), valoresDaFaceta(r, "nome"));
        }

        @Test
        @DisplayName("facetas — são calculadas SOB o WHERE corrente (o filtro de turno encolhe a faceta de sala)")
        void facetas_respeitamOWhereCorrente() {
            Sala alfa = CenarioFactory.novaSala(emReal(), "SALA_ALFA");
            Sala bravo = CenarioFactory.novaSala(emReal(), "SALA_BRAVO");
            Operador operador = CenarioFactory.novoOperador(emReal());
            CenarioFactory.novoChecklist(emReal(), alfa, operador, LocalDate.of(2026, 3, 1), Turno.MATUTINO);
            CenarioFactory.novoChecklist(emReal(), bravo, operador, LocalDate.of(2026, 3, 15), Turno.VESPERTINO);

            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null,
                    Map.of("turno", comValores("Matutino")));

            assertEquals(List.of(alfa.getNome()), valoresDaFaceta(r, "sala"),
                    "a faceta não é cruzada: ela reflete o WHERE aplicado");
            assertEquals(List.of("Matutino"), valoresDaFaceta(r, "turno"));
        }

        @Test
        @DisplayName("facetas — a faceta sobre EXPRESSÃO derivada (status) consolida os 3 grupos distintos em ASC")
        void facetas_expressaoDerivadaComMultiplosGrupos() {
            // status é a única faceta cujo colMap é uma EXPRESSÃO (CASE/EXISTS), não coluna simples.
            // Com Falha, Ok e '--' simultâneos prova-se que o GROUPING SETS sobre o alias Gi projeta
            // e ordena a expressão corretamente — não só o grupo homogêneo '--'.
            Sala sala = CenarioFactory.novaSala(emReal(), "SALA_ALFA");
            Operador operador = CenarioFactory.novoOperador(emReal());
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(emReal());
            Checklist comFalha = CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 3, 1), Turno.MATUTINO);
            Checklist comOk = CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 3, 2), Turno.MATUTINO);
            CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 3, 3), Turno.MATUTINO); // sem resposta → '--'
            CenarioFactory.novaResposta(emReal(), comFalha, tipo, StatusResposta.FALHA, "falhou");
            CenarioFactory.novaResposta(emReal(), comOk, tipo, StatusResposta.OK, null);

            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null, null);

            // ASC lexicográfico: '-' (0x2D) < 'F' < 'O'
            assertEquals(List.of("--", "Falha", "Ok"), valoresDaFaceta(r, "status"),
                    "os 3 grupos distintos da expressão de status saem consolidados e ordenados ASC");
        }

        @Test
        @DisplayName("facetas — valores NULL do grouping set são descartados")
        void facetas_pulamNulos() {
            Sala sala = CenarioFactory.novaSala(emReal(), "SALA_ALFA");
            Operador operador = CenarioFactory.novoOperador(emReal(), "Operador Zulu");
            CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 3, 1), Turno.MATUTINO);
            // sem criador → LEFT JOIN PES_OPERADOR rende NOME_COMPLETO nulo
            CenarioFactory.novoChecklist(emReal(), sala, null, LocalDate.of(2026, 3, 2), Turno.MATUTINO);

            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null, null);

            assertEquals(2, r.total());
            assertEquals(List.of(operador.getNomeCompleto()), valoresDaFaceta(r, "nome"),
                    "o NULL do LEFT JOIN não vira item de faceta");
            assertEquals(2, valoresDaFaceta(r, "data").size(), "as demais facetas seguem completas");
        }

        @Test
        @DisplayName("facetas — listagem sem dateCol (operadores): 2 facetas de texto em ASC")
        void facetas_listOperadoresDuasFacetasTextoAsc() {
            Operador zulu = CenarioFactory.novoOperador(emReal(), "Operador Zulu", "zulu");
            Operador alfa = CenarioFactory.novoOperador(emReal(), "Operador Alfa", "alfa");

            PagedResult r = admin.listOperadores(1, 10, "", "nome", "asc", null);

            assertEquals(List.of(alfa.getNomeCompleto(), zulu.getNomeCompleto()), valoresDaFaceta(r, "nome"));
            assertEquals(List.of(alfa.getEmail(), zulu.getEmail()), valoresDaFaceta(r, "email"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("somenteDados=true — sem COUNT e sem facetas (relatórios)")
    class SomenteDados {

        @Test
        @DisplayName("somenteDados — total passa a ser o tamanho da página e o mapa distinct vem vazio")
        void somenteDados_naoContaNemFaceta() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            for (int dia = 1; dia <= 5; dia++) {
                CenarioFactory.novoChecklist(emReal(), sala, operador, LocalDate.of(2026, 3, dia), Turno.MATUTINO);
            }

            PagedResult normal = admin.listChecklists(1, 2, "", "data", "asc", null, null, false);
            PagedResult relatorio = admin.listChecklists(1, 2, "", "data", "asc", null, null, true);

            assertEquals(5, normal.total(), "a listagem normal conta a tabela inteira");
            assertFalse(normal.distinct().isEmpty());

            assertEquals(2, relatorio.data().size());
            assertEquals(2, relatorio.total(), "sem COUNT, total = linhas devolvidas (não 5)");
            assertTrue(relatorio.distinct().isEmpty(), "sem facetas em somenteDados");
            assertEquals(ids(normal), ids(relatorio), "mesmo WHERE, mesmo ORDER BY, mesma página");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("tiebreaker — desempate estável do ORDER BY")
    class Tiebreaker {

        @Test
        @DisplayName("tiebreaker — três linhas empatadas na coluna de sort saem por c.ID DESC")
        void tiebreaker_desempataPorIdDesc() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            LocalDate mesmoDia = LocalDate.of(2026, 3, 15);
            Checklist primeiro = CenarioFactory.novoChecklist(emReal(), sala, operador, mesmoDia, Turno.MATUTINO);
            Checklist segundo = CenarioFactory.novoChecklist(emReal(), sala, operador, mesmoDia, Turno.MATUTINO);
            Checklist terceiro = CenarioFactory.novoChecklist(emReal(), sala, operador, mesmoDia, Turno.MATUTINO);

            PagedResult r = admin.listChecklists(1, 10, "", "data", "asc", null, null);

            assertEquals(List.of(terceiro.getId(), segundo.getId(), primeiro.getId()), ids(r),
                    "empate em DATA_OPERACAO → a ordem é a do tiebreaker (c.ID DESC), não a de inserção");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("colação pt-BR (F30) — ordenação, paridade dev↔prod e o limite da mudança")
    class ColacaoPtBr {

        /**
         * O conjunto adversarial. Os pares acentuado/sem acento provam o pedido (acento com peso
         * zero); o <b>"2 Discriminador Numerico"</b> é o que dá dentes ao teste, e merece explicação.
         *
         * <p>Sem um valor começando por DÍGITO, este teste passaria em dev mesmo com a fixação do
         * {@code NLS_SORT} removida — e produção continuaria quebrada. Motivo: a JVM de dev roda em
         * pt_BR, então o driver deriva {@code WEST_EUROPEAN}, uma colação linguística que TAMBÉM
         * agrupa acentos; a máquina de desenvolvimento mascara o defeito (é exatamente por isso que o
         * F30 sobreviveu tanto tempo). O dígito separa as três: {@code BINARY_AI} o põe ANTES das
         * letras, {@code WEST_EUROPEAN} o joga para DEPOIS de "Zulmira", e {@code BINARY} (produção)
         * ainda por cima manda os acentuados para o fim. Medido no Oracle real (C15).
         */
        private static final List<String> NOMES_ADVERSARIAIS = List.of(
                "Zulmira Zebra", "Álvaro Alfa", "Alvaro Bravo", "Ângela Charlie", "Angela Delta",
                "Ana Echo", "2 Discriminador Numerico");

        /**
         * A ordem pt-BR CONTRATADA (decisão do Douglas): o acento tem peso ZERO — não é "desempate por
         * acento". Repare que "Álvaro Alfa" vem ANTES de "Alvaro Bravo" e "Ângela Charlie" antes de
         * "Angela Delta": quem decide é o resto do nome ("alfa" &lt; "bravo"), como se o acento não
         * existisse. Sob a colação BINARY que produção usava, "Álvaro" (0xC1) e "Ângela" (0xC2)
         * cairiam no FIM da lista, depois de "Zulmira" — o F30 tal como o usuário o via.
         */
        private static final List<String> ORDEM_PT_BR = List.of(
                "2 Discriminador Numerico", "Álvaro Alfa", "Alvaro Bravo", "Ana Echo",
                "Ângela Charlie", "Angela Delta", "Zulmira Zebra");

        /**
         * Operador com o nome EXATO — o {@code CenarioFactory} sufixa o nome com um discriminador para
         * garantir unicidade, e aqui o nome é a própria matéria do teste: a ordem tem de ser decidida
         * pelo nome inteiro, e os pares que COLIDEM na chave sem-acento ("José Silva"/"Jose Silva") só
         * colidem se nada os diferenciar no fim. O login segue vindo do factory (único).
         */
        private Operador comNomeExato(String nome, String login) {
            Operador op = CenarioFactory.novoOperador(emReal(), "Semente", login);
            op.setNomeCompleto(nome);
            emReal().flush();
            return op;
        }

        private void semearAdversariais() {
            int i = 0;
            for (String nome : NOMES_ADVERSARIAIS) comNomeExato(nome, "adv" + i++);
        }

        /**
         * A régua: a ordem que o próprio Oracle chama de BINARY_AI, pedida a ele com {@code NLSSORT}
         * EXPLÍCITO. Não pode vir de {@code String.compareTo} (foi o locale do Java que gerou o F30),
         * nem da colação da sessão — que é justamente o que está sob teste: se alguém remover a
         * fixação do {@code NLS_SORT}, a listagem volta a BINARY e esta régua NÃO, e o teste cai.
         */
        @SuppressWarnings("unchecked")
        private List<String> ordemPtBrSegundoOBanco() {
            return emReal().createNativeQuery(
                    "SELECT NOME_COMPLETO FROM PES_OPERADOR"
                            + " ORDER BY NLSSORT(NOME_COMPLETO, 'NLS_SORT=BINARY_AI'),"
                            + "          NLSSORT(NOME_COMPLETO, 'NLS_SORT=BINARY')").getResultList();
        }

        private String nlsDaSessao(String parametro) {
            return (String) emReal().createNativeQuery(
                    "SELECT value FROM nls_session_parameters WHERE parameter = ?")
                    .setParameter(1, parametro).getSingleResult();
        }

        @Test
        @DisplayName("corrige F30 — ordenação pt-BR: o acentuado sai JUNTO do par sem acento, não depois do 'Z'")
        void ordenacao_acentuadosJuntoDosParesSemAcento() {
            semearAdversariais();

            PagedResult r = admin.listOperadores(1, 10, "", "nome", "asc", null);

            assertEquals(ORDEM_PT_BR, ordemPtBrSegundoOBanco(),
                    "a ordem contratada tem de ser a que o Oracle chama de BINARY_AI — se não for, é a"
                            + " expectativa deste teste que está errada, não o motor");
            assertEquals(ORDEM_PT_BR, textos(r, "nome_completo"),
                    "a listagem tem de sair na ordem pt-BR: sob BINARY (o que produção fazia), 'Álvaro'"
                            + " e 'Ângela' viriam depois de 'Zulmira'");
        }

        /**
         * §5.5 — a contramedida contra a regressão silenciosa, no lado JAVA da colação: a faceta é
         * ordenada em memória, e a chave ({@code semAcento}) usa {@code toLowerCase(Locale.ROOT)}.
         * Sem o {@code Locale.ROOT}, o lowercase passaria a depender do locale default da JVM — a
         * MESMA doença do F30, na outra ponta: a lista mudaria conforme quem roda o processo.
         *
         * <p>⚠️ O locale hostil é {@code tr-TR} de propósito, e não {@code en-US}: para o alfabeto
         * latino, {@code en-US} e {@code pt-BR} produzem lowercase IDÊNTICO — um teste que comparasse
         * esses dois passaria mesmo com o bug (foi o que este teste fazia até a revisão do C15 pegá-lo).
         * É o turco que mapeia 'I' → 'ı' (U+0131), e aí a faceta sairia fora de ordem.
         *
         * <p>⚠️ O que este teste NÃO é: ele não reabre conexão sob cada locale (o pool já está
         * quente), então não exercita a derivação do {@code NLS_SORT} pelo driver. Quem prova que a
         * fixação da SESSÃO venceu essa derivação é o {@code identidade_naoAfrouxouForaDaBusca},
         * lendo {@code nls_session_parameters}. Os dois juntos fecham a paridade — um o lado do
         * banco, outro o lado do Java.
         */
        @Test
        @DisplayName("guarda de paridade (F30) — a faceta ordenada em Java não muda com o locale da JVM")
        void ordenacao_naoDependeDoLocaleDaJvm() {
            for (String nome : List.of("Ítalo Iris", "Ivan Ipsilon", "Ana Echo")) comNomeExato(nome, "loc" + nome.charAt(1));

            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));   // o locale que quebra toLowerCase()
                PagedResult sobLocaleHostil = admin.listOperadores(1, 10, "", "nome", "asc", null);
                Locale.setDefault(Locale.forLanguageTag("pt-BR"));
                PagedResult sobLocaleDeDev = admin.listOperadores(1, 10, "", "nome", "asc", null);

                List<String> esperado = List.of("Ana Echo", "Ítalo Iris", "Ivan Ipsilon");
                assertEquals(esperado, valoresDaFaceta(sobLocaleHostil, "nome"),
                        "sob tr-TR, um semAcento sem Locale.ROOT devolveria 'ıtalo' e jogaria o nome"
                                + " para fora do lugar — a faceta não pode depender de quem roda a JVM");
                assertEquals(valoresDaFaceta(sobLocaleDeDev, "nome"), valoresDaFaceta(sobLocaleHostil, "nome"),
                        "e as duas execuções têm de dar a mesma lista");
                assertEquals(esperado, textos(sobLocaleDeDev, "nome_completo"),
                        "a listagem (ordenada pelo BANCO) concorda com a faceta (ordenada em Java)");
            } finally {
                Locale.setDefault(original);
            }
        }

        /**
         * O caractere que quase escapou (F30). O Oracle, sob {@code BINARY_AI}, trata o ordinal
         * {@code ª} como um {@code a} — mas o {@code Normalizer.NFD} do Java NÃO o decompõe, e 0xAA
         * ordena depois do "z". O sistema tem CENTENAS de {@code NOME_EVENTO} assim ("1ª Reunião" —
         * 229 linhas em OPR_REGISTRO_ENTRADA, 6 em OPR_ANORMALIDADE no espelho de produção), e
         * {@code NOME_EVENTO} é faceta E coluna de busca: com NFD, o dropdown de filtro (ordenado em
         * Java) mostraria "1ª Reunião" no fim enquanto a coluna (ordenada no banco) a mostra junto dos
         * "1a…". Seria o F30 reintroduzido dentro da própria correção. Por isso {@code semAcento} usa
         * NFKD — e por isso este teste semeia o vizinho "1z", que é quem denuncia a diferença.
         */
        @Test
        @DisplayName("corrige F30 — o ordinal (1ª Reunião) ordena como '1a' nos DOIS motores, não depois do 'z'")
        void ordenacao_ordinalConcordaEntreBancoEJava() {
            int i = 0;
            for (String nome : List.of("1ª Reuniao", "1a Sessao", "1o Turno", "1z Extra")) comNomeExato(nome, "ord" + i++);

            PagedResult r = admin.listOperadores(1, 10, "", "nome", "asc", null);

            List<String> esperado = List.of("1ª Reuniao", "1a Sessao", "1o Turno", "1z Extra");
            assertEquals(esperado, textos(r, "nome_completo"), "a listagem (banco) põe o ordinal junto do 'a'");
            assertEquals(esperado, valoresDaFaceta(r, "nome"),
                    "e a faceta (Java) tem de concordar — com NFD, '1ª Reuniao' cairia no FIM");
        }

        /**
         * §3.3 — o limite da decisão, feito teste. A busca ficou insensível a acento/caixa por
         * EXPRESSÃO ({@code COLLATE BINARY_AI} no LIKE). A tentação seria fazê-lo pela sessão
         * ({@code NLS_COMP=LINGUISTIC}), que dá a mesma busca de graça — e junto, sem ninguém pedir:
         * 'douglas' passaria a casar 'DOUGLAS' e 'dóuglas' no LOGIN, o DISTINCT que alimenta as
         * próprias facetas colapsaria 'Jose' com 'José', o GROUP BY dos dashboards mudaria de contagem
         * e a unicidade de username/e-mail passaria a acusar duplicata onde não há (tudo medido no
         * Oracle real, C15). Esta testemunha é estrutural: se alguém "simplificar" o
         * connection-init-sql, a busca continua verde e é AQUI que o alarme toca.
         */
        @Test
        @DisplayName("§3.3 — a identidade textual NÃO afrouxou: a sessão fixa o NLS_SORT, jamais o NLS_COMP")
        void identidade_naoAfrouxouForaDaBusca() {
            assertEquals("BINARY_AI", nlsDaSessao("NLS_SORT"),
                    "o connection-init-sql do Hikari (application.yml) não chegou à sessão — sem ele a"
                            + " colação volta a ser a que o driver deriva do locale da JVM (o F30)");
            assertEquals("BINARY", nlsDaSessao("NLS_COMP"),
                    "NLS_COMP=LINGUISTIC tornaria TODA igualdade de texto do sistema insensível a"
                            + " acento e caixa — login, unicidade, DISTINCT das facetas, GROUP BY");

            Number acentoIgnorado = (Number) emReal().createNativeQuery(
                    "SELECT COUNT(*) FROM dual WHERE 'douglas' = 'dóuglas'").getSingleResult();
            assertEquals(0, acentoIgnorado.intValue(),
                    "a igualdade continua sensível ao ACENTO — é o que protege o login (a CAIXA ele já"
                            + " ignora de propósito, por LOWER()=LOWER(); ver"
                            + " AuthServiceIT.findUserForLogin_acentoNaoEhIgnoradoNaAutenticacao)");

            Number distintos = (Number) emReal().createNativeQuery(
                    "SELECT COUNT(DISTINCT c) FROM (SELECT 'Jose' c FROM dual UNION ALL SELECT 'José' c FROM dual)")
                    .getSingleResult();
            assertEquals(2, distintos.intValue(),
                    "'Jose' e 'José' seguem valores DISTINTOS — a lista de facetas mostra os dois, e é"
                            + " por isso que marcar um deles (IN exato) não pode trazer o outro");
        }

        /**
         * §5.4 — a decisão 5, provada: o caminho consolidado ordena as facetas <b>em Java</b> e o
         * fallback por-coluna as ordena <b>no banco</b>. São dois motores de colação diferentes, e
         * confiar que concordam "no universal" seria ingênuo — eles concordam no domínio real porque
         * o par foi escolhido para isso: {@code BINARY_AI} (acento e caixa com peso zero, resto em
         * binário) do lado do Oracle e {@code semAcento(v)} + desempate pelo valor cru do lado do Java.
         *
         * <p>O fallback é chamado DIRETO: pelo caminho normal só se chega a ele quebrando a
         * consolidação, e aí o WARN derrubaria o teste no {@link #nenhumWarnDeFaceta()}.
         */
        @Test
        @DisplayName("F30 — faceta consolidada (Java) e fallback por-coluna (banco) saem IDÊNTICAS no domínio adversarial")
        void facetas_consolidadoEFallbackConcordam() {
            // Três coisas no mesmo cenário: (a) o par acentuado; (b) o caso que só o desempate resolve
            // — 'Jose' e 'José' são DISTINCT diferentes (a igualdade é binária) que COLIDEM na chave
            // AI, e sem desempate estável um motor os devolveria numa ordem e o outro na outra; (c) o
            // "2 Numerico" que amarra o PAR (NLS_SORT, comparador): trocar a colação da sessão por
            // outra plausível (WEST_EUROPEAN_AI manda dígito para o fim) faria o banco discordar do
            // Java aqui. Nomes EXATOS, senão o sufixo do factory desfaria a colisão de (b).
            int i = 0;
            for (String nome : List.of("José Silva", "Jose Silva", "JOSE SILVA", "Ângela Costa",
                    "Angela Costa", "2 Numerico Discriminador")) {
                comNomeExato(nome, "conc" + i++);
            }

            PagedResult consolidado = admin.listOperadores(1, 50, "", "nome", "asc", null);

            Map<String, List<Map<String, String>>> fallback = DashboardQueryHelper.fetchDistinctPorColuna(
                    emReal(), "FROM PES_OPERADOR o", "", List.of(),
                    List.of(Map.entry("nome", "o.NOME_COMPLETO"), Map.entry("email", "o.EMAIL")),
                    Map.of("nome", "text", "email", "text"));

            assertEquals(fallback.get("nome"), consolidado.distinct().get("nome"),
                    "as duas facetas de nome têm de ser item a item iguais — se divergirem, a listagem"
                            + " muda de ordem quando o motor cai no fallback");
            assertEquals(fallback.get("email"), consolidado.distinct().get("email"),
                    "idem para a faceta de e-mail");
            assertEquals(6, consolidado.distinct().get("nome").size(),
                    "as 6 grafias continuam 6 itens distintos na lista (a colação ordena; não funde)");
        }

        /**
         * O filtro textual POR COLUNA é o mesmo gesto do campo de busca — o usuário digita um trecho —
         * e ganhou a mesma colação. Sem este teste, reverter só o {@code appendTextFilter} deixaria a
         * suíte inteira verde (o único teste do ramo, {@code filtroTexto_emColunaDeTexto}, usa termo
         * sem acento e passa dos dois jeitos).
         *
         * <p>O filtro por VALORES (o item marcado na lista de facetas) é o oposto e está no mesmo
         * teste de propósito: ele tem de continuar EXATO. Se alguém "uniformizar" e aplicar COLLATE
         * nele também, marcar "Jose Silva" passaria a trazer as três grafias — e a lista de facetas,
         * que segue mostrando as três como itens distintos, contradiria o próprio filtro.
         */
        @Test
        @DisplayName("F30 — filtro POR COLUNA ignora acento (o usuário digita); filtro por VALORES continua EXATO")
        void filtros_textoDigitadoIgnoraAcentoMasValorMarcadoEhExato() {
            Operador jose = comNomeExato("José Silva", "fil0");
            Operador semAcento = comNomeExato("Jose Silva", "fil1");
            comNomeExato("Zulmira Zebra", "fil2");

            PagedResult digitado = admin.listOperadores(1, 10, "", "nome", "asc",
                    Map.of("nome", comTexto("jose silva")));
            assertEquals(2, digitado.total(),
                    "digitar 'jose silva' no filtro de coluna acha as duas grafias — mesma regra da busca");

            PagedResult marcado = admin.listOperadores(1, 10, "", "nome", "asc",
                    Map.of("nome", comValores(jose.getNomeCompleto())));
            assertEquals(1, marcado.total(),
                    "marcar 'José Silva' na lista traz SÓ ele: o item marcado é igualdade exata");
            assertEquals(List.of(jose.getNomeCompleto()), textos(marcado, "nome_completo"));

            PagedResult marcadoSemAcento = admin.listOperadores(1, 10, "", "nome", "asc",
                    Map.of("nome", comValores(semAcento.getNomeCompleto())));
            assertEquals(List.of(semAcento.getNomeCompleto()), textos(marcadoSemAcento, "nome_completo"),
                    "e marcar 'Jose Silva' traz só o sem acento — os dois são itens distintos da lista");
        }

        /**
         * A colação AI criou um empate que antes não existia: "José Silva" e "JOSE SILVA" são linhas
         * DIFERENTES com a MESMA chave de ordenação (sob BINARY, a chave era total). Chave não-total
         * + {@code OFFSET/FETCH} é paginação sem garantia: o Oracle não promete estabilidade entre
         * duas execuções de um sort com empates, e uma linha poderia sair em duas páginas enquanto
         * outra some. O desempate binário do {@code buildOrderBy} restaura a ordem TOTAL — e, de
         * quebra, a faz coincidir com a que o comparador Java produz para a faceta.
         *
         * <p>⚠️ <b>Honestidade sobre o alcance deste teste:</b> ele fixa a ordem total esperada, mas
         * NÃO detecta a remoção do desempate — medido (mutação M10 do C15): sem ele, o Oracle ainda
         * devolve estes 4 nomes na mesma ordem, porque com tão poucas linhas o plano é determinístico
         * na prática. A garantia é estrutural (ordem total no SQL), não observável neste volume; um
         * plano diferente (mais dados, índice, paralelismo) é que a exporia. O que este teste guarda
         * de fato é o CONTRATO: as 4 grafias saem uma vez cada, na ordem binária, e a paginação
         * concatena exatamente a lista inteira.
         */
        @Test
        @DisplayName("F30 — nomes que colidem na chave sem-acento têm ordem total: paginam sem repetir nem sumir")
        void paginacao_ordemTotalComNomesQueColidemNaChaveAi() {
            for (String nome : List.of("José Silva", "Jose Silva", "JOSE SILVA", "josé silva")) {
                comNomeExato(nome, "pag" + Math.abs(nome.hashCode()));
            }

            List<String> pagina1 = textos(admin.listOperadores(1, 2, "", "nome", "asc", null), "nome_completo");
            List<String> pagina2 = textos(admin.listOperadores(2, 2, "", "nome", "asc", null), "nome_completo");

            List<String> tudo = new ArrayList<>(pagina1);
            tudo.addAll(pagina2);
            assertEquals(List.of("JOSE SILVA", "Jose Silva", "José Silva", "josé silva"), tudo,
                    "as 4 grafias colidem na chave sem-acento; o desempate binário as põe em ordem"
                            + " total — a mesma que o comparador Java dá à faceta");
            assertEquals(textos(admin.listOperadores(1, 10, "", "nome", "asc", null), "nome_completo"), tudo,
                    "e a concatenação das páginas é a lista inteira, sem repetir nem omitir");
            assertEquals(tudo, valoresDaFaceta(admin.listOperadores(1, 10, "", "nome", "asc", null), "nome"),
                    "a faceta (ordenada em Java) concorda com as linhas (ordenadas no banco)");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("motor direto — baseParams, fromJoins com WHERE e colType bool")
    class MotorDireto {

        private static final Map<String, String> SORT_NOME = new LinkedHashMap<>() {{
            put("nome", "o.NOME_COMPLETO");
            put("email", "o.EMAIL");
        }};

        /** Mesmo shape do ownership de OperadorDashboardService: o WHERE mora no fromJoins, com '?'. */
        private PagedResult porIds(String search, Map<String, Object> filters, List<Object> baseParams) {
            String placeholders = String.join(" OR ", baseParams.stream().map(p -> "o.ID = ?").toList());
            return DashboardQueryHelper.executePagedQuery(emReal(),
                    "o.ID, o.NOME_COMPLETO, o.EMAIL, o.PLENARIO_PRINCIPAL",
                    "FROM PES_OPERADOR o WHERE (" + placeholders + ")",
                    null, SORT_NOME, List.of("o.NOME_COMPLETO"),
                    Map.of("pp", "o.PLENARIO_PRINCIPAL"), Map.of("pp", "bool"),
                    1, 10, search, "nome", "asc", null, filters, null, false, baseParams);
        }

        @Test
        @DisplayName("baseParams — os binds do fromJoins são PREPENDADOS aos do WHERE dinâmico")
        void baseParams_precedemOsBindsDoWhereDinamico() {
            Operador alfa = CenarioFactory.novoOperador(emReal(), "Operador Alfa", "alfa");
            Operador bravo = CenarioFactory.novoOperador(emReal(), "Operador Bravo", "bravo");
            CenarioFactory.novoOperador(emReal(), "Operador Charlie", "charlie"); // fora dos baseParams

            PagedResult r = porIds("alfa", null, List.of(alfa.getId(), bravo.getId()));

            // Se o '%ALFA%' do search caísse no 1º '?' (o.ID = '%ALFA%'), nada seria devolvido.
            assertEquals(1, r.total(), "baseParams primeiro, depois o termo de busca");
            assertEquals(alfa.getNomeCompleto(), textos(r, "nome_completo").get(0));
        }

        @Test
        @DisplayName("fromJoins com WHERE e nenhuma condição dinâmica ainda produz SQL válido")
        void fromJoinsComWhere_semCondicoesDinamicas() {
            Operador alfa = CenarioFactory.novoOperador(emReal(), "Operador Alfa", "alfa");
            Operador bravo = CenarioFactory.novoOperador(emReal(), "Operador Bravo", "bravo");

            PagedResult r = porIds(null, null, List.of(alfa.getId(), bravo.getId()));

            assertEquals(2, r.total());
            assertEquals(List.of(alfa.getId(), bravo.getId()),
                    r.data().stream().map(linha -> (String) linha.get("id")).toList());
        }

        @Test
        @DisplayName("colType bool — filtro converte 'true'/'false' para 1/0 e a faceta rende Sim/Não em ASC")
        void bool_filtroConverteEFacetaRotula() {
            Operador alfa = CenarioFactory.novoOperador(emReal(), "Operador Alfa", "alfa");
            Operador bravo = CenarioFactory.novoOperador(emReal(), "Operador Bravo", "bravo");
            alfa.setPlenarioPrincipal(true);
            emReal().flush();
            List<Object> ambos = List.of(alfa.getId(), bravo.getId());

            PagedResult semFiltro = porIds(null, null, ambos);
            assertEquals(List.of("false", "true"), valoresDaFaceta(semFiltro, "pp"),
                    "faceta bool ordena ASC pelo value ('false' < 'true')");
            assertEquals(List.of("Não", "Sim"), rotulosDaFaceta(semFiltro, "pp"));

            PagedResult soPP = porIds(null, Map.of("pp", comValores("true")), ambos);
            assertEquals(1, soPP.total(), "'true' vira o literal 1 do NUMBER(1)");
            assertEquals(alfa.getNomeCompleto(), textos(soPP, "nome_completo").get(0));

            // o ramo 'false'→0 é distinto: a mutação (? 1 : 1) traria alfa aqui em vez de bravo
            PagedResult soNaoPP = porIds(null, Map.of("pp", comValores("false")), ambos);
            assertEquals(1, soNaoPP.total(), "'false' vira o literal 0");
            assertEquals(bravo.getNomeCompleto(), textos(soNaoPP, "nome_completo").get(0));
        }

        @Test
        @DisplayName("mapRows/convertValue — alias ' AS ' vira a chave, decimal não-inteiro preserva o número e NULL vira null")
        void mapRows_aliasEConversaoDeValores() {
            Operador alfa = CenarioFactory.novoOperador(emReal(), "Operador Alfa", "alfa");

            // colunas com alias e tipos que os veículos operadores/checklists não produzem:
            // CAST(1.5) exercita o ramo decimal de convertValue; NULL o ramo nulo; 'fracao'/'vazio'
            // o ramo de extração de alias de parseColumnNames (sem alias, a chave seria a expressão crua).
            PagedResult r = DashboardQueryHelper.executePagedQuery(emReal(),
                    "o.ID, CAST(1.5 AS NUMBER) AS fracao, CAST(NULL AS VARCHAR2(10)) AS vazio, o.NOME_COMPLETO",
                    "FROM PES_OPERADOR o WHERE (o.ID = ?)",
                    null, SORT_NOME, List.of("o.NOME_COMPLETO"), Map.of(), Map.of(),
                    1, 10, null, "nome", "asc", null, null, null, false, List.of(alfa.getId()));

            assertEquals(1, r.data().size());
            Map<String, Object> linha = r.data().get(0);
            assertTrue(linha.containsKey("fracao"), "o alias ' AS fracao' deveria virar a chave, não a expressão CAST crua");
            assertTrue(linha.containsKey("vazio"));
            assertTrue(linha.get("fracao") instanceof Number, "decimal não-inteiro é preservado como Number");
            assertEquals(1.5, ((Number) linha.get("fracao")).doubleValue(), 1e-9,
                    "1.5 não colapsa para Long (longValue != doubleValue)");
            assertNull(linha.get("vazio"), "NULL do banco vira null no mapa");
            assertEquals(alfa.getNomeCompleto(), linha.get("nome_completo"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("ORDER BY default quando o sort pedido não está em validSortCols")
    class SortDefault {

        @Test
        @DisplayName("sort inválido — cai na PRIMEIRA entrada da iteração de validSortCols (determinístico com LinkedHashMap)")
        void sortInvalido_usaAPrimeiraColunaDaIteracao() {
            Operador zulu = CenarioFactory.novoOperador(emReal(), "Operador Zulu", "alfa");
            Operador alfa = CenarioFactory.novoOperador(emReal(), "Operador Alfa", "zulu");
            Map<String, String> sortCols = new LinkedHashMap<>();
            sortCols.put("nome", "o.NOME_COMPLETO");
            sortCols.put("email", "o.EMAIL");

            PagedResult r = DashboardQueryHelper.executePagedQuery(emReal(),
                    "o.ID, o.NOME_COMPLETO, o.EMAIL",
                    "FROM PES_OPERADOR o WHERE (o.ID = ? OR o.ID = ?)",
                    null, sortCols, List.of("o.NOME_COMPLETO"), Map.of(), Map.of(),
                    1, 10, null, "coluna_que_nao_existe", "asc", null, null, null, false,
                    List.of(zulu.getId(), alfa.getId()));

            assertEquals(List.of(alfa.getNomeCompleto(), zulu.getNomeCompleto()),
                    textos(r, "nome_completo"),
                    "sort desconhecido → primeira entrada do map (nome), nunca erro nem ORDER BY ausente");
        }

        @Test
        @DisplayName("corrige F16 — sort inválido em listOperadores ordena SEMPRE por NOME (OP_SORT é LinkedHashMap)")
        void sortInvalido_ordemDefaultEstavelPorNome() {
            // F16 (§5 do plano): OP_SORT/TEC_SORT/ADM_SORT eram Map.of, cuja ordem de iteração é
            // randomizada por JVM (SALT) — e buildOrderBy resolve o sort desconhecido com o PRIMEIRO
            // valor do map. A mesma chamada ordenava por NOME_COMPLETO numa JVM e por EMAIL na
            // seguinte. Agora são LinkedHashMap com "nome" primeiro (paridade com o
            // defaultValue="nome" das rotas): a ordem default é estável entre deploys.
            // Semear em ordem que NÃO coincida com nome nem e-mail: assim a ausência de ORDER BY
            // (rowid ≈ inserção) também é rejeitada — o teste prova que HÁ ordenação, e qual.
            Operador x = CenarioFactory.novoOperador(emReal(), "Nome Alfa", "charlie");   // 1ª a inserir
            Operador z = CenarioFactory.novoOperador(emReal(), "Nome Charlie", "bravo");  // 2ª
            Operador y = CenarioFactory.novoOperador(emReal(), "Nome Bravo", "alfa");     // 3ª

            PagedResult r = admin.listOperadores(1, 10, "", "coluna_que_nao_existe", "asc", null);

            List<String> obtida = textos(r, "nome_completo");
            List<String> porNome = List.of(x.getNomeCompleto(), y.getNomeCompleto(), z.getNomeCompleto());
            List<String> ordemDeInsercao = List.of(x.getNomeCompleto(), z.getNomeCompleto(), y.getNomeCompleto());
            assertNotEquals(ordemDeInsercao, obtida,
                    "a ordem de inserção não pode 'vazar' — sem ORDER BY o teste seria inútil");
            assertEquals(porNome, obtida,
                    "sort desconhecido → NOME_COMPLETO, a 1ª entrada do LinkedHashMap; veio " + obtida);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("checklistItemMap — 8 chaves em ordem fixa (unitário, sem banco)")
    class ChecklistItemMap {

        @Test
        @DisplayName("checklistItemMap — as 8 chaves saem sempre na mesma ordem")
        void checklistItemMap_ordemFixaDasChaves() {
            Map<String, Object> item = DashboardQueryHelper.checklistItemMap(
                    1L, 2L, "Microfone", "Ok", "sem falha", "texto livre", true, "radio");

            assertEquals(List.of("id", "item_tipo_id", "item_nome", "status",
                    "descricao_falha", "valor_texto", "editado", "tipo_widget"),
                    List.copyOf(item.keySet()));
        }

        @Test
        @DisplayName("checklistItemMap — descricao_falha/valor_texto nulos viram \"\"; os demais nulos passam")
        void checklistItemMap_defaultsVaziosApenasNessesDois() {
            Map<String, Object> item = DashboardQueryHelper.checklistItemMap(
                    null, null, null, null, null, null, false, null);

            assertEquals("", item.get("descricao_falha"));
            assertEquals("", item.get("valor_texto"));
            assertEquals(false, item.get("editado"));
            assertTrue(item.containsKey("status"), "status nulo é preservado como chave");
            assertNull(item.get("status"));
            assertNull(item.get("tipo_widget"));
        }

        @Test
        @DisplayName("checklistItemMap — valores não nulos são preservados como vieram")
        void checklistItemMap_preservaValoresNaoNulos() {
            Map<String, Object> item = DashboardQueryHelper.checklistItemMap(
                    "10", 2L, "Cabo", "Falha", "cabo rompido", "obs", true, "text");

            assertEquals("10", item.get("id"));
            assertEquals(2L, item.get("item_tipo_id"));
            assertEquals("Cabo", item.get("item_nome"));
            assertEquals("Falha", item.get("status"));
            assertEquals("cabo rompido", item.get("descricao_falha"));
            assertEquals("obs", item.get("valor_texto"));
            assertEquals(true, item.get("editado"));
            assertEquals("text", item.get("tipo_widget"));
        }
    }
}
