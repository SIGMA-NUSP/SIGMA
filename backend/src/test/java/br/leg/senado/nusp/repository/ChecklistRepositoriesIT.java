package br.leg.senado.nusp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.ChecklistItemTipo;
import br.leg.senado.nusp.entity.ChecklistResposta;
import br.leg.senado.nusp.entity.ChecklistSalaConfig;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.StatusResposta;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.service.NativeQueryUtils;
import jakarta.persistence.EntityManager;

/**
 * ITs dos statements nativos dos repositories de checklist
 * ({@link ChecklistItemTipoRepository#findItensPorSala},
 * {@link ChecklistSalaConfigRepository#findConfigItemsBySalaId},
 * {@link ChecklistRespostaRepository#findByChecklistIdNative}) e do único
 * {@code @Modifying} JPQL da suíte ({@link ChecklistSalaConfigRepository#deactivateAllBySalaId})
 * contra Oracle real.
 */
@OracleIT
class ChecklistRepositoriesIT {

    @Autowired
    private ChecklistItemTipoRepository itemTipoRepo;

    @Autowired
    private ChecklistSalaConfigRepository salaConfigRepo;

    @Autowired
    private ChecklistRespostaRepository respostaRepo;

    @Autowired
    private TestEntityManager em;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @Nested
    @DisplayName("findItensPorSala")
    class FindItensPorSala {

        @Test
        @DisplayName("findItensPorSala — só itens ativos da sala, ordenados por ORDEM")
        void findItensPorSala_soAtivosDaSalaOrdenadosPorOrdem() {
            Sala salaAlvo = CenarioFactory.novaSala(emReal());
            Sala outraSala = CenarioFactory.novaSala(emReal());
            ChecklistItemTipo tipoSegundo = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoPrimeiro = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoInativo = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoDeOutraSala = CenarioFactory.novoItemTipo(emReal());
            CenarioFactory.novaConfig(emReal(), salaAlvo, tipoSegundo, 2, true);
            CenarioFactory.novaConfig(emReal(), salaAlvo, tipoPrimeiro, 1, true);
            CenarioFactory.novaConfig(emReal(), salaAlvo, tipoInativo, 3, false);
            CenarioFactory.novaConfig(emReal(), outraSala, tipoDeOutraSala, 1, true);

            List<Object[]> itens = itemTipoRepo.findItensPorSala(salaAlvo.getId());

            assertEquals(2, itens.size(), "config inativa e itens de outra sala ficam fora");
            // colunas: [0]=ID, [1]=NOME, [2]=ORDEM, [3]=TIPO_WIDGET
            assertEquals(tipoPrimeiro.getId().intValue(), ((Number) itens.get(0)[0]).intValue());
            assertEquals(tipoPrimeiro.getNome(), itens.get(0)[1]);
            assertEquals(1, ((Number) itens.get(0)[2]).intValue());
            assertEquals("radio", itens.get(0)[3]);
            assertEquals(tipoSegundo.getId().intValue(), ((Number) itens.get(1)[0]).intValue());
        }

        @Test
        @DisplayName("findItensPorSala — empate de ORDEM desempata pelo ID do item")
        void findItensPorSala_empateDeOrdemDesempataPorId() {
            Sala sala = CenarioFactory.novaSala(emReal());
            ChecklistItemTipo tipoIdMenor = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoIdMaior = CenarioFactory.novoItemTipo(emReal());
            // configs criadas na ordem inversa do ID: o resultado não pode ser eco da inserção
            CenarioFactory.novaConfig(emReal(), sala, tipoIdMaior, 1, true);
            CenarioFactory.novaConfig(emReal(), sala, tipoIdMenor, 1, true);

            List<Object[]> itens = itemTipoRepo.findItensPorSala(sala.getId());

            assertEquals(2, itens.size());
            assertEquals(tipoIdMenor.getId().intValue(), ((Number) itens.get(0)[0]).intValue(),
                    "com ORDEM empatada, o desempate é t.ID ASC");
            assertEquals(tipoIdMaior.getId().intValue(), ((Number) itens.get(1)[0]).intValue());
        }

        @Test
        @DisplayName("findItensPorSala — sala sem configuração retorna vazio")
        void findItensPorSala_salaSemConfigRetornaVazio() {
            Sala sala = CenarioFactory.novaSala(emReal());

            List<Object[]> itens = itemTipoRepo.findItensPorSala(sala.getId());
            assertTrue(itens.isEmpty(),
                    "sala sem configuração deveria retornar vazio, vieram " + itens.size() + " linha(s)");
        }
    }

