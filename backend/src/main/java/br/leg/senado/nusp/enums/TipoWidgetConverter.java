package br.leg.senado.nusp.enums;

import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoWidgetConverter extends AbstractEnumConverter<TipoWidget> {

    public TipoWidgetConverter() {
        super(TipoWidget::getValor, TipoWidget::fromValor);
    }
}
