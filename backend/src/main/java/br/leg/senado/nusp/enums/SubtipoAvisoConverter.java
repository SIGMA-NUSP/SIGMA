package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SubtipoAvisoConverter extends AbstractEnumConverter<SubtipoAviso> {

    public SubtipoAvisoConverter() {
        super(SubtipoAviso::name, dbValue -> SubtipoAviso.valueOf(dbValue.trim().toUpperCase()));
    }
}
