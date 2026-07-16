package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PontoPessoaMarcacaoRepository extends JpaRepository<PontoPessoaMarcacao, String> {

    /** Marcações pessoa-dia do range [ini, fim) — DATA sargável, sem TRUNC (gotcha 4). */
    List<PontoPessoaMarcacao> findByDataGreaterThanEqualAndDataLessThanOrderByData(LocalDate ini, LocalDate fim);

    /** Marcações pessoa-dia de uma categoria no range [ini, fim) — IX (PESSOA_TIPO, DATA); grade mensal (E10). */
    List<PontoPessoaMarcacao> findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan(
            String pessoaTipo, LocalDate ini, LocalDate fim);

    /** Marcações de UMA pessoa no range [ini, fim) — dias bloqueados do banco de horas (Q12/F#4). */
    List<PontoPessoaMarcacao> findByPessoaIdAndPessoaTipoAndDataGreaterThanEqualAndDataLessThan(
            String pessoaId, String pessoaTipo, LocalDate ini, LocalDate fim);

    /** Marcação de uma pessoa num dia (UK PESSOA_ID+PESSOA_TIPO+DATA) — para o upsert. */
    Optional<PontoPessoaMarcacao> findByPessoaIdAndPessoaTipoAndData(String pessoaId, String pessoaTipo, LocalDate data);

    /** Remove a marcação da pessoa no dia (desmarcar = delete físico). */
    void deleteByPessoaIdAndPessoaTipoAndData(String pessoaId, String pessoaTipo, LocalDate data);
}
