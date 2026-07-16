package br.leg.senado.nusp.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser do cartão-ponto Secullum (texto extraído de UMA página via OpenPDF).
 *
 * <p>Objetivo único: identificar, em cada linha-dia, em qual das 7 colunas
 * (DIA, ENT. 1, SAÍ. 1, ENT. 2, SAÍ. 2, TOTALDIA, BANCO) cada registro entra, e
 * copiar o texto <b>verbatim</b> — sem traduzir/expandir status (Feriado, Falta,
 * DISPOSI, FERNC, Atesc, …), apenas ocultando os marcadores de batida ({@code * ¨ ^}).
 * Os funcionários já conhecem os textos das folhas; só replicamos.
 *
 * <p>Gramática validada contra 6406 linhas (229 páginas, 7 períodos):
 * <pre>
 *   LINHA  := " " dd/mm/aa " - " DIASEM CORPO
 *   DIASEM := seg|ter|qua|qui|sex|sáb|dom|feri      (feri = feriado; troca o dia da semana)
 *   CORPO  := CÉLULA{0..4} DELTA{0..2}
 *   CÉLULA := BATIDA | STATUS
 *   BATIDA := [*¨^]? hh:mm [*¨^]?                    (SEM sinal → ENT.1/SAÍ.1/ENT.2/SAÍ.2 na ordem)
 *   DELTA  := [+-]h{2,3}:mm                          (COM sinal → penúltimo=TOTALDIA, último=BANCO; o BANCO acumulado pode passar de 99h)
 *   STATUS := corrida de letras repetida ×2 (ENT.1/SAÍ.1) ou ×4 (as quatro)
 * </pre>
 * Status e batidas nunca aparecem na mesma linha (verificado).
 */
public final class SecullumFolhaParser {

    private SecullumFolhaParser() {}

    /** Uma linha-dia com o texto VERBATIM de cada uma das 7 colunas (campos vazios = ""). */
    public record LinhaPonto(
            String dia, String ent1, String sai1, String ent2, String sai2,
            String totalDia, String banco) {}

    /** Início de uma linha-dia: dd/mm/aa (ano com 2 dígitos) seguido de " - ". */
    private static final Pattern LINHA_DIA =
            Pattern.compile("^\\s*(\\d{2})/(\\d{2})/(\\d{2})\\s*-\\s*(.*\\S)\\s*$");

    /** Marcadores de dia da semana / feriado (nenhum é prefixo de outro). */
    private static final String[] DIAS_SEMANA = {"seg", "ter", "qua", "qui", "sex", "sáb", "dom", "feri"};

    /** Batida: hh:mm sem sinal, com marcadores opcionais (* ¨ ^) que são descartados. */
    private static final Pattern BATIDA = Pattern.compile("[*¨^]?(\\d{2}:\\d{2})[*¨^]?");

    /**
     * Delta (TOTALDIA/BANCO): hh:mm COM sinal. A hora aceita 2 OU 3 dígitos:
     * a coluna BANCO é acumulada e pode passar de 99h — com {@code \d{2}} um
     * "+102:05" não casaria, o TOTALDIA do dia seria promovido a BANCO e o
     * "02:05" residual viraria batida fantasma (saldo silenciosamente errado).
     */
    private static final Pattern DELTA = Pattern.compile("[+-]\\d{2,3}:\\d{2}");

    private static final Pattern TEM_LETRA = Pattern.compile("[A-Za-zÀ-ÿ]");

    /**
     * Faz o parse do texto completo de uma página, retornando só as linhas-dia
     * (cabeçalho, totais e assinatura são ignorados naturalmente).
     */
    public static List<LinhaPonto> parse(String textoPagina) {
        List<LinhaPonto> out = new ArrayList<>();
        if (textoPagina == null || textoPagina.isBlank()) return out;
        for (String raw : textoPagina.split("\\r?\\n")) {
            Matcher m = LINHA_DIA.matcher(raw);
            if (m.matches()) {
                LinhaPonto linha = parseLinha(m.group(1), m.group(2), m.group(3), m.group(4));
                if (linha != null) out.add(linha);
            }
        }
        return out;
    }

    /**
     * Saldo final do banco de horas da folha, em minutos com sinal: o último
     * {@code banco()} não-vazio das linhas-dia (a coluna BANCO é o acumulado,
     * atualizado dia a dia) — <b>inclusive de linhas de status</b>: uma Falta
     * no fim do mês debita a jornada e só a linha de status carrega o
     * acumulado reduzido (pulá-las superestimaria o saldo em ~11% das folhas);
     * há folhas inteiras de status (férias) cujo acumulado só aparece nelas.
     * Regra validada contra a linha TOTAIS impressa em 324/324 folhas reais
     * (homolog, jul/2026). Dias sem delta já vêm com {@code banco=""} e são
     * ignorados naturalmente. Retorna {@code null} se nenhuma linha tem banco
     * (ex.: página sem texto ou sem linhas-dia).
     */
    public static Integer bancoFinalMin(List<LinhaPonto> linhas) {
        if (linhas == null) return null;
        for (int i = linhas.size() - 1; i >= 0; i--) {
            Integer min = deltaEmMinutos(linhas.get(i).banco());
            if (min != null) return min;
        }
        return null;
    }

