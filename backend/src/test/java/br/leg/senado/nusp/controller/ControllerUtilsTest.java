package br.leg.senado.nusp.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import br.leg.senado.nusp.exception.ServiceValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ramos dos leitores tipados de corpo cru do {@link ControllerUtils} ({@code optTexto},
 * {@code optBooleano}): matriz completa <b>ausente</b> / {@code null} / <b>tipo certo</b> /
 * <b>tipo errado</b>, inclusive o ramo {@code padrao = false} do booleano, sem consumidor
 * em produção. Os vizinhos ({@code reqTexto}, {@code optLong}, {@code listaDeTextos}) são
 * cobertos pelos slices que os consomem (Escala, AdminCrud).
 *
 * <p>Distinção mantida pelos helpers: <b>ausência não é erro</b> (cai no default),
 * mas <b>tipo errado é</b> — e o 400 nomeia o campo.
 */
class ControllerUtilsTest {

    /** Um {@code Map} que aceita valor nulo — {@code Map.of} não aceita, e o {@code null} é um dos ramos. */
    private static Map<String, Object> corpo(String campo, Object valor) {
        Map<String, Object> body = new HashMap<>();
        body.put(campo, valor);
        return body;
    }

    @Nested
    @DisplayName("optTexto — texto opcional, tipado")
    class OptTexto {

        @Test
        @DisplayName("ausente, corpo nulo ou valor null → null (é a ordem de DESVINCULAR, não um erro)")
        void ausenteOuNulo() {
            assertNull(ControllerUtils.optTexto(null, "pessoa_id"));
            assertNull(ControllerUtils.optTexto(Map.of("outro", "x"), "pessoa_id"));
            assertNull(ControllerUtils.optTexto(corpo("pessoa_id", null), "pessoa_id"));
        }

        @Test
        @DisplayName("String → o valor, intacto (sem strip: quem decide o que é branco é o service)")
        void textoIntacto() {
            assertThat(ControllerUtils.optTexto(Map.of("motivo", "  sem cobertura  "), "motivo"))
                    .isEqualTo("  sem cobertura  ");
            assertThat(ControllerUtils.optTexto(Map.of("pessoa_id", "op-1"), "pessoa_id")).isEqualTo("op-1");
        }

        @Test
        @DisplayName("lista, objeto ou número → 400 nomeando o campo (antes: \"[x]\", \"{a=1}\", \"123\")")
        void tipoErrado() {
            for (Object valor : List.of(List.of("OPERADOR"), Map.of("a", 1), 123)) {
                assertThatExceptionOfType(ServiceValidationException.class)
                        .isThrownBy(() -> ControllerUtils.optTexto(corpo("pessoa_tipo", valor), "pessoa_tipo"))
                        .withMessageContaining("pessoa_tipo")
                        .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
            }
        }
    }

    @Nested
    @DisplayName("optBooleano — booleano opcional, tipado")
    class OptBooleano {

        @Test
        @DisplayName("ausente, corpo nulo ou valor null → o padrão (nos dois sentidos)")
        void ausenteOuNuloCaiNoPadrao() {
            assertThat(ControllerUtils.optBooleano(null, "emitir_aviso", true)).isTrue();
            assertThat(ControllerUtils.optBooleano(Map.of("outro", 1), "emitir_aviso", true)).isTrue();
            assertThat(ControllerUtils.optBooleano(corpo("emitir_aviso", null), "emitir_aviso", true)).isTrue();
            assertThat(ControllerUtils.optBooleano(null, "flag", false)).isFalse();
        }

        @Test
        @DisplayName("Boolean → o valor (o false explícito é o único que desliga o aviso)")
        void booleanoGenuino() {
            assertThat(ControllerUtils.optBooleano(Map.of("emitir_aviso", false), "emitir_aviso", true)).isFalse();
            assertThat(ControllerUtils.optBooleano(Map.of("emitir_aviso", true), "emitir_aviso", false)).isTrue();
        }

        @Test
        @DisplayName("string \"false\", número 0 ou lista → 400 nomeando o campo (antes: passavam como TRUE)")
        void tipoErrado() {
            for (Object valor : List.of("false", 0, List.of(false))) {
                assertThatExceptionOfType(ServiceValidationException.class)
                        .isThrownBy(() -> ControllerUtils.optBooleano(corpo("emitir_aviso", valor),
                                "emitir_aviso", true))
                        .withMessageContaining("emitir_aviso")
                        .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
            }
        }
    }
}
