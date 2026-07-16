package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoRetificacao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PontoRetificacaoRepository extends JpaRepository<PontoRetificacao, String> {

    /** Já existe retificação para a pessoa naquele dia? (UK PESSOA_ID+PESSOA_TIPO+DATA — Q1). */
    boolean existsByPessoaIdAndPessoaTipoAndData(String pessoaId, String pessoaTipo, LocalDate data);

    /**
     * Retificações da PESSOA dentro de um período, em ordem cronológica — a chave de LEITURA da
     * tela de retificação (F32). É a MESMA chave da UK (pessoa+tipo+dia), recortada pelo período da
     * folha consultada: por PAGINA_ID, o dia retificado por outra folha da mesma pessoa (semanais
     * sobrepostas — 01–05, 01–12…, cumulativas por decisão) sumia da tela, e o usuário só descobria
     * que ele existia ao levar 400 "já foi retificado" sem ver retificação nenhuma.
     * {@code Between} é INCLUSIVO nas duas bordas — {@code dataInicio} e {@code dataFim} da folha entram.
     */
    List<PontoRetificacao> findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
            String pessoaId, String pessoaTipo, LocalDate inicio, LocalDate fim);

    /** Retificações de uma categoria no range [ini, fim) — DATA sargável, sem TRUNC (gotcha 4); grade mensal (E10). */
    List<PontoRetificacao> findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan(
            String pessoaTipo, LocalDate ini, LocalDate fim);

    /**
     * Retificações ancoradas nas páginas informadas (F59) — as que morrem quando a folha delas é
     * excluída. A chave é a PÁGINA (proveniência), nunca a pessoa: a mesma pessoa pode ter
     * retificações ancoradas em OUTRA folha publicada, e essas sobrevivem à exclusão desta.
     * É também a razão de as retificações irem embora ANTES das páginas — a FK_PNT_RETIF_PAGINA
     * não tem cascade (ORA-02292).
     */
    List<PontoRetificacao> findByPaginaIdIn(Collection<String> paginaIds);
}
