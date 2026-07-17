package br.leg.senado.nusp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Comissao;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.TipoEvento;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.service.NativeQueryUtils;
import jakarta.persistence.EntityManager;

/**
 * ITs dos 7 statements nativos de {@link RegistroOperacaoOperadorRepository} contra
 * Oracle real — as entradas da sessão (OPR_REGISTRO_ENTRADA) e seus vínculos.
 *
 * A releitura das ENTRADAS usa em.find(): AuditableEntity mapeia CRIADO_EM/ATUALIZADO_EM
 * como LocalDateTime, imune ao ORA-18716 dos campos Instant. ATUALIZADO_EM é conferido
 * por SQL nativo apenas onde a comparação é entre carimbos do banco (ancoragem).
 */
@OracleIT
class RegistroOperacaoOperadorRepositoryIT {

    @Autowired
    private RegistroOperacaoOperadorRepository repo;

    @Autowired
    private TestEntityManager em;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    private Timestamp lerAtualizadoEm(Long entradaId) {
        return (Timestamp) emReal()
                .createNativeQuery("SELECT ATUALIZADO_EM FROM OPR_REGISTRO_ENTRADA WHERE ID = :id")
                .setParameter("id", entradaId)
                .getSingleResult();
    }

    @Nested
    @DisplayName("listarEntradasDaSessao")
    class ListarEntradasDaSessao {

        @Test
        @DisplayName("listarEntradasDaSessao — ordena por ORDEM, traz o nome do operador e o flag/id de anormalidade "
                + "vem do LEFT JOIN (com e sem RAOA, em entradas distintas)")
        void listarEntradasDaSessao_devolveOrdenadoComNomeEAnormalidade() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador donoDaPrimeira = CenarioFactory.novoOperador(emReal());
            Operador donoDaSegunda = CenarioFactory.novoOperador(emReal());
            Comissao comissao = CenarioFactory.novaComissao(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            // ordem 2 semeada ANTES da ordem 1: o ORDER BY é por ORDEM, não pela inserção/ID
            RegistroOperacaoOperador segunda = CenarioFactory.novaEntrada(emReal(), registro, donoDaSegunda, 2);
            RegistroOperacaoOperador primeira = CenarioFactory.novaEntradaCompleta(emReal(), registro,
                    donoDaPrimeira, 1, comissao);
            // UQ_OPR_ANOM_ENTRADA: 1 RAOA por entrada — o caso "com e sem" usa entradas distintas
            RegistroAnormalidade anormalidade = CenarioFactory.novaAnormalidade(emReal(), registro, sala,
                    primeira.getId(), donoDaPrimeira.getId());
            RegistroOperacaoAudio registroControle = CenarioFactory.novoRegistroAudio(emReal(), sala, false);
            CenarioFactory.novaEntrada(emReal(), registroControle, donoDaPrimeira, 1);

            List<Object[]> rows = repo.listarEntradasDaSessao(registro.getId());

            assertEquals(2, rows.size(), "a entrada do OUTRO registro não pode vazar");
            Object[] r0 = rows.get(0);
            assertEquals(primeira.getId().longValue(), ((Number) r0[0]).longValue(),
                    "a de ORDEM 1 vem primeiro mesmo tendo sido inserida depois (ID maior)");
            assertEquals(registro.getId().longValue(), ((Number) r0[1]).longValue());
            assertEquals(donoDaPrimeira.getId(), r0[2]);
            assertEquals(donoDaPrimeira.getNomeCompleto(), r0[3], "OPERADOR_NOME vem do JOIN com PES_OPERADOR");
            assertEquals(1, ((Number) r0[4]).intValue());
            assertEquals(1, ((Number) r0[5]).intValue(), "SEQ default");
            assertEquals(primeira.getNomeEvento(), r0[6]);
            assertEquals(primeira.getHorarioPauta(), r0[7]);
            assertEquals(primeira.getHorarioInicio(), r0[8]);
            assertEquals(primeira.getHorarioTermino(), r0[9]);
            assertEquals(TipoEvento.OPERACAO.getValor(), r0[10]);
            assertEquals(primeira.getUsb01(), r0[11]);
            assertEquals(primeira.getUsb02(), r0[12]);
            assertEquals(primeira.getObservacoes(), NativeQueryUtils.str(r0[13]));
            // a coluna física HOUVE_ANORMALIDADE está NULL: o 1 abaixo prova que o flag do
            // SELECT é o CASE calculado sobre o LEFT JOIN, não a coluna da entrada
            assertNull(primeira.getHouveAnormalidade());
            assertEquals(1, ((Number) r0[14]).intValue(), "flag calculado do LEFT JOIN com OPR_ANORMALIDADE");
            assertEquals(anormalidade.getId().longValue(), ((Number) r0[15]).longValue());
            assertEquals(comissao.getId().longValue(), ((Number) r0[16]).longValue());
            assertEquals(primeira.getResponsavelEvento(), r0[17]);
            assertEquals(primeira.getHoraEntrada(), r0[18]);
            assertEquals(primeira.getHoraSaida(), r0[19]);

            Object[] r1 = rows.get(1);
            assertEquals(segunda.getId().longValue(), ((Number) r1[0]).longValue());
            assertEquals(0, ((Number) r1[14]).intValue(), "entrada sem RAOA deveria vir com flag 0");
            assertNull(r1[15], "entrada sem RAOA deveria vir com ANORMALIDADE_ID nulo");
        }

