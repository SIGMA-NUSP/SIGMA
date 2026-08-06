package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A regra que este teste protege é a da SUBSTITUIÇÃO: uma pessoa costuma ter, no mesmo mês,
 * semanais cumulativas e as duas folhas mensais — e todas repetem os mesmos dias. Se o sumário
 * somasse todas, cada dia de férias apareceria três vezes.
 *
 * <p>Por isso a escolha é por (pessoa, MÊS-CALENDÁRIO), e não por folha: a semanal que cruza a
 * virada participa das duas disputas e pode vencer de um lado e perder do outro.
 */
@ExtendWith(MockitoExtension.class)
class SumarioOcorrenciasServiceTest {

    private static final String ANA = "ana-id";
    private static final String BRUNO = "bruno-id";
    private static final String OPERADOR = "OPERADOR";

    private static final String JULHO_DE = "2026-07";
    private static final String JULHO_ATE = "2026-07";

    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private PontoFolhaLinhaRepository folhaLinhaRepo;
    @Mock private PessoaCadastroLookup pessoaCadastro;

    @InjectMocks private SumarioOcorrenciasService service;

    @BeforeEach
    void nomesDoCadastro() {
        lenient().when(pessoaCadastro.nome(ANA, OPERADOR)).thenReturn("Ana Lima");
        lenient().when(pessoaCadastro.nome(BRUNO, OPERADOR)).thenReturn("Bruno Dias");
    }

    // ── Fixtures ──

    private static PontoLote lote(String id, String tipo, String categoria,
                                  LocalDate inicio, LocalDate fim, int ordemDeCriacao) {
        PontoLote l = new PontoLote();
        l.setId(id);
        l.setTipo(tipo);
        l.setCategoria(categoria);
        l.setDataInicio(inicio);
        l.setDataFim(fim);
        l.setStatus("PUBLICADO");
        l.setCriadoEm(LocalDateTime.of(2026, 8, 1, 9, 0).plusMinutes(ordemDeCriacao));
        return l;
    }

    private static PontoLote mensal(String id, String categoria, int ano, int mes, int ordemDeCriacao) {
        LocalDate ini = LocalDate.of(ano, mes, 1);
        return lote(id, "MENSAL", categoria, ini, ini.withDayOfMonth(ini.lengthOfMonth()), ordemDeCriacao);
    }

    private static PontoLote semanal(String id, LocalDate inicio, LocalDate fim, int ordemDeCriacao) {
        return lote(id, "SEMANAL", null, inicio, fim, ordemDeCriacao);
    }

    /** Par [página, lote] como a consulta das candidatas o devolve. */
    private static Object[] folhaDe(String paginaId, PontoLote lote, String pessoaId) {
        PontoLotePagina p = new PontoLotePagina();
        p.setId(paginaId);
        p.setLoteId(lote.getId());
        p.setNumeroPagina(1);
        p.setPessoaId(pessoaId);
        p.setPessoaTipo(OPERADOR);
        p.setArquivoPagina("ponto/paginas/" + paginaId + ".pdf");
        return new Object[]{p, lote};
    }

    /** Trio [paginaId, data, ocorrencia] como a consulta dos dias com status o devolve. */
    private static Object[] dia(String paginaId, String dataIso, String ocorrencia) {
        return new Object[]{paginaId, LocalDate.parse(dataIso), ocorrencia};
    }

    // ── Stubs ──

    private void candidatas(Object[]... folhas) {
        when(paginaRepo.findPublicadasNoPeriodo(any(), any())).thenReturn(List.of(folhas));
    }

    private void dias(Object[]... linhas) {
        when(folhaLinhaRepo.findOcorrenciasNoPeriodo(anyCollection(), any(), any())).thenReturn(List.of(linhas));
    }

    // ── Leitura da resposta ──

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lista(Map<String, Object> resposta, String chave) {
        return (List<Map<String, Object>>) resposta.get(chave);
    }

    private static List<String> codigos(Map<String, Object> resposta) {
        return lista(resposta, "ocorrencias").stream().map(m -> String.valueOf(m.get("codigo"))).toList();
    }

