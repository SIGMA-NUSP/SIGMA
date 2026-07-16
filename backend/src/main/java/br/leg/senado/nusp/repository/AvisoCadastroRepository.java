package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoCadastro;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AvisoCadastroRepository extends JpaRepository<AvisoCadastro, String> {

    Optional<AvisoCadastro> findByNumero(Long numero);

    /**
     * Cadastros criados pela PUBLICAÇÃO daquele lote de folhas (F59) — a chave da exclusão dos
     * avisos. Só o caminho da publicação grava ORIGEM_LOTE_ID, então nada de outra origem entra
     * aqui (o desfecho de folga do banco de horas, por exemplo, deixa a coluna NULL).
     */
    List<AvisoCadastro> findByOrigemLoteId(String origemLoteId);
}
