package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoEventoConverter extends AbstractEnumConverter<TipoEvento> {

    public TipoEventoConverter() {
        super(TipoEvento::getValor, TipoEvento::fromValor);
    }
}