    @Nested
    @DisplayName("findConfigItemsBySalaId")
    class FindConfigItemsBySalaId {

        @Test
        @DisplayName("findConfigItemsBySalaId — traz ativos E inativos da sala, ativos primeiro")
        void findConfigItemsBySalaId_ativosEInativosOrdenados() {
            Sala salaAlvo = CenarioFactory.novaSala(emReal());
            Sala outraSala = CenarioFactory.novaSala(emReal());
            ChecklistItemTipo tipoSegundo = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoPrimeiro = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoInativo = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoDeOutraSala = CenarioFactory.novoItemTipo(emReal());
            ChecklistSalaConfig configSegunda = CenarioFactory.novaConfig(emReal(), salaAlvo, tipoSegundo, 2, true);
            ChecklistSalaConfig configPrimeira = CenarioFactory.novaConfig(emReal(), salaAlvo, tipoPrimeiro, 1, true);
            ChecklistSalaConfig configInativa = CenarioFactory.novaConfig(emReal(), salaAlvo, tipoInativo, 3, false);
            CenarioFactory.novaConfig(emReal(), outraSala, tipoDeOutraSala, 1, true);

            List<Object[]> configs = salaConfigRepo.findConfigItemsBySalaId(salaAlvo.getId());

            assertEquals(3, configs.size(), "inclui a config inativa; exclui outras salas");
            // colunas: [0]=ID da config, [1]=ITEM_TIPO_ID, [2]=NOME, [3]=TIPO_WIDGET, [4]=ORDEM, [5]=ATIVO
            // ordem: ATIVO DESC, ORDEM ASC → [ativa ordem 1, ativa ordem 2, inativa]
            assertEquals(configPrimeira.getId().intValue(), ((Number) configs.get(0)[0]).intValue());
            assertEquals(configSegunda.getId().intValue(), ((Number) configs.get(1)[0]).intValue());
            assertEquals(configInativa.getId().intValue(), ((Number) configs.get(2)[0]).intValue());
            assertEquals(tipoPrimeiro.getId().intValue(), ((Number) configs.get(0)[1]).intValue());
            assertEquals(tipoPrimeiro.getNome(), configs.get(0)[2]);
            assertEquals("radio", configs.get(0)[3]);
            assertEquals(1, ((Number) configs.get(0)[4]).intValue());
            assertEquals(3, ((Number) configs.get(2)[4]).intValue());
            assertEquals(1, ((Number) configs.get(0)[5]).intValue());
            assertEquals(0, ((Number) configs.get(2)[5]).intValue());
        }

        @Test
        @DisplayName("findConfigItemsBySalaId — sala sem configuração retorna vazio")
        void findConfigItemsBySalaId_salaSemConfigRetornaVazio() {
            Sala sala = CenarioFactory.novaSala(emReal());

            List<Object[]> configs = salaConfigRepo.findConfigItemsBySalaId(sala.getId());
            assertTrue(configs.isEmpty(),
                    "sala sem configuração deveria retornar vazio, vieram " + configs.size() + " linha(s)");
        }
    }

    @Nested
    @DisplayName("findByChecklistIdNative")
    class FindByChecklistIdNative {

        @Test
        @DisplayName("findByChecklistIdNative — só as respostas do checklist, com STATUS cru e DESCRICAO_FALHA (CLOB)")
        void findByChecklistIdNative_respostasDoChecklist() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            ChecklistItemTipo tipoOk = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoFalha = CenarioFactory.novoItemTipo(emReal());
            Checklist alvo = CenarioFactory.novoChecklist(emReal(), sala, operador);
            Checklist ruido = CenarioFactory.novoChecklist(emReal(), sala, operador);
            ChecklistResposta respostaOk = CenarioFactory.novaResposta(emReal(), alvo, tipoOk, StatusResposta.OK, null);
            ChecklistResposta respostaFalha = CenarioFactory.novaResposta(emReal(), alvo, tipoFalha,
                    StatusResposta.FALHA, "Falha grave no equipamento de audio");
            respostaOk.setValorTexto("Console mesa 2");
            emReal().flush();
            CenarioFactory.novaResposta(emReal(), ruido, tipoOk, StatusResposta.OK, null);

            List<Object[]> rows = respostaRepo.findByChecklistIdNative(alvo.getId());

