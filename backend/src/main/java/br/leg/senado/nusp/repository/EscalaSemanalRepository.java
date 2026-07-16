package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.EscalaSemanal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EscalaSemanalRepository extends JpaRepository<EscalaSemanal, Long> {

    @Query("SELECT e FROM EscalaSemanal e ORDER BY e.dataInicio DESC")
    List<EscalaSemanal> findAllOrderByDataInicioDesc();

    /** Escala vigente para uma data específica */
    @Query("SELECT e FROM EscalaSemanal e WHERE e.dataInicio <= :data AND e.dataFim >= :data ORDER BY e.id DESC")
    List<EscalaSemanal> findVigentesPorData(@Param("data") LocalDate data);

    /** Escala imediatamente anterior a um novo período. */
    Optional<EscalaSemanal> findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(LocalDate dataInicio);
}
