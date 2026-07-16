package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PontoSolicitacaoFolgaRepository extends JpaRepository<PontoSolicitacaoFolga, String> {

    /**
     * Solicitações da pessoa com status no conjunto e DATA_FOLGA no range
     * [ini, fim) — sargável (gotcha 4). Com os status vivos (PENDENTE/APROVADO)
     * alimenta os dias bloqueados e a contagem "Folgas" do mês (Q12/Q13).
     */
    @Query("SELECT s FROM PontoSolicitacaoFolga s " +
           "WHERE s.pessoaId = :pessoaId AND s.pessoaTipo = :pessoaTipo " +
           "AND s.status IN :status AND s.dataFolga >= :ini AND s.dataFolga < :fim " +
           "ORDER BY s.dataFolga")
    List<PontoSolicitacaoFolga> findPorStatusNoRange(@Param("pessoaId") String pessoaId,
                                                     @Param("pessoaTipo") String pessoaTipo,
                                                     @Param("status") Collection<StatusSolicitacaoFolga> status,
                                                     @Param("ini") LocalDate ini,
                                                     @Param("fim") LocalDate fim);

    /**
     * Σ MINUTOS_DEBITADOS dos pedidos vivos (PENDENTE/APROVADO) com DATA_FOLGA
     * posterior à âncora — os únicos que descontam "por fora" do BANCO do PDF,
     * que já embute as folgas do período coberto (Q4).
     */
    @Query("SELECT COALESCE(SUM(s.minutosDebitados), 0) FROM PontoSolicitacaoFolga s " +
           "WHERE s.pessoaId = :pessoaId AND s.pessoaTipo = :pessoaTipo " +
           "AND s.status IN :statusVivos AND s.dataFolga > :apos")
    long somaDebitosVivosApos(@Param("pessoaId") String pessoaId,
                              @Param("pessoaTipo") String pessoaTipo,
                              @Param("statusVivos") Collection<StatusSolicitacaoFolga> statusVivos,
                              @Param("apos") LocalDate apos);

    /** Variante sem âncora (pessoa sem folha oficial): todo pedido vivo desconta. */
    @Query("SELECT COALESCE(SUM(s.minutosDebitados), 0) FROM PontoSolicitacaoFolga s " +
           "WHERE s.pessoaId = :pessoaId AND s.pessoaTipo = :pessoaTipo AND s.status IN :statusVivos")
    long somaDebitosVivos(@Param("pessoaId") String pessoaId,
                          @Param("pessoaTipo") String pessoaTipo,
                          @Param("statusVivos") Collection<StatusSolicitacaoFolga> statusVivos);

    /**
     * Folgas de UM status de uma categoria no range [ini, fim) — DATA_FOLGA
     * sargável, sem TRUNC (gotcha 4). Com APROVADO alimenta as células
     * "Banco de horas" e a contagem "Folgas" por pessoa da grade (Q13, E10).
     */
    @Query("SELECT s FROM PontoSolicitacaoFolga s " +
           "WHERE s.status = :status AND s.pessoaTipo = :pessoaTipo " +
           "AND s.dataFolga >= :ini AND s.dataFolga < :fim")
    List<PontoSolicitacaoFolga> findPorStatusECategoriaNoRange(@Param("status") StatusSolicitacaoFolga status,
                                                               @Param("pessoaTipo") String pessoaTipo,
                                                               @Param("ini") LocalDate ini,
                                                               @Param("fim") LocalDate fim);
}
