package br.leg.senado.nusp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.ChecklistItemTipo;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.StatusResposta;
import br.leg.senado.nusp.it.support.Causas;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * ITs dos 4 statements nativos de {@link RegistroOperacaoAudioRepository} contra
 * Oracle real — a raiz do grafo da operação (sessão de áudio).
 *
 * Nos testes de comportamento, toda releitura de OPR_REGISTRO_AUDIO é por SQL NATIVO
 * ({@link #lerRegistroAudio}) — e não por em.find(). O @Nested "leitura da entidade
 * pelo JPA", ao fim desta classe, é o ÚNICO em.find() daqui: trava que a leitura JPA
 * dos campos Instant (CRIADO_EM/FECHADO_EM, coluna TIMESTAMP) não estoura ORA-18716 —
 * ela depende da property {@code hibernate.type.preferred_instant_jdbc_type: TIMESTAMP}
 * no application.yml (sem ela, o Hibernate lê Instant como TIMESTAMP_UTC e pede
 * OffsetDateTime numa coluna sem fuso).
 */
@OracleIT
class RegistroOperacaoAudioRepositoryIT {

    @Autowired
    private RegistroOperacaoAudioRepository repo;

    @Autowired
    private TestEntityManager em;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    /** Estado do registro sem passar pela entidade: [EM_ABERTO, SALA_ID, FECHADO_POR, FECHADO_EM]. */
    private Object[] lerRegistroAudio(Long id) {
        return (Object[]) emReal().createNativeQuery("""
                SELECT EM_ABERTO, SALA_ID, FECHADO_POR, FECHADO_EM
                FROM OPR_REGISTRO_AUDIO WHERE ID = :id
                """)
                .setParameter("id", id)
                .getSingleResult();
    }

    @Nested
    @DisplayName("findSessaoAbertaPorSala")
    class FindSessaoAbertaPorSala {

        @Test
        @DisplayName("findSessaoAbertaPorSala — devolve a sessão aberta da sala com as 7 colunas, ignorando a fechada")
        void findSessaoAbertaPorSala_devolveAAbertaComTodasAsColunas() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, operador);
            CenarioFactory.novoRegistroAudio(emReal(), sala, false); // fechada, mais antiga
            RegistroOperacaoAudio aberta = CenarioFactory.novoRegistroAudio(emReal(), sala, true);
            aberta.setChecklistDoDiaId(checklist.getId());
            aberta.setChecklistDoDiaOk(true);
            aberta.setNomeDemaisSalas("Sala Extra IT");
            emReal().flush();

            List<Object[]> rows = repo.findSessaoAbertaPorSala(sala.getId());

            assertEquals(1, rows.size(), "só a sessão aberta da sala pode voltar");
            Object[] r = rows.get(0);
            assertEquals(aberta.getId().longValue(), ((Number) r[0]).longValue());
            assertEquals("2026-07-01", r[1].toString().substring(0, 10), "DATA do registro");
            assertEquals(sala.getId().intValue(), ((Number) r[2]).intValue());
            assertEquals(sala.getNome(), r[3], "SALA_NOME vem do JOIN com CAD_SALA");
            assertEquals(checklist.getId().longValue(), ((Number) r[4]).longValue());
            assertEquals(1, ((Number) r[5]).intValue(), "CHECKLIST_DO_DIA_OK");
            assertEquals("Sala Extra IT", r[6]);
        }

        @Test
        @DisplayName("findSessaoAbertaPorSala — sala só com sessão fechada retorna vazio (a aberta de outra sala não vaza)")
        void findSessaoAbertaPorSala_salaSoComFechadaRetornaVazio() {
            Sala salaConsultada = CenarioFactory.novaSala(emReal());
            Sala salaControle = CenarioFactory.novaSala(emReal());
            CenarioFactory.novoRegistroAudio(emReal(), salaConsultada, false);
            CenarioFactory.novoRegistroAudio(emReal(), salaControle, true);

            List<Object[]> rows = repo.findSessaoAbertaPorSala(salaConsultada.getId());

            assertTrue(rows.isEmpty(), "sessão fechada (ou aberta de OUTRA sala) não pode casar a forma FBI"
                    + " CASE WHEN EM_ABERTO = 1 THEN SALA_ID END = :salaId, vieram " + rows.size() + " linha(s)");
        }

        @Test
        @DisplayName("caracteriza UQ_OPR_REGAUDIO_SALA_ABERTA — o banco impede 2ª sessão ABERTA na mesma sala "
                + "(o cenário 'FETCH FIRST 1 com duas abertas' é inalcançável; o ORDER BY ID DESC é hoje defensivo)")
        void persistirSegundaSessaoAbertaNaMesmaSala_violaUnique() {
            Sala sala = CenarioFactory.novaSala(emReal());
            CenarioFactory.novoRegistroAudio(emReal(), sala, true);

            // A FBI única real do schema (a mesma cuja forma o WHERE da query espelha)
            // torna o cenário "FETCH FIRST 1 com duas abertas" impossível.
            PersistenceException ex = assertThrows(PersistenceException.class,
                    () -> CenarioFactory.novoRegistroAudio(emReal(), sala, true));

            assertTrue(Causas.contem(ex, "UQ_OPR_REGAUDIO_SALA_ABERTA"),
                    "a violação deveria vir do índice único funcional UQ_OPR_REGAUDIO_SALA_ABERTA, não de outro erro");
        }
    }

    @Nested
    @DisplayName("findChecklistDoDia")
    class FindChecklistDoDia {

        @Test
        @DisplayName("findChecklistDoDia — checklist sem resposta 'Falha' retorna OK = 1")
        void findChecklistDoDia_semFalhaRetornaOk1() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, operador);
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(emReal());
            CenarioFactory.novaResposta(emReal(), checklist, tipo, StatusResposta.OK, null);

            List<Object[]> rows = repo.findChecklistDoDia("2026-07-01", sala.getId());

            assertEquals(1, rows.size());
            assertEquals(checklist.getId().longValue(), ((Number) rows.get(0)[0]).longValue());
            assertEquals(1, ((Number) rows.get(0)[1]).intValue(),
                    "resposta 'Ok' não pode zerar o flag — o EXISTS procura STATUS = 'Falha'");
        }

        @Test
        @DisplayName("findChecklistDoDia — existe resposta 'Falha' retorna OK = 0")
        void findChecklistDoDia_comFalhaRetornaOk0() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, operador);
            ChecklistItemTipo tipoOk = CenarioFactory.novoItemTipo(emReal());
            ChecklistItemTipo tipoFalha = CenarioFactory.novoItemTipo(emReal());
            CenarioFactory.novaResposta(emReal(), checklist, tipoOk, StatusResposta.OK, null);
            CenarioFactory.novaResposta(emReal(), checklist, tipoFalha, StatusResposta.FALHA, "Falha IT");

            List<Object[]> rows = repo.findChecklistDoDia("2026-07-01", sala.getId());

            assertEquals(1, rows.size());
            assertEquals(0, ((Number) rows.get(0)[1]).intValue(),
                    "uma resposta 'Falha' entre as demais deveria zerar o flag OK");
        }

        @Test
        @DisplayName("findChecklistDoDia — com 2 checklists na mesma sala+data vence o mais recente (ORDER BY ID DESC), e o OK é dele")
        void findChecklistDoDia_doisChecklistsVenceOMaisRecente() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Checklist antigo = CenarioFactory.novoChecklist(emReal(), sala, operador);
            ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(emReal());
            CenarioFactory.novaResposta(emReal(), antigo, tipo, StatusResposta.FALHA, "Falha IT");
            Checklist recente = CenarioFactory.novoChecklist(emReal(), sala, operador);

            List<Object[]> rows = repo.findChecklistDoDia("2026-07-01", sala.getId());

            assertEquals(1, rows.size(), "FETCH FIRST 1 deveria devolver uma única linha");
            assertEquals(recente.getId().longValue(), ((Number) rows.get(0)[0]).longValue(),
                    "deveria vencer o checklist de maior ID");
            assertEquals(1, ((Number) rows.get(0)[1]).intValue(),
                    "o OK é do checklist retornado — a 'Falha' do antigo não pode contaminar o recente");
        }

        @Test
        @DisplayName("findChecklistDoDia — data sem checklist ou sala errada retorna vazio")
        void findChecklistDoDia_dataOuSalaErradaRetornaVazio() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Sala salaSemChecklist = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            CenarioFactory.novoChecklist(emReal(), sala, operador); // DATA_OPERACAO = 2026-07-01

            assertTrue(repo.findChecklistDoDia("2026-07-02", sala.getId()).isEmpty(),
                    "outra data não pode casar o TO_DATE(:data, 'YYYY-MM-DD')");
            assertTrue(repo.findChecklistDoDia("2026-07-01", salaSemChecklist.getId()).isEmpty(),
                    "checklist de outra sala não pode vazar");
        }
    }

    @Nested
    @DisplayName("finalizarSessao")
    class FinalizarSessao {

        @Test
        @DisplayName("finalizarSessao — fecha a sessão aberta (1), grava FECHADO_EM/FECHADO_POR e não toca a de outra sala")
        void finalizarSessao_fechaAAbertaEGravaAuditoria() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Sala salaControle = CenarioFactory.novaSala(emReal());
            RegistroOperacaoAudio alvo = CenarioFactory.novoRegistroAudio(emReal(), sala, true);
            RegistroOperacaoAudio controle = CenarioFactory.novoRegistroAudio(emReal(), salaControle, true);
            String userId = UUID.randomUUID().toString();

            int fechadas = repo.finalizarSessao(alvo.getId(), userId);

            assertEquals(1, fechadas);
            em.clear();
            Object[] estado = lerRegistroAudio(alvo.getId());
            assertEquals(0, ((Number) estado[0]).intValue(), "EM_ABERTO deveria virar 0");
            assertEquals(userId, estado[2], "FECHADO_POR deveria ser o usuário informado");
            assertNotNull(estado[3], "FECHADO_EM deveria ser gravado com SYSTIMESTAMP");
            Object[] estadoControle = lerRegistroAudio(controle.getId());
            assertEquals(1, ((Number) estadoControle[0]).intValue(), "a sessão de controle não pode ser fechada");
            assertNull(estadoControle[3], "FECHADO_EM da sessão de controle não pode ser gravado");
        }

        @Test
        @DisplayName("finalizarSessao — repetida retorna 0 sem regravar a auditoria; id inexistente retorna 0")
        void finalizarSessao_repetidaEIdInexistenteRetornam0() {
            Sala sala = CenarioFactory.novaSala(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala, true);
            String primeiroUser = UUID.randomUUID().toString();
            String segundoUser = UUID.randomUUID().toString();

            assertEquals(1, repo.finalizarSessao(registro.getId(), primeiroUser));
            em.clear();
            Object fechadoEmAntes = lerRegistroAudio(registro.getId())[3];

            assertEquals(0, repo.finalizarSessao(registro.getId(), segundoUser),
                    "sessão já fechada não pode casar o predicado EM_ABERTO = 1");
            em.clear();
            Object[] estado = lerRegistroAudio(registro.getId());
            assertEquals(primeiroUser, estado[2], "FECHADO_POR não pode ser sobrescrito pela repetição");
            assertEquals(fechadoEmAntes, estado[3], "FECHADO_EM não pode ser regravado pela repetição");

            assertEquals(0, repo.finalizarSessao(-1L, segundoUser), "id inexistente não afeta linha alguma");
        }
    }

    @Nested
    @DisplayName("updateSalaByEntrada")
    class UpdateSalaByEntrada {

        @Test
        @DisplayName("updateSalaByEntrada — muda a sala do registro DONO da entrada; o de outra entrada fica intacto")
        void updateSalaByEntrada_mudaASalaDoRegistroDonoDaEntrada() {
            Sala salaOrigem = CenarioFactory.novaSala(emReal());
            Sala salaControle = CenarioFactory.novaSala(emReal());
            Sala salaDestino = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio alvo = CenarioFactory.novoRegistroAudio(emReal(), salaOrigem, true);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), alvo, operador, 1);
            RegistroOperacaoAudio controle = CenarioFactory.novoRegistroAudio(emReal(), salaControle, true);
            CenarioFactory.novaEntrada(emReal(), controle, operador, 1);

            repo.updateSalaByEntrada(entrada.getId(), salaDestino.getId());

            em.clear();
            assertEquals(salaDestino.getId().intValue(), ((Number) lerRegistroAudio(alvo.getId())[1]).intValue(),
                    "SALA_ID do registro dono da entrada deveria mudar");
            assertEquals(salaControle.getId().intValue(), ((Number) lerRegistroAudio(controle.getId())[1]).intValue(),
                    "o registro da OUTRA entrada não pode mudar de sala");
        }

        @Test
        @DisplayName("updateSalaByEntrada — entrada inexistente não muda registro algum (subquery vazia)")
        void updateSalaByEntrada_entradaInexistenteNaoMudaNada() {
            Sala salaOrigem = CenarioFactory.novaSala(emReal());
            Sala salaDestino = CenarioFactory.novaSala(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), salaOrigem, true);

            repo.updateSalaByEntrada(-1L, salaDestino.getId());

            em.clear();
            assertEquals(salaOrigem.getId().intValue(), ((Number) lerRegistroAudio(registro.getId())[1]).intValue(),
                    "nenhum registro pode mudar quando a entrada não existe");
        }
    }

    @Nested
    @DisplayName("leitura da entidade pelo JPA")
    class LeituraJpaDaEntidade {

        @Test
        @DisplayName("em.find de campo Instant (coluna TIMESTAMP) devolve os carimbos gravados, "
                + "sem ORA-18716")
        void emFind_deCampoInstantDevolveOsCarimbosGravados() {
            // Trava a ausência do ORA-18716 na leitura JPA de campos Instant.
            //
            //  · O driver não é o culpado: o erro reproduzia idêntico mesmo no ojdbc11 mais recente.
            //  · A causa é o mapeamento: no dialeto Oracle, o Hibernate 6 lê Instant como
            //    TIMESTAMP_UTC, isto é, pede getObject(col, OffsetDateTime.class) ao driver — e o
            //    ojdbc recusa isso numa coluna TIMESTAMP *sem* fuso ("ORA-18716: não está em nenhum
            //    fuso horário").
            //  · A cura é a property `hibernate.type.preferred_instant_jdbc_type: TIMESTAMP` no
            //    application.yml — Instant passa a ir e voltar por get/setTimestamp(), e ninguém mais
            //    pede OffsetDateTime. Que a ESCRITA continua byte a byte a de produção (wall-clock
            //    UTC) é o que o InstantJdbcTypeIT mede, com a JVM de teste pinada em UTC.
            //
            // A releitura por SQL nativo dos demais testes (lerRegistroAudio, acima) é opcional,
            // não errada.
            Sala sala = CenarioFactory.novaSala(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala, false);
            Instant fechadoEm = Instant.parse("2026-07-12T15:00:00Z");
            registro.setFechadoEm(fechadoEm);
            registro.setFechadoPor("uuid-fechador");
            emReal().flush();
            Long id = registro.getId();
            Instant criadoEm = registro.getCriadoEm();   // carimbo do @PrePersist, já gravado

            emReal().clear();   // sem isto, o find devolveria a instância do cache de 1º nível — o driver nem seria tocado

            RegistroOperacaoAudio lido = emReal().find(RegistroOperacaoAudio.class, id);

            assertNotNull(lido, "a leitura JPA da entidade com campos Instant não pode mais estourar");
            assertEquals(fechadoEm, lido.getFechadoEm(), "FECHADO_EM volta do banco idêntico ao gravado");
            assertTrue(Math.abs(Duration.between(criadoEm, lido.getCriadoEm()).toNanos()) <= 1_000L,
                    "CRIADO_EM idem — com 1 µs de folga, e não igualdade exata, porque o carimbo do @PrePersist"
                            + " vem de Instant.now() (nanos) e a coluna é TIMESTAMP(6), que ARREDONDA a fração;"
                            + " veio " + lido.getCriadoEm() + ", esperado ~" + criadoEm);
            assertEquals("uuid-fechador", lido.getFechadoPor());
        }
    }
}
