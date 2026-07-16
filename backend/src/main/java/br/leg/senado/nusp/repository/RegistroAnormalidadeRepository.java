package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.RegistroAnormalidade;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RegistroAnormalidadeRepository extends JpaRepository<RegistroAnormalidade, Long> {

    /**
     * Verifica se existe alguma anormalidade vinculada a uma entrada.
     * Usado pelo syncHouveAnormalidade().
     */
    boolean existsByEntradaId(Long entradaId);

    /**
     * Busca anormalidade mais recente por entrada_id.
     * Equivale a get_registro_anormalidade_por_entrada() do Python.
     */
    @Query(value = """
            SELECT * FROM OPR_ANORMALIDADE
            WHERE ENTRADA_ID = :entradaId
            ORDER BY ID DESC
            FETCH FIRST 1 ROWS ONLY
            """, nativeQuery = true)
    List<Object[]> findByEntradaIdNative(@Param("entradaId") long entradaId);

    /**
     * Atualiza houve_anormalidade na entrada do operador.
     * Usado pelo syncHouveAnormalidade() — substitui a trigger do PostgreSQL.
     */
    @Modifying @Transactional
    @Query(value = """
            UPDATE OPR_REGISTRO_ENTRADA
            SET HOUVE_ANORMALIDADE = :valor
            WHERE ID = :entradaId
            """, nativeQuery = true)
    void updateHouveAnormalidade(@Param("entradaId") long entradaId, @Param("valor") int valor);
}
