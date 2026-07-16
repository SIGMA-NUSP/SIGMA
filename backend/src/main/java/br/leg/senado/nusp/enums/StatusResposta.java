package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Substitui CHECK constraint ck_cli_resp_status no banco */
public enum StatusResposta {
    OK("Ok"),
    FALHA("Falha");

    private final String valor;

    StatusResposta(String valor) { this.valor = valor; }

    @JsonValue
    public String getValor() { return valor; }

    @JsonCreator
    public static StatusResposta fromValor(String valor) {
        for (StatusResposta s : values()) {
            if (s.valor.equalsIgnoreCase(valor)) return s;
        }
        throw new IllegalArgumentException("StatusResposta inválido: " + valor);
    }
}
