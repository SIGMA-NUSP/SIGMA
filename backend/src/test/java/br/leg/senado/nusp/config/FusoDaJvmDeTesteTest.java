package br.leg.senado.nusp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guarda o pin de fuso da JVM do surefire ({@code argLine} do pom): {@code America/Sao_Paulo},
 * espelhando o {@code TZ} declarado nos containers. Com
 * {@code hibernate.type.preferred_instant_jdbc_type: TIMESTAMP}, a escrita de {@code Instant}
 * grava o wall-clock da zona default da JVM — uma JVM noutro fuso gravaria valores diferentes
 * dos de produção. Não valida a escrita em si (isso é do {@code InstantJdbcTypeIT}, failsafe,
 * que confirma o fork dele); guarda só a CONFIGURAÇÃO: um refactor do pom ou um
 * {@code -DargLine=...} na linha de comando desfaria o pin em silêncio.
 */
class FusoDaJvmDeTesteTest {

    @Test
    @DisplayName("a JVM do surefire roda em America/Sao_Paulo, espelhando os containers")
    void jvmDosTestesUnitariosRodaEmBrasilia() {
        assertEquals("America/Sao_Paulo", TimeZone.getDefault().getID(),
                "o argLine -Duser.timezone=America/Sao_Paulo de <properties> não chegou ao fork do"
                        + " surefire: os testes rodariam noutro fuso, e não no dos containers (BRT)");
    }
}
