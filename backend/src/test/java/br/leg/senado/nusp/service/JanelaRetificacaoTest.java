package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A janela de retificação: quais dias de uma pessoa ainda aceitam correção, decidido só pelo que foi
 * publicado para ela.
 *
 * <p>Sem mocks — a classe é uma conta sobre as folhas publicadas, e é essa conta que interessa
 * provar: o dia fica aberto enquanto alguma folha vigente o cobre, semanais e prévias se acumulam
 * sem fechar nada, e só a definitiva encerra os dias que imprimiu — para sempre.
 *
 * <p>É a COBERTURA que decide, não a data de publicação: reenviar ou acumular folhas sobre o mesmo
 * dia não o fecha. A data de publicação só é usada para escolher qual folha representa o dia.
 */
class JanelaRetificacaoTest {

    private static final String PESSOA = "op-1";
    private static final String TIPO_PESSOA = "OPERADOR";
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 8, 10, 9, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 8, 17, 9, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 8, 24, 9, 0);
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 8, 25, 9, 0);

    private static LocalDate dia(int d) {
        return LocalDate.of(2026, 8, d);
    }

    // ══ Fixtures ══════════════════════════════════════════════════

    /** Um par [página, lote] como a consulta das folhas publicadas do dono o devolve. */
    private static Object[] folha(String loteId, String tipo, String categoria,
                                  LocalDate inicio, LocalDate fim, LocalDateTime publicadoEm) {
        return folha(loteId, tipo, categoria, inicio, fim, publicadoEm, PESSOA, TIPO_PESSOA);
    }

    private static Object[] folha(String loteId, String tipo, String categoria,
                                  LocalDate inicio, LocalDate fim, LocalDateTime publicadoEm,
                                  String pessoaId, String pessoaTipo) {
        PontoLote lote = new PontoLote();
        lote.setId(loteId);
        lote.setTipo(tipo);
        lote.setCategoria(categoria);
        lote.setDataInicio(inicio);
        lote.setDataFim(fim);
        lote.setStatus("PUBLICADO");
        lote.setPublicadoEm(publicadoEm);
        lote.setCriadoEm(publicadoEm);

        PontoLotePagina pagina = new PontoLotePagina();
        pagina.setId("pag-" + loteId);
        pagina.setLoteId(loteId);
        pagina.setPessoaId(pessoaId);
        pagina.setPessoaTipo(pessoaTipo);
        return new Object[] {pagina, lote};
    }

    private static Object[] semanal(String loteId, int inicio, int fim, LocalDateTime publicadoEm) {
        return folha(loteId, "SEMANAL", null, dia(inicio), dia(fim), publicadoEm);
    }

    /** Mensal do mês inteiro — o período que a folha da Plansul costuma trazer. */
    private static Object[] mensal(String loteId, String categoria, LocalDateTime publicadoEm) {
        return mensal(loteId, categoria, 1, 31, publicadoEm);
    }

    /** Mensal com o período digitado à mão, menor que o mês. */
    private static Object[] mensal(String loteId, String categoria, int inicio, int fim,
                                   LocalDateTime publicadoEm) {
        return folha(loteId, "MENSAL", categoria, dia(inicio), dia(fim), publicadoEm);
    }

    private static JanelaRetificacao janela(Object[]... folhas) {
        return JanelaRetificacao.daPessoa(List.of(folhas), PESSOA, TIPO_PESSOA);
    }

    /** Os dias abertos do intervalo, para comparar o recorte inteiro de uma vez. */
    private static List<Integer> abertos(JanelaRetificacao janela, int de, int ate) {
        List<Integer> out = new ArrayList<>();
        for (int d = de; d <= ate; d++) {
            if (janela.aberto(dia(d))) out.add(d);
        }
        return out;
    }

    // ══ Semanais cumulativas ══════════════════════════════════════

    @Test
    void aUnicaSemanalPublicadaAbreTodoOPeriodoDela() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, T1));

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), abertos(janela, 1, 16));
    }

    @Test
    void semanalNovaNaoFechaOsDiasDaAnterior() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, T1), semanal("b", 1, 16, T2));

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
                abertos(janela, 1, 16));
    }

    @Test
    void todosOsDiasDasSemanaisAcumuladasSeguemAbertos() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, T1), semanal("b", 1, 16, T2),
                semanal("c", 1, 23, T3));

        assertEquals(23, abertos(janela, 1, 23).size());
    }

    @Test
    void diaSemFolhaNenhumaNaoAbre() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, T1));

        assertFalse(janela.aberto(dia(20)));
        assertEquals(JanelaRetificacao.Motivo.SEM_FOLHA, janela.motivo(dia(20)));
    }

    @Test
    void pessoaSemFolhaNaoRetificaNada() {
        JanelaRetificacao janela = janela();

        assertEquals(JanelaRetificacao.Motivo.SEM_FOLHA, janela.motivo(dia(5)));
        assertEquals(List.of(), janela.paginasDoDia(dia(5)));
    }

    // ══ Reenvio do mesmo período ══════════════════════════════════

    @Test
    void aSemanalReenviadaNaoMudaOAbertoEFechado() {
        Object[] primeiraSemana = semanal("a", 1, 9, T1);
        Object[] segundaSemana = semanal("b", 1, 16, T2);
        assertEquals(16, abertos(janela(primeiraSemana, segundaSemana), 1, 16).size());

        JanelaRetificacao depois = janela(primeiraSemana, segundaSemana, semanal("b2", 1, 16, T4));

        assertEquals(16, abertos(depois, 1, 16).size());
    }

    @Test
    void oReenvioNaoReabreOsDiasQueADefinitivaFechou() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, T1), semanal("b", 1, 16, T2),
                semanal("c", 1, 23, T3), mensal("def", PontoLote.CATEGORIA_DEFINITIVA, T3),
                semanal("b2", 1, 16, T4));

        assertEquals(List.of(), abertos(janela, 1, 31));
        assertEquals(JanelaRetificacao.Motivo.DEFINITIVA_PUBLICADA, janela.motivo(dia(12)));
    }

    // ══ Mensal prévia e definitiva ════════════════════════════════

    @Test
    void aPreviaNaoFechaNadaEEstendeOAbertoAoMesInteiro() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, T1), semanal("b", 1, 16, T2),
                mensal("prev", PontoLote.CATEGORIA_PREVIA, T3));

        assertEquals(31, abertos(janela, 1, 31).size());
    }

    @Test
    void definitivaFechaOMesInteiro() {
        JanelaRetificacao janela = janela(mensal("prev", PontoLote.CATEGORIA_PREVIA, T1),
                mensal("def", PontoLote.CATEGORIA_DEFINITIVA, T2));

        assertEquals(List.of(), abertos(janela, 1, 31));
        assertEquals(JanelaRetificacao.Motivo.DEFINITIVA_PUBLICADA, janela.motivo(dia(15)));
    }

    @Test
    void aDefinitivaNaoFechaOsDiasForaDoPeriodoDela() {
        JanelaRetificacao janela = janela(semanal("a", 17, 23, T1),
                mensal("def", PontoLote.CATEGORIA_DEFINITIVA, 1, 20, T2));

        assertEquals(JanelaRetificacao.Motivo.DEFINITIVA_PUBLICADA, janela.motivo(dia(20)));
        assertEquals(List.of(21, 22, 23), abertos(janela, 1, 31));
        assertEquals(JanelaRetificacao.Motivo.SEM_FOLHA, janela.motivo(dia(25)));
    }

    @Test
    void aDefinitivaSubstituidaPorOutraMenorNaoReabreOQueJaFechara() {
        JanelaRetificacao janela = janela(mensal("def", PontoLote.CATEGORIA_DEFINITIVA, T2),
                mensal("def2", PontoLote.CATEGORIA_DEFINITIVA, 1, 20, T4));

        assertEquals(List.of(), abertos(janela, 1, 31));
        assertEquals(JanelaRetificacao.Motivo.DEFINITIVA_PUBLICADA, janela.motivo(dia(25)));
    }

    @Test
    void semanalPublicadaDepoisDaDefinitivaNaoReabreOMes() {
        JanelaRetificacao janela = janela(mensal("def", PontoLote.CATEGORIA_DEFINITIVA, T1),
                semanal("a", 17, 23, T4));

        assertEquals(List.of(), abertos(janela, 1, 31));
    }

    @Test
    void previaPublicadaDepoisDaDefinitivaNaoReabreOMes() {
        JanelaRetificacao janela = janela(mensal("def", PontoLote.CATEGORIA_DEFINITIVA, T1),
                mensal("prev", PontoLote.CATEGORIA_PREVIA, T4));

        assertEquals(List.of(), abertos(janela, 1, 31));
    }

    // ══ A pessoa da folha ═════════════════════════════════════════

    @Test
    void folhaDeOutraPessoaNaoAbreDia() {
        Object[] deOutro = folha("x", "SEMANAL", null, dia(1), dia(9), T1, "op-2", TIPO_PESSOA);

        assertEquals(JanelaRetificacao.Motivo.SEM_FOLHA, janela(deOutro).motivo(dia(5)));
    }

    @Test
    void mesmoIdComOutroPapelEhOutraPessoa() {
        Object[] comoTecnico = folha("x", "SEMANAL", null, dia(1), dia(9), T1, PESSOA, "TECNICO");

        assertEquals(JanelaRetificacao.Motivo.SEM_FOLHA, janela(comoTecnico).motivo(dia(5)));
    }

    // ══ A folha que representa o dia ══════════════════════════════

    @Test
    void oDiaEhRepresentadoPelaFolhaMaisRecenteQueOCobre() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, T1), semanal("b", 1, 16, T2));

        assertEquals(List.of("pag-b"), janela.paginasDoDia(dia(5)));
        assertEquals(List.of("pag-b"), janela.paginasDoDia(dia(12)));
    }

    @Test
    void aFolhaSubstituidaNaoRepresentaMaisODia() {
        JanelaRetificacao janela = janela(semanal("b", 10, 16, T2), semanal("b2", 10, 16, T4));

        assertEquals(List.of("pag-b2"), janela.paginasDoDia(dia(12)));
    }

    @Test
    void asDuasPaginasDaMesmaFolhaVoltamJuntas() {
        Object[] primeira = semanal("a", 1, 9, T1);
        Object[] segunda = semanal("a", 1, 9, T1);
        ((PontoLotePagina) segunda[0]).setId("pag-a-2");

        assertTrue(janela(primeira, segunda).paginasDoDia(dia(5))
                .containsAll(List.of("pag-a", "pag-a-2")));
    }

    @Test
    void folhaSemDataDePublicacaoNaoMudaOAbertoEFechado() {
        JanelaRetificacao janela = janela(semanal("a", 1, 9, null), semanal("b", 1, 16, T2));

        assertEquals(16, abertos(janela, 1, 16).size());
        assertEquals(List.of("pag-b"), janela.paginasDoDia(dia(5)));
    }
}
