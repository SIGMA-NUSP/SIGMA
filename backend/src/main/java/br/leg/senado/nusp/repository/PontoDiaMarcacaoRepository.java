package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PontoDiaMarcacaoRepository extends JpaRepository<PontoDiaMarcacao, String> {

    /** Marcações globais do range [ini, fim) — DATA sargável, sem TRUNC (gotcha 4). */
    List<PontoDiaMarcacao> findByDataGreaterThanEqualAndDataLessThanOrderByData(LocalDate ini, LocalDate fim);

    /** Marcação global de um dia (UK DATA) — para o upsert. */
    Optional<PontoDiaMarcacao> findByData(LocalDate data);

    /** Remove a marcação global do dia (desmarcar = delete físico). */
    void deleteByData(LocalDate data);
}
