package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Tipo de aviso — define em qual tela o aviso aparece e se exige ciência.
 * Persistido em FRM_AVISO_CADASTRO.TIPO (valor = nome do enum).
 */
public enum TipoAviso {
    VERIFICACAO("Verificação"),
    ESCALA("Escala"),
    PESSOAL("Pessoal"),
    AGENDA("Agenda"),
    GERAL("Geral");

    private final String label;

    TipoAviso(String label) { this.label = label; }

    /** Texto exibido na coluna "Tipo de Aviso" da tabela do admin. */
    public String getLabel() { return label; }

    /** Tipos que pedem ciência do destinatário (checkbox "Ciente"). */
    public boolean exigeCiencia() {
        return this == VERIFICACAO || this == ESCALA || this == PESSOAL;
    }

    /** Tipos amarrados a uma sala (a ciência é por sala). Hoje só VERIFICACAO. */
    public boolean exigeSala() {
        return this == VERIFICACAO;
    }

    @JsonCreator
    public static TipoAviso fromString(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Tipo de aviso obrigatório.");
        return TipoAviso.valueOf(v.trim().toUpperCase());
    }
}
