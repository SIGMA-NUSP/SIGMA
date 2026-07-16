package br.leg.senado.nusp.service;

import br.leg.senado.nusp.service.SecullumFolhaParser.LinhaPonto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static br.leg.senado.nusp.service.SecullumFolhaParser.bancoFinalMin;
import static br.leg.senado.nusp.service.SecullumFolhaParser.parse;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes do parser numérico do BANCO (E2). As amostras são trechos VERBATIM
 * de folhas reais de homolog (jul/2026), reduzidas às linhas relevantes —
 * o formato completo (cabeçalho/rodapé) já é exercitado pelos casos, pois a
 * linha TOTAIS e a assinatura não casam com a gramática de linha-dia.
 *
 * A regra sob teste (interpretação validada em 324/324 folhas contra a linha
 * TOTAIS impressa): último banco não-vazio de QUALQUER linha, inclusive de
 * status — Falta no fim do mês reduz o acumulado e só a linha de status
 * carrega o valor certo.
 */
class SecullumFolhaParserTest {

    @Nested
    @DisplayName("bancoFinalMin — folhas reais")
    class FolhasReais {

        @Test
        @DisplayName("folha comum: banco final positivo da última linha com delta")
        void positivo() {
            // Trecho real (fev/2025): dias com batidas; fds carrega o acumulado como delta único.
            String texto = """
                     25/02/25 - ter06:2713:09+01:24+28:40
                     26/02/25 - qua06:2512:48+00:46+29:26
                     27/02/25 - qui06:3312:30+29:26
                     TOTAIS 03:39 +29:26
                    """;
            assertEquals(29 * 60 + 26, bancoFinalMin(parse(texto)));
        }

        @Test
        @DisplayName("falta no último dia: o acumulado REDUZIDO da linha de status vence (não +29:26)")
        void faltaNoFimDoMes() {
            // Trecho real (fev/2025, Eduardo): 28/02 é Falta ×4 com TOTALDIA -06:00 e BANCO +23:26.
            String texto = """
                     26/02/25 - qua06:2512:48+00:46+29:26
                     27/02/25 - qui06:3312:30+29:26
                     28/02/25 - sexFaltaFaltaFaltaFalta-06:00+23:26
                     TOTAIS 03:39 +23:26
                    """;
            assertEquals(23 * 60 + 26, bancoFinalMin(parse(texto)));
        }

        @Test
        @DisplayName("banco final negativo (folha real com DISPOSI no fim)")
        void negativo() {
            // Trecho real (jul/2025, André): últimos dias À DISPOSIÇÃO carregando acumulado negativo.
            String texto = """
                     30/07/25 - quaDISPOSIDISPOSIDISPOSIDISPOSI-18:28
                     31/07/25 - quiDISPOSIDISPOSIDISPOSIDISPOSI-18:28
                     TOTAIS -19:55 -18:28
                    """;
            assertEquals(-(18 * 60 + 28), bancoFinalMin(parse(texto)));
        }

        @Test
        @DisplayName("folha inteira de status (férias): o acumulado só existe nas linhas de status")
        void folhaSoDeStatus() {
            // Folha semanal real (01–05/07/2026, FERNC ×4 em todos os dias): sem ela seria "sem folha".
            String texto = """
                     01/07/26 - quaFERNCFERNCFERNCFERNC+10:50
                     02/07/26 - quiFERNCFERNCFERNCFERNC+10:50
                     03/07/26 - sexFERNCFERNCFERNCFERNC+10:50
                     04/07/26 - sábFERNCFERNCFERNCFERNC+10:50
                     05/07/26 - domFERNCFERNCFERNCFERNC+10:50
                     TOTAIS 00:00 +10:50
                    """;
            assertEquals(10 * 60 + 50, bancoFinalMin(parse(texto)));
        }

        @Test
        @DisplayName("dias finais sem delta não escondem o último banco real")
        void diasFinaisSemDelta() {
            // Dia sem par completo não gera delta (banco="") — o acumulado fica no dia anterior.
            String texto = """
                     17/02/25 - seg06:4812:43+22:44
                     18/02/25 - ter06:3814:07+02:58+25:42
                     24/02/25 - seg06:2512:29
                    """;
            assertEquals(25 * 60 + 42, bancoFinalMin(parse(texto)));
        }
    }

    @Nested
    @DisplayName("bancoFinalMin — casos-limite")
    class CasosLimite {

        @Test
        @DisplayName("página sem texto ou sem linhas-dia → null")
        void semLinhas() {
            assertNull(bancoFinalMin(parse("")));
            assertNull(bancoFinalMin(parse(null)));
            assertNull(bancoFinalMin(parse("CARTÃO PONTO\n Ponto Secullum 4\n TOTAIS 00:00 +10:50")));
            assertNull(bancoFinalMin(null));
        }

        @Test
        @DisplayName("folha com linhas-dia mas nenhum banco → null")
        void linhasSemBanco() {
            String texto = " 24/02/25 - seg06:2512:29\n 25/02/25 - ter06:2713:09\n";
            assertNull(bancoFinalMin(parse(texto)));
        }

        @Test
        @DisplayName("valor com espaços e hora acima de 99h são tolerados")
        void toleraEspacosEHorasLongas() {
            List<LinhaPonto> comEspaco = List.of(
                    new LinhaPonto("01/02/25 - seg", "", "", "", "", "", " + 01:30 "));
            assertEquals(90, bancoFinalMin(comEspaco));
            List<LinhaPonto> tresDigitos = List.of(
                    new LinhaPonto("01/02/25 - seg", "", "", "", "", "", "-102:05"));
            assertEquals(-(102 * 60 + 5), bancoFinalMin(tresDigitos));
        }

        @Test
        @DisplayName("banco acumulado >= 100h atravessa o parse() completo (sem batida fantasma)")
        void bancoDeTresDigitosViaParse() {
            // O BANCO é acumulado e pode passar de 99h: com o DELTA restrito a 2 dígitos,
            // "+100:54" não casaria, o TOTALDIA viraria BANCO e "00:54" viraria ENT.2 falsa
            // — saldo silenciosamente errado (achado da revisão do E2).
            String texto = """
                     25/02/25 - ter06:2713:09+01:24+100:54
                     26/02/25 - qua06:2512:48+00:46+101:40
                    """;
            List<LinhaPonto> linhas = parse(texto);
            assertEquals(101 * 60 + 40, bancoFinalMin(linhas));
            LinhaPonto primeira = linhas.get(0);
            assertEquals("06:27", primeira.ent1());
            assertEquals("13:09", primeira.sai1());
            assertEquals("", primeira.ent2());
            assertEquals("+01:24", primeira.totalDia());
            assertEquals("+100:54", primeira.banco());
        }

        @Test
        @DisplayName("minutos fora de 00-59 são ilegíveis (não valem como banco)")
        void minutosInvalidosSaoPulados() {
            List<LinhaPonto> linhas = List.of(
                    new LinhaPonto("01/02/25 - seg", "", "", "", "", "", "+02:00"),
                    new LinhaPonto("02/02/25 - ter", "", "", "", "", "", "+05:75"));
            assertEquals(120, bancoFinalMin(linhas));
        }

        @Test
        @DisplayName("banco ilegível é pulado; vale o anterior")
        void ilegivelEhPulado() {
            List<LinhaPonto> linhas = List.of(
                    new LinhaPonto("01/02/25 - seg", "", "", "", "", "", "+02:00"),
                    new LinhaPonto("02/02/25 - ter", "", "", "", "", "", "xx:yy"));
            assertEquals(120, bancoFinalMin(linhas));
        }
    }
}
