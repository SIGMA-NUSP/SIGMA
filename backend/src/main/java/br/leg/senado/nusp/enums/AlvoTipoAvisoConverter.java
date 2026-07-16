package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AlvoTipoAvisoConverter extends AbstractEnumConverter<AlvoTipoAviso> {

    public AlvoTipoAvisoConverter() {
        super(AlvoTipoAviso::name, dbValue -> AlvoTipoAviso.valueOf(dbValue.trim().toUpperCase()));
    }
}
