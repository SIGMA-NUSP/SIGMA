package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoRetificacao;

import java.util.regex.Pattern;

/**
 * As quatro células de horário de um dia — Ent. 1, Saí. 1, Ent. 2, Saí. 2 — na ordem em que a folha
 * as imprime.
 *
 * <p>A retificação guarda só o que o funcionário corrigiu; o que ele não tocou continua valendo o
 * que veio na folha oficial. Ler o dia é, portanto, sobrepor uma coisa à outra — a mesma conta na
 * validação do que ele digita e na célula que o administrador enxerga.
 *
 * <p>Célula da folha que não é um horário (o status do dia, o espaço em branco) não entra: ela não
 * é comparável nem mesclável.
 */
record HorariosDoDia(String ent1, String sai1, String ent2, String sai2) {

    /** Horário 'HH:MM' 00:00–23:59 — o formato que a retificação aceita e a folha imprime. */
    static final Pattern HORA = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    static final HorariosDoDia VAZIO = new HorariosDoDia(null, null, null, null);

    /** O que o funcionário corrigiu neste dia. */
    static HorariosDoDia daRetificacao(PontoRetificacao r) {
        if (r == null) return VAZIO;
        return new HorariosDoDia(r.getEnt1(), r.getSai1(), r.getEnt2(), r.getSai2());
    }

    /** As células como a folha as imprimiu — o que não é horário vira ausência. */
    static HorariosDoDia daFolha(String ent1, String sai1, String ent2, String sai2) {
        return new HorariosDoDia(horaOuNada(ent1), horaOuNada(sai1), horaOuNada(ent2), horaOuNada(sai2));
    }

    /** Estes horários por cima dos outros: o que está aqui vence, o que falta vem de lá. */
    HorariosDoDia sobre(HorariosDoDia base) {
        if (base == null) return this;
        return new HorariosDoDia(
                ent1 != null ? ent1 : base.ent1(),
                sai1 != null ? sai1 : base.sai1(),
                ent2 != null ? ent2 : base.ent2(),
                sai2 != null ? sai2 : base.sai2());
    }

    boolean vazio() {
        return ent1 == null && sai1 == null && ent2 == null && sai2 == null;
    }

    /** As quatro células na ordem impressa; a ausente é {@code null}. */
    String[] valores() {
        return new String[] {ent1, sai1, ent2, sai2};
    }

    /**
     * Os horários presentes avançam no relógio da primeira célula à última, e a entrada é anterior
     * à saída do próprio par (o intervalo entre a saída de um par e a entrada do seguinte pode ser
     * nulo, mas nunca negativo).
     *
     * <p>Com uma exceção: o turno que atravessa a meia-noite. Quem entra às 19:03 e sai às 02:28
     * trabalhou o dia inteiro, e o relógio volta uma vez — é jornada corriqueira de quem cobre
     * sessão noturna. Duas voltas ou mais, não: aí os horários estão trocados.
     *
     * <p>Só o que está presente é comparado: as células que a folha não trouxe simplesmente não
     * participam — a comparação é entre horários vizinhos, não entre posições fixas.
     */
    boolean emOrdem() {
        String[] v = valores();
        int voltas = 0;
        int anterior = -1;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == null) continue;
            if (anterior >= 0) {
                int cmp = v[anterior].compareTo(v[i]);
                boolean mesmoPar = (anterior == 0 && i == 1) || (anterior == 2 && i == 3);
                if (cmp > 0 || (cmp == 0 && mesmoPar)) voltas++;
            }
            anterior = i;
        }
        return voltas <= 1;
    }

    private static String horaOuNada(String celula) {
        if (celula == null) return null;
        String s = celula.trim();
        return HORA.matcher(s).matches() ? s : null;
    }
}
