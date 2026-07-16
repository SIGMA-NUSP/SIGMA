package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Sala;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SalaRepository extends JpaRepository<Sala, Integer> {

    /**
     * Salas ativas ordenadas por ordem (COALESCE com 9999), nome e id.
     * Equivale ao lookup_salas() do Python.
     */
    @Query("SELECT s FROM Sala s WHERE s.ativo = true ORDER BY COALESCE(s.ordem, 9999), s.nome ASC")
    List<Sala> findAtivasOrdenadas();

    /** Salas ativas SEM multi-operador (para operadores comuns) */
    @Query("SELECT s FROM Sala s WHERE s.ativo = true AND s.multiOperador = false ORDER BY COALESCE(s.ordem, 9999), s.nome ASC")
    List<Sala> findAtivasOrdenadasSemMultiOperador();

    /** Para form-edit: retorna TODAS (ativas primeiro, depois inativas). */
    @Query("SELECT s FROM Sala s ORDER BY s.ativo DESC, COALESCE(s.ordem, 9999) ASC, s.nome ASC, s.id ASC")
    List<Sala> findAllOrdered();
}
