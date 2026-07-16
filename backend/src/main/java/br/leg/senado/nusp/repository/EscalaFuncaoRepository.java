package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.EscalaFuncao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscalaFuncaoRepository extends JpaRepository<EscalaFuncao, Long> {

    List<EscalaFuncao> findByEscalaId(Long escalaId);

    void deleteByEscalaId(Long escalaId);
}
