package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoLote;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PontoLoteRepository extends JpaRepository<PontoLote, String> {

    /** Lotes mais recentes primeiro (lista do admin). */
    List<PontoLote> findAllByOrderByCriadoEmDesc();

    /**
     * Lote com lock pessimista de linha (SELECT ... FOR UPDATE) — serializa a publicação (F49).
     * Sem ele, duas publicações concorrentes do MESMO lote leem o status REVISAO no mesmo instante,
     * ambas passam da guarda e o lote é publicado duas vezes (avisos pessoais duplicados e re-âncora
     * dupla). Com o lock, a segunda transação espera o commit da primeira, relê a linha já PUBLICADO
     * e cai na recusa honesta ("Lote já está publicado."). Mesmo mecanismo do
     * {@link PontoBancoSaldoRepository#lockPorPessoa}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM PontoLote l WHERE l.id = :id")
    Optional<PontoLote> lockPorId(@Param("id") String id);
}
