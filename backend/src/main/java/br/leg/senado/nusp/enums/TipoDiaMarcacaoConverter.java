package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoDiaMarcacaoConverter extends AbstractEnumConverter<TipoDiaMarcacao> {

    public TipoDiaMarcacaoConverter() {
        super(TipoDiaMarcacao::getValor, TipoDiaMarcacao::fromValor);
    }
}
