package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoFolhaLinha;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PontoFolhaLinhaRepository extends JpaRepository<PontoFolhaLinha, String> {

    /** Linhas da folha na ordem em que aparecem na página. */
    List<PontoFolhaLinha> findByPaginaIdOrderByOrdem(String paginaId);

    /**
     * Apaga as linhas já gravadas de uma folha. Republicar ou reprocessar uma folha regrava a
     * tabela inteira dela — sem isto, a segunda gravação esbarraria na unicidade (página, ordem).
     */
    void deleteByPaginaId(String paginaId);

    /**
     * Apaga as linhas das folhas informadas (exclusão de lote ou de página avulsa).
     *
     * <p>A coleção nunca é vazia: um {@code IN ()} vazio não é SQL válido no Oracle.
     */
    void deleteByPaginaIdIn(Collection<String> paginaIds);

    long countByPaginaIdIn(Collection<String> paginaIds);

    /**
     * Dias com ocorrência (status) das folhas informadas dentro da janela — trios
     * {@code [paginaId, data, ocorrencia]}. É a matéria-prima do sumário: cada trio é um dia em que
     * a folha daquela pessoa trouxe um status em vez de batidas.
     *
     * <p>Linha sem data legível fica de fora: sem ela não há como saber a que mês o dia pertence, e
     * o sumário conta por mês-calendário. O verbatim continua gravado — só não é somável.
     *
     * <p>A coleção nunca é vazia: um {@code IN ()} vazio não é SQL válido no Oracle.
     */
    @Query("SELECT l.paginaId, l.data, l.ocorrencia FROM PontoFolhaLinha l " +
           "WHERE l.paginaId IN :paginaIds AND l.ocorrencia IS NOT NULL " +
           "AND l.data IS NOT NULL AND l.data >= :inicio AND l.data <= :fim")
    List<Object[]> findOcorrenciasNoPeriodo(@Param("paginaIds") Collection<String> paginaIds,
                                            @Param("inicio") LocalDate inicio,
                                            @Param("fim") LocalDate fim);
}
