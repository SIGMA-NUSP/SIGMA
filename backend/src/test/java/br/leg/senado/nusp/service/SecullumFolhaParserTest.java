package br.leg.senado.nusp.service;

import br.leg.senado.nusp.service.SecullumFolhaParser.LinhaPonto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static br.leg.senado.nusp.service.SecullumFolhaParser.bancoFinalMin;
import static br.leg.senado.nusp.service.SecullumFolhaParser.dataDe;
import static br.leg.senado.nusp.service.SecullumFolhaParser.ocorrenciaDe;
import static br.leg.senado.nusp.service.SecullumFolhaParser.parse;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes do parser da folha: o saldo numérico do BANCO e as duas colunas
 * derivadas de cada linha (data-calendário e ocorrência do dia). As amostras
 * são trechos VERBATIM de folhas reais, reduzidos às linhas relevantes — o
 * formato completo (cabeçalho/rodapé) já é exercitado pelos casos, pois a
 * linha TOTAIS e a assinatura não casam com a gramática de linha-dia.
 *
 * A regra do banco sob teste: último banco não-vazio de QUALQUER linha,
 * inclusive de status — Falta no fim do mês reduz o acumulado e só a linha de
 * status carrega o valor certo.
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
            // — saldo silenciosamente errado.
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

    @Nested
    @DisplayName("dataDe — data-calendário da coluna DIA")
    class DataDaLinha {

        @Test
        @DisplayName("dia comum: a data sai do início da coluna, sem o dia da semana")
        void diaComum() {
            String texto = """
                     25/02/25 - ter06:2713:09+01:24+28:40
                     26/02/25 - qua06:2512:48+00:46+29:26
                    """;
            List<LinhaPonto> linhas = parse(texto);
            assertEquals(2, linhas.size());
            assertEquals(LocalDate.of(2025, 2, 25), dataDe(linhas.get(0)));
            assertEquals(LocalDate.of(2025, 2, 26), dataDe(linhas.get(1)));
        }

        @Test
        @DisplayName("virada de ano e feriado: dia/mês/ano de 2 dígitos com o dia da semana recalculado")
        void viradaDeAnoEFeriado() {
            // Folha semanal a cavalo de dois anos; o feriado do dia 1º chega como "feri".
            String texto = """
                     30/12/25 - ter06:2512:48+00:46+21:35
                     31/12/25 - qua06:3312:30+21:35
                     01/01/26 - feriFeriadoFeriadoFeriadoFeriado+21:35
                     02/01/26 - sex06:2812:31+21:40
                    """;
            List<LinhaPonto> linhas = parse(texto);
            assertEquals(4, linhas.size());
            assertEquals(LocalDate.of(2025, 12, 30), dataDe(linhas.get(0)));
            assertEquals(LocalDate.of(2025, 12, 31), dataDe(linhas.get(1)));
            // No feriado a coluna DIA é reescrita com o dia real da semana; a data não muda.
            assertEquals("01/01/26 - qui", linhas.get(2).dia());
            assertEquals(LocalDate.of(2026, 1, 1), dataDe(linhas.get(2)));
            assertEquals(LocalDate.of(2026, 1, 2), dataDe(linhas.get(3)));
        }

        @Test
        @DisplayName("o ano de 2 dígitos é sempre deste século")
        void anoSempreNesteSeculo() {
            // O cartão não imprime o século: "99" é 2099, nunca 1999.
            assertEquals(LocalDate.of(2099, 2, 1),
                    dataDe(new LinhaPonto("01/02/99 - seg", "", "", "", "", "", "")));
            assertEquals(LocalDate.of(2000, 1, 3),
                    dataDe(new LinhaPonto("03/01/00 - seg", "", "", "", "", "", "")));
        }

        @Test
        @DisplayName("data impossível, coluna vazia ou linha nula → sem data")
        void semDataLegivel() {
            // A gramática de linha-dia só cobra o formato dd/mm/aa: um dia/mês impossível
            // atravessa o parse e continua valendo pelo texto verbatim, sem data derivada.
            List<LinhaPonto> ilegivel = parse(" 32/13/25 - seg");
            assertEquals(1, ilegivel.size());
            assertEquals("32/13/25 - seg", ilegivel.get(0).dia());
            assertNull(dataDe(ilegivel.get(0)));

            assertNull(dataDe(new LinhaPonto("", "", "", "", "", "", "")));
            assertNull(dataDe(new LinhaPonto("TOTAIS", "", "", "", "", "", "")));
            assertNull(dataDe(new LinhaPonto(null, null, null, null, null, null, null)));
            assertNull(dataDe(null));
        }
    }

    @Nested
    @DisplayName("ocorrenciaDe — status do dia")
    class OcorrenciaDaLinha {

        @Test
        @DisplayName("status repetido nas quatro células devolve o texto do cartão")
        void statusEmQuatroCelulas() {
            String ferias = """
                     03/07/26 - sexFERNCFERNCFERNCFERNC+10:50
                     04/07/26 - sábFERNCFERNCFERNCFERNC+10:50
                    """;
            List<LinhaPonto> linhasFerias = parse(ferias);
            assertEquals(2, linhasFerias.size());
            assertEquals("FERNC", ocorrenciaDe(linhasFerias.get(0)));
            assertEquals("FERNC", ocorrenciaDe(linhasFerias.get(1)));

            String disposicao = """
                     30/07/25 - quaDISPOSIDISPOSIDISPOSIDISPOSI-18:28
                     31/07/25 - quiDISPOSIDISPOSIDISPOSIDISPOSI-18:28
                    """;
            List<LinhaPonto> linhasDisposicao = parse(disposicao);
            assertEquals(2, linhasDisposicao.size());
            assertEquals("DISPOSI", ocorrenciaDe(linhasDisposicao.get(0)));
            assertEquals("DISPOSI", ocorrenciaDe(linhasDisposicao.get(1)));
        }

        @Test
        @DisplayName("status só nas duas primeiras células também é ocorrência do dia")
        void statusEmDuasCelulas() {
            // Falta ×2: o status ocupa ENT. 1/SAÍ. 1 e as duas últimas células ficam vazias.
            List<LinhaPonto> linhas = parse(" 28/02/25 - sexFaltaFalta-06:00+23:26");
            assertEquals(1, linhas.size());
            LinhaPonto falta = linhas.get(0);
            assertEquals("Falta", falta.sai1());
            assertEquals("", falta.ent2());
            assertEquals("Falta", ocorrenciaDe(falta));
        }

        @Test
        @DisplayName("o texto do status volta íntegro, com pontuação e maiúsculas")
        void textoDoStatusIntegro() {
            // Nada é traduzido nem normalizado: o ponto de "P.facul" e o "N" final de "BancN"
            // fazem parte do texto que o funcionário já conhece da folha.
            LinhaPonto facultativo = parse(" 03/03/25 - segP.faculP.faculP.faculP.facul+21:20").get(0);
            assertEquals("P.facul", ocorrenciaDe(facultativo));

            LinhaPonto banco = parse(" 12/06/25 - quiBancNBancN-08:00+02:30").get(0);
            assertEquals("BancN", ocorrenciaDe(banco));
        }

        @Test
        @DisplayName("dia de batidas e linha sem células não têm ocorrência")
        void semOcorrencia() {
            LinhaPonto batidas = parse(" 25/02/25 - ter06:2713:09+01:24+28:40").get(0);
            assertEquals("06:27", batidas.ent1());
            assertNull(ocorrenciaDe(batidas));

            LinhaPonto semCelulas = parse(" 24/02/25 - seg").get(0);
            assertNull(ocorrenciaDe(semCelulas));

            assertNull(ocorrenciaDe(new LinhaPonto("", "", "", "", "", "", "")));
            assertNull(ocorrenciaDe(new LinhaPonto(null, null, null, null, null, null, null)));
            assertNull(ocorrenciaDe(null));
        }

        @Test
        @DisplayName("feriado sem texto nas células vira a ocorrência \"Feriado\"")
        void feriadoSemTextoNasCelulas() {
            // Quando o cartão só marca o feriado no lugar do dia da semana, as quatro células
            // recebem "Feriado" — e é esse o status que a linha passa a carregar.
            LinhaPonto semTexto = parse(" 07/09/25 - feri+18:20").get(0);
            assertEquals("07/09/25 - dom", semTexto.dia());
            assertEquals("Feriado", semTexto.ent1());
            assertEquals("Feriado", ocorrenciaDe(semTexto));
            assertEquals(LocalDate.of(2025, 9, 7), dataDe(semTexto));

            // Com o texto impresso na folha, ele é copiado como está.
            LinhaPonto comTexto = parse(" 21/04/25 - feriFeriadoFeriadoFeriadoFeriado+22:10").get(0);
            assertEquals("21/04/25 - seg", comTexto.dia());
            assertEquals("Feriado", ocorrenciaDe(comTexto));
        }
    }
}