        @Test
        @DisplayName("listarEntradasDaSessao — registro sem entradas retorna vazio")
        void listarEntradasDaSessao_registroSemEntradasRetornaVazio() {
            Sala sala = CenarioFactory.novaSala(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);

            assertTrue(repo.listarEntradasDaSessao(registro.getId()).isEmpty());
        }
    }

    @Nested
    @DisplayName("getSnapshot")
    class GetSnapshot {

        @Test
        @DisplayName("getSnapshot — devolve as 14 colunas da entrada (HOUVE_ANORMALIDADE físico) mais o SALA_ID do registro")
        void getSnapshot_devolveTodasAsColunasDaEntradaESalaDoRegistro() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            Comissao comissao = CenarioFactory.novaComissao(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaCompleta(emReal(), registro,
                    operador, 1, comissao);
            // diferente do listar, aqui HOUVE_ANORMALIDADE é a coluna FÍSICA da entrada
            entrada.setHouveAnormalidade(true);
            emReal().flush();

            List<Object[]> rows = repo.getSnapshot(entrada.getId());

            assertEquals(1, rows.size());
            Object[] s = rows.get(0);
            assertEquals(entrada.getNomeEvento(), s[0]);
            assertEquals(entrada.getResponsavelEvento(), s[1]);
            assertEquals(entrada.getHorarioPauta(), s[2]);
            assertEquals(entrada.getHorarioInicio(), s[3]);
            assertEquals(entrada.getHorarioTermino(), s[4]);
            assertEquals(TipoEvento.OPERACAO.getValor(), s[5]);
            assertEquals(entrada.getUsb01(), s[6]);
            assertEquals(entrada.getUsb02(), s[7]);
            assertEquals(entrada.getObservacoes(), NativeQueryUtils.str(s[8]));
            assertEquals(comissao.getId().longValue(), ((Number) s[9]).longValue());
            assertEquals(1, ((Number) s[10]).intValue(), "HOUVE_ANORMALIDADE físico da entrada");
            assertEquals(sala.getId().intValue(), ((Number) s[11]).intValue(), "SALA_ID vem do registro (JOIN)");
            assertEquals(entrada.getHoraEntrada(), s[12]);
            assertEquals(entrada.getHoraSaida(), s[13]);
        }

        @Test
        @DisplayName("getSnapshot — entrada inexistente retorna vazio")
        void getSnapshot_entradaInexistenteRetornaVazio() {
            assertTrue(repo.getSnapshot(-1L).isEmpty());
        }
    }

    @Nested
    @DisplayName("countEntradasPorSessao")
    class CountEntradasPorSessao {

        @Test
        @DisplayName("countEntradasPorSessao — conta TODAS as entradas da sessão da entrada consultada, e só as dela")
        void countEntradasPorSessao_contaTodasAsEntradasDaMesmaSessao() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador opUm = CenarioFactory.novoOperador(emReal());
            Operador opDois = CenarioFactory.novoOperador(emReal());
            Operador opTres = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio cheio = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador primeira = CenarioFactory.novaEntrada(emReal(), cheio, opUm, 1);
            CenarioFactory.novaEntrada(emReal(), cheio, opDois, 2);
            RegistroOperacaoOperador terceira = CenarioFactory.novaEntrada(emReal(), cheio, opTres, 3);
            RegistroOperacaoAudio unitario = CenarioFactory.novoRegistroAudio(emReal(), sala, false);
            RegistroOperacaoOperador sozinha = CenarioFactory.novaEntrada(emReal(), unitario, opUm, 1);

            assertEquals(3, repo.countEntradasPorSessao(primeira.getId()));
            assertEquals(3, repo.countEntradasPorSessao(terceira.getId()),
                    "qualquer entrada da sessão deveria enxergar a mesma contagem");
            assertEquals(1, repo.countEntradasPorSessao(sozinha.getId()),
                    "as entradas da OUTRA sessão não podem entrar na conta");
        }

