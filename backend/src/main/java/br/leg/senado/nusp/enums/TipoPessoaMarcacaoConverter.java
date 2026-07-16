package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoPessoaMarcacaoConverter extends AbstractEnumConverter<TipoPessoaMarcacao> {

    public TipoPessoaMarcacaoConverter() {
        super(TipoPessoaMarcacao::getValor, TipoPessoaMarcacao::fromValor);
    }
}