    private static List<String> nomes(Map<String, Object> resposta) {
        return lista(resposta, "funcionarios").stream().map(m -> String.valueOf(m.get("nome"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contagensDe(Map<String, Object> resposta, String nome) {
        return lista(resposta, "funcionarios").stream()
                .filter(m -> nome.equals(m.get("nome")))
                .map(m -> (Map<String, Object>) m.get("contagens"))
                .findFirst().orElseThrow(() -> new AssertionError("funcionário ausente do sumário: " + nome));
    }

    private static Object celula(Map<String, Object> resposta, String nome, String codigo) {
        return contagensDe(resposta, nome).get(codigo);
    }

    private static int totalDaColuna(Map<String, Object> resposta, String codigo) {
        return lista(resposta, "ocorrencias").stream()
                .filter(m -> codigo.equals(m.get("codigo")))
                .map(m -> (Integer) m.get("total"))
                .findFirst().orElseThrow(() -> new AssertionError("coluna ausente do sumário: " + codigo));
    }

    @Nested
    @DisplayName("Qual folha representa o mês")
    class Precedencia {

        @Test
        @DisplayName("a definitiva fecha o mês: os dias saem dela, e os da prévia não são contados de novo")
        void definitivaVenceAPrevia() {
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            PontoLote definitiva = mensal("l-definitiva", PontoLote.CATEGORIA_DEFINITIVA, 2026, 7, 2);
            candidatas(folhaDe("p-previa", previa, ANA), folhaDe("p-definitiva", definitiva, ANA));
            dias(dia("p-previa", "2026-07-10", "FERNC"),
                 dia("p-previa", "2026-07-11", "FERNC"),
                 dia("p-definitiva", "2026-07-10", "FERNC"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(1, celula(resposta, "Ana Lima", "FERNC"),
                    "só a definitiva conta: a prévia foi substituída, não somada");
        }

        @Test
        @DisplayName("sem definitiva, a prévia vence as semanais do mesmo mês")
        void previaVenceASemanal() {
            PontoLote semanal = semanal("l-sem", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14), 1);
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 2);
            candidatas(folhaDe("p-sem", semanal, ANA), folhaDe("p-previa", previa, ANA));
            dias(dia("p-sem", "2026-07-06", "Falta"),
                 dia("p-previa", "2026-07-06", "Falta"),
                 dia("p-previa", "2026-07-20", "Falta"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(2, celula(resposta, "Ana Lima", "Falta"), "os dias vêm da mensal, não da semanal");
        }

        @Test
        @DisplayName("só semanais: vale a de cobertura mais recente — as anteriores estão contidas nela")
        void ultimaSemanalVence() {
            PontoLote curta = semanal("l-curta", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), 1);
            PontoLote longa = semanal("l-longa", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 21), 2);
            candidatas(folhaDe("p-curta", curta, ANA), folhaDe("p-longa", longa, ANA));
            dias(dia("p-curta", "2026-07-03", "Atesc"),
                 dia("p-longa", "2026-07-03", "Atesc"),
                 dia("p-longa", "2026-07-15", "Atesc"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(2, celula(resposta, "Ana Lima", "Atesc"));
        }

        @Test
        @DisplayName("mês coberto pela metade: conta o que a semanal alcança, sem inventar o resto")
        void mesParcial() {
            PontoLote parcial = semanal("l-parcial", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14), 1);
            candidatas(folhaDe("p-parcial", parcial, ANA));
            dias(dia("p-parcial", "2026-07-02", "Atecc"), dia("p-parcial", "2026-07-03", "Atecc"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(2, celula(resposta, "Ana Lima", "Atecc"));
        }

        @Test
        @DisplayName("semanal na virada do mês: vence onde não há mensal e perde onde há")
        void semanalNaViradaDisputaCadaMesSeparadamente() {
            PontoLote virada = semanal("l-virada", LocalDate.of(2026, 6, 28), LocalDate.of(2026, 7, 4), 1);
            PontoLote julho = mensal("l-julho", PontoLote.CATEGORIA_PREVIA, 2026, 7, 2);
            candidatas(folhaDe("p-virada", virada, ANA), folhaDe("p-julho", julho, ANA));
            dias(dia("p-virada", "2026-06-29", "FERNC"),   // junho: só a semanal existe → conta
                 dia("p-virada", "2026-07-02", "FERNC"),   // julho: a mensal venceu → não conta
                 dia("p-julho", "2026-07-02", "FERNC"));

            Map<String, Object> resposta = service.sumario("2026-06", JULHO_ATE);

            assertEquals(2, celula(resposta, "Ana Lima", "FERNC"),
                    "um dia de junho pela semanal e um de julho pela mensal");
        }

        /**
         * O critério é a cobertura DAQUELE mês, não a data final da folha: a semanal da virada
         * termina depois de todas as outras, mas em junho ela tem dois dias — deixá-la representar
         * o mês apagaria as quatro semanas anteriores.
         */
        @Test
        @DisplayName("a semanal da virada vence o mês em que alcança mais, e perde o outro")
        void semanalDaViradaNaoRoubaOMesQueMalEncosta() {
            PontoLote cumulativa = semanal("l-cum", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 28), 1);
            PontoLote virada = semanal("l-virada", LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 5), 2);
            candidatas(folhaDe("p-cum", cumulativa, ANA), folhaDe("p-virada", virada, ANA));
            dias(dia("p-cum", "2026-06-03", "FERNC"),
                 dia("p-cum", "2026-06-04", "FERNC"),
                 dia("p-virada", "2026-06-29", "Falta"),   // junho é da cumulativa → não conta
                 dia("p-virada", "2026-07-02", "Falta"));  // julho é da virada → conta

            Map<String, Object> resposta = service.sumario("2026-06", JULHO_ATE);

            assertEquals(2, celula(resposta, "Ana Lima", "FERNC"), "junho continua com a folha que o cobre");
            assertEquals(1, celula(resposta, "Ana Lima", "Falta"), "da virada só valem os dias de julho");
        }

        /**
         * A "mensal" com período atípico (o admin digita as datas) só pesa como mensal no mês que
         * ela cobre inteiro — senão uma definitiva de cinco dias fecharia um mês que a prévia
         * daquele mês cobre por completo.
         */
        @Test
        @DisplayName("mensal que só encosta no mês não o fecha")
        void mensalQueNaoCobreOMesInteiroNaoVence() {
            PontoLote previaJunho = mensal("l-jun", PontoLote.CATEGORIA_PREVIA, 2026, 6, 1);
            PontoLote atipica = lote("l-atipica", "MENSAL", PontoLote.CATEGORIA_DEFINITIVA,
                    LocalDate.of(2026, 6, 26), LocalDate.of(2026, 7, 25), 2);
            candidatas(folhaDe("p-jun", previaJunho, ANA), folhaDe("p-atipica", atipica, ANA));
            dias(dia("p-jun", "2026-06-10", "FERNC"),
                 dia("p-jun", "2026-06-11", "FERNC"),
                 dia("p-atipica", "2026-06-29", "Falta"));

            Map<String, Object> resposta = service.sumario("2026-06", "2026-06");

            assertEquals(2, celula(resposta, "Ana Lima", "FERNC"));
            assertNull(celula(resposta, "Ana Lima", "Falta"), "junho é da prévia, que cobre o mês inteiro");
        }

        /** A folha da pessoa pode ocupar duas páginas do PDF; as duas são a mesma folha do mês. */
        @Test
        @DisplayName("duas páginas da mesma pessoa no mesmo lote: as duas contam")
        void duasPaginasDoMesmoLoteContamJuntas() {
            PontoLote sem = semanal("l-sem", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14), 1);
            candidatas(folhaDe("p-a", sem, ANA), folhaDe("p-b", sem, ANA));
            dias(dia("p-a", "2026-07-06", "Falta"), dia("p-b", "2026-07-07", "Falta"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(2, celula(resposta, "Ana Lima", "Falta"));
        }

        @Test
        @DisplayName("o mesmo dia repetido na folha conta uma vez: a célula é 'em quantos dias'")
        void diaRepetidoNaoInfla() {
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            candidatas(folhaDe("p-previa", previa, ANA));
            dias(dia("p-previa", "2026-07-10", "Falta"), dia("p-previa", "2026-07-10", "Falta"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(1, celula(resposta, "Ana Lima", "Falta"));
        }
    }

    @Nested
    @DisplayName("Colunas do sumário")
    class Colunas {

        @Test
        @DisplayName("feriado e ponto facultativo ficam de fora — são de calendário, todo mundo os tem")
        void excluiOcorrenciasDeCalendario() {
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            candidatas(folhaDe("p-previa", previa, ANA));
            dias(dia("p-previa", "2026-07-09", "Feriado"),
                 dia("p-previa", "2026-07-10", "P.facul"),
                 dia("p-previa", "2026-07-13", "FERNC"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(List.of("FERNC"), codigos(resposta));
            assertNull(celula(resposta, "Ana Lima", "Feriado"));
            assertNull(celula(resposta, "Ana Lima", "P.facul"));
        }

        @Test
        @DisplayName("da mais frequente para a menos — é a ordem que faz a tabela ser lida")
        void ordenadasPorTotalDescendente() {
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            candidatas(folhaDe("p-ana", previa, ANA), folhaDe("p-bruno", previa, BRUNO));
            dias(dia("p-ana", "2026-07-01", "FERNC"),
                 dia("p-ana", "2026-07-02", "FERNC"),
                 dia("p-bruno", "2026-07-01", "FERNC"),
                 dia("p-bruno", "2026-07-06", "Falta"),
                 dia("p-ana", "2026-07-07", "Atjus"),
                 dia("p-ana", "2026-07-08", "Atjus"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(List.of("FERNC", "Atjus", "Falta"), codigos(resposta));
            assertEquals(3, totalDaColuna(resposta, "FERNC"), "o total da coluna soma a equipe inteira");
        }

        /** O texto vem verbatim da folha; caixa diferente é o mesmo status, não outra coluna. */
        @Test
        @DisplayName("o mesmo código impresso com outra caixa é uma coluna só")
        void caixaDiferenteNaoParteAColuna() {
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            candidatas(folhaDe("p-previa", previa, ANA));
            dias(dia("p-previa", "2026-07-06", "FERNC"), dia("p-previa", "2026-07-07", "Fernc"));

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(List.of("FERNC"), codigos(resposta), "o rótulo é o primeiro texto visto");
            assertEquals(2, celula(resposta, "Ana Lima", "FERNC"));
        }

        @Test
        @DisplayName("folha sem nenhum status: nenhuma coluna, e ninguém desaparece da tabela")
        void semOcorrenciaNenhuma() {
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            candidatas(folhaDe("p-previa", previa, ANA));
            dias();

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertTrue(codigos(resposta).isEmpty());
            assertEquals(List.of("Ana Lima"), nomes(resposta));
            assertTrue(contagensDe(resposta, "Ana Lima").isEmpty());
        }
    }

    @Nested
    @DisplayName("Linhas do sumário")
    class Linhas {

        @Test
        @DisplayName("ordem alfabética pt-BR: o acento não joga o nome para o fim da lista")
        void ordemPtBr() {
            when(pessoaCadastro.nome(ANA, OPERADOR)).thenReturn("Katiane Souza");
            when(pessoaCadastro.nome(BRUNO, OPERADOR)).thenReturn("Kátia Nunes");
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            candidatas(folhaDe("p-ana", previa, ANA), folhaDe("p-bruno", previa, BRUNO));
            dias();

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(List.of("Kátia Nunes", "Katiane Souza"), nomes(resposta));
        }

        @Test
        @DisplayName("quem não tem folha publicada no período não vira linha")
        void semFolhaNaoAparece() {
            PontoLote previa = mensal("l-previa", PontoLote.CATEGORIA_PREVIA, 2026, 7, 1);
            candidatas(folhaDe("p-ana", previa, ANA));
            dias();

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertEquals(List.of("Ana Lima"), nomes(resposta));
        }
    }

    @Nested
    @DisplayName("Período pedido")
    class Periodo {

        @Test
        @DisplayName("a janela consultada vai do 1º dia do mês inicial ao último do mês final")
        void janelaFechadaNosDoisExtremos() {
            candidatas();

            service.sumario("2026-01", "2026-12");

            verify(paginaRepo).findPublicadasNoPeriodo(
                    eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31)));
        }

        @Test
        @DisplayName("nenhuma folha no período: nem chega a perguntar pelas linhas")
        void semFolhasNaoConsultaAsLinhas() {
            candidatas();

            Map<String, Object> resposta = service.sumario(JULHO_DE, JULHO_ATE);

            assertTrue(nomes(resposta).isEmpty());
            assertTrue(codigos(resposta).isEmpty());
            verify(folhaLinhaRepo, never()).findOcorrenciasNoPeriodo(anyCollection(), any(), any());
        }

        @Test
        @DisplayName("competência ilegível é recusada nomeando o campo")
        void competenciaInvalida() {
            assertEquals("Competência inválida em de (use AAAA-MM).",
                    assertThrows(ServiceValidationException.class, () -> service.sumario("2026/07", "2026-07")).getMessage());
            assertEquals("Competência inválida em ate (use AAAA-MM).",
                    assertThrows(ServiceValidationException.class, () -> service.sumario("2026-07", "julho")).getMessage());
            assertEquals("Competência inválida em de (use AAAA-MM).",
                    assertThrows(ServiceValidationException.class, () -> service.sumario(null, "2026-07")).getMessage());
        }

        @Test
        @DisplayName("intervalo invertido é recusado antes de qualquer consulta")
        void intervaloInvertido() {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.sumario("2026-07", "2026-06"));

            assertEquals("O mês final não pode ser anterior ao inicial.", ex.getMessage());
            verify(paginaRepo, never()).findPublicadasNoPeriodo(any(), any());
        }
    }
}
