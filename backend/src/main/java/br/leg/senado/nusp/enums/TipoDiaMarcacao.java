package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Marcação global de dia do ponto (todos os funcionários — Q6).
 * Persistido em PNT_DIA_MARCACAO.TIPO com o literal do CHECK
 * (changeset 037). O rótulo é o texto de exibição da célula na
 * grade/XLSX e do motivo de bloqueio do banco de horas (§1 do plano).
 */
public enum TipoDiaMarcacao {
    FERIADO("FERIADO", "Feriado"),
    PONTO_FACULTATIVO("PONTO_FACULTATIVO", "P. Facultativo");

    private final String valor;
    private final String rotulo;

    TipoDiaMarcacao(String valor, String rotulo) {
        this.valor = valor;
        this.rotulo = rotulo;
    }

    @JsonValue
    public String getValor() { return valor; }

    public String getRotulo() { return rotulo; }

    public static TipoDiaMarcacao fromValor(String valor) {
        if (valor == null || valor.isBlank()) return null;
        for (TipoDiaMarcacao t : values()) {
            if (t.valor.equalsIgnoreCase(valor)) return t;
        }
        throw new IllegalArgumentException("TipoDiaMarcacao inválido: " + valor);
    }
}
