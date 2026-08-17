package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoFolhaLinha;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.service.GradeRetificacaoService.Celula;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * O que o funcionário corrige na própria folha, visto do outro lado — a grade mensal que o
 * administrador abre. O percurso é o real: o funcionário grava pelo serviço de retificação e a
 * grade é montada depois, do banco, sem que nada seja passado de um para o outro em memória.
 *
 * <p>São três formas de retificação e três células diferentes: a ocorrência que vale como folga do
 * banco aparece pelo nome do tipo e entra na mesma contagem da folga aprovada, a ocorrência comum
 * aparece pelo nome sem contar folga, e os horários saem MESCLADOS — o que ele digitou por cima do
 * que a folha publicada imprimiu naquele dia, em texto liso. Nenhuma delas leva tipo ao payload: o
 * administrador não marca ocorrência por cima de uma retificação.
 *
 * <p>Harness de {@code @SpringBootTest} (não o slice {@code @OracleIT}) para que o
 * {@code @Transactional} do serviço commite de verdade e a grade leia o que ficou gravado; sem
 * rollback automático, a limpeza é manual.
 *
 * <p>O cenário: uma pessoa com UMA folha semanal publicada (01–19/jun), que imprimiu o dia 16 com
 * entrada e primeira saída. Sendo a única folha do período, todos os dias dela estão abertos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class GradeRetificacaoCelulaIT {

    private static final int ANO = 2026;
    private static final int MES = 6;

    /** Folha SEMANAL publicada 01–19/jun — a única da pessoa no mês. */
    private static final LocalDate FOLHA_INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate FOLHA_FIM = LocalDate.of(2026, 6, 19);

    /** Segunda-feira: o dia declarado como banco de horas. */
    private static final LocalDate DIA_15 = LocalDate.of(2026, 6, 15);
    /** Terça-feira: o dia que a folha imprimiu com batidas e o funcionário corrigiu. */
    private static final LocalDate DIA_16 = LocalDate.of(2026, 6, 16);
    /** Quarta-feira: o dia da ocorrência comum. */
    private static final LocalDate DIA_17 = LocalDate.of(2026, 6, 17);

    /** A coluna DIA da folha, como o cartão a imprime. */
    private static final DateTimeFormatter DIA_IMPRESSO = DateTimeFormatter.ofPattern("dd/MM/yy");

    @Autowired private RetificacaoService retificacaoService;
    @Autowired private GradeRetificacaoService gradeService;

    /** O contexto completo liga @EnableScheduling; estes dois fazem I/O de rede a cada poucos segundos. */
    @MockitoBean private AgendaLegislativaService agendaLegislativaService;
    @MockitoBean private CessaoSheetService cessaoSheetService;

    @Autowired private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;

    /** Tudo que o teste semeou — a limpeza manual (sem rollback) apaga por estes ids. */
    private final List<String> adminIds = new ArrayList<>();
    private final List<String> pessoaIds = new ArrayList<>();
    private final List<String> loteIds = new ArrayList<>();
    private final List<String> tipoIds = new ArrayList<>();

    private Administrador admin;
    private Operador funcionario;
    private PontoLotePagina folha;
    private PontoTipoMarcacao tipoBanco;
    private PontoTipoMarcacao tipoAtestado;

    @BeforeEach
    void semear() {
        tx = new TransactionTemplate(txManager);
        tx.setTimeout(60);
        tx.executeWithoutResult(status -> {
            admin = CenarioFactory.novoAdministrador(em);
            adminIds.add(admin.getId());
            funcionario = CenarioFactory.novoOperador(em);
            pessoaIds.add(funcionario.getId());

            PontoLote lote = CenarioFactory.novoLotePontoPublicado(em, "SEMANAL",
                    FOLHA_INICIO, FOLHA_FIM, admin);
            loteIds.add(lote.getId());
            folha = CenarioFactory.novaPaginaLote(em, lote, 1, funcionario.getId(), "OPERADOR");

            // A folha imprimiu a entrada e a primeira saída do dia 16 — é sobre elas que a correção entra.
            imprimirNaFolha(1, DIA_16, "08:00", "12:00");

            // O catálogo do schema de teste nasce vazio: os tipos que o funcionário declara são semeados aqui.
            tipoBanco = tipoDoFuncionario("Banco", "Bnc", true);
            tipoAtestado = tipoDoFuncionario("Atestado", "Ate", false);
        });
    }

    @AfterEach
    void limpar() {
        tx.executeWithoutResult(status -> {
            executar("DELETE FROM PNT_FOLHA_LINHA WHERE PAGINA_ID IN "
                    + "(SELECT ID FROM PNT_LOTE_PAGINA WHERE LOTE_ID IN :lotes)");
            executar("DELETE FROM PNT_RETIFICACAO WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_SOLICITACAO_FOLGA WHERE PESSOA_ID IN :pessoas");
            executar("DELETE FROM PNT_LOTE WHERE ID IN :lotes");          // ON DELETE CASCADE leva as páginas
            executar("DELETE FROM PNT_TIPO_MARCACAO WHERE ID IN :tipos");
            executar("DELETE FROM PES_OPERADOR WHERE ID IN :pessoas");
            executar("DELETE FROM PES_ADMINISTRADOR WHERE ID IN :admins");
        });
    }

    private void executar(String sql) {
        var query = em.createNativeQuery(sql);
        if (sql.contains(":admins")) query.setParameter("admins", adminIds);
        if (sql.contains(":pessoas")) query.setParameter("pessoas", pessoaIds);
        if (sql.contains(":lotes")) query.setParameter("lotes", loteIds);
        if (sql.contains(":tipos")) query.setParameter("tipos", tipoIds);
        query.executeUpdate();
    }

    // ══ Seed ══════════════════════════════════════════════════════

    /** Grava a linha-dia da folha como o PDF a imprimiu. */
    private void imprimirNaFolha(int ordem, LocalDate data, String ent1, String sai1) {
        PontoFolhaLinha linha = new PontoFolhaLinha();
        linha.setPaginaId(folha.getId());
        linha.setOrdem(ordem);
        linha.setDiaVerbatim(DIA_IMPRESSO.format(data));
        linha.setData(data);
        linha.setEnt1(ent1);
        linha.setSai1(sai1);
        em.persist(linha);
        em.flush();
    }

    /** Tipo que o funcionário escolhe ao retificar o dia; {@code contaFolga} o põe na contagem de folgas. */
    private PontoTipoMarcacao tipoDoFuncionario(String nome, String badge, boolean contaFolga) {
        PontoTipoMarcacao tipo = CenarioFactory.novoTipoMarcacao(em, nome, badge,
                PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
        tipo.setVisivelFuncionario(true);
        tipo.setContaFolga(contaFolga);
        em.merge(tipo);
        em.flush();
        tipoIds.add(tipo.getId());
        return tipo;
    }

    /** Folga do banco de horas já deliberada pelo administrador. */
    private void aprovarFolga(LocalDate data) {
        tx.executeWithoutResult(status -> CenarioFactory.novaSolicitacaoFolga(em, funcionario.getId(),
                "OPERADOR", data, StatusSolicitacaoFolga.APROVADO, admin, null));
    }

    // ══ O que o funcionário faz na folha dele ═════════════════════

    private void corrigir(LocalDate data, String campo, String valor) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("data", data.toString());
        corpo.put("campo", campo);
        corpo.put("valor", valor);
        retificacaoService.salvarCelula(folha.getId(), funcionario.getId(), corpo);
    }

    private void declarar(LocalDate data, PontoTipoMarcacao tipo) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("data", data.toString());
        corpo.put("tipo_id", tipo.getId());
        retificacaoService.salvarTipo(folha.getId(), funcionario.getId(), corpo);
    }

    // ══ O que o administrador vê na grade do mês ══════════════════

    private GradeRetificacaoService.Grade grade() {
        return gradeService.montarGrade("operadores", ANO, MES);
    }

    /** A célula daquele dia na linha do funcionário; nula quando a grade não tem o que mostrar. */
    private Celula celula(LocalDate dia) {
        return grade().celulas().getOrDefault(funcionario.getId(), Map.of()).get(dia.getDayOfMonth());
    }

    /** A contagem "Folgas" do funcionário no mês. */
    private int folgasNaGrade() {
        return grade().funcionarios().stream()
                .filter(f -> funcionario.getId().equals(f.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("o funcionário não veio na grade"))
                .folgas();
    }

    /** A mesma célula, como o payload JSON a entrega. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> celulaDoPayload(LocalDate dia) {
        Map<String, Object> celulas =
                (Map<String, Object>) gradeService.montar("operadores", ANO, MES).get("celulas");
        Map<String, Object> linha = (Map<String, Object>) celulas.get(funcionario.getId());
        return (Map<String, Object>) linha.get(String.valueOf(dia.getDayOfMonth()));
    }

    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("o dia declarado como banco de horas vira a célula \"banco\" e conta uma folga")
    void diaDeclaradoComoBancoContaFolga() {
        assertEquals(0, folgasNaGrade(), "antes da declaração a pessoa não tem folga no mês");

        declarar(DIA_15, tipoBanco);

        Celula cel = celula(DIA_15);
        assertEquals("banco", cel.tipo());
        assertEquals(tipoBanco.getNome(), cel.texto(),
                "o dia declarado mostra o nome do tipo, não o texto fixo da folga aprovada");
        assertEquals(1, folgasNaGrade());
        assertFalse(celulaDoPayload(DIA_15).containsKey("tipo_id"),
                "retificação não leva tipo ao payload: o administrador não marca ocorrência por cima dela");
    }

    @Test
    @DisplayName("os horários corrigidos saem mesclados com os que a folha imprimiu, sem distinguir a origem")
    void horariosCorrigidosSaemMescladosComOsDaFolha() {
        assertNull(celula(DIA_16), "o dia que só a folha imprimiu não é célula da grade");

        corrigir(DIA_16, "sai1", "12:30");
        corrigir(DIA_16, "ent2", "13:00");

        Celula cel = celula(DIA_16);
        assertEquals("horarios", cel.tipo());
        assertEquals("08:00 12:30 13:00", cel.texto(),
                "a entrada vem da folha, a saída corrigida vence a impressa e a segunda entrada é nova");
        assertEquals(0, folgasNaGrade(), "corrigir horário não é folga");
    }

    @Test
    @DisplayName("a ocorrência comum declarada aparece pelo nome do tipo e não conta folga")
    void ocorrenciaComumApareceComONomeDoTipo() {
        declarar(DIA_17, tipoAtestado);

        Celula cel = celula(DIA_17);
        assertEquals("ocorrencia", cel.tipo());
        assertEquals(tipoAtestado.getNome(), cel.texto());
        assertEquals(0, folgasNaGrade(), "só o tipo que vale como folga do banco entra na contagem");
    }

    @Test
    @DisplayName("folga aprovada e declaração no mesmo dia contam um dia só")
    void folgaAprovadaEDeclaracaoNoMesmoDiaContamUmDiaSo() {
        aprovarFolga(DIA_15);
        aprovarFolga(DIA_16);

        declarar(DIA_15, tipoBanco);

        assertEquals(2, folgasNaGrade(),
                "os dias 15 e 16 — o 15 tem a folga e a declaração, e continua sendo um dia");
        assertEquals("banco", celula(DIA_16).tipo(), "o dia só de folga aprovada segue como banco de horas");
    }
}
