package br.leg.senado.nusp.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.it.support.OracleIT;

/**
 * Smoke test do andaime de integração: prova que o slice sobe com
 * ddl-auto=validate contra o clone NUSP_TEST, que as colunas IDENTITY
 * (sequences ISEQ$$ importadas no clone) geram ID, que o rollback do
 * {@code @DataJpaTest} isola os testes entre si e que a única sequence
 * NOMEADA que o código usa (SEQ_FRM_AVISO_CADASTRO, no AvisoService)
 * veio no clone.
 *
 * A ordem explícita é obrigatória: o teste {@code @Order(3)} prova que o
 * dado semeado no {@code @Order(2)} NÃO persistiu, e o JUnit 5 não garante
 * ordem de execução por padrão.
 */
@OracleIT
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AndaimeSmokeIT {

    /** Nome-sentinela: se sobreviver fora da transação do @Order(2), o rollback furou. */
    private static final String NOME_SALA_SMOKE = "SALA_SMOKE_ANDAIME_T1";

    @Autowired
    private TestEntityManager em;

    @Test
    @Order(1)
    @DisplayName("boot do slice + SELECT 1 FROM dual — datasource NUSP_TEST no ar (o boot prova o validate)")
    void conexao_selectUmDual() {
        Object result = em.getEntityManager()
                .createNativeQuery("SELECT 1 FROM dual")
                .getSingleResult();

        assertEquals(1, ((Number) result).intValue());
    }

    @Test
    @Order(2)
    @DisplayName("save+findById de Sala — IDENTITY (ISEQ$$ do clone) gera ID não-nulo")
    void persistencia_identityGeraId() {
        Sala sala = new Sala();
        sala.setNome(NOME_SALA_SMOKE);
        sala.setAtivo(true);
        sala.setMultiOperador(false);
        em.persistAndFlush(sala);

        assertNotNull(sala.getId(), "IDENTITY não gerou ID — sequence ISEQ$$ ausente do clone?");

        // clear força o find a ir ao banco em vez de servir o cache de 1º nível
        em.clear();
        Sala achada = em.find(Sala.class, sala.getId());
        assertEquals(NOME_SALA_SMOKE, achada.getNome());
    }

    @Test
    @Order(3)
    @DisplayName("rollback do @DataJpaTest — a Sala do teste anterior não persistiu")
    void rollback_salaDoTesteAnteriorNaoPersistiu() {
        Number count = (Number) em.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM CAD_SALA WHERE NOME = :nome")
                .setParameter("nome", NOME_SALA_SMOKE)
                .getSingleResult();

        assertEquals(0, count.intValue(),
                "a Sala semeada no @Order(2) persistiu — rollback do andaime furado");
    }

    @Test
    @Order(4)
    @DisplayName("sequence nomeada SEQ_FRM_AVISO_CADASTRO responde NEXTVAL (única usada pelo código)")
    void sequenceNomeada_nextval() {
        // se a sequence não existisse no clone, o Oracle lançaria ORA-02289 aqui
        Object nextval = em.getEntityManager()
                .createNativeQuery("SELECT SEQ_FRM_AVISO_CADASTRO.NEXTVAL FROM dual")
                .getSingleResult();

        assertNotNull(nextval);
    }
}
