package br.leg.senado.nusp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.it.support.Causas;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * ITs dos 2 statements nativos de {@link RegistroAnormalidadeRepository} contra Oracle
 * real — incluindo {@code updateHouveAnormalidade}, a substituta Java da trigger
 * {@code operacao.sync_houve_anormalidade()} do PostgreSQL legado (escreve em
 * OPR_REGISTRO_ENTRADA, não em OPR_ANORMALIDADE).
 */
@OracleIT
class RegistroAnormalidadeRepositoryIT {

    @Autowired
    private RegistroAnormalidadeRepository repo;

    @Autowired
    private TestEntityManager em;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @Nested
    @DisplayName("findByEntradaIdNative")
    class FindByEntradaIdNative {

        @Test
        @DisplayName("findByEntradaIdNative — devolve exatamente a anormalidade da entrada consultada")
        void findByEntradaIdNative_devolveADaEntradaConsultada() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operadorUm = CenarioFactory.novoOperador(emReal());
            Operador operadorDois = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entradaUm = CenarioFactory.novaEntrada(emReal(), registro, operadorUm, 1);
            RegistroOperacaoOperador entradaDois = CenarioFactory.novaEntrada(emReal(), registro, operadorDois, 2);
            RegistroAnormalidade anormalidadeDaUm = CenarioFactory.novaAnormalidade(emReal(), registro, sala,
                    entradaUm.getId(), operadorUm.getId());
            CenarioFactory.novaAnormalidade(emReal(), registro, sala, entradaDois.getId(), operadorDois.getId());

            List<Object[]> rows = repo.findByEntradaIdNative(entradaUm.getId());

            assertEquals(1, rows.size(), "anormalidade da outra entrada não pode voltar");
            // SELECT * — ID é a 1ª coluna física de OPR_ANORMALIDADE
            assertEquals(anormalidadeDaUm.getId().longValue(), ((Number) rows.get(0)[0]).longValue());
        }

        @Test
        @DisplayName("findByEntradaIdNative — entrada sem anormalidade retorna vazio")
        void findByEntradaIdNative_entradaSemAnormalidadeRetornaVazio() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, operador);

            List<Object[]> rows = repo.findByEntradaIdNative(entrada.getId());
            assertTrue(rows.isEmpty(),
                    "entrada sem anormalidade deveria retornar vazio, vieram " + rows.size() + " linha(s)");
        }

        @Test
        @DisplayName("caracteriza UQ_OPR_ANOM_ENTRADA — o banco impede 2ª anormalidade na mesma entrada "
                + "(o ORDER BY ID DESC FETCH FIRST 1 da query é hoje inalcançável com N > 1)")
        void persistirSegundaAnormalidadeDaMesmaEntrada_violaUnique() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), registro, operador, 1);
            CenarioFactory.novaAnormalidade(emReal(), registro, sala, entrada.getId(), operador.getId());

            // O plano previa "semear 2 e ler a mais recente"; o índice único real do schema
            // (UQ_OPR_ANOM_ENTRADA em ENTRADA_ID) torna o cenário impossível — registrado
            // como divergência plano-vs-banco na entrada T10 do registro de execução.
            PersistenceException ex = assertThrows(PersistenceException.class,
                    () -> CenarioFactory.novaAnormalidade(emReal(), registro, sala,
                            entrada.getId(), operador.getId()));

            assertTrue(Causas.contem(ex, "UQ_OPR_ANOM_ENTRADA"),
                    "a violação deveria vir do índice único UQ_OPR_ANOM_ENTRADA, não de outro erro");
        }
    }

    @Nested
    @DisplayName("updateHouveAnormalidade")
    class UpdateHouveAnormalidade {

        @Test
        @DisplayName("updateHouveAnormalidade(id, 1) — liga o flag da entrada alvo sem tocar as demais")
        void updateHouveAnormalidade_gravaUmSoNaEntradaAlvo() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operadorUm = CenarioFactory.novoOperador(emReal());
            Operador operadorDois = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador alvo = CenarioFactory.novaEntrada(emReal(), registro, operadorUm, 1);
            RegistroOperacaoOperador controle = CenarioFactory.novaEntrada(emReal(), registro, operadorDois, 2);
            assertNull(alvo.getHouveAnormalidade(), "entrada nasce com HOUVE_ANORMALIDADE NULL");

            repo.updateHouveAnormalidade(alvo.getId(), 1);

            em.clear(); // UPDATE nativo fura o persistence context — reler do banco
            assertEquals(Boolean.TRUE, em.find(RegistroOperacaoOperador.class, alvo.getId()).getHouveAnormalidade());
            assertNull(em.find(RegistroOperacaoOperador.class, controle.getId()).getHouveAnormalidade(),
                    "a entrada de controle não pode ser afetada");
        }

        @Test
        @DisplayName("updateHouveAnormalidade(id, 0) — desliga o flag antes ligado (ida e volta 1 → 0)")
        void updateHouveAnormalidade_gravaZeroDepoisDeUm() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, operador);

            repo.updateHouveAnormalidade(entrada.getId(), 1);
            em.clear();
            assertEquals(Boolean.TRUE, em.find(RegistroOperacaoOperador.class, entrada.getId()).getHouveAnormalidade());

            repo.updateHouveAnormalidade(entrada.getId(), 0);
            em.clear();
            assertEquals(Boolean.FALSE, em.find(RegistroOperacaoOperador.class, entrada.getId()).getHouveAnormalidade());
        }
    }
}
