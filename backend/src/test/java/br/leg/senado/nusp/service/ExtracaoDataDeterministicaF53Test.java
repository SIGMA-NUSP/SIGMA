package br.leg.senado.nusp.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A extração de dia/mês/ano nos dois helpers de relatório ({@link ReportConfig#fmtDate(Object)}
 * e {@link RdsXlsxService#extractDay(Object)}) usa millis intrínsecos + zona EXPLÍCITA
 * America/Sao_Paulo ({@code Instant.ofEpochMilli(getTime()).atZone(BRT)}) — invariante ao fuso
 * default da JVM. Os ramos {@code java.util.Date} exercitados são provavelmente mortos no
 * runtime (os chamadores passam String ou {@code LocalDate}); a cobertura é defensiva.
 *
 * <p>{@code @Isolated}: o teste MUTA a zona default da JVM ({@code TimeZone.setDefault}) para
 * provar a invariância — estado global do processo; o isolamento impede corromper a zona de
 * testes concorrentes, e o {@code try/finally} a restaura sempre.
 */
@Isolated
class ExtracaoDataDeterministicaF53Test {

    /** Instante fixo: 01:30 UTC = 22:30 do dia ANTERIOR em Brasília — a borda que expõe o fuso. */
    private static final Instant REF = Instant.parse("2026-07-16T01:30:00Z");
    // Em America/Sao_Paulo (UTC-3): 15/07 22:30 → dia 15, mês 07. O antigo Calendar daria 16 em UTC.

    /** Os dois tipos que caem no ramo java.util.Date: o real (Timestamp, de query nativa Oracle) e o
     *  defensivo (java.util.Date puro). from(Instant) fixa os millis sem depender do fuso na construção. */
    private static Object[] carimbos() {
        return new Object[] { java.sql.Timestamp.from(REF), Date.from(REF) };
    }

    private static final String[] FUSOS = { "UTC", "Asia/Tokyo", "America/Los_Angeles" };

    @Test
    @DisplayName("ReportConfig.fmtDate extrai a data em BRT, invariante ao fuso default da JVM")
    void fmtDate_invarianteAoFusoDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zona : FUSOS) {
                TimeZone.setDefault(TimeZone.getTimeZone(zona));
                for (Object carimbo : carimbos()) {
                    assertEquals("15/07/2026", ReportConfig.fmtDate(carimbo),
                            "fmtDate deve dar a data em Brasília (15/07) para qualquer fuso default (aqui "
                                    + zona + ", tipo " + carimbo.getClass().getSimpleName()
                                    + "): 01:30Z = 22:30 BRT do dia 15 — sem o acoplamento do antigo Calendar");
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("RdsXlsxService.extractDay extrai o dia em BRT, invariante ao fuso default da JVM")
    void extractDay_invarianteAoFusoDefault() {
        RdsXlsxService svc = new RdsXlsxService();
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zona : FUSOS) {
                TimeZone.setDefault(TimeZone.getTimeZone(zona));
                for (Object carimbo : carimbos()) {
                    assertEquals(15, svc.extractDay(carimbo),
                            "extractDay deve dar o dia em Brasília (15) para qualquer fuso default (aqui "
                                    + zona + ", tipo " + carimbo.getClass().getSimpleName()
                                    + "): 01:30Z = 22:30 BRT do dia 15 — sem o acoplamento do antigo Calendar");
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
