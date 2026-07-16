package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Comissao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ComissaoRepository extends JpaRepository<Comissao, Long> {

    /**
     * Comissões ativas ordenadas por ordem (COALESCE com 9999) e nome.
     * Equivale ao lookup_comissoes() do Python.
     */
    @Query("SELECT c FROM Comissao c WHERE c.ativo = true ORDER BY COALESCE(c.ordem, 9999), c.nome ASC")
    List<Comissao> findAtivasOrdenadas();

    /** Para form-edit: retorna TODAS (ativas primeiro, depois inativas). */
    @Query("SELECT c FROM Comissao c ORDER BY c.ativo DESC, COALESCE(c.ordem, 9999) ASC, c.nome ASC, c.id ASC")
    List<Comissao> findAllOrdered();
}
