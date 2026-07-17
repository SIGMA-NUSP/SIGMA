package br.leg.senado.nusp.service;

import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialClob;
import java.math.BigDecimal;
import java.sql.Clob;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static br.leg.senado.nusp.service.NativeQueryUtils.ORACLE_IN_LIMIT;
import static br.leg.senado.nusp.service.NativeQueryUtils.partitionForIn;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Testes unitários do NativeQueryUtils.
 *
 * partitionForIn — o chunking protege as buscas em lote IN dos relatórios
 * contra o ORA-01795 (máx. 1000 itens por lista IN); os dados reais não
 * alcançam o limite, então a validação do particionador é sintética.
 */
class NativeQueryUtilsTest {

    private static List<Integer> range(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Nested
    @DisplayName("partitionForIn")
    class PartitionForIn {

        @Test
        @DisplayName("coleção vazia → nenhum bloco (nenhuma query)")
        void empty_returnsNoBlocks() {
            assertTrue(partitionForIn(List.of()).isEmpty());
        }

        @Test
        @DisplayName("abaixo do limite → um único bloco com todos os itens")
        void belowLimit_singleBlock() {
            List<List<Integer>> blocos = partitionForIn(range(999));
            assertEquals(1, blocos.size());
            assertEquals(range(999), blocos.get(0));
        }

        @Test
        @DisplayName("exatamente no limite → um único bloco cheio")
        void atLimit_singleFullBlock() {
            List<List<Integer>> blocos = partitionForIn(range(ORACLE_IN_LIMIT));
            assertEquals(1, blocos.size());
            assertEquals(ORACLE_IN_LIMIT, blocos.get(0).size());
        }

        @Test
        @DisplayName("limite + 1 → dois blocos (1000 e 1)")
        void oneOverLimit_splitsIntoTwo() {
            List<List<Integer>> blocos = partitionForIn(range(ORACLE_IN_LIMIT + 1));
            assertEquals(2, blocos.size());
            assertEquals(ORACLE_IN_LIMIT, blocos.get(0).size());
            assertEquals(1, blocos.get(1).size());
        }

        @Test
        @DisplayName("nenhum bloco excede o limite, ordem e conteúdo preservados")
        void largeInput_preservesOrderAndBound() {
            List<Integer> entrada = range(2500);
            List<List<Integer>> blocos = partitionForIn(entrada);
            assertEquals(3, blocos.size());
            for (List<Integer> bloco : blocos) {
                assertTrue(bloco.size() <= ORACLE_IN_LIMIT);
            }
            assertEquals(entrada, blocos.stream().flatMap(List::stream).toList());
        }
    }

    @Nested
    @DisplayName("coerção — str(Object), num, boolVal, bool, intVal")
    class Coercao {

        @Test
        @DisplayName("str(Object) — null vira null; Object comum usa toString; CLOB do Oracle é lido por inteiro")
        void str_convertsObjectAndOracleClob() throws Exception {
            assertNull(NativeQueryUtils.str(null));
            assertEquals("42", NativeQueryUtils.str(42));

            Clob clob = new SerialClob("conteúdo do clob".toCharArray());
            assertEquals("conteúdo do clob", NativeQueryUtils.str(clob));
        }

        @Test
        @DisplayName("num — null vira null; Number (inclusive BigDecimal do Oracle) vira Long")
        void num_convertsNumberIncludingOracleBigDecimal() {
            assertNull(NativeQueryUtils.num(null));
            assertEquals(42L, NativeQueryUtils.num(new BigDecimal("42")));
            assertEquals(7L, NativeQueryUtils.num(7));
        }

        @Test
        @DisplayName("boolVal — NUMBER(1) do Oracle: 1 é true; 0, outro número e null são false")
        void boolVal_treatsOracleNumber1AsTrue() {
            assertFalse(NativeQueryUtils.boolVal(null));
            assertTrue(NativeQueryUtils.boolVal(new BigDecimal("1")));
            assertFalse(NativeQueryUtils.boolVal(new BigDecimal("0")));
            assertFalse(NativeQueryUtils.boolVal(new BigDecimal("2")));
        }

        @Test
        @DisplayName("bool(Map,key) — Boolean, Number e String tolerante (true/1/sim); ausente é false")
        void bool_toleratesBooleanNumberAndString() {
            assertTrue(NativeQueryUtils.bool(Map.of("a", Boolean.TRUE), "a"));
            assertFalse(NativeQueryUtils.bool(Map.of("a", Boolean.FALSE), "a"));
            assertTrue(NativeQueryUtils.bool(Map.of("a", 1), "a"));
            assertFalse(NativeQueryUtils.bool(Map.of("a", 0), "a"));
            assertTrue(NativeQueryUtils.bool(Map.of("a", "true"), "a"));
            assertTrue(NativeQueryUtils.bool(Map.of("a", "SIM"), "a"));
            assertTrue(NativeQueryUtils.bool(Map.of("a", "1"), "a"));
            assertFalse(NativeQueryUtils.bool(Map.of("a", "nao"), "a"));
            assertFalse(NativeQueryUtils.bool(Map.of(), "a"));
        }

        @Test
        @DisplayName("intVal — Number vira int; String numérica faz parse; não-numérica e ausente viram 0")
        void intVal_parsesOrDefaultsToZero() {
            assertEquals(42, NativeQueryUtils.intVal(Map.of("a", 42), "a"));
            assertEquals(7, NativeQueryUtils.intVal(Map.of("a", "7"), "a"));
            assertEquals(0, NativeQueryUtils.intVal(Map.of("a", "abc"), "a"));
            assertEquals(0, NativeQueryUtils.intVal(Map.of(), "a"));
        }
    }

    @Nested
    @DisplayName("strings — clean, blankToNull, hhmm, str(Map,keys), strOrDefault, nonEmpty, localDisplay, semAcento, mesPtParaNumero")
    class Strings {

        @Test
        @DisplayName("clean — apara espaços; chave ausente vira string vazia")
        void clean_stripsOrEmpty() {
            assertEquals("valor", NativeQueryUtils.clean(Map.of("a", "  valor  "), "a"));
            assertEquals("", NativeQueryUtils.clean(Map.of(), "a"));
        }

        @Test
        @DisplayName("blankToNull — null e em branco viram null; com conteúdo, apara")
        void blankToNull_normalizesBlankToNull() {
            assertNull(NativeQueryUtils.blankToNull(null));
            assertNull(NativeQueryUtils.blankToNull("   "));
            assertEquals("abc", NativeQueryUtils.blankToNull("  abc  "));
        }

        @Test
        @DisplayName("hhmm — HH:MM:SS vira HH:MM; null e string curta retornam o original")
        void hhmm_truncatesSecondsOnly() {
            assertEquals("14:30", NativeQueryUtils.hhmm("14:30:00"));
            assertNull(NativeQueryUtils.hhmm(null));
            assertEquals("1:3", NativeQueryUtils.hhmm("1:3"));
        }

        @Test
        @DisplayName("str(Map,keys) — primeiro valor não-vazio entre as chaves; nenhum encontrado vira \"--\"")
        void strWithKeys_firstNonEmptyWins() {
            assertEquals("b", NativeQueryUtils.str(Map.of("k1", "", "k2", "b"), "k1", "k2"));
            assertEquals("--", NativeQueryUtils.str(Map.of(), "k1", "k2"));
        }

        @Test
        @DisplayName("strOrDefault — presente vira toString; ausente vira o default")
        void strOrDefault_usesDefaultWhenAbsent() {
            assertEquals("42", NativeQueryUtils.strOrDefault(Map.of("a", 42), "a", "def"));
            assertEquals("def", NativeQueryUtils.strOrDefault(Map.of(), "a", "def"));
        }

        @Test
        @DisplayName("nonEmpty — presente e não-branco (aparado) vence; ausente ou só espaços usam o default")
        void nonEmpty_defaultsOnBlankOrAbsent() {
            assertEquals("valor", NativeQueryUtils.nonEmpty(Map.of("a", "  valor  "), "a", "def"));
            assertEquals("def", NativeQueryUtils.nonEmpty(Map.of("a", "   "), "a", "def"));
            assertEquals("def", NativeQueryUtils.nonEmpty(Map.of(), "a", "def"));
        }

        @Test
        @DisplayName("localDisplay — concatena \"Sala: nome livre\" só quando nome_demais_salas está preenchido")
        void localDisplay_concatenatesOnlyWhenDemaisSalasPreenchido() {
            Map<String, Object> semDemais = Map.of("sala_nome", "Plenário X");
            assertEquals("Plenário X", NativeQueryUtils.localDisplay(semDemais, "sala_nome"));

            Map<String, Object> comDemais = Map.of("sala_nome", "Sala 5", "nome_demais_salas", "Sala Extra");
            assertEquals("Sala 5: Sala Extra", NativeQueryUtils.localDisplay(comDemais, "sala_nome"));
        }

        @Test
        @DisplayName("semAcento — minúsculas sem diacríticos; null vira string vazia")
        void semAcento_removesDiacriticsAndLowercases() {
            assertEquals("sao paulo", NativeQueryUtils.semAcento("São Paulo"));
            assertEquals("", NativeQueryUtils.semAcento(null));
        }

        /**
         * O ordinal é o caso que obriga o NFKD. O NFD decompõe acentos mas deixa {@code ª}
         * (U+00AA) intacto — e 0xAA ordena DEPOIS do "z", enquanto o Oracle, sob
         * {@code NLS_SORT=BINARY_AI}, trata o mesmo caractere como um {@code a} (chave medida:
         * {@code NLSSORT('1ª Reuniao')} == {@code NLSSORT('1a Reuniao')}). Não é hipótese: o sistema
         * tem centenas de {@code NOME_EVENTO} como "1ª Reunião" — coluna que é faceta E busca. Com
         * NFD, a faceta (ordenada em Java) e a coluna (ordenada no banco) discordariam nessas linhas.
         */
        @Test
        @DisplayName("semAcento — o ordinal ª/º vira a/o, como no Oracle (NFKD, não NFD)")
        void semAcento_normalizaOrdinalComoOOracle() {
            assertEquals("1a reuniao", NativeQueryUtils.semAcento("1ª Reunião"));
            assertEquals("2o turno", NativeQueryUtils.semAcento("2º Turno"));
        }

        /**
         * A ordenação pt-BR do sistema, do lado Java: acento e caixa com peso zero, desempate pelo
         * valor cru. É a régua que as listas ordenadas em memória (facetas, grade do Ponto, seletor
         * de pessoas) precisam usar para não discordar das que vêm ordenadas do banco.
         */
        @Test
        @DisplayName("ORDEM_TEXTO_PT_BR — acento/caixa não contam; o desempate é estável")
        void ordemTextoPtBr_ignoraAcentoECaixaComDesempateEstavel() {
            List<String> nomes = new java.util.ArrayList<>(List.of(
                    "Zulmira", "Kátia Mayara", "Katiane", "JOSE SILVA", "José Silva", "Jose Silva", "1ª Reunião"));
            nomes.sort(NativeQueryUtils.ORDEM_TEXTO_PT_BR);

            assertEquals(List.of("1ª Reunião", "JOSE SILVA", "Jose Silva", "José Silva",
                            "Kátia Mayara", "Katiane", "Zulmira"), nomes,
                    "Kátia antes de Katiane (o acento não empurra o nome para o fim); as 3 grafias de"
                            + " 'Jose Silva' colidem na chave e saem em ordem binária estável");
        }

        /**
         * Guarda do {@code Locale.ROOT}. O {@code semAcento} é a chave de ordenação das
         * facetas — ele tem de reproduzir, em Java, a colação {@code BINARY_AI} do Oracle. Um
         * {@code toLowerCase()} sem locale usa o default da JVM: em tr/az o 'I' vira 'ı'
         * (U+0131) e a ordem passaria a depender de onde o processo roda — o mesmo defeito,
         * na outra ponta.
         */
        @Test
        @DisplayName("semAcento — a normalização NÃO depende do locale default da JVM")
        void semAcento_naoDependeDoLocaleDaJvm() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));   // o locale que quebra toLowerCase()
                assertEquals("italo iris", NativeQueryUtils.semAcento("ÍTALO ÍRIS"),
                        "sob tr-TR, um toLowerCase() sem Locale.ROOT devolveria 'ıtalo ırıs' — e a"
                                + " faceta ordenaria diferente do banco");
                assertEquals("sao paulo", NativeQueryUtils.semAcento("São Paulo"));
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        @DisplayName("mesPtParaNumero — tolerante a caixa/espaços, aceita \"marco\" sem cedilha; desconhecido vira 0")
        void mesPtParaNumero_toleratesCaseSpacingAndNoCedilla() {
            assertEquals(1, NativeQueryUtils.mesPtParaNumero("  Janeiro  "));
            assertEquals(3, NativeQueryUtils.mesPtParaNumero("marco"));
            assertEquals(3, NativeQueryUtils.mesPtParaNumero("março"));
            assertEquals(12, NativeQueryUtils.mesPtParaNumero("DEZEMBRO"));
            assertEquals(0, NativeQueryUtils.mesPtParaNumero("mês inexistente"));
        }