        @Test
        @DisplayName("countEntradasPorSessao — entrada inexistente retorna 0 (subquery vazia)")
        void countEntradasPorSessao_entradaInexistenteRetorna0() {
            assertEquals(0, repo.countEntradasPorSessao(-1L));
        }
    }

    @Nested
    @DisplayName("countOperadorAcessoEntrada")
    class CountOperadorAcessoEntrada {

        @Test
        @DisplayName("countOperadorAcessoEntrada — dono direto retorna 1; dono TAMBÉM vinculado na junction continua 1 "
                + "(conta entradas, não vínculos)")
        void countOperadorAcessoEntrada_donoRetorna1() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, dono);

            assertEquals(1, repo.countOperadorAcessoEntrada(entrada.getId(), dono.getId()));

            CenarioFactory.novoVinculoOperador(emReal(), entrada, dono);
            assertEquals(1, repo.countOperadorAcessoEntrada(entrada.getId(), dono.getId()),
                    "dono + junction simultâneos não podem duplicar a contagem (COUNT sobre a entrada)");
        }

        @Test
        @DisplayName("countOperadorAcessoEntrada — co-operador via junction OPR_ENTRADA_OPERADOR retorna 1")
        void countOperadorAcessoEntrada_coOperadorViaJunctionRetorna1() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador coOperador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, dono);
            CenarioFactory.novoVinculoOperador(emReal(), entrada, coOperador);

            assertEquals(1, repo.countOperadorAcessoEntrada(entrada.getId(), coOperador.getId()));
        }

        @Test
        @DisplayName("countOperadorAcessoEntrada — terceiro retorna 0; vínculo em OUTRA entrada não dá acesso a esta")
        void countOperadorAcessoEntrada_terceiroRetorna0() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador terceiro = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador consultada = CenarioFactory.novaEntrada(emReal(), registro, dono, 1);
            RegistroOperacaoOperador outra = CenarioFactory.novaEntrada(emReal(), registro, terceiro, 2);
            CenarioFactory.novoVinculoOperador(emReal(), outra, terceiro);

            assertEquals(0, repo.countOperadorAcessoEntrada(consultada.getId(), terceiro.getId()),
                    "nem dono nem vinculado NESTA entrada — o EXISTS é por ENTRADA_ID");
        }
    }

    @Nested
    @DisplayName("marcarSalaEditado")
    class MarcarSalaEditado {

        @Test
        @DisplayName("marcarSalaEditado — sala nova difere da sala ATUAL DO REGISTRO: liga o flag só da entrada alvo")
        void marcarSalaEditado_salaDiferenteLigaFlag() {
            Sala salaAtual = CenarioFactory.novaSala(emReal());
            Sala salaNova = CenarioFactory.novaSala(emReal());
            Operador opUm = CenarioFactory.novoOperador(emReal());
            Operador opDois = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), salaAtual);
            RegistroOperacaoOperador alvo = CenarioFactory.novaEntrada(emReal(), registro, opUm, 1);
            RegistroOperacaoOperador controle = CenarioFactory.novaEntrada(emReal(), registro, opDois, 2);

            repo.marcarSalaEditado(alvo.getId(), salaNova.getId());

            em.clear();
            assertEquals(Boolean.TRUE, em.find(RegistroOperacaoOperador.class, alvo.getId()).getSalaEditado());
            assertEquals(Boolean.FALSE, em.find(RegistroOperacaoOperador.class, controle.getId()).getSalaEditado(),
                    "a entrada de controle não pode ser marcada (predicado ID = :entradaId)");
        }

        @Test
        @DisplayName("marcarSalaEditado — sala igual à atual do registro não liga o flag (ramo ELSE do CASE)")
        void marcarSalaEditado_mesmaSalaNaoLigaFlag() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, operador);

            repo.marcarSalaEditado(entrada.getId(), sala.getId());

            em.clear();
            assertEquals(Boolean.FALSE, em.find(RegistroOperacaoOperador.class, entrada.getId()).getSalaEditado(),
                    "sala igual à atual não é edição — o CASE só liga quando difere");
        }

        @Test
        @DisplayName("marcarSalaEditado — flag já ligado permanece ligado com sala igual (o ELSE preserva o valor: sticky)")
        void marcarSalaEditado_flagJaLigadoPermaneceComMesmaSala() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, operador);
            entrada.setSalaEditado(true);
            emReal().flush();

            repo.marcarSalaEditado(entrada.getId(), sala.getId());

            em.clear();
            assertEquals(Boolean.TRUE, em.find(RegistroOperacaoOperador.class, entrada.getId()).getSalaEditado(),
                    "o ELSE do CASE preserva SALA_EDITADO — o flag nunca volta a 0 por esta via");
        }
    }

    @Nested
    @DisplayName("updateEntradaBasica")
    class UpdateEntradaBasica {

        @Test
        @DisplayName("updateEntradaBasica — grava os 13 campos + ATUALIZADO_POR, avança ATUALIZADO_EM e não toca "
                + "flags de edição, vínculos nem a entrada de controle")
        void updateEntradaBasica_atualizaOsCamposEAvancaAtualizadoEm() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador outroDono = CenarioFactory.novoOperador(emReal());
            Comissao comissaoOriginal = CenarioFactory.novaComissao(emReal());
            Comissao comissaoNova = CenarioFactory.novaComissao(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador alvo = CenarioFactory.novaEntradaCompleta(emReal(), registro, dono, 1,
                    comissaoOriginal);
            RegistroOperacaoOperador controle = CenarioFactory.novaEntradaCompleta(emReal(), registro, outroDono, 2,
                    comissaoOriginal);
            CenarioFactory.fixarTimestamp(emReal(), "OPR_REGISTRO_ENTRADA", "ATUALIZADO_EM", alvo.getId(), 60);
            Timestamp atualizadoEmAntes = lerAtualizadoEm(alvo.getId());

            repo.updateEntradaBasica(alvo.getId(), "Sessao Editada IT", "10:00:00", "10:15:00", "13:00:00",
                    TipoEvento.CESSAO.getValor(), "Observacoes editadas IT", "USB-C", "USB-D",
                    comissaoNova.getId(), "Novo Responsavel IT", "09:45:00", "13:15:00", outroDono.getId());

            em.clear();
            RegistroOperacaoOperador relida = em.find(RegistroOperacaoOperador.class, alvo.getId());
            assertEquals("Sessao Editada IT", relida.getNomeEvento());
            assertEquals("10:00:00", relida.getHorarioPauta());
            assertEquals("10:15:00", relida.getHorarioInicio());
            assertEquals("13:00:00", relida.getHorarioTermino());
            assertEquals(TipoEvento.CESSAO, relida.getTipoEvento());
            assertEquals("Observacoes editadas IT", relida.getObservacoes());
            assertEquals("USB-C", relida.getUsb01());
            assertEquals("USB-D", relida.getUsb02());
            assertEquals(comissaoNova.getId(), relida.getComissaoId());
            assertEquals("Novo Responsavel IT", relida.getResponsavelEvento());
            assertEquals("09:45:00", relida.getHoraEntrada());
            assertEquals("13:15:00", relida.getHoraSaida());
            assertEquals(outroDono.getId(), relida.getAtualizadoPor());
            assertTrue(lerAtualizadoEm(alvo.getId()).after(atualizadoEmAntes),
                    "ATUALIZADO_EM deveria avançar da âncora (SYSTIMESTAMP - 60s) para SYSTIMESTAMP");
            // fora da lista do UPDATE: nada disto pode mudar
            assertEquals(registro.getId(), relida.getRegistroId());
            assertEquals(dono.getId(), relida.getOperadorId());
            assertEquals(1, relida.getOrdem().intValue());
            assertEquals(1, relida.getSeq().intValue());
            assertEquals(dono.getId(), relida.getCriadoPor());
            assertFalse(relida.getEditado(), "updateEntradaBasica não mexe em flag de edição");
            assertFalse(relida.getNomeEventoEditado());
            assertFalse(relida.getSalaEditado());

            RegistroOperacaoOperador controleRelida = em.find(RegistroOperacaoOperador.class, controle.getId());
            assertEquals(controle.getNomeEvento(), controleRelida.getNomeEvento(),
                    "a entrada de controle não pode ser alterada (predicado ID = :entradaId)");
            assertEquals(comissaoOriginal.getId(), controleRelida.getComissaoId());
        }

        @Test
        @DisplayName("updateEntradaBasica — parâmetros nulos SOBRESCREVEM as colunas com NULL (limpeza de campo do form)")
        void updateEntradaBasica_valoresNulosSobrescrevem() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Comissao comissao = CenarioFactory.novaComissao(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaCompleta(emReal(), registro, dono, 1,
                    comissao);

            repo.updateEntradaBasica(entrada.getId(), null, null, null, null,
                    TipoEvento.OPERACAO.getValor(), null, null, null, null, null, null, null, dono.getId());

            em.clear();
            RegistroOperacaoOperador relida = em.find(RegistroOperacaoOperador.class, entrada.getId());
            assertNull(relida.getNomeEvento(), "null não é 'manter o valor': a coluna é sobrescrita");
            assertNull(relida.getHorarioPauta());
            assertNull(relida.getHorarioInicio());
            assertNull(relida.getHorarioTermino());
            assertNull(relida.getObservacoes());
            assertNull(relida.getUsb01());
            assertNull(relida.getUsb02());
            assertNull(relida.getComissaoId());
            assertNull(relida.getResponsavelEvento());
            assertNull(relida.getHoraEntrada());
            assertNull(relida.getHoraSaida());
        }
    }

    @Nested
    @DisplayName("findDadosParaAnormalidade")
    class FindDadosParaAnormalidade {

        @Test
        @DisplayName("findDadosParaAnormalidade — devolve os dados do registro e da entrada para o pré-preenchimento")
        void findDadosParaAnormalidade_preenchimentoCompleto() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            registro.setNomeDemaisSalas("Sala Extra IT");
            emReal().flush();
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaCompleta(emReal(), registro,
                    operador, 1, null);

            List<Object[]> rows = repo.findDadosParaAnormalidade(registro.getId(), entrada.getId());

            assertEquals(1, rows.size());
            Object[] r = rows.get(0);
            assertEquals(registro.getId().longValue(), ((Number) r[0]).longValue());
            assertEquals("2026-07-01", r[1].toString().substring(0, 10));
            assertEquals(sala.getId().intValue(), ((Number) r[2]).intValue());
            assertEquals(entrada.getNomeEvento(), r[3]);
            assertEquals(entrada.getResponsavelEvento(), r[4]);
            assertEquals("Sala Extra IT", r[5]);
        }

        @Test
        @DisplayName("findDadosParaAnormalidade — entradaId nulo devolve só o registro (colunas da entrada nulas)")
        void findDadosParaAnormalidade_entradaNulaDevolveSoORegistro() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            CenarioFactory.novaEntradaCompleta(emReal(), registro, operador, 1, null);

            List<Object[]> rows = repo.findDadosParaAnormalidade(registro.getId(), null);

            assertEquals(1, rows.size(), "o LEFT JOIN preserva o registro mesmo sem entrada");
            Object[] r = rows.get(0);
            assertEquals(registro.getId().longValue(), ((Number) r[0]).longValue());
            assertNull(r[3], "sem entradaId não há NOME_EVENTO — a entrada existente do registro NÃO é usada");
            assertNull(r[4]);
        }

        @Test
        @DisplayName("findDadosParaAnormalidade — registro inexistente retorna vazio")
        void findDadosParaAnormalidade_registroInexistenteRetornaVazio() {
            assertTrue(repo.findDadosParaAnormalidade(-1L, null).isEmpty());
        }

        @Test
        @DisplayName("a junção da entrada é correlacionada ao registro: entrada de OUTRO registro "
                + "não vaza para o lookup (colunas de entrada nulas, como no entrada_id inexistente)")
        void findDadosParaAnormalidade_entradaDeOutroRegistroNaoPreenche() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador operador = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio consultado = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoAudio outroRegistro = CenarioFactory.novoRegistroAudio(emReal(), sala, false);
            RegistroOperacaoOperador entradaAlheia = CenarioFactory.novaEntradaCompleta(emReal(), outroRegistro,
                    operador, 1, null);

            // O ON era apenas e.ID = :entradaId, sem e.REGISTRO_ID = r.ID — o port perdeu a
            // correlação que a query legada fazia (get_registro_operacao_audio_for_anormalidade)
            // e a entrada de outro registro pré-preenchia evento/responsável. O LEFT JOIN foi
            // MANTIDO: o par incoerente devolve linha com nulos, não vazio como o legado.
            List<Object[]> rows = repo.findDadosParaAnormalidade(consultado.getId(), entradaAlheia.getId());

            assertEquals(1, rows.size(), "o LEFT JOIN preserva o registro consultado");
            Object[] r = rows.get(0);
            assertEquals(consultado.getId().longValue(), ((Number) r[0]).longValue(),
                    "o registro é o consultado...");
            assertNull(r[3], "...e o NOME_EVENTO da entrada alheia NÃO vaza");
            assertNull(r[4], "idem RESPONSAVEL_EVENTO");
            // A contraprova (entrada do PRÓPRIO registro segue pré-preenchendo) é o teste
            // findDadosParaAnormalidade_preenchimentoCompleto, acima — a correlação não o quebra.
        }
    }
}
