package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoCiencia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AvisoCienciaRepository extends JpaRepository<AvisoCiencia, String> {

    Optional<AvisoCiencia> findByCadastroIdAndSalaIdAndOperadorId(String cadastroId, Integer salaId, String operadorId);

    Optional<AvisoCiencia> findByCadastroIdAndSalaIdAndTecnicoId(String cadastroId, Integer salaId, String tecnicoId);

    // Avisos sem sala (ex.: PESSOAL): a ciência é por (cadastro, pessoa).
    Optional<AvisoCiencia> findByCadastroIdAndOperadorId(String cadastroId, String operadorId);

    Optional<AvisoCiencia> findByCadastroIdAndTecnicoId(String cadastroId, String tecnicoId);

    Optional<AvisoCiencia> findByCadastroIdAndAdminId(String cadastroId, String adminId);

    List<AvisoCiencia> findByCadastroIdOrderByCienteEm(String cadastroId);
}
