package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Trava COMO as condições de busca/filtro entram no SQL conforme o formato do {@code fromJoins}:
 * abrem o próprio WHERE quando a query externa não tem um, e emendam com AND quando tem. O caso
 * decisivo é o fromJoins cujo único WHERE vive DENTRO de uma subquery de JOIN — a query externa
 * não tem WHERE, e emendar com AND ali penduraria as condições no ON do último LEFT JOIN, onde
 * elas não eliminam linha nenhuma: busca, período e filtros morreriam em silêncio.
 */
class DashboardQueryHelperTest {

    /** Executa a listagem com uma busca qualquer e devolve o SQL do COUNT (a única query emitida). */
    private String sqlDoCount(String fromJoins) {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class, Mockito.RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(0);   // COUNT = 0 → nem query de dados, nem facetas
        DashboardQueryHelper.executePagedQuery(em, "t.ID", fromJoins, null,
                Map.of("id", "t.ID"), List.of("t.NOME"), Map.of(), Map.of(),
                1, 10, "ana", "id", "asc", null, null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @Test
    @DisplayName("fromJoins sem WHERE: as condições abrem o próprio WHERE")
    void semWhereNoFromJoins() {
        String sql = sqlDoCount("FROM CAD_SALA t");
        assertTrue(sql.contains(" WHERE ("), "as condições devem abrir o WHERE da query externa");
        assertFalse(sql.contains(" AND ("), "sem WHERE prévio não há o que emendar");
    }

    @Test
    @DisplayName("fromJoins com WHERE externo: as condições emendam com AND, sem abrir segundo WHERE")
    void whereExternoNoFromJoins() {
        String sql = sqlDoCount("FROM CAD_SALA t WHERE t.ATIVO = 1");
        assertTrue(sql.contains(" AND ("), "as condições devem emendar no WHERE que o fromJoins já tem");
        assertEquals(sql.indexOf(" WHERE "), sql.lastIndexOf(" WHERE "),
                "o único WHERE do SQL é o que o fromJoins trouxe");
    }

    @Test
    @DisplayName("WHERE só dentro de subquery de JOIN: a query externa não tem WHERE — as condições abrem um")
    void whereSoDentroDeSubqueryDeJoin() {
        String sql = sqlDoCount("FROM CAD_SALA t "
                + "LEFT JOIN (SELECT x.SALA_ID FROM FRM_AVISO_ALVO x WHERE x.ALVO_TIPO = 'SALA' "
                + "GROUP BY x.SALA_ID) ag ON ag.SALA_ID = t.ID");
        assertTrue(sql.contains("ag ON ag.SALA_ID = t.ID WHERE ("),
                "o WHERE novo deve abrir após o ON, no nível da query externa");
        assertFalse(sql.contains(" AND ("),
                "emendar com AND aqui grudaria as condições no ON do LEFT JOIN, sem eliminar linhas");
    }
}
