package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoRetificacao;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * As quatro células de horário de um dia: o que a pessoa corrigiu por cima do que a folha imprimiu,
 * e a conferência de que o dia resultante avança no relógio — tolerada a única volta do turno que
 * atravessa a meia-noite.
 */
class HorariosDoDiaTest {

    private static PontoRetificacao retificacao(String ent1, String sai1, String ent2, String sai2) {
        PontoRetificacao r = new PontoRetificacao();
        r.setEnt1(ent1);
        r.setSai1(sai1);
        r.setEnt2(ent2);
        r.setSai2(sai2);
        return r;
    }

    private static HorariosDoDia horarios(String ent1, String sai1, String ent2, String sai2) {
        return new HorariosDoDia(ent1, sai1, ent2, sai2);
    }

    @Nested
    class Mescla {

        @Test
        void oQueFoiCorrigidoVenceOQueAFolhaTrouxe() {
            HorariosDoDia folha = HorariosDoDia.daFolha("08:00", "12:00", "13:00", "17:00");

            HorariosDoDia efetivo = horarios(null, "12:30", null, null).sobre(folha);

            assertEquals(new HorariosDoDia("08:00", "12:30", "13:00", "17:00"), efetivo);
        }

        @Test
        void celulaDaFolhaQueNaoEhHorarioNaoEntra() {
            HorariosDoDia folha = HorariosDoDia.daFolha("Falta", "Falta", "  ", null);

            assertEquals(HorariosDoDia.VAZIO, folha);
            assertEquals(horarios("08:00", null, null, null), horarios("08:00", null, null, null).sobre(folha));
        }

        @Test
        void celulaDaFolhaVemAparada() {
            assertEquals(horarios("08:00", null, null, null), HorariosDoDia.daFolha(" 08:00 ", null, null, null));
        }

        @Test
        void horarioForaDaFaixaNaoEhHorario() {
            assertEquals(HorariosDoDia.VAZIO, HorariosDoDia.daFolha("24:00", "08:60", "8:00", "0800"));
        }

        @Test
        void diaSemRetificacaoEhOQueAFolhaTrouxe() {
            HorariosDoDia folha = HorariosDoDia.daFolha("08:00", "12:00", null, null);

            assertEquals(folha, HorariosDoDia.daRetificacao(null).sobre(folha));
        }

        @Test
        void retificacaoDeTipoNaoTrazHorario() {
            HorariosDoDia dia = HorariosDoDia.daRetificacao(retificacao(null, null, null, null));

            assertTrue(dia.vazio());
            assertNull(dia.ent1());
        }
    }

    @Nested
    class Ordem {

        @Test
        void diaCompletoEmOrdemPassa() {
            assertTrue(horarios("08:00", "12:00", "13:00", "17:00").emOrdem());
        }

        @Test
        void saidaDeUmParPodeEncostarNaEntradaDoOutro() {
            assertTrue(horarios("08:00", "12:00", "12:00", "17:00").emOrdem());
        }

        @Test
        void turnoQueAtravessaAMeiaNoitePassa() {
            assertTrue(horarios("19:03", "02:28", null, null).emOrdem());
            assertTrue(horarios("14:24", "00:49", null, null).emOrdem());
            assertTrue(horarios("17:15", "00:08", null, null).emOrdem());
        }

        @Test
        void turnoQueAtravessaAMeiaNoiteComIntervaloPassa() {
            assertTrue(horarios("19:00", "23:00", "00:12", "02:28").emOrdem());
            assertTrue(horarios("17:15", "20:00", "21:00", "00:08").emOrdem());
        }

        /**
         * A entrada depois da saída do próprio par é indistinguível do turno que começa à noite e
         * termina na madrugada — 19:00 às 12:00 é a jornada de quem cobre sessão que varou. Enquanto
         * essa for a única volta do relógio no dia, o dia passa: aceitar a inversão rara custa menos
         * do que recusar a jornada noturna, que é rotina.
         */
        @Test
        void entradaDepoisDaSaidaDoMesmoParPassaComoTurnoNoturno() {
            assertTrue(horarios("12:00", "08:00", null, null).emOrdem());
            assertTrue(horarios(null, null, "17:00", "13:00").emOrdem());
            assertTrue(horarios("19:00", "12:00", "13:00", "17:00").emOrdem());
        }

        @Test
        void entradaIgualASaidaDoMesmoParGastaAVolta() {
            assertTrue(horarios("08:00", "08:00", null, null).emOrdem());
            assertFalse(horarios("08:00", "08:00", "07:00", "12:00").emOrdem());
        }

        @Test
        void segundoParAntesDoPrimeiroPassaComoAViradaDoDia() {
            assertTrue(horarios("08:00", "12:00", "07:00", "17:00").emOrdem());
        }

        @Test
        void duasVoltasNoRelogioRecusam() {
            assertFalse(horarios("17:00", "02:00", "01:00", "05:00").emOrdem());
            assertFalse(horarios("08:00", "12:00", "07:00", "06:00").emOrdem());
        }

        @Test
        void celulasAusentesNaoParticipam() {
            assertTrue(horarios(null, "12:00", null, null).emOrdem());
            assertTrue(horarios(null, null, null, "17:00").emOrdem());
            assertTrue(HorariosDoDia.VAZIO.emOrdem());
        }

        @Test
        void horariosSalteadosSaoComparadosEntreSi() {
            assertTrue(horarios("08:00", null, "13:00", null).emOrdem());
            assertTrue(horarios("13:00", null, "08:00", null).emOrdem());
            assertFalse(horarios("13:00", null, "08:00", "07:00").emOrdem());
        }
    }
}
