package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Tipo de aviso — define em qual tela o aviso aparece e se exige ciência.
 * Persistido em FRM_AVISO_CADASTRO.TIPO (valor = nome do enum).
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
