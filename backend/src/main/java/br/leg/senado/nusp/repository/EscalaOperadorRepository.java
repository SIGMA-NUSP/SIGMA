package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.EscalaOperador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EscalaOperadorRepository extends JpaRepository<EscalaOperador, Long> {

    /** Ordenado por sala e turno ('M' < 'V'), garantindo matutino antes de vespertino na exibição. */
    @Query("SELECT eo FROM EscalaOperador eo WHERE eo.escalaId = :escalaId ORDER BY eo.salaId ASC, eo.turno ASC")
    List<EscalaOperador> findByEscalaId(@Param("escalaId") Long escalaId);

    void deleteByEscalaId(Long escalaId);

    List<EscalaOperador> findByEscalaIdAndOperadorId(Long escalaId, String operadorId);

    /** Retorna linhas [operadorId, salaId, count] agregando aparições por (operador, sala) no histórico. */
    @Query("SELECT eo.operadorId, eo.salaId, COUNT(eo) FROM EscalaOperador eo GROUP BY eo.operadorId, eo.salaId")
    List<Object[]> countAparicoesPorOperadorSala();
}
