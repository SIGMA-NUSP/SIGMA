package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.ChecklistResposta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChecklistRespostaRepository extends JpaRepository<ChecklistResposta, Long> {

    @Query(value = """
            SELECT ID, ITEM_TIPO_ID, STATUS, DESCRICAO_FALHA, VALOR_TEXTO
            FROM FRM_CHECKLIST_RESPOSTA
            WHERE CHECKLIST_ID = :checklistId
            """, nativeQuery = true)
    List<Object[]> findByChecklistIdNative(@Param("checklistId") long checklistId);

    @Query("SELECT r FROM ChecklistResposta r WHERE r.checklistId = :checklistId AND r.itemTipoId = :itemTipoId")
    java.util.Optional<ChecklistResposta> findByChecklistAndItem(
            @Param("checklistId") long checklistId,
            @Param("itemTipoId") int itemTipoId);

    /** Todas as respostas do checklist como entidades gerenciadas (upsert por mapa em memória — Q17a). */
    List<ChecklistResposta> findByChecklistId(long checklistId);
}
