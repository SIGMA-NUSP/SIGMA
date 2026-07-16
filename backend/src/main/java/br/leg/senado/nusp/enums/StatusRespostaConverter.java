package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StatusRespostaConverter extends AbstractEnumConverter<StatusResposta> {

    public StatusRespostaConverter() {
        super(StatusResposta::getValor, StatusResposta::fromValor);
    }
}
