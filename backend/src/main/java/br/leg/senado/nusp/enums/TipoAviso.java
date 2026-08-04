package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Tipo de aviso — define em qual tela o aviso aparece e se a exigência de ciência é escolhida no
 * cadastro ou imposta. Persistido em FRM_AVISO_CADASTRO.TIPO (valor = nome do enum).
 *
 * <p>Carrega também o {@link RotuloAviso} de FALLBACK: o que o usuário vê quando o cadastro não tem
 * subtipo (Verificação e o legado PESSOAL).
 */
public enum TipoAviso {
    VERIFICACAO("Verificação", CategoriaAviso.AVISO),
    ESCALA("Escala", CategoriaAviso.AVISO),
    /** Sem contexto: a categoria Mensagem já identifica a comunicação pessoal. */
    PESSOAL("Pessoal", CategoriaAviso.MENSAGEM, "", ""),
    AGENDA("Agenda", CategoriaAviso.COMUNICADO, "Agenda Legislativa", "Agenda"),
    GERAL("Geral", CategoriaAviso.COMUNICADO);

    private final String label;
    private final RotuloAviso rotulo;

    /** Tipos cujos contextos exibidos coincidem com o próprio label. */
    TipoAviso(String label, CategoriaAviso categoria) {
        this(label, categoria, label, label);
    }

    TipoAviso(String label, CategoriaAviso categoria, String contextoPopup, String contextoTabela) {
        this.label = label;
        this.rotulo = new RotuloAviso(categoria, contextoPopup, contextoTabela);
    }

    /** Nome do tipo interno em pt-BR (o que o cadastro escolheu). */
    public String getLabel() { return label; }

    /** Categoria e contextos exibidos quando o cadastro não tem subtipo. */
    public RotuloAviso getRotulo() { return rotulo; }

    /**
     * Valor inicial da exigência de ciência no cadastro — e o valor IMPOSTO nos tipos sem escolha
     * ({@link #cienciaConfiguravel()} falso). Quem manda em quem já está gravado é a coluna
     * EXIGE_CIENCIA do cadastro, não este método.
     */
    public boolean cienciaPadrao() {
        return this == VERIFICACAO || this == ESCALA || this == PESSOAL;
    }

    /**
     * Tipos em que o admin escolhe se a comunicação exige ciência. Verificação (ciência por sala,
     * dentro do fluxo do checklist) e Agenda (visto na exibição) ficam de fora: o valor delas é fixo.
     */
    public boolean cienciaConfiguravel() {
        return this == ESCALA || this == PESSOAL || this == GERAL;
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
