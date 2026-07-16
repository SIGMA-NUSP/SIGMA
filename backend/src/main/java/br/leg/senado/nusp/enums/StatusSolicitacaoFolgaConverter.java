package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StatusSolicitacaoFolgaConverter extends AbstractEnumConverter<StatusSolicitacaoFolga> {

    public StatusSolicitacaoFolgaConverter() {
        super(StatusSolicitacaoFolga::getValor, StatusSolicitacaoFolga::fromValor);
    }
}
