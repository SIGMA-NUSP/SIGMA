package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.RegistroAnormalidadeRepository;
import br.leg.senado.nusp.repository.RegistroOperacaoOperadorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários do AnormalidadeService (T5 — §4.2/§4.6 da auditoria).
 *
 * Prioridade máxima: syncHouveAnormalidade (substitui trigger do PostgreSQL)
 * e validações condicionais (substituem CHECK constraints).
 */
@ExtendWith(MockitoExtension.class)
class AnormalidadeServiceTest {

    @Mock
    private RegistroAnormalidadeRepository anormalidadeRepo;

    @Mock
    private RegistroOperacaoOperadorRepository entradaRepo;

    @InjectMocks
    private AnormalidadeService service;

    // ══ syncHouveAnormalidade — o método mais crítico ═══════════

    @Nested
    @DisplayName("syncHouveAnormalidade")
    class SyncHouveAnormalidade {

        @Test
        @DisplayName("com anormalidade existente → marca houve_anormalidade = 1")
        void withExistingAnormalidade_setsTrue() {
            when(anormalidadeRepo.existsByEntradaId(42L)).thenReturn(true);

            service.syncHouveAnormalidade(42L);

            verify(anormalidadeRepo).updateHouveAnormalidade(42L, 1);
        }

        @Test
        @DisplayName("sem anormalidade → marca houve_anormalidade = 0")
        void withNoAnormalidade_setsFalse() {
            when(anormalidadeRepo.existsByEntradaId(42L)).thenReturn(false);

            service.syncHouveAnormalidade(42L);

            verify(anormalidadeRepo).updateHouveAnormalidade(42L, 0);
        }

        @Test
        @DisplayName("com null → não faz nada")
        void withNull_doesNothing() {
            service.syncHouveAnormalidade(null);

            verifyNoInteractions(anormalidadeRepo);
        }
    }

    // ══ Validações condicionais (CHECK constraints migrados) ═══

    @Nested
    @DisplayName("Validações de registro")
    class Validacoes {

        private Map<String, Object> bodyValido() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("registro_id", "100");
            body.put("data", "2026-03-17");
            body.put("sala_id", "1");
            body.put("nome_evento", "Sessão Plenária");
            body.put("hora_inicio_anormalidade", "14:00:00");
            body.put("descricao_anormalidade", "Falha no microfone");
            body.put("responsavel_evento", "João Silva");
            body.put("houve_prejuizo", "false");
            body.put("houve_reclamacao", "false");
            body.put("acionou_manutencao", "false");
            body.put("resolvida_pelo_operador", "false");
            return body;
        }

        @Test
        @DisplayName("campos obrigatórios faltando → lança exceção")
        void missingRequiredFields_throws() {
            Map<String, Object> body = new LinkedHashMap<>();

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.registrar(body, "user-1"));

