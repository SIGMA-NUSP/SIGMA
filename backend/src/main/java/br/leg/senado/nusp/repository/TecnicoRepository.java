package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Tecnico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TecnicoRepository extends JpaRepository<Tecnico, String> {

    Optional<Tecnico> findByUsername(String username);
    Optional<Tecnico> findByEmail(String email);

    /** Busca foto_url de um técnico pelo id */
    @Query("SELECT t.fotoUrl FROM Tecnico t WHERE t.id = :id")
    Optional<String> findFotoUrlById(@Param("id") String id);
}
