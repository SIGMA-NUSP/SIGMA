package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Substitui CHECK constraint ck_checklist_turno no banco */
public enum Turno {
    MATUTINO("Matutino"),
    VESPERTINO("Vespertino");

    private final String valor;

    Turno(String valor) { this.valor = valor; }

    @JsonValue
    public String getValor() { return valor; }

    @JsonCreator
    public static Turno fromValor(String valor) {
        for (Turno t : values()) {
            if (t.valor.equalsIgnoreCase(valor)) return t;
        }
        throw new IllegalArgumentException("Turno inválido: " + valor);
    }
}
