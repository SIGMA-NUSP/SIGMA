package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoBancoSaldo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PontoBancoSaldoRepository extends JpaRepository<PontoBancoSaldo, String> {

    /** Linha única da pessoa (UK UQ_PNT_SALDO_PESSOA) — alvo do upsert da re-âncora. */
    Optional<PontoBancoSaldo> findByPessoaIdAndPessoaTipo(String pessoaId, String pessoaTipo);

    /**
     * Mesma linha única, com SELECT ... FOR UPDATE: serializa as transações de
     * escrita de saldo da MESMA pessoa (a FBI só barra dia duplicado; sem o
     * lock, dois pedidos concorrentes de dias DISTINTOS passariam ambos na
     * validação de saldo — Q10). É o portão de TODA escrita de saldo: a
     * {@code SaldoAberturaService#reancorar} também o atravessa (F60), e por
     * isso publicação e solicitação da mesma pessoa não intercalam mais o
     * par ler-débitos/gravar-saldo. Linha inexistente não bloqueia nada, o que
     * é inócuo: pessoa sem linha tem saldo 0 e falha por saldo insuficiente.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PontoBancoSaldo b WHERE b.pessoaId = :pessoaId AND b.pessoaTipo = :pessoaTipo")
    Optional<PontoBancoSaldo> lockPorPessoa(@Param("pessoaId") String pessoaId,
                                            @Param("pessoaTipo") String pessoaTipo);
}
