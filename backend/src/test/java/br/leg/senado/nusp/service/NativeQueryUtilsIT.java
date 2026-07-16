package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import jakarta.persistence.EntityManager;

/**
 * ITs dos 2 estáticos de {@link NativeQueryUtils} que recebem EntityManager.
 *
 * {@code isDonoOuAdicional} é o gate de edição de checklist e de entrada de
 * operação: os nomes de tabela/coluna são interpolados por String.format, então
 * cada um dos 2 call sites reais (ChecklistService:97 e OperacaoService:54) é
 * exercitado com os seus argumentos literais — um erro de nome de coluna aqui só
 * aparece contra o banco real.
 */
@OracleIT
class NativeQueryUtilsIT {

    private static final long ID_INEXISTENTE = 999_999_999L;

    @Autowired
    private TestEntityManager em;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @Nested
    @DisplayName("isDonoOuAdicional — checklist (ChecklistService.validarPermissaoEdicao)")
    class DonoOuAdicionalChecklist {

        private boolean acessa(long checklistId, String userId) {
            return NativeQueryUtils.isDonoOuAdicional(emReal(), "FRM_CHECKLIST", "CRIADO_POR",
                    "FRM_CHECKLIST_OPERADOR", "CHECKLIST_ID", checklistId, userId);
        }

        @Test
        @DisplayName("isDonoOuAdicional — o criador do checklist (1º ramo do UNION ALL) tem acesso")
        void isDonoOuAdicional_donoDiretoTemAcesso() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, dono);

            assertTrue(acessa(checklist.getId(), dono.getId()));
        }

        @Test
        @DisplayName("isDonoOuAdicional — operador adicional da junction (2º ramo) tem acesso, e um terceiro não")
        void isDonoOuAdicional_adicionalTemAcessoTerceiroNao() {
            Sala sala = CenarioFactory.novaSala(emReal(), true);
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador adicional = CenarioFactory.novoOperador(emReal());
            Operador terceiro = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, dono);
            CenarioFactory.novoVinculoChecklist(emReal(), checklist, adicional);

            assertTrue(acessa(checklist.getId(), adicional.getId()),
                    "vínculo em FRM_CHECKLIST_OPERADOR (CHECKLIST_ID + OPERADOR_ID) dá acesso");
            assertFalse(acessa(checklist.getId(), terceiro.getId()),
                    "operador sem vínculo e sem autoria não pode acessar");
        }

        @Test
        @DisplayName("isDonoOuAdicional — o vínculo é por checklist: adicional de OUTRO checklist não acessa")
        void isDonoOuAdicional_vinculoDeOutroChecklistNaoVaza() {
            Sala sala = CenarioFactory.novaSala(emReal(), true);
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador adicional = CenarioFactory.novoOperador(emReal());
            Checklist alvo = CenarioFactory.novoChecklist(emReal(), sala, dono);
            Checklist outro = CenarioFactory.novoChecklist(emReal(), sala, dono);
            CenarioFactory.novoVinculoChecklist(emReal(), outro, adicional);

            assertFalse(acessa(alvo.getId(), adicional.getId()),
                    "o predicado CHECKLIST_ID = ?1 confina o vínculo ao checklist certo");
            assertTrue(acessa(outro.getId(), adicional.getId()));
        }

        @Test
        @DisplayName("isDonoOuAdicional — checklist inexistente não dá acesso a ninguém")
        void isDonoOuAdicional_checklistInexistenteRetornaFalse() {
            Operador operador = CenarioFactory.novoOperador(emReal());

            assertFalse(acessa(ID_INEXISTENTE, operador.getId()));
        }
    }

    @Nested
    @DisplayName("isDonoOuAdicional — entrada de operação (OperacaoService.validarPermissaoEdicaoEntrada)")
    class DonoOuAdicionalEntrada {

        private boolean acessa(long entradaId, String userId) {
            return NativeQueryUtils.isDonoOuAdicional(emReal(), "OPR_REGISTRO_ENTRADA", "OPERADOR_ID",
                    "OPR_ENTRADA_OPERADOR", "ENTRADA_ID", entradaId, userId);
        }

        @Test
        @DisplayName("isDonoOuAdicional — o operador da entrada tem acesso; co-operador da junction também; terceiro não")
        void isDonoOuAdicional_donoCoOperadorETerceiro() {
            Sala sala = CenarioFactory.novaSala(emReal(), true);
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador coOperador = CenarioFactory.novoOperador(emReal());
            Operador terceiro = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, dono);
            CenarioFactory.novoVinculoOperador(emReal(), entrada, coOperador);

            assertTrue(acessa(entrada.getId(), dono.getId()),
                    "OPR_REGISTRO_ENTRADA.OPERADOR_ID é a coluna de dono desta variante");
            assertTrue(acessa(entrada.getId(), coOperador.getId()),
                    "OPR_ENTRADA_OPERADOR.ENTRADA_ID + OPERADOR_ID é a junction desta variante");
            assertFalse(acessa(entrada.getId(), terceiro.getId()));
        }

        @Test
        @DisplayName("isDonoOuAdicional — entrada inexistente não dá acesso a ninguém")
        void isDonoOuAdicional_entradaInexistenteRetornaFalse() {
            Operador operador = CenarioFactory.novoOperador(emReal());

            assertFalse(acessa(ID_INEXISTENTE, operador.getId()));
        }
    }

    @Nested
    @DisplayName("salaMultiOperador")
    class SalaMultiOperador {

        @Test
        @DisplayName("salaMultiOperador — MULTI_OPERADOR = 1 devolve true e = 0 devolve false")
        void salaMultiOperador_refleteAColuna() {
            Sala multi = CenarioFactory.novaSala(emReal(), true);
            Sala simples = CenarioFactory.novaSala(emReal(), false);

            assertTrue(NativeQueryUtils.salaMultiOperador(emReal(), multi.getId()));
            assertFalse(NativeQueryUtils.salaMultiOperador(emReal(), simples.getId()));
        }

        @Test
        @DisplayName("salaMultiOperador — sala inexistente devolve false (resultado vazio, sem exceção)")
        void salaMultiOperador_salaInexistenteRetornaFalse() {
            CenarioFactory.novaSala(emReal(), true); // ruído: existe sala multi no banco

            assertFalse(NativeQueryUtils.salaMultiOperador(emReal(), ID_INEXISTENTE));
        }
    }
}
