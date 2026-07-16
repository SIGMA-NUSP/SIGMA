package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Marcação por pessoa-dia do ponto (Q7/F#4) — reproduz as marcações da
 * planilha real (à disposição, atestado, férias, recesso, lic. médica).
 * Persistido em PNT_PESSOA_MARCACAO.TIPO com o literal do CHECK
 * (changeset 038). Enum extensível: valor novo = literal novo no CHECK.
 * O rótulo é o texto de exibição da célula na grade/XLSX e do motivo de
 * bloqueio do banco de horas (§1 do plano).
 */
public enum TipoPessoaMarcacao {
    A_DISPOSICAO("A_DISPOSICAO", "À disposição"),
    ATESTADO("ATESTADO", "Atestado"),
    FERIAS("FERIAS", "Férias"),
    RECESSO("RECESSO", "Recesso"),
    LICENCA_MEDICA("LICENCA_MEDICA", "Lic. médica");

    private final String valor;
    private final String rotulo;

    TipoPessoaMarcacao(String valor, String rotulo) {
        this.valor = valor;
        this.rotulo = rotulo;
    }

    @JsonValue
    public String getValor() { return valor; }

    public String getRotulo() { return rotulo; }

    public static TipoPessoaMarcacao fromValor(String valor) {
        if (valor == null || valor.isBlank()) return null;
        for (TipoPessoaMarcacao t : values()) {
            if (t.valor.equalsIgnoreCase(valor)) return t;
        }
        throw new IllegalArgumentException("TipoPessoaMarcacao inválido: " + valor);
    }
}
