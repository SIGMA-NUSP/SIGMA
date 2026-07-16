package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TurnoConverter extends AbstractEnumConverter<Turno> {

    public TurnoConverter() {
        super(Turno::getValor, Turno::fromValor);
    }
}
