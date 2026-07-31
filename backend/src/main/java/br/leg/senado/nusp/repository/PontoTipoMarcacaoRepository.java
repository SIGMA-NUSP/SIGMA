package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PontoTipoMarcacaoRepository extends JpaRepository<PontoTipoMarcacao, String> {

    /** Tipos de um escopo (GLOBAL | INDIVIDUAL) — os chips do modal de ocorrências. */
    List<PontoTipoMarcacao> findByEscopo(String escopo);

    /**
     * Tipos que já ocupam algum destes nomes/badges normalizados — a checagem de unicidade
     * global do cadastro, numa consulta só para o lote inteiro.
     */
    List<PontoTipoMarcacao> findByNomeNormInOrBadgeNormIn(Collection<String> nomesNorm,
                                                          Collection<String> badgesNorm);

    /**
     * Trava a linha do tipo enquanto a exclusão levanta e apaga as marcações dele — duas
     * exclusões do mesmo tipo serializam, e nenhuma conta o que a outra já apagou.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PontoTipoMarcacao t WHERE t.id = :id")
    Optional<PontoTipoMarcacao> lockPorId(@Param("id") String id);
}