    /** Valor de delta com sinal, tolerando espaços e horas de 1 a 3 dígitos (minutos 00–59). */
    private static final Pattern DELTA_NUM = Pattern.compile("([+-])\\s*(\\d{1,3}):([0-5]\\d)");

    /** {@code "±hh:mm"} → minutos com sinal; {@code null} se vazio/ilegível. */
    private static Integer deltaEmMinutos(String delta) {
        if (delta == null) return null;
        Matcher m = DELTA_NUM.matcher(delta.trim());
        if (!m.matches()) return null;
        int min = Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
        return "-".equals(m.group(1)) ? -min : min;
    }

    private static LinhaPonto parseLinha(String dd, String mm, String aa, String resto) {
        // 1) Marcador de dia da semana (ou "feri" de feriado), colado ao corpo.
        String marcador = detectarMarcador(resto);
        String corpo = marcador == null ? resto : resto.substring(marcador.length());

        // 2) Coluna DIA: verbatim ("dd/mm/aa - <dia>"); em feriado, calcula o dia real da semana.
        String dia = montarColunaDia(dd, mm, aa, marcador);

        // 3) Separa deltas (TOTALDIA/BANCO, com sinal) do resto (as células ENT./SAÍ.).
        List<String> deltas = extrairDeltas(corpo);
        String celulas = DELTA.matcher(corpo).replaceAll("").trim();

        // 4) Preenche as 4 células de batida (ENT.1, SAÍ.1, ENT.2, SAÍ.2).
        String[] c = preencherCelulas(celulas, marcador);

        // 5) TOTALDIA = penúltimo delta; BANCO = último (BANCO pode não existir).
        String[] tb = atribuirTotalEBanco(deltas);

        return new LinhaPonto(dia, c[0], c[1], c[2], c[3], tb[0], tb[1]);
    }

    private static String detectarMarcador(String resto) {
        String marcador = null;
        for (String d : DIAS_SEMANA) {
            if (resto.startsWith(d)) { marcador = d; break; }
        }
        return marcador;
    }

    private static String montarColunaDia(String dd, String mm, String aa, String marcador) {
        String diaSemana;
        if ("feri".equals(marcador)) {
            diaSemana = diaSemanaDaData(dd, mm, aa);
        } else {
            diaSemana = marcador == null ? "" : marcador;
        }
        return dd + "/" + mm + "/" + aa + (diaSemana.isEmpty() ? "" : " - " + diaSemana);
    }

    private static List<String> extrairDeltas(String corpo) {
        List<String> deltas = new ArrayList<>();
        Matcher dm = DELTA.matcher(corpo);
        while (dm.find()) deltas.add(dm.group());
        return deltas;
    }

    private static String[] preencherCelulas(String celulas, String marcador) {
        String[] c = {"", "", "", ""};
        if (!celulas.isEmpty()) {
            if (TEM_LETRA.matcher(celulas).find()) {
                // Dia de status (Feriado/Falta/DISPOSI/FERNC/Atesc/…): texto repetido ×k.
                Repeticao r = menorUnidadeRepetida(celulas);
                for (int i = 0; i < Math.min(r.vezes(), 4); i++) c[i] = r.unidade();
            } else {
                // Dia com batidas: hh:mm na ordem → ENT.1, SAÍ.1, ENT.2, SAÍ.2 (marcadores ocultos).
                Matcher bm = BATIDA.matcher(celulas);
                int i = 0;
                while (bm.find() && i < 4) c[i++] = bm.group(1);
            }
        } else if ("feri".equals(marcador)) {
            // Feriado cujo texto não veio nas células do PDF: marca "Feriado" nas quatro (requisito).
            java.util.Arrays.fill(c, "Feriado");
        }
        return c;
    }

    private record Repeticao(String unidade, int vezes) {}

    private static Repeticao menorUnidadeRepetida(String s) {
        int n = s.length();
        String unidade = s;
        int k = 1;
        for (int p = 1; p <= n; p++) {
            if (n % p == 0 && s.substring(0, p).repeat(n / p).equals(s)) {
                unidade = s.substring(0, p);
                k = n / p;
                break;
            }
        }
        return new Repeticao(unidade, k);
    }

    private static String[] atribuirTotalEBanco(List<String> deltas) {
        String totalDia = "", banco = "";
        if (deltas.size() == 1) {
            banco = deltas.get(0);
        } else if (deltas.size() >= 2) {
            totalDia = deltas.get(deltas.size() - 2);
            banco = deltas.get(deltas.size() - 1);
        }
        return new String[]{totalDia, banco};
    }

    /** Dia da semana (seg..dom) calculado da data — usado quando o Secullum mostra "feri". */
    private static String diaSemanaDaData(String dd, String mm, String aa) {
        try {
            LocalDate d = LocalDate.of(2000 + Integer.parseInt(aa), Integer.parseInt(mm), Integer.parseInt(dd));
            DayOfWeek dow = d.getDayOfWeek();
            return switch (dow) {
                case MONDAY -> "seg";
                case TUESDAY -> "ter";
                case WEDNESDAY -> "qua";
                case THURSDAY -> "qui";
                case FRIDAY -> "sex";
                case SATURDAY -> "sáb";
                case SUNDAY -> "dom";
            };
        } catch (RuntimeException e) {
            return "feri"; // fallback: mantém o marcador original em data inválida
        }
    }
}
