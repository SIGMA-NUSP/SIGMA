package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoAlvo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AvisoAlvoRepository extends JpaRepository<AvisoAlvo, String> {

    List<AvisoAlvo> findByCadastroId(String cadastroId);
}
