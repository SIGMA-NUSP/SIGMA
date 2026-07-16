package br.leg.senado.nusp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guarda do único artefato de PRODUÇÃO que o T26a acrescenta: o bean {@link Clock}.
 *
 * <p>O plano põe {@code config/} fora do alvo de cobertura (§4) — esta classe é a
 * exceção deliberada, e é o gatilho da convenção "corrige Fn" (§0.5). Depois do F7/C17,
 * o bean tem de resolver para o fuso de Brasília: um grep por "F7" mostra este teste já
 * na forma CORRIGIDA (não mais o caracteriza que cimentava "containers em UTC").
 *
 * <p>Por que a asserção é direta, sem mexer na zona da JVM: o bean é
 * {@code systemDefaultZone()} e a JVM de teste está pinada em {@code America/Sao_Paulo}
 * (o {@code argLine} do pom espelha os containers, que passaram a declarar
 * {@code TZ=America/Sao_Paulo} — F7/C17). Então basta afirmar que o Clock resolve para
 * BRT. O antigo {@code caracteriza F7} MUTAVA {@code TimeZone.setDefault} num loop
 * UTC/Tóquio — estado global do processo, que exigia isolamento (F56). Eliminando a
 * mutação, o F56 se dissolve aqui: não há mais estado global tocado.
 */
class ClockConfigTest {

    @Test
    @DisplayName("corrige F7 — o relógio da aplicação resolve para America/Sao_Paulo (o 'hoje' é BRT)")
    void clock_resolveParaBrasilia() {
        // Com o TZ declarado nos containers (e o pin do pom espelhando-o nos testes), a zona
        // default da JVM é America/Sao_Paulo — logo systemDefaultZone() resolve para BRT, e o
        // corte do dia deixa de escorregar entre 21h e 00h (prazo de retificação, "mês corrente",
        // escala do dia). Reverter o pin do pom OU o TZ do container derruba esta asserção — o alarme.
        assertEquals(ZoneId.of("America/Sao_Paulo"), new ClockConfig().clock().getZone(),
                "o bean é systemDefaultZone(): com a JVM pinada em BRT (pom, espelhando os"
                        + " containers do F7/C17), o relógio da aplicação resolve para America/Sao_Paulo");
    }
}