            assertEquals(2, rows.size(), "respostas de outro checklist ficam fora");
            // colunas: [0]=ID, [1]=ITEM_TIPO_ID, [2]=STATUS, [3]=DESCRICAO_FALHA, [4]=VALOR_TEXTO — sem ORDER BY
            Map<Integer, Object[]> porTipo = rows.stream()
                    .collect(Collectors.toMap(r -> ((Number) r[1]).intValue(), Function.identity()));
            Object[] rowOk = porTipo.get(tipoOk.getId());
            assertEquals(respostaOk.getId().longValue(), ((Number) rowOk[0]).longValue());
            assertEquals("Ok", rowOk[2]);
            assertNull(rowOk[3]);
            assertEquals("Console mesa 2", rowOk[4]);
            Object[] rowFalha = porTipo.get(tipoFalha.getId());
            assertEquals(respostaFalha.getId().longValue(), ((Number) rowFalha[0]).longValue());
            assertEquals("Falha", rowFalha[2]);
            // DESCRICAO_FALHA é CLOB — pode vir java.sql.Clob do driver; str() do projeto normaliza
            assertEquals("Falha grave no equipamento de audio", NativeQueryUtils.str(rowFalha[3]));
            assertNull(rowFalha[4]);
        }

        @Test
        @DisplayName("findByChecklistIdNative — checklist sem respostas retorna vazio")
        void findByChecklistIdNative_checklistSemRespostasRetornaVazio() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, operador);

            List<Object[]> rows = respostaRepo.findByChecklistIdNative(checklist.getId());
            assertTrue(rows.isEmpty(),
                    "checklist sem respostas deveria retornar vazio, vieram " + rows.size() + " linha(s)");
        }
    }

    @Nested
    @DisplayName("deactivateAllBySalaId (@Modifying JPQL — exceção D12)")
    class DeactivateAllBySalaId {

        @Test
        @DisplayName("deactivateAllBySalaId — desativa (e zera ORDEM) em massa só as configs da sala alvo")
        void deactivateAllBySalaId_desativaSoASalaAlvo() {
            Sala salaAlvo = CenarioFactory.novaSala(emReal());
            Sala outraSala = CenarioFactory.novaSala(emReal());
            ChecklistItemTipo tipoUm = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoDois = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoDeOutraSala = CenarioFactory.novoItemTipo(emReal());
            ChecklistSalaConfig configUm = CenarioFactory.novaConfig(emReal(), salaAlvo, tipoUm, 1, true);
            ChecklistSalaConfig configDois = CenarioFactory.novaConfig(emReal(), salaAlvo, tipoDois, 2, true);
            ChecklistSalaConfig configDeOutraSala = CenarioFactory.novaConfig(emReal(), outraSala, tipoDeOutraSala, 5, true);

            salaConfigRepo.deactivateAllBySalaId(salaAlvo.getId());

            em.clear(); // bulk JPQL não sincroniza o persistence context — reler do banco
            ChecklistSalaConfig umDepois = em.find(ChecklistSalaConfig.class, configUm.getId());
            ChecklistSalaConfig doisDepois = em.find(ChecklistSalaConfig.class, configDois.getId());
            ChecklistSalaConfig outraDepois = em.find(ChecklistSalaConfig.class, configDeOutraSala.getId());
            assertEquals(Boolean.FALSE, umDepois.getAtivo());
            assertEquals(0, umDepois.getOrdem());
            assertEquals(Boolean.FALSE, doisDepois.getAtivo());
            assertEquals(0, doisDepois.getOrdem());
            assertEquals(Boolean.TRUE, outraDepois.getAtivo(), "a outra sala NÃO pode ser desativada");
            assertEquals(5, outraDepois.getOrdem());
        }

        @Test
        @DisplayName("deactivateAllBySalaId — repetir a chamada é no-op sem erro (idempotente)")
        void deactivateAllBySalaId_repetidaEIdempotente() {
            Sala sala = CenarioFactory.novaSala(emReal());
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(emReal());
            ChecklistSalaConfig config = CenarioFactory.novaConfig(emReal(), sala, tipo, 1, true);

            salaConfigRepo.deactivateAllBySalaId(sala.getId());
            salaConfigRepo.deactivateAllBySalaId(sala.getId());

            em.clear();
            ChecklistSalaConfig depois = em.find(ChecklistSalaConfig.class, config.getId());
            assertEquals(Boolean.FALSE, depois.getAtivo());
            assertEquals(0, depois.getOrdem());
        }
    }
}
