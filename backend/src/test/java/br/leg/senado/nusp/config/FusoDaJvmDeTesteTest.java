package br.leg.senado.nusp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guarda do pin de fuso das JVMs de teste (property {@code argLine} do pom) — a contramedida que o
 * C5 acrescentou junto com a cura do F14, agora VIRADA para Brasília (F7/C17).
 *
 * <p>Por que o pin existe: com {@code hibernate.type.preferred_instant_jdbc_type: TIMESTAMP}, a
 * ESCRITA de {@code Instant} deixou de ser imune ao fuso da JVM (o Hibernate passou a usar
 * {@code setTimestamp()}, que grava o wall-clock da zona default). Os containers rodam em
 * {@code America/Sao_Paulo} — o TZ passou a ser DECLARADO no runtime (F7/C17); sem o pin, uma JVM de
 * teste noutro fuso (o mini-PC de dev roda em BRT, um CI poderia rodar em UTC) gravaria wall-clock
 * diferente do de produção.
 *
 * <p>⚠️ O que este teste NÃO é: ele não valida a escrita de {@code Instant} — nenhum teste unitário
 * encosta em JDBC. Quem prova a escrita é o {@code InstantJdbcTypeIT} (failsafe), e a suíte unitária
 * é, medida, insensível ao fuso (roda igual em BRT, UTC e UTC+14). O que ele guarda é a CONFIGURAÇÃO:
 * o pom pina os dois forks pela MESMA property, e cada suíte confirma o seu — aqui o do surefire, no
 * {@code InstantJdbcTypeIT} o do failsafe. Sem esta guarda, um refactor do pom (ou um
 * {@code -DargLine=...} na linha de comando) desfaria o pin em silêncio, e só o failsafe reclamaria.
 *
 * <p>Este assert foi movido de {@code UTC} para {@code America/Sao_Paulo} junto com o pom quando o
 * F7/C17 alinhou os containers a BRT — é esse o alarme de que o pin foi movido de propósito.
 */
class FusoDaJvmDeTesteTest {

    @Test
    @DisplayName("pin do C5, virado em C17 — a JVM do surefire roda em America/Sao_Paulo, espelhando os containers (F14/F7)")
    void jvmDosTestesUnitariosRodaEmBrasilia() {
        assertEquals("America/Sao_Paulo", TimeZone.getDefault().getID(),
                "o argLine -Duser.timezone=America/Sao_Paulo de <properties> não chegou ao fork do"
                        + " surefire: os testes rodariam noutro fuso, e não no dos containers (BRT)");
    }
}
