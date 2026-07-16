package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.ChecklistOperador;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChecklistOperadorRepository extends JpaRepository<ChecklistOperador, Long> {

    List<ChecklistOperador> findByChecklistId(Long checklistId);

    @Transactional
    void deleteByChecklistId(Long checklistId);
}
