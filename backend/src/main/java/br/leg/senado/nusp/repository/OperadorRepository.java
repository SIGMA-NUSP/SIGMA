package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Operador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OperadorRepository extends JpaRepository<Operador, String> {

    Optional<Operador> findByUsername(String username);
    Optional<Operador> findByEmail(String email);

    /** Para o lookup público: retorna todos ordenados por nome */
    @Query("SELECT o FROM Operador o ORDER BY o.nomeCompleto ASC")
    List<Operador> findAllOrderByNomeCompleto();

    /** Operadores com flag plenário principal */
    @Query("SELECT o FROM Operador o WHERE o.plenarioPrincipal = true ORDER BY o.nomeCompleto ASC")
    List<Operador> findOperadoresPlenarioPrincipal();

    /** Operadores que participam da escala semanal de comissões (rodízio) */
    @Query("SELECT o FROM Operador o WHERE o.participaEscala = true ORDER BY o.nomeCompleto ASC")
    List<Operador> findParticipantesEscala();

    /** Busca foto_url de um operador pelo id */
    @Query("SELECT o.fotoUrl FROM Operador o WHERE o.id = :id")
    Optional<String> findFotoUrlById(@Param("id") String id);
}
