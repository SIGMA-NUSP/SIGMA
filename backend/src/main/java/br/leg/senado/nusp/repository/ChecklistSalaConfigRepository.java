package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.ChecklistSalaConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChecklistSalaConfigRepository extends JpaRepository<ChecklistSalaConfig, Integer> {

    /**
     * Lista itens de checklist configurados para uma sala (ativos + inativos).
     * Equivale a list_sala_config_items() do Python.
     */
    @Query(value = """
            SELECT csc.ID, csc.ITEM_TIPO_ID, cit.NOME, cit.TIPO_WIDGET,
                   csc.ORDEM, csc.ATIVO
            FROM FRM_CHECKLIST_SALA_CONFIG csc
            INNER JOIN FRM_CHECKLIST_ITEM_TIPO cit ON csc.ITEM_TIPO_ID = cit.ID
            WHERE csc.SALA_ID = :salaId
            ORDER BY csc.ATIVO DESC, csc.ORDEM ASC NULLS LAST, cit.NOME ASC
            """, nativeQuery = true)
    List<Object[]> findConfigItemsBySalaId(@Param("salaId") int salaId);

    /** Desativa todos os itens de uma sala (ativo=0, ordem=0). */
    @Modifying
    @Query("UPDATE ChecklistSalaConfig c SET c.ativo = false, c.ordem = 0 WHERE c.salaId = :salaId")
    void deactivateAllBySalaId(@Param("salaId") int salaId);

    Optional<ChecklistSalaConfig> findBySalaIdAndItemTipoId(int salaId, int itemTipoId);
}
