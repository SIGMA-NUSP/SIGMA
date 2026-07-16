package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.EntradaOperador;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntradaOperadorRepository extends JpaRepository<EntradaOperador, Long> {

    List<EntradaOperador> findByEntradaId(Long entradaId);

    @Transactional
    void deleteByEntradaId(Long entradaId);
}
