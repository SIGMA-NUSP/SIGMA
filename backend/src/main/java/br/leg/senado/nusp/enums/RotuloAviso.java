package br.leg.senado.nusp.enums;

/**
 * Como um aviso se apresenta ao usuário: a {@link CategoriaAviso} e o CONTEXTO que a qualifica, em
 * duas leituras propositalmente diferentes:
 *
 * <ul>
 *   <li>{@code contextoPopup} — complementa o título do popup ("Aviso — Verificação");</li>
 *   <li>{@code contextoTabela} — vai ao lado do selo na coluna "Tipo" da tabela do admin.</li>
 * </ul>
 *
 * <p>Contexto vazio é válido e esperado: a MENSAGEM se identifica pela categoria sozinha.
 *
 * <p>Os valores moram nos enums {@link SubtipoAviso} (mais específico) e {@link TipoAviso}
 * (fallback do legado e da Verificação, que não têm subtipo); {@link #de} escolhe entre eles.
 * É a fonte única desses rótulos — popup, listagem e detalhe derivam todos daqui.
 */
public record RotuloAviso(CategoriaAviso categoria, String contextoPopup, String contextoTabela) {

    /**
     * Rótulo de um aviso: do SUBTIPO quando houver, senão do TIPO. Subtipo nulo é o caso normal do
     * legado e da Verificação; tipo nulo não existe em base íntegra, e cai em MENSAGEM sem contexto
     * para que a exibição nunca quebre.
     */
    public static RotuloAviso de(TipoAviso tipo, SubtipoAviso subtipo) {
        if (subtipo != null) return subtipo.getRotulo();
        if (tipo != null) return tipo.getRotulo();
        return new RotuloAviso(CategoriaAviso.MENSAGEM, "", "");
    }
}
