package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AvisoAlvoRepository extends JpaRepository<AvisoAlvo, String> {

    List<AvisoAlvo> findByCadastroId(String cadastroId);

    /**
     * O complemento que o cadastro dirige a ESTA pessoa — o texto que só ela lê. Vazio no caso
     * normal: ou o alvo dela não tem complemento, ou ela é alcançada por um alvo coletivo, que não
     * personaliza ninguém.
     *
     * <p>O tipo do alvo entra na busca porque é ele que diz em qual coluna o identificador da pessoa
     * está — operador, técnico e administrador são cadastros distintos.
     */
    @Query("SELECT a.complemento FROM AvisoAlvo a " +
           "WHERE a.cadastroId = :cadastroId AND a.alvoTipo = :alvoTipo AND a.complemento IS NOT NULL " +
           "AND (a.operadorId = :pessoaId OR a.tecnicoId = :pessoaId OR a.adminId = :pessoaId)")
    List<String> findComplementosDaPessoa(@Param("cadastroId") String cadastroId,
                                          @Param("alvoTipo") AlvoTipoAviso alvoTipo,
                                          @Param("pessoaId") String pessoaId);
}
