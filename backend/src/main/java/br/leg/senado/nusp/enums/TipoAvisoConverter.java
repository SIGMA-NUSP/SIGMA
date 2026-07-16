package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoAvisoConverter extends AbstractEnumConverter<TipoAviso> {

    public TipoAvisoConverter() {
        super(TipoAviso::name, dbValue -> TipoAviso.valueOf(dbValue.trim().toUpperCase()));
    }
}
