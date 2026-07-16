package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Status de uma solicitação de folga do banco de horas. Persistido em
 * PNT_SOLICITACAO_FOLGA.STATUS com o literal do CHECK (changeset 036).
 * CANCELADO = cancelamento pelo próprio funcionário, só de PENDENTE,
 * sem delete físico (Q19); junto com REJEITADO, sai da soma do saldo
 * e libera o dia na FBI de pedido vivo.
 */
public enum StatusSolicitacaoFolga {
    PENDENTE("PENDENTE"),
    APROVADO("APROVADO"),
    REJEITADO("REJEITADO"),
    CANCELADO("CANCELADO");

    private final String valor;

    StatusSolicitacaoFolga(String valor) { this.valor = valor; }

    @JsonValue
    public String getValor() { return valor; }

    public static StatusSolicitacaoFolga fromValor(String valor) {
        if (valor == null || valor.isBlank()) return null;
        for (StatusSolicitacaoFolga s : values()) {
            if (s.valor.equalsIgnoreCase(valor)) return s;
        }
        throw new IllegalArgumentException("StatusSolicitacaoFolga inválido: " + valor);
    }
}
