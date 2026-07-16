package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Substitui CHECK constraint ck_regopop_tipo_evento no banco */
public enum TipoEvento {
    OPERACAO("operacao"),
    CESSAO("cessao"),
    OUTROS("outros");

    private final String valor;

    TipoEvento(String valor) { this.valor = valor; }

    @JsonValue
    public String getValor() { return valor; }

    @JsonCreator
    public static TipoEvento fromValor(String valor) {
        for (TipoEvento t : values()) {
            if (t.valor.equalsIgnoreCase(valor)) return t;
        }
        throw new IllegalArgumentException("TipoEvento inválido: " + valor);
    }
}
