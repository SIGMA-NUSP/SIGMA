package br.leg.senado.nusp.enums;

/**
 * Categoria de comunicação de um aviso: a leitura de mais alto nível que o destinatário vê — comanda
 * o título do popup, o selo colorido e a coluna "Tipo" da tabela do admin.
 *
 * <p>É DERIVADA do par tipo+subtipo gravado (ver {@link RotuloAviso}) e nunca persistida. O código
 * é estável porque o frontend o usa para escolher a variação de estilo de cada categoria.
 */
public enum CategoriaAviso {
    /** Operacional: pede atenção do destinatário. */
    AVISO("Aviso"),
    /** Institucional: dirigido a um coletivo. */
    COMUNICADO("Comunicado"),
    /** Pessoal: do administrador para pessoas específicas. */
    MENSAGEM("Mensagem"),
    /** Automática: disparada pelo próprio sistema. */
    NOTIFICACAO("Notificação");

    private final String label;

    CategoriaAviso(String label) { this.label = label; }

    /** Texto exibido ao usuário ("Aviso", "Comunicado", "Mensagem", "Notificação"). */
    public String getLabel() { return label; }
}
