package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.leg.senado.nusp.entity.Comissao;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.TipoEvento;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.EntradaOperadorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.RegistroOperacaoAudioRepository;
import br.leg.senado.nusp.repository.RegistroOperacaoOperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.repository.SuspensaoRepository;
import jakarta.persistence.EntityManager;

/**
 * ITs da SEMÂNTICA dos UPDATEs de {@link OperacaoService} contra Oracle real (§4.3) — o que o T6
 * travou só por contrato de chamada. Foco nos flags {@code *_EDITADO} sticky (CASE/NVL reais), na
 * propagação dos campos compartilhados da sessão, no NOME_DEMAIS_SALAS e no histórico obrigatório.
 *
 * <p>O SUT é construído à mão (EntityManager + repositories reais do slice + ObjectMapper). O
 * {@code @Transactional} é inerte nesse arranjo (sem proxy — limitação da FASE C); o que se prova
 * aqui é o SQL/persistência dentro da transação do próprio teste. Toda releitura de
 * OPR_REGISTRO_AUDIO é por SQL nativo — contorno do F14 (a leitura JPA dos {@code Instant}
 * FECHADO_EM/CRIADO_EM morria com ORA-18716 até o C5 curar; mantido por escolha, não por
 * necessidade); as ENTRADAS ({@code LocalDateTime}) usam {@code em.find}.
 * {@code flush()} antes de {@code clear()} garante que o histórico (persist pendente) e os
 * dirty-checks materializem antes da releitura (gotcha 17).
 */
@OracleIT
class OperacaoServiceIT {

    @Autowired
    private TestEntityManager em;
    @Autowired
    private RegistroOperacaoAudioRepository audioRepo;
    @Autowired
    private RegistroOperacaoOperadorRepository entradaRepo;
    @Autowired
    private EntradaOperadorRepository entradaOperadorRepo;
    @Autowired
    private SuspensaoRepository suspensaoRepo;
    @Autowired
    private SalaRepository salaRepo;
    @Autowired
    private OperadorRepository operadorRepo;

