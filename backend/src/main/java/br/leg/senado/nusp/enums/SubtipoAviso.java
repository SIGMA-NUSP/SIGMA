package br.leg.senado.nusp.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Subtipo de um aviso — código estável gravado em {@code FRM_AVISO_CADASTRO.SUBTIPO} (valor = nome
 * do enum), do qual o backend deriva o {@link RotuloAviso} exibido: a categoria da comunicação e os
 * contextos do popup e da tabela do admin.
 *
 * <p>Nulo é válido e esperado: avisos de Verificação e todo o legado PESSOAL ficam sem subtipo e
 * caem no rótulo do {@link TipoAviso}. Nunca derivar uma leitura de um subtipo assumindo não-nulo.
 */
public enum SubtipoAviso {
    FOLHA_SEMANAL(CategoriaAviso.NOTIFICACAO, "Folha semanal disponível", "Folha Semanal"),
    FOLHA_MENSAL(CategoriaAviso.NOTIFICACAO, "Folha mensal disponível", "Folha Mensal"),
    /** Folha publicada em que algum dia ficou com entrada ou saída faltando. */
    FOLHA_REGISTRO_INCOMPLETO(CategoriaAviso.NOTIFICACAO, "Folha disponível", "Registros incompletos"),
    SOLICITACAO_APROVADA(CategoriaAviso.NOTIFICACAO, "Solicitação aprovada", "Solicitação Banco"),
    SOLICITACAO_REJEITADA(CategoriaAviso.NOTIFICACAO, "Solicitação rejeitada", "Solicitação Banco"),
    ESCALA(CategoriaAviso.AVISO, "Escala", "Escala"),
    AGENDA(CategoriaAviso.COMUNICADO, "Agenda Legislativa", "Agenda"),
    /** Do administrador para pessoas específicas: a categoria já a identifica — sem contexto. */
    PESSOAL(CategoriaAviso.MENSAGEM, "", ""),
    GRUPO_OPERADORES(CategoriaAviso.COMUNICADO, "Operadores", "Operadores"),
    GRUPO_TECNICOS(CategoriaAviso.COMUNICADO, "Técnicos", "Técnicos"),
    GRUPO_TODOS(CategoriaAviso.COMUNICADO, "Todos", "Operadores e Técnicos"),
    GRUPO_ADMINISTRADORES(CategoriaAviso.COMUNICADO, "Administradores", "Administradores");

    private final RotuloAviso rotulo;

    SubtipoAviso(CategoriaAviso categoria, String contextoPopup, String contextoTabela) {
        this.rotulo = new RotuloAviso(categoria, contextoPopup, contextoTabela);
    }

    /** Categoria e contextos exibidos para este subtipo. */
    public RotuloAviso getRotulo() { return rotulo; }

    /** Nulo/branco → null (subtipo é opcional: Verificação e legado não têm). */
    @JsonCreator
    public static SubtipoAviso fromString(String v) {
        if (v == null || v.isBlank()) return null;
        return SubtipoAviso.valueOf(v.trim().toUpperCase());
    }
}