            assertEquals("Erros de validação nos campos.", ex.getMessage());
        }

        @Test
        @DisplayName("ck_prejuizo_desc: houve_prejuizo=true sem descrição → erro")
        void ckPrejuizoDesc_throws() {
            Map<String, Object> body = bodyValido();
            body.put("houve_prejuizo", "true");
            // descricao_prejuizo está ausente

            assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, "user-1"));
        }

        @Test
        @DisplayName("ck_reclamacao_desc: houve_reclamacao=true sem autores → erro")
        void ckReclamacaoDesc_throws() {
            Map<String, Object> body = bodyValido();
            body.put("houve_reclamacao", "true");
            // autores_conteudo_reclamacao está ausente

            assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, "user-1"));
        }

        @Test
        @DisplayName("ck_manutencao_hora: acionou_manutencao=true sem hora → erro")
        void ckManutencaoHora_throws() {
            Map<String, Object> body = bodyValido();
            body.put("acionou_manutencao", "true");
            // hora_acionamento_manutencao está ausente

            assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, "user-1"));
        }

        @Test
        @DisplayName("ck_datas_coerentes: data_solucao < data → erro")
        void ckDatasCoerentes_dataSolucaoAnterior_throws() {
            Map<String, Object> body = bodyValido();
            body.put("data_solucao", "2026-03-16"); // anterior à data 2026-03-17

            assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, "user-1"));
        }

        @Test
        @DisplayName("ck_datas_coerentes: hora_solucao < hora_inicio no mesmo dia → erro")
        void ckDatasCoerentes_horaSolucaoAnterior_throws() {
            Map<String, Object> body = bodyValido();
            body.put("data_solucao", "2026-03-17"); // mesmo dia
            body.put("hora_solucao", "13:00:00"); // anterior ao inicio 14:00:00

            assertThrows(ServiceValidationException.class,
                    () -> service.registrar(body, "user-1"));
        }

        @Test
        @DisplayName("dados válidos com INSERT → retorna id")
        void validInsert_success() {
            Map<String, Object> body = bodyValido();

            RegistroAnormalidade saved = new RegistroAnormalidade();
            saved.setId(99L);
            when(anormalidadeRepo.save(any())).thenReturn(saved);

            Map<String, Object> result = service.registrar(body, "user-1");

            assertEquals(99L, result.get("registro_anormalidade_id"));
            assertEquals(100L, result.get("registro_id"));
        }

        @Test
        @DisplayName("INSERT com entrada_id → chama syncHouveAnormalidade")
        void insertWithEntrada_callsSync() {
            Map<String, Object> body = bodyValido();
            body.put("entrada_id", "55");

            RegistroAnormalidade saved = new RegistroAnormalidade();
            saved.setId(99L);
            when(anormalidadeRepo.save(any())).thenReturn(saved);
            when(anormalidadeRepo.existsByEntradaId(55L)).thenReturn(true);

            service.registrar(body, "user-1");

            verify(anormalidadeRepo).updateHouveAnormalidade(55L, 1);
        }

        @Test
        @DisplayName("UPDATE: edição não reatribui entrada_id (mesmo que o body tente) — sync único "
                + "com o entradaId original (característica do sistema: RAOA não é reatribuível entre "
                + "entradas, herdada do legado Python — aplicarCampos() nunca seta entradaId na edição)")
        void updateNaoReatribuiEntradaId_syncUnicoComOriginal() {
            Map<String, Object> body = bodyValido();
            body.put("id", "10");
            body.put("entrada_id", "60"); // o form real NUNCA envia isto — só reenvia o próprio A (§ abaixo)

            RegistroAnormalidade existing = new RegistroAnormalidade();
            existing.setId(10L);
            existing.setEntradaId(50L); // entrada original (A) — imutável na edição, por design
            existing.setRegistroId(100L);

            when(anormalidadeRepo.findById(10L)).thenReturn(Optional.of(existing));
            when(anormalidadeRepo.save(any())).thenReturn(existing);
            when(anormalidadeRepo.existsByEntradaId(50L)).thenReturn(true);

            service.registrar(body, "user-1");

            // aplicarCampos() não seta entradaId no fluxo de edição — a entidade permanece com a
            // entrada original mesmo que o body tente enviar outra. Confirmado com o Douglas: RAOA
            // não é reatribuível entre entradas — o form real (anormalidade-form.component.ts) só
            // reenvia o mesmo entrada_id da rota (nunca oferece trocar), e o legado Python nunca
            // incluiu entrada_id no SET do UPDATE (db/anormalidade.py); não é um bug do port Java,
            // é uma invariante intencional do sistema — este teste a trava contra regressão futura.
            assertEquals(50L, existing.getEntradaId());
            verify(anormalidadeRepo).updateHouveAnormalidade(eq(50L), eq(1));
            verify(anormalidadeRepo, never()).updateHouveAnormalidade(eq(60L), anyInt());
            verify(anormalidadeRepo, times(1)).updateHouveAnormalidade(anyLong(), anyInt());
        }
    }

    // ══ validarAcessoEntrada ═════════════════════════════════════

    @Nested
    @DisplayName("validarAcessoEntrada")
    class ValidarAcessoEntrada {

        @Test
        @DisplayName("entrada inexistente → 404 not_found")
        void entradaInexistente_404() {
            when(entradaRepo.findOperadorIdByEntradaId(77L)).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.validarAcessoEntrada(77L, "user-1"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("not_found", ex.getMessage());
            verify(entradaRepo, never()).countOperadorAcessoEntrada(anyLong(), anyString());
        }

        @Test
        @DisplayName("entrada existe mas sem acesso (count=0) → 403 forbidden")
        void semAcesso_403() {
            when(entradaRepo.findOperadorIdByEntradaId(77L)).thenReturn(Optional.of("dono-uuid"));
            when(entradaRepo.countOperadorAcessoEntrada(77L, "user-1")).thenReturn(0);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.validarAcessoEntrada(77L, "user-1"));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            assertEquals("forbidden", ex.getMessage());
        }

        @Test
        @DisplayName("com acesso (count≥1) → não lança")
        void comAcesso_passa() {
            when(entradaRepo.findOperadorIdByEntradaId(77L)).thenReturn(Optional.of("dono-uuid"));
            when(entradaRepo.countOperadorAcessoEntrada(77L, "user-1")).thenReturn(1);

            assertDoesNotThrow(() -> service.validarAcessoEntrada(77L, "user-1"));
        }
    }

    // ══ buscarPorEntrada ═════════════════════════════════════════

    @Nested
    @DisplayName("buscarPorEntrada")
    class BuscarPorEntrada {

        /** Ordem real de OPR_ANORMALIDADE (SELECT *) — docker/oracle-init/01-schema.sql, 23 colunas. */
        private Object[] linhaCompleta() {
            return new Object[] {
                    1L, 100L, 55L,                          // ID, REGISTRO_ID, ENTRADA_ID
                    "2026-03-17", 3,                         // DATA, SALA_ID
                    "Sessão Plenária", "14:00:00",           // NOME_EVENTO, HORA_INICIO_ANORMALIDADE
                    "Falha no microfone",                    // DESCRICAO_ANORMALIDADE
                    1, "Prejuízo financeiro",                // HOUVE_PREJUIZO, DESCRICAO_PREJUIZO
                    0, null,                                 // HOUVE_RECLAMACAO, AUTORES_CONTEUDO_RECLAMACAO
                    0, null,                                 // ACIONOU_MANUTENCAO, HORA_ACIONAMENTO_MANUTENCAO
                    1, "Reiniciado o equipamento",           // RESOLVIDA_PELO_OPERADOR, PROCEDIMENTOS_ADOTADOS
                    "2026-03-17", "15:00:00",                // DATA_SOLUCAO, HORA_SOLUCAO
                    "João Silva",                            // RESPONSAVEL_EVENTO
                    "user-1", "2026-03-17 14:05:00",         // CRIADO_POR, CRIADO_EM
                    "2026-03-17 15:05:00", "user-1"          // ATUALIZADO_EM, ATUALIZADO_POR
            };
        }

        @Test
        @DisplayName("achado → mapeia id/registro/entrada/sala + os 14 campos comuns + derivado")
        void achado_mapeiaCampos() {
            when(anormalidadeRepo.findByEntradaIdNative(55L)).thenReturn(List.<Object[]>of(linhaCompleta()));

            Map<String, Object> result = service.buscarPorEntrada(55L);

            assertEquals(1L, result.get("id"));
            assertEquals(100L, result.get("registro_id"));
            assertEquals(55L, result.get("entrada_id"));
            assertEquals(3, result.get("sala_id"));
            assertEquals("Sessão Plenária", result.get("nome_evento"));
            assertTrue((Boolean) result.get("houve_prejuizo"));
            assertFalse((Boolean) result.get("houve_reclamacao"));
            assertEquals("João Silva", result.get("responsavel_evento"));
            assertTrue((Boolean) result.get("anormalidade_solucionada"));
        }

        @Test
        @DisplayName("não achado → null")
        void naoAchado_retornaNull() {
            when(anormalidadeRepo.findByEntradaIdNative(99L)).thenReturn(List.of());

            assertNull(service.buscarPorEntrada(99L));
        }
    }

    // ══ putCamposAnormalidade (estático) ═════════════════════════

    @Nested
    @DisplayName("putCamposAnormalidade")
    class PutCamposAnormalidade {

        @Test
        @DisplayName("shape do Map: os 14 campos comuns na ordem r[base..base+13]")
        void mapeiaOsCatorzeCampos() {
            Object[] r = {
                    "ignorado_0", "ignorado_1", "ignorado_2",     // fora do alcance (base=3)
                    "Sessão Plenária", "14:00:00", "Falha no microfone",
                    1, "Prejuízo financeiro",
                    0, null,
                    1, "13:50:00",
                    0, null,
                    "2026-03-17", "15:00:00", "João Silva"
            };
            Map<String, Object> result = new LinkedHashMap<>();

            AnormalidadeService.putCamposAnormalidade(result, r, 3);

            assertEquals(14, result.size());
            assertEquals("Sessão Plenária", result.get("nome_evento"));
            assertEquals("14:00:00", result.get("hora_inicio_anormalidade"));
            assertEquals("Falha no microfone", result.get("descricao_anormalidade"));
            assertTrue((Boolean) result.get("houve_prejuizo"));
            assertEquals("Prejuízo financeiro", result.get("descricao_prejuizo"));
            assertFalse((Boolean) result.get("houve_reclamacao"));
            assertNull(result.get("autores_conteudo_reclamacao"));
            assertTrue((Boolean) result.get("acionou_manutencao"));
            assertEquals("13:50:00", result.get("hora_acionamento_manutencao"));
            assertFalse((Boolean) result.get("resolvida_pelo_operador"));
            assertNull(result.get("procedimentos_adotados"));
            assertEquals("2026-03-17", result.get("data_solucao"));
            assertEquals("15:00:00", result.get("hora_solucao"));
            assertEquals("João Silva", result.get("responsavel_evento"));
        }
    }
}