        @Test
        @DisplayName("PT_BR_MONTHS — 12 meses minúsculos, índice 0 = janeiro")
        void ptBrMonths_hasTwelveLowercaseMonths() {
            assertEquals(12, NativeQueryUtils.PT_BR_MONTHS.length);
            assertEquals("janeiro", NativeQueryUtils.PT_BR_MONTHS[0]);
            assertEquals("dezembro", NativeQueryUtils.PT_BR_MONTHS[11]);
        }
    }

    @Nested
    @DisplayName("binds — inPlaceholders, inPlaceholdersNumbered, bindList")
    class Binds {

        @Test
        @DisplayName("inPlaceholders — n placeholders posicionais separados por vírgula")
        void inPlaceholders_buildsPositionalPlaceholders() {
            assertEquals("", NativeQueryUtils.inPlaceholders(0));
            assertEquals("?", NativeQueryUtils.inPlaceholders(1));
            assertEquals("?,?,?", NativeQueryUtils.inPlaceholders(3));
        }

        @Test
        @DisplayName("inPlaceholdersNumbered — placeholders numerados ?1,?2,...")
        void inPlaceholdersNumbered_buildsNumberedPlaceholders() {
            assertEquals("", NativeQueryUtils.inPlaceholdersNumbered(0));
            assertEquals("?1", NativeQueryUtils.inPlaceholdersNumbered(1));
            assertEquals("?1,?2,?3", NativeQueryUtils.inPlaceholdersNumbered(3));
        }

        @Test
        @DisplayName("bindList — contrato posicional: valores bindados a partir de primeiroIndice, em ordem (único mock do arquivo)")
        void bindList_bindsPositionallyFromFirstIndex() {
            Query query = mock(Query.class);

            NativeQueryUtils.bindList(query, List.of("a", "b", "c"), 2);

            verify(query).setParameter(2, "a");
            verify(query).setParameter(3, "b");
            verify(query).setParameter(4, "c");
            verifyNoMoreInteractions(query);
        }
    }

    @Nested
    @DisplayName("userRowToMap")
    class UserRowToMap {

        @Test
        @DisplayName("mapeia as 5 posições da row para as chaves esperadas")
        void mapsPositionsToExpectedKeys() {
            Object[] row = {"OPERADOR", 42L, "Fulano da Silva", "fulano", "fulano@senado.leg.br"};

            Map<String, String> user = NativeQueryUtils.userRowToMap(row);

            assertEquals("OPERADOR", user.get("perfil"));
            assertEquals("42", user.get("id"));
            assertEquals("Fulano da Silva", user.get("nome_completo"));
            assertEquals("fulano", user.get("username"));
            assertEquals("fulano@senado.leg.br", user.get("email"));
        }

        @Test
        @DisplayName("posição null vira a STRING \"null\" (String.valueOf, comportamento intencional)")
        void nullPosition_becomesLiteralNullString() {
            Object[] row = {"TECNICO", 7L, null, "tecnico1", null};

            Map<String, String> user = NativeQueryUtils.userRowToMap(row);

            assertEquals("null", user.get("nome_completo"));
            assertEquals("null", user.get("email"));
        }
    }
}
