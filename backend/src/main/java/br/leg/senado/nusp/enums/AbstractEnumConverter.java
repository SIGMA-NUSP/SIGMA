package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;

import java.util.function.Function;

/**
 * Base comum dos converters JPA de enum ↔ coluna VARCHAR2 do Oracle.
 *
 * <p>Cada converter concreto continua sendo uma classe própria anotada com
 * {@code @Converter(autoApply = true)} (a JPA exige uma classe concreta por tipo para o
 * auto-apply), reduzida a fornecer as duas funções de ida/volta no construtor. As guardas de
 * null/blank ficam aqui, idênticas às que estavam em cada converter:
 * {@code null → null} na ida, e {@code null || isBlank() → null} na volta.
 */
abstract class AbstractEnumConverter<E> implements AttributeConverter<E, String> {

    private final Function<E, String> toDb;
    private final Function<String, E> fromDb;

    protected AbstractEnumConverter(Function<E, String> toDb, Function<String, E> fromDb) {
        this.toDb = toDb;
        this.fromDb = fromDb;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : toDb.apply(attribute);
    }

    @Override
    public E convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return fromDb.apply(dbValue);
    }
}
