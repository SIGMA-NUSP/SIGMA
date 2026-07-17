package br.leg.senado.nusp.service;

import br.leg.senado.nusp.exception.ServiceValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * corrige F73 (C19) — a régua única de horário de entrada manual. O contrato completo
 * fica travado aqui; os testes por PORTA (Operacao/Checklist/AnormalidadeServiceTest)
 * provam só que a régua está LIGADA em cada campo.
 */
class HoraValidatorTest {

    private static final String ROTULO = "Término do evento";

    @Nested
    @DisplayName("corrige F73 — valores válidos passam")
    class Validos {

        @Test
        @DisplayName("HH:MM com comSegundos completa ':00' (formato das colunas de operação)")
        void hhmm_comSegundos_completa() {
            assertEquals("08:00:00", HoraValidator.normalizar("08:00", ROTULO, true));
        }

        @Test
        @DisplayName("HH:MM sem comSegundos fica como chegou (formato vivo da anormalidade)")
        void hhmm_semSegundos_intacto() {
            assertEquals("08:00", HoraValidator.normalizar("08:00", ROTULO, false));
        }

        @Test
        @DisplayName("HH:MM:SS passa intacto nas duas variantes (segundos vivos do checklist)")
        void hhmmss_intacto() {
            assertEquals("23:59:59", HoraValidator.normalizar("23:59:59", ROTULO, true));
            assertEquals("10:36:50", HoraValidator.normalizar("10:36:50", ROTULO, false));
        }

        @Test
        @DisplayName("espaços em volta são tolerados (strip antes de validar)")
        void espacos_strip() {
            assertEquals("14:30:00", HoraValidator.normalizar("  14:30  ", ROTULO, true));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("null/vazio/blank → null (campo opcional segue opcional; obrigatoriedade é da porta)")
        void vazio_null(String valor) {
            assertNull(HoraValidator.normalizar(valor, ROTULO, true));
        }
    }

    @Nested
    @DisplayName("corrige F73 — tortas recusam com 400 nomeando o campo")
    class Recusas {

        @ParameterizedTest
        @ValueSource(strings = {"24:00:00", "24:00", "25:10:00", "12:60:00", "12:00:60",
                "xx", "1:5", "123:00", "08-00", "0800", "8:00", "xx:yy:zz"})
        @DisplayName("fora de HH:MM[:SS] 00–23/00–59 → ServiceValidationException 400")
        void torta_recusa(String valor) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> HoraValidator.normalizar(valor, ROTULO, true));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("'" + ROTULO + "'"),
                    "a mensagem nomeia o campo pelo rótulo da tela: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("'" + valor + "'"),
                    "a mensagem mostra o valor recusado: " + ex.getMessage());
        }

        @Test
        @DisplayName("24:00:00 é recusado NA PORTA (assunção do Douglas: 24:00 nunca é fim legítimo)")
        void vinteEQuatro_recusa() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> HoraValidator.normalizar("24:00:00", ROTULO, true));
            assertEquals("Horário inválido em 'Término do evento': '24:00:00'. Use o formato HH:MM.",
                    ex.getMessage());
        }
    }
}
