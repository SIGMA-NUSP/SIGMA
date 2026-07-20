package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Subtipo de um aviso — código estável gravado em {@code FRM_AVISO_CADASTRO.SUBTIPO} (valor = nome
 * do enum) do qual o backend deriva as DUAS leituras do mesmo aviso, propositalmente diferentes:
 *
 * <ul>
 *   <li>{@link #getTituloPopup()} — o "Aviso - …" do popup global (aviso-popup);</li>
 *   <li>{@link #getLabelTabela()} — a coluna "Tipo de Aviso" da tabela do admin.</li>
 * </ul>
 *
 * Nulo é válido e esperado: avisos de Verificação e todo o legado PESSOAL ficam sem subtipo e
 * usam os fallbacks no label do {@link TipoAviso} (ver plano §2). Nunca derivar uma leitura de um
 * subtipo assumindo não-nulo. Este enum é a fonte única do mapa da §2 do plano.
 */
public enum SubtipoAviso {
    FOLHA_SEMANAL("Folha semanal disponível", "Folha Semanal"),
    FOLHA_MENSAL("Folha mensal disponível", "Folha Mensal"),
    SOLICITACAO_APROVADA("Solicitação aprovada", "Solicitação Banco"),
    SOLICITACAO_REJEITADA("Solicitação rejeitada", "Solicitação Banco"),
    ESCALA("Escala", "Escala"),
    AGENDA("Agenda Legislativa", "Agenda"),
    PESSOAL("Pessoal", "Pessoal"),
    GRUPO_OPERADORES("Operadores", "Operadores"),
    GRUPO_TECNICOS("Técnicos", "Técnicos"),
    GRUPO_TODOS("Todos", "Operadores e Técnicos"),
    GRUPO_ADMINISTRADORES("Administradores", "Administradores");

    private final String tituloPopup;
    private final String labelTabela;

    SubtipoAviso(String tituloPopup, String labelTabela) {
        this.tituloPopup = tituloPopup;
        this.labelTabela = labelTabela;
    }

    /** Título exibido no popup global ("Aviso - {tituloPopup}"). */
    public String getTituloPopup() { return tituloPopup; }

    /** Rótulo exibido na coluna "Tipo de Aviso" da tabela do admin. */
    public String getLabelTabela() { return labelTabela; }

    /** Nulo/branco → null (subtipo é opcional: Verificação e legado não têm). */
    @JsonCreator
    public static SubtipoAviso fromString(String v) {
        if (v == null || v.isBlank()) return null;
        return SubtipoAviso.valueOf(v.trim().toUpperCase());
    }
}
