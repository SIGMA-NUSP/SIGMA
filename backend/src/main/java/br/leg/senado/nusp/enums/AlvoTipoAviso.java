package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Público de um aviso (linha de FRM_AVISO_ALVO).
 * Persistido em FRM_AVISO_ALVO.ALVO_TIPO (valor = nome do enum).
 */
public enum AlvoTipoAviso {
    SALA,
    OPERADOR,
    TECNICO,
    ADMIN,
    TODOS_OPERADORES,
    TODOS_TECNICOS,
    TODOS_ADMIN,
    TODOS;

    /** Alvos sem destinatário individual (não preenchem FK). */
    public boolean ehColetivo() {
        return this == TODOS_OPERADORES || this == TODOS_TECNICOS || this == TODOS_ADMIN || this == TODOS;
    }

    public boolean atingeOperadores() {
        return this == SALA || this == OPERADOR || this == TODOS_OPERADORES || this == TODOS;
    }

    public boolean atingeTecnicos() {
        return this == SALA || this == TECNICO || this == TODOS_TECNICOS || this == TODOS;
    }

    /** Administradores como público. TODOS (operadores+técnicos) NÃO inclui admin — use ADMIN/TODOS_ADMIN. */
    public boolean atingeAdmins() {
        return this == ADMIN || this == TODOS_ADMIN;
    }

    @JsonCreator
    public static AlvoTipoAviso fromString(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Tipo de público obrigatório.");
        return AlvoTipoAviso.valueOf(v.trim().toUpperCase());
    }
}
