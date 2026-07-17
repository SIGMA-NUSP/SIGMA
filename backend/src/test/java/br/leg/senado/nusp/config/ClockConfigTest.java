package br.leg.senado.nusp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guarda o bean {@link Clock} da aplicação: {@code systemDefaultZone()} tem de resolver para
 * {@code America/Sao_Paulo}. A JVM de teste está pinada em BRT pelo {@code argLine} do pom,
 * espelhando o {@code TZ} declarado nos containers — por isso a asserção é direta, sem mutar
 * {@code TimeZone.setDefault} (nenhum estado global do processo é tocado).
 */
class ClockConfigTest {

    @Test
    @DisplayName("o relógio da aplicação resolve para America/Sao_Paulo (o 'hoje' é BRT)")
    void clock_resolveParaBrasilia() {
        // Com o TZ declarado nos containers (e o pin do pom espelhando-o nos testes), a zona
        // default da JVM é America/Sao_Paulo — logo systemDefaultZone() resolve para BRT, e o
        // corte do dia deixa de escorregar entre 21h e 00h (prazo de retificação, "mês corrente",
        // escala do dia). Reverter o pin do pom OU o TZ do container derruba esta asserção — o alarme.
        assertEquals(ZoneId.of("America/Sao_Paulo"), new ClockConfig().clock().getZone(),
                "o bean é systemDefaultZone(): com a JVM pinada em BRT (pom, espelhando os"
                        + " containers), o relógio da aplicação resolve para America/Sao_Paulo");
    }
}
