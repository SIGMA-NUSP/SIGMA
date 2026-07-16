package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Administrador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdministradorRepository extends JpaRepository<Administrador, String> {

    Optional<Administrador> findByUsername(String username);
    Optional<Administrador> findByEmail(String email);

    /** Busca foto_url de um administrador pelo id */
    @Query("SELECT a.fotoUrl FROM Administrador a WHERE a.id = :id")
    Optional<String> findFotoUrlById(@Param("id") String id);
}
