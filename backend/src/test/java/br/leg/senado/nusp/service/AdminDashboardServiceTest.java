package br.leg.senado.nusp.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Trava a <b>estrutura</b> dos 3 maps de sort das listagens de pessoas de
 * {@link AdminDashboardService}. O IT ({@code DashboardQueryHelperIT}) prova o
 * <b>efeito</b> — sort inválido ordena por NOME —, mas só na JVM em que roda: com
 * {@code Map.of}, a ordem de iteração é sorteada por processo. O guard determinístico
 * é o <b>tipo</b> do map (ordem de inserção) somado à posição de "nome" — que é o que
 * {@code buildOrderBy} consome ao resolver um sort desconhecido (o 1º valor do map).
 *
 * <p>É também a única cobertura de {@code TEC_SORT}/{@code ADM_SORT}: nenhum teste chama
 * {@code listTecnicos}/{@code listAdministradores} contra banco.
 */
class AdminDashboardServiceTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OP_SORT", "TEC_SORT", "ADM_SORT"})
    @DisplayName("os 3 maps de sort de pessoas têm ordem de iteração previsível, com \"nome\" primeiro")
    @SuppressWarnings("unchecked")
    void mapsDeSortDePessoas_ordemPrevisivelComNomePrimeiro(String campo) {
        Map<String, String> map =
                (Map<String, String>) ReflectionTestUtils.getField(AdminDashboardService.class, campo);

        assertInstanceOf(LinkedHashMap.class, map,
                campo + " não pode ser Map.of: a ordem de iteração dele é randomizada por JVM "
                        + "(ImmutableCollections.SALT) e buildOrderBy resolve o sort desconhecido com o "
                        + "PRIMEIRO valor do map — era assim que a listagem reordenava entre deploys");
        assertEquals(List.of("nome", "email"), List.copyOf(map.keySet()),
                campo + ": \"nome\" tem de ser a 1ª chave — é o defaultValue=\"nome\" das rotas");
    }
}
