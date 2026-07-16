package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Status de um cadastro de aviso. Persistido em FRM_AVISO_CADASTRO.STATUS
 * com o valor amigável ("Ativo"/"Expirado"/"Desativado"), igual ao
 * padrão de StatusResposta.
 */
public enum StatusAviso {
    ATIVO("Ativo"),
    EXPIRADO("Expirado"),
    DESATIVADO("Desativado");

    private final String valor;

    StatusAviso(String valor) { this.valor = valor; }

    @JsonValue
    public String getValor() { return valor; }

    public static StatusAviso fromValor(String valor) {
        if (valor == null || valor.isBlank()) return null;
        for (StatusAviso s : values()) {
            if (s.valor.equalsIgnoreCase(valor)) return s;
        }
        throw new IllegalArgumentException("StatusAviso inválido: " + valor);
    }
}
