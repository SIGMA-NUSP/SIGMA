package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoMensagem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AvisoMensagemRepository extends JpaRepository<AvisoMensagem, String> {

    List<AvisoMensagem> findByCadastroIdOrderByOrdem(String cadastroId);

    /** Útil para edição futura de cadastro (regravar mensagens). */
    void deleteByCadastroId(String cadastroId);
}
