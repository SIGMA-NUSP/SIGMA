package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StatusAvisoConverter extends AbstractEnumConverter<StatusAviso> {

    public StatusAvisoConverter() {
        super(StatusAviso::getValor, StatusAviso::fromValor);
    }
}
