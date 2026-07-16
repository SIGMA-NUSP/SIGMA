package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoLotePagina;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PontoLotePaginaRepository extends JpaRepository<PontoLotePagina, String> {

    List<PontoLotePagina> findByLoteIdOrderByNumeroPagina(String loteId);

    /**
     * Conta, por lote, as páginas com o statusMatch informado (ex.: "PENDENTE"), em 1 agregação —
     * evita carregar todas as páginas de cada lote só para contar pendentes (Q18). Pares [loteId, COUNT].
     */
    @Query("SELECT p.loteId, COUNT(p) FROM PontoLotePagina p WHERE p.statusMatch = :status GROUP BY p.loteId")
    List<Object[]> contarPorLoteEStatusMatch(@Param("status") String status);

    /**
     * Folhas (páginas) de lotes PUBLICADOS pertencentes à pessoa, mais recentes primeiro.
     * Retorna pares [PontoLotePagina, PontoLote].
     */
    @Query("SELECT p, l FROM PontoLotePagina p, PontoLote l " +
           "WHERE l.id = p.loteId AND l.status = 'PUBLICADO' AND p.pessoaId = :pessoaId " +
           "ORDER BY l.dataInicio DESC, l.criadoEm DESC")
    List<Object[]> findFolhasPublicadasByPessoa(@Param("pessoaId") String pessoaId);

    /**
     * Candidatas a âncora oficial do banco da pessoa (E2): páginas de lotes
     * PUBLICADOS com BANCO_FINAL_MIN extraído, da cobertura mais recente para
     * a mais antiga (l.dataFim DESC; desempates para determinismo). Pares
     * [PontoLotePagina, PontoLote] — o SaldoAberturaService usa a primeira,
     * com {@code Limit.of(1)} (o histórico da pessoa cresce sem parar).
     */
    @Query("SELECT p, l FROM PontoLotePagina p, PontoLote l " +
           "WHERE l.id = p.loteId AND l.status = 'PUBLICADO' " +
           "AND p.pessoaId = :pessoaId AND p.pessoaTipo = :pessoaTipo " +
           "AND p.bancoFinalMin IS NOT NULL " +
           "ORDER BY l.dataFim DESC, l.criadoEm DESC, p.numeroPagina DESC")
    List<Object[]> findCandidatasAncora(@Param("pessoaId") String pessoaId,
                                        @Param("pessoaTipo") String pessoaTipo,
                                        Limit limit);

    /**
     * Como a {@link #findCandidatasAncora}, IGNORANDO um conjunto de páginas — as que uma exclusão
     * vai apagar (F59). Serve só ao PREVIEW: ele precisa dizer, ANTES de apagar, para qual folha a
     * âncora da pessoa vai voltar. A exclusão de verdade não usa esta query: lá as páginas já morreram
     * (flush antes da re-âncora), e a {@link #findCandidatasAncora} normal acha a anterior sozinha.
     *
     * <p>{@code excluidas} nunca é vazia (toda exclusão tem ao menos uma página alvo) — um
     * {@code NOT IN ()} vazio não é SQL válido no Oracle.
     */
    @Query("SELECT p, l FROM PontoLotePagina p, PontoLote l " +
           "WHERE l.id = p.loteId AND l.status = 'PUBLICADO' " +
           "AND p.pessoaId = :pessoaId AND p.pessoaTipo = :pessoaTipo " +
           "AND p.bancoFinalMin IS NOT NULL AND p.id NOT IN :excluidas " +
           "ORDER BY l.dataFim DESC, l.criadoEm DESC, p.numeroPagina DESC")
    List<Object[]> findCandidatasAncoraExcluindo(@Param("pessoaId") String pessoaId,
                                                 @Param("pessoaTipo") String pessoaTipo,
                                                 @Param("excluidas") Collection<String> excluidas,
                                                 Limit limit);

    /** Páginas vinculadas de lotes PUBLICADOS ainda sem BANCO_FINAL_MIN (alvo do backfill E2). */
    @Query("SELECT p FROM PontoLotePagina p, PontoLote l WHERE l.id = p.loteId " +
           "AND l.status = 'PUBLICADO' AND p.pessoaId IS NOT NULL AND p.bancoFinalMin IS NULL " +
           "ORDER BY p.loteId, p.numeroPagina")
    List<PontoLotePagina> findPublicadasSemBancoFinal();

    /** Pares distintos [pessoaId, pessoaTipo] com folha publicada (pool da re-âncora do backfill). */
    @Query("SELECT DISTINCT p.pessoaId, p.pessoaTipo FROM PontoLotePagina p, PontoLote l " +
           "WHERE l.id = p.loteId AND l.status = 'PUBLICADO' AND p.pessoaId IS NOT NULL")
    List<Object[]> findPessoasComFolhaPublicada();

    /**
     * Guarda da folha mensal (F32): trios distintos [pessoaId, pessoaTipo, dataInicio da mensal] do
     * conjunto informado que JÁ TÊM folha MENSAL publicada tocando a janela de competência — em OUTRO
     * lote. É a mesma pergunta para os dois lados da regra: uma segunda MENSAL da pessoa no mês é
     * proibida, e uma MENSAL publicada FECHA o mês para SEMANAIS atrasadas da mesma pessoa.
     *
     * <p>A {@code dataInicio} volta junto porque é dela que sai a competência REALMENTE fechada, que
     * a recusa cita ao admin — a janela consultada pode abranger dois meses (lote que cruza a virada),
     * e nomear a janela em vez do mês da mensal conflitante seria mentir sobre o mês que está aberto.
     *
     * <p>O filtro é só de tipo MENSAL: a sobreposição entre SEMANAIS é o funcionamento normal (elas
     * são cumulativas — 01–05, 01–12, 01–19…) e não pode ser barrada aqui.
     */
    @Query("SELECT DISTINCT p.pessoaId, p.pessoaTipo, l.dataInicio FROM PontoLotePagina p, PontoLote l " +
           "WHERE l.id = p.loteId AND l.status = 'PUBLICADO' AND l.tipo = 'MENSAL' " +
           "AND l.id <> :loteId AND p.pessoaId IN :pessoaIds " +
           "AND l.dataInicio <= :fim AND l.dataFim >= :inicio")
    List<Object[]> findPessoasComMensalPublicadaNoPeriodo(@Param("loteId") String loteId,
                                                          @Param("pessoaIds") Collection<String> pessoaIds,
                                                          @Param("inicio") LocalDate inicio,
                                                          @Param("fim") LocalDate fim);
}
