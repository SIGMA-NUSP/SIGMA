package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.ChecklistItemTipo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.leg.senado.nusp.enums.TipoWidget;

import java.util.List;
import java.util.Optional;

public interface ChecklistItemTipoRepository extends JpaRepository<ChecklistItemTipo, Integer> {

    /** Para find_or_create na sala config. Índice UQ_FRM_ITEM_TIPO_NOME_WIDGET garante unicidade. */
    Optional<ChecklistItemTipo> findByNomeAndTipoWidget(String nome, TipoWidget tipoWidget);

    @Query("SELECT t FROM ChecklistItemTipo t ORDER BY t.nome ASC")
    List<ChecklistItemTipo> findAllOrdered();

    /** Itens ativos configurados para uma sala (com ordem). Equivale a list_checklist_itens_por_sala(). */
    @Query(value = """
            SELECT t.ID, t.NOME, c.ORDEM, t.TIPO_WIDGET
            FROM FRM_CHECKLIST_SALA_CONFIG c
            JOIN FRM_CHECKLIST_ITEM_TIPO t ON c.ITEM_TIPO_ID = t.ID
            WHERE c.SALA_ID = :salaId AND c.ATIVO = 1
            ORDER BY c.ORDEM ASC, t.ID ASC
            """, nativeQuery = true)
    List<Object[]> findItensPorSala(@Param("salaId") int salaId);
}
