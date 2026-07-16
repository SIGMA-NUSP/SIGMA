package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Checklist;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChecklistRepository extends JpaRepository<Checklist, Long> {

    @Query("SELECT c.criadoPor FROM Checklist c WHERE c.id = :id")
    Optional<String> findCriadoPorById(@Param("id") long id);
}
