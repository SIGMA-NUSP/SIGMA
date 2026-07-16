package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Suspensao;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuspensaoRepository extends JpaRepository<Suspensao, Long> {

    List<Suspensao> findByEntradaIdOrderByOrdemAsc(Long entradaId);

    @Transactional
    void deleteByEntradaId(Long entradaId);
}