    private OperacaoService service;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        service = new OperacaoService(audioRepo, entradaRepo, entradaOperadorRepo, suspensaoRepo,
                salaRepo, operadorRepo, emReal(), new ObjectMapper());
    }

    // ── Helpers ──────────────────────────────────────────────

    /** Body de edição a partir de pares chave/valor — o endpoint recebe Map<String,Object>. */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * Entrada com os 3 campos que o editarEntrada de sala comum exige (nome_evento, hora_inicio,
     * responsavel_evento) e o resto NULL — para que só o campo que o body altera ligue sua flag.
     */
    private RegistroOperacaoOperador entradaEditavel(RegistroOperacaoAudio registro, Operador dono, int ordem,
            String nomeEvento, String horaInicio, String responsavel) {
        RegistroOperacaoOperador e = new RegistroOperacaoOperador();
        e.setRegistroId(registro.getId());
        e.setOperadorId(dono.getId());
        e.setOrdem(ordem);
        e.setSeq(1);
        e.setNomeEvento(nomeEvento);
        e.setHorarioInicio(horaInicio);
        e.setResponsavelEvento(responsavel);
        e.setTipoEvento(TipoEvento.OPERACAO);
        e.setCriadoPor(dono.getId());
        e.setAtualizadoPor(dono.getId());
        emReal().persist(e);
        emReal().flush();
        return e;
    }

    /** Registro de áudio aberto apontando a uma sala por id (usado pela sala fixa "Demais Salas"). */
    private RegistroOperacaoAudio registroNaSala(int salaId) {
        RegistroOperacaoAudio reg = new RegistroOperacaoAudio();
        reg.setData(LocalDate.of(2026, 7, 1));
        reg.setSalaId(salaId);
        reg.setEmAberto(true);
        emReal().persist(reg);
        emReal().flush();
        return reg;
    }

    /**
     * Insere a sala "Demais Salas" (ID = {@link OperacaoService#SALA_DEMAIS_SALAS_ID} = 11), fixo no
     * código. CAD_SALA.ID é GENERATED BY DEFAULT → aceita override; o clone é vazio e o rollback
     * isola, então não colide entre testes.
     */
    private void criarSalaDemaisSalas() {
        emReal().createNativeQuery(
                "INSERT INTO CAD_SALA (ID, NOME, ATIVO, CRIADO_EM, ATUALIZADO_EM, MULTI_OPERADOR) "
                        + "VALUES (:id, :nome, 1, SYSTIMESTAMP, SYSTIMESTAMP, 0)")
                .setParameter("id", OperacaoService.SALA_DEMAIS_SALAS_ID)
                .setParameter("nome", "Demais Salas IT")
                .executeUpdate();
        emReal().clear();
    }

    /** Recarrega a entrada do banco (após UPDATE nativo — flush pendente + limpa o cache L1). */
    private RegistroOperacaoOperador relerEntrada(Long id) {
        emReal().flush();
        emReal().clear();
        return emReal().find(RegistroOperacaoOperador.class, id);
    }

    /** [DATA, DATA_EDITADO, NOME_DEMAIS_SALAS] por SQL nativo — OPR_REGISTRO_AUDIO tem Instant (F14). */
    private Object[] lerRegistroAudio(Long id) {
        emReal().flush();
        emReal().clear();
        return (Object[]) emReal().createNativeQuery(
                "SELECT DATA, DATA_EDITADO, NOME_DEMAIS_SALAS FROM OPR_REGISTRO_AUDIO WHERE ID = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    @Nested
    @DisplayName("editarEntrada — flags *_EDITADO sticky")
    class FlagsSticky {

        @Test
        @DisplayName("editarEntrada — alterar só nome_evento liga NOME_EVENTO_EDITADO e mais nenhuma flag (CASE/NVL real)")
        void editarEntrada_soOCampoAlteradoLigaSuaFlag() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = entradaEditavel(registro, dono, 1,
                    "Evento Original", "09:00:00", "Resp Original");

            // hora_inicio e responsavel_evento repetem o valor atual → suas flags NÃO ligam
            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Evento NOVO", "hora_inicio", "09:00:00", "responsavel_evento", "Resp Original"),
                    dono.getId());

            RegistroOperacaoOperador r = relerEntrada(entrada.getId());
            assertEquals("Evento NOVO", r.getNomeEvento());
            assertTrue(r.getEditado(), "EDITADO é sempre ligado no UPDATE");
            assertTrue(r.getNomeEventoEditado(), "o único campo que mudou de valor");
            assertFalse(r.getResponsavelEventoEditado(), "valor repetido → NVL(a)=NVL(b) → 0");
            assertFalse(r.getHorarioInicioEditado(), "valor repetido → 0");
            assertFalse(r.getHorarioPautaEditado());
            assertFalse(r.getHorarioTerminoEditado());
            assertFalse(r.getUsb01Editado());
            assertFalse(r.getUsb02Editado());
            assertFalse(r.getObservacoesEditado());
            assertFalse(r.getComissaoEditado(), "NVL(null,-1)=NVL(null,-1) → 0");
            assertFalse(r.getHoraEntradaEditado());
            assertFalse(r.getHoraSaidaEditado());
        }

        @Test
        @DisplayName("editarEntrada — flag sticky: reeditar SEM mudar o campo NÃO zera a flag; voltar ao original também não")
        void editarEntrada_flagStickyNaoVoltaAZero() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = entradaEditavel(registro, dono, 1,
                    "Evento Original", "09:00:00", "Resp Original");

            // 1ª edição: muda o nome → a flag liga (old != new)
            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Evento Editado", "hora_inicio", "09:00:00", "responsavel_evento", "Resp Original"),
                    dono.getId());
            assertTrue(relerEntrada(entrada.getId()).getNomeEventoEditado(), "1ª edição liga a flag (0→1)");

            // 2ª edição: reenvia o MESMO valor (old == new) — SEM o ramo "WHEN _EDITADO=1 THEN 1" o CASE
            // daria 0. É este cenário (e não "voltar ao original") que isola o sticky.
            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Evento Editado", "hora_inicio", "09:00:00", "responsavel_evento", "Resp Original"),
                    dono.getId());
            RegistroOperacaoOperador r2 = relerEntrada(entrada.getId());
            assertEquals("Evento Editado", r2.getNomeEvento());
            assertTrue(r2.getNomeEventoEditado(), "reeditar sem mudar o campo NÃO zera a flag (sticky)");

            // 3ª edição: volta ao valor ORIGINAL — o valor volta, a flag continua 1
            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Evento Original", "hora_inicio", "09:00:00", "responsavel_evento", "Resp Original"),
                    dono.getId());
            RegistroOperacaoOperador r3 = relerEntrada(entrada.getId());
            assertEquals("Evento Original", r3.getNomeEvento(), "o VALOR volta ao original...");
            assertTrue(r3.getNomeEventoEditado(), "...mas a FLAG não retorna a 0");
        }

        @Test
        @DisplayName("editarEntrada — DATA_EDITADO do registro é sticky: muda a data (Plenário Principal) e não volta a 0")
        void editarEntrada_dataEditadoSticky() {
            // isMultiOp=true é a condição para parsear data_operacao (só o Plenário Principal altera data)
            Sala pp = CenarioFactory.novaSala(emReal(), true);
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), pp); // DATA = 2026-07-01
            RegistroOperacaoOperador entrada = entradaEditavel(registro, dono, 1, "Sessao PP", "09:00:00", null);

            // 1ª edição: nova data → DATA muda e DATA_EDITADO liga
            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Sessao PP", "hora_inicio", "09:00:00", "data_operacao", "2026-08-15"),
                    dono.getId());
            Object[] apos1 = lerRegistroAudio(registro.getId());
            assertEquals("2026-08-15", apos1[0].toString().substring(0, 10));
            assertEquals(1, ((Number) apos1[1]).intValue(), "DATA != nova → DATA_EDITADO liga");

            // 2ª edição: reenvia a MESMA data (DATA == nova) — sem o ramo "WHEN DATA_EDITADO=1 THEN 1"
            // o CASE (DATA != :dt2) daria 0. É isto que isola o sticky de DATA_EDITADO.
            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Sessao PP", "hora_inicio", "09:00:00", "data_operacao", "2026-08-15"),
                    dono.getId());
            Object[] apos2 = lerRegistroAudio(registro.getId());
            assertEquals("2026-08-15", apos2[0].toString().substring(0, 10));
            assertEquals(1, ((Number) apos2[1]).intValue(), "reenviar a mesma data NÃO zera DATA_EDITADO (sticky)");
        }
    }

    @Nested
    @DisplayName("editarEntrada — NOME_DEMAIS_SALAS")
    class NomeDemaisSalas {

        @Test
        @DisplayName("NOME_DEMAIS_SALAS — sessão na sala 11 grava o nome livre recebido no body")
        void nomeDemaisSalas_sala11Seta() {
            criarSalaDemaisSalas();
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = registroNaSala(OperacaoService.SALA_DEMAIS_SALAS_ID);
            RegistroOperacaoOperador entrada = entradaEditavel(registro, dono, 1, "Evento", "09:00:00", "Resp");

            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Evento", "hora_inicio", "09:00:00", "responsavel_evento", "Resp",
                            "nome_demais_salas", "Sala Improvisada IT"),
                    dono.getId());

            assertEquals("Sala Improvisada IT", lerRegistroAudio(registro.getId())[2]);
        }

        @Test
        @DisplayName("NOME_DEMAIS_SALAS — sala comum (≠ 11) zera o campo para NULL (resíduo é limpo)")
        void nomeDemaisSalas_salaComumZera() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            registro.setNomeDemaisSalas("Residual a limpar");
            emReal().flush();
            RegistroOperacaoOperador entrada = entradaEditavel(registro, dono, 1, "Evento", "09:00:00", "Resp");

            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Evento", "hora_inicio", "09:00:00", "responsavel_evento", "Resp"),
                    dono.getId());

            assertNull(lerRegistroAudio(registro.getId())[2], "sala ≠ 11 força NOME_DEMAIS_SALAS a NULL");
        }
    }

    @Nested
    @DisplayName("editarEntrada — propagação e histórico")
    class PropagacaoEHistorico {

        @Test
        @DisplayName("propagarCamposSessao — editar a entrada de ORDEM 1 propaga os 6 campos compartilhados para as OUTRAS")
        void propagarCamposSessao_ordem1PropagaParaAsOutras() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono1 = CenarioFactory.novoOperador(emReal());
            Operador dono2 = CenarioFactory.novoOperador(emReal());
            Comissao comissao = CenarioFactory.novaComissao(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador ordem1 = entradaEditavel(registro, dono1, 1, "Orig 1", "09:00:00", "Resp 1");
            RegistroOperacaoOperador ordem2 = CenarioFactory.novaEntrada(emReal(), registro, dono2, 2);

            service.editarEntrada(ordem1.getId(),
                    body("nome_evento", "Evento Propagado", "hora_inicio", "09:30:00", "responsavel_evento", "Resp Propagado",
                            "horario_pauta", "08:00:00", "tipo_evento", "cessao", "comissao_id", String.valueOf(comissao.getId())),
                    dono1.getId());

            RegistroOperacaoOperador outra = relerEntrada(ordem2.getId());
            assertEquals("Evento Propagado", outra.getNomeEvento(), "nome_evento propaga da ordem 1");
            assertEquals("08:00:00", outra.getHorarioPauta());
            assertEquals("09:30:00", outra.getHorarioInicio());
            assertEquals(TipoEvento.CESSAO, outra.getTipoEvento());
            assertEquals(comissao.getId(), outra.getComissaoId());
            assertEquals("Resp Propagado", outra.getResponsavelEvento());
        }

        @Test
        @DisplayName("editarEntrada — grava histórico em OPR_REGISTRO_ENTRADA_HIST com snapshot JSON do estado ANTERIOR")
        void editarEntrada_gravaHistoricoDoEstadoAnterior() throws Exception {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = entradaEditavel(registro, dono, 1, "Antes", "09:00:00", "Resp");

            service.editarEntrada(entrada.getId(),
                    body("nome_evento", "Depois", "hora_inicio", "09:00:00", "responsavel_evento", "Resp"),
                    dono.getId());

            emReal().flush();
            emReal().clear();
            Number total = (Number) emReal().createNativeQuery(
                    "SELECT COUNT(*) FROM OPR_REGISTRO_ENTRADA_HIST WHERE ENTRADA_ID = :id")
                    .setParameter("id", entrada.getId()).getSingleResult();
            assertEquals(1, total.intValue(), "uma linha de histórico por edição");

            Object snapRaw = emReal().createNativeQuery(
                    "SELECT SNAPSHOT FROM OPR_REGISTRO_ENTRADA_HIST WHERE ENTRADA_ID = :id")
                    .setParameter("id", entrada.getId()).getSingleResult();
            Map<?, ?> snapshot = new ObjectMapper().readValue(NativeQueryUtils.str(snapRaw), Map.class);
            assertEquals("Antes", snapshot.get("nome_evento"),
                    "o snapshot é do estado ANTES da alteração (montado antes do UPDATE)");
            assertEquals("Resp", snapshot.get("responsavel_evento"));
        }
    }
}
