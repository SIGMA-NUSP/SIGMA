package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Substitui CHECK constraint checklist_item_tipo_tipo_widget_check no banco */
public enum TipoWidget {
    RADIO("radio"),
    TEXT("text");

    private final String valor;

    TipoWidget(String valor) { this.valor = valor; }

    @JsonValue
    public String getValor() { return valor; }

    @JsonCreator
    public static TipoWidget fromValor(String valor) {
        for (TipoWidget t : values()) {
            if (t.valor.equalsIgnoreCase(valor)) return t;
        }
        throw new IllegalArgumentException("TipoWidget inválido: " + valor);
    }
}
