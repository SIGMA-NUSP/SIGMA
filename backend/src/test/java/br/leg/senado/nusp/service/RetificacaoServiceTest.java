package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contrato do {@link RetificacaoService} — a célula como unidade: uma gravação mexe em UM campo de
 * UM dia, sem lote, sem par obrigatório e sem prazo em dias.
 *
 * <p>O que o unitário prova: o que cada gravação escreve e preserva, a recusa nomeando o dia e a
 * leitura que a tela recebe. A janela em si tem prova própria ({@link JanelaRetificacaoTest}); aqui
 * ela aparece pelo efeito — o dia que ela fecha não grava.
 */
@ExtendWith(MockitoExtension.class)
class RetificacaoServiceTest {

    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private PontoLoteRepository loteRepo;
    @Mock private PontoRetificacaoRepository retificacaoRepo;
    @Mock private PontoDiaMarcacaoRepository diaMarcacaoRepo;
    @Mock private PontoTipoMarcacaoRepository tipoRepo;
    @Mock private PontoFolhaLinhaRepository folhaLinhaRepo;

    @InjectMocks
    private RetificacaoService service;

    private static final String PAG = "pag-1";
    private static final String LOTE = "lote-1";
    private static final String DONO = "op-1";
    private static final String TIPO_BANCO = "tipo-banco";
    /** Período da folha aberta na tela: junho/2026, prévia mensal publicada. */
    private static final LocalDate INICIO = LocalDate.of(2026, 6, 1);
    private static final LocalDate FIM = LocalDate.of(2026, 6, 30);
    private static final LocalDateTime PUBLICADA_EM = LocalDateTime.of(2026, 6, 30, 10, 0);
    /** Segunda-feira dentro do período — o dia de trabalho de quase todos os casos. */
    private static final LocalDate DIA = LocalDate.of(2026, 6, 15);
    private static final LocalDate SABADO = LocalDate.of(2026, 6, 13);

    // ══ Fixtures ══════════════════════════════════════════════════

    private static PontoLote lote(String id, String tipo, String categoria,
                                  LocalDate inicio, LocalDate fim, LocalDateTime publicadoEm) {
        PontoLote l = new PontoLote();
        l.setId(id);
        l.setTipo(tipo);
        l.setCategoria(categoria);
        l.setDataInicio(inicio);
        l.setDataFim(fim);
        l.setStatus("PUBLICADO");
        l.setPublicadoEm(publicadoEm);
        l.setCriadoEm(publicadoEm);
        return l;
    }

    private static PontoLotePagina pagina(String id, String loteId, String pessoaId, String pessoaTipo) {
        PontoLotePagina p = new PontoLotePagina();
        p.setId(id);
        p.setLoteId(loteId);
        p.setPessoaId(pessoaId);
        p.setPessoaTipo(pessoaTipo);
        return p;
    }

    /** A página da folha, sempre buscada por id — a trava da gravação é a do lote, não a dela. */
    private void mockPagina(PontoLotePagina pg) {
        lenient().when(paginaRepo.findById(pg.getId())).thenReturn(Optional.of(pg));
    }

    /** A folha aberta na tela + as demais folhas publicadas da pessoa (a janela sai delas). */
    private PontoLote mockFolha(String pessoaId, String pessoaTipo, PontoLote lote, Object[]... outras) {
        PontoLotePagina pg = pagina(PAG, lote.getId(), pessoaId, pessoaTipo);
        List<Object[]> publicadas = new ArrayList<>();
        publicadas.add(new Object[] {pg, lote});
        publicadas.addAll(List.of(outras));

        mockPagina(pg);
        // Os dois caminhos até o lote: quem só lê busca por id, quem vai gravar trava a linha antes.
        lenient().when(loteRepo.findById(lote.getId())).thenReturn(Optional.of(lote));
        lenient().when(loteRepo.lockPorId(lote.getId())).thenReturn(Optional.of(lote));
        lenient().when(paginaRepo.findFolhasPublicadasByPessoa(pessoaId)).thenReturn(publicadas);
        return lote;
    }

    /** A folha do caso comum: prévia mensal de junho, do dono, publicada. */
    private PontoLote mockFolha(Object[]... outras) {
        return mockFolha(DONO, "OPERADOR",
                lote(LOTE, "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM, PUBLICADA_EM), outras);
    }

    /**
     * A folha da tela é a semanal do meio do mês, e a definitiva do mês chegou depois. A semanal não
     * é substituída pela mensal (elas ocupam lugares diferentes), então a folha continua abrindo —
     * o que fecha o dia é a competência.
     */
    private void mockSemanalComDefinitivaDoMes() {
        mockFolha(DONO, "OPERADOR",
                lote(LOTE, "SEMANAL", null, LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 19),
                        PUBLICADA_EM),
                outraFolha("lote-def", "MENSAL", PontoLote.CATEGORIA_DEFINITIVA, INICIO, FIM,
                        PUBLICADA_EM.plusDays(1)));
    }

    private static Object[] outraFolha(String loteId, String tipo, String categoria,
                                       LocalDate inicio, LocalDate fim, LocalDateTime publicadoEm) {
        PontoLote l = lote(loteId, tipo, categoria, inicio, fim, publicadoEm);
        return new Object[] {pagina("pag-" + loteId, loteId, DONO, "OPERADOR"), l};
    }

    private static PontoTipoMarcacao tipo(String id, String nome, boolean visivel, boolean contaFolga) {
        PontoTipoMarcacao t = new PontoTipoMarcacao();
        t.setId(id);
        t.setNome(nome);
        t.setBadge(nome.substring(0, Math.min(3, nome.length())));
        t.setEscopo(PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
        t.setVisivelFuncionario(visivel);
        t.setContaFolga(contaFolga);
        return t;
    }

    private void mockCatalogo(PontoTipoMarcacao... tipos) {
        lenient().when(tipoRepo.findAll()).thenReturn(List.of(tipos));
        for (PontoTipoMarcacao t : tipos) {
            lenient().when(tipoRepo.findById(t.getId())).thenReturn(Optional.of(t));
        }
    }

    /** A ocorrência que o administrador marcou naquele dia para o quadro inteiro. */
    private void mockMarcacaoGlobal(LocalDate data, PontoTipoMarcacao tipo) {
        mockCatalogo(tipo);
        PontoDiaMarcacao marcacao = new PontoDiaMarcacao();
        marcacao.setData(data);
        marcacao.setTipoId(tipo.getId());
        lenient().when(diaMarcacaoRepo.findByData(data)).thenReturn(Optional.of(marcacao));
    }

    /** A retificação que já existe naquele dia. */
    private PontoRetificacao mockRetificacaoDoDia(LocalDate data, String e1, String s1, String e2, String s2) {
        PontoRetificacao r = new PontoRetificacao();
        r.setId("ret-1");
        r.setPaginaId("pag-antiga");
        r.setPessoaId(DONO);
        r.setPessoaTipo("OPERADOR");
        r.setData(data);
        r.setEnt1(e1);
        r.setSai1(s1);
        r.setEnt2(e2);
        r.setSai2(s2);
        when(retificacaoRepo.findByPessoaIdAndPessoaTipoAndData(DONO, "OPERADOR", data))
                .thenReturn(Optional.of(r));
        return r;
    }

    /** As células que a folha imprimiu naquele dia, na projeção do repositório. */
    private void mockLinhaDaFolha(String paginaId, LocalDate data, String e1, String s1, String e2, String s2) {
        lenient().when(folhaLinhaRepo.findCelulasDasFolhas(List.of(paginaId)))
                .thenReturn(List.<Object[]>of(
                        new Object[] {paginaId, null, data, "15/06/26 - seg", e1, s1, e2, s2}));
    }

    private static Map<String, Object> corpoCelula(LocalDate data, String campo, String valor) {
        return corpoCelula(data == null ? null : data.toString(), campo, valor);
    }

    private static Map<String, Object> corpoCelula(String data, String campo, String valor) {
        Map<String, Object> m = new HashMap<>();
        if (data != null) m.put("data", data);
        if (campo != null) m.put("campo", campo);
        if (valor != null) m.put("valor", valor);
        return m;
    }

    private static Map<String, Object> corpoTipo(LocalDate data, String tipoId) {
        Map<String, Object> m = new HashMap<>();
        m.put("data", data.toString());
        m.put("tipo_id", tipoId);
        return m;
    }

    private PontoRetificacao capturarSalva() {
        ArgumentCaptor<PontoRetificacao> captor = ArgumentCaptor.forClass(PontoRetificacao.class);
        verify(retificacaoRepo).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private ServiceValidationException recusaAoSalvar(Map<String, Object> corpo) {
        return assertThrows(ServiceValidationException.class,
                () -> service.salvarCelula(PAG, DONO, corpo));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lista(Map<String, Object> resposta, String chave) {
        return (List<Map<String, Object>>) resposta.get(chave);
    }

    // ══════════════════════════════════════════════════════════════

    @Nested
    class AcessoAFolha {

        @Test
        void folhaDeOutraPessoaNaoAbre() {
            mockFolha("op-2", "OPERADOR",
                    lote(LOTE, "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM, PUBLICADA_EM));

            ServiceValidationException e = assertThrows(ServiceValidationException.class,
                    () -> service.listarRetificacoes(PAG, DONO));

            assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
            verifyNoInteractions(retificacaoRepo);
        }

        @Test
        void paginaSemPessoaNaoAbre() {
            mockFolha(null, "OPERADOR",
                    lote(LOTE, "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM, PUBLICADA_EM));

            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ServiceValidationException.class,
                            () -> service.listarRetificacoes(PAG, DONO)).getStatus());
        }

        @Test
        void paginaInexistente() {
            when(paginaRepo.findById(PAG)).thenReturn(Optional.empty());

            ServiceValidationException e = assertThrows(ServiceValidationException.class,
                    () -> service.listarRetificacoes(PAG, DONO));

            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
            verifyNoInteractions(loteRepo, retificacaoRepo);
        }

        @Test
        void loteInexistente() {
            when(paginaRepo.findById(PAG)).thenReturn(Optional.of(pagina(PAG, LOTE, DONO, "OPERADOR")));
            when(loteRepo.findById(LOTE)).thenReturn(Optional.empty());

            assertEquals(HttpStatus.NOT_FOUND,
                    assertThrows(ServiceValidationException.class,
                            () -> service.listarRetificacoes(PAG, DONO)).getStatus());
        }

        @Test
        void folhaEmRevisaoNaoAbreNemParaODono() {
            PontoLote lote = lote(LOTE, "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM, null);
            lote.setStatus("REVISAO");
            mockFolha(DONO, "OPERADOR", lote);

            assertEquals("Folha indisponível.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.listarRetificacoes(PAG, DONO)).getMessage());
        }

        @Test
        void folhaDeLoteOcultoNaoExisteParaODono() {
            PontoLote lote = lote(LOTE, "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM, PUBLICADA_EM);
            lote.setOculto(true);
            mockFolha(DONO, "OPERADOR", lote);

            assertEquals("Folha indisponível.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.listarRetificacoes(PAG, DONO)).getMessage());
        }

        @Test
        void folhaSubstituidaPorOutraPublicadaDepoisNaoAbre() {
            mockFolha(outraFolha("lote-2", "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM,
                    PUBLICADA_EM.plusDays(1)));

            assertEquals("Folha indisponível.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.listarRetificacoes(PAG, DONO)).getMessage());
        }
    }

    @Nested
    class Listagem {

        @Test
        void osDiasCobremOPeriodoDaFolha() {
            mockFolha();

            List<Map<String, Object>> dias = lista(service.listarRetificacoes(PAG, DONO), "dias");

            assertEquals(30, dias.size());
            assertEquals("2026-06-01", dias.get(0).get("data"));
            assertEquals("2026-06-30", dias.get(29).get("data"));
            assertTrue(dias.stream().allMatch(d -> Boolean.TRUE.equals(d.get("aberto"))));
            assertNull(dias.get(0).get("marcacao_global"));
        }

        @Test
        void diaFechadoPelaJanelaVemFechado() {
            // A folha da tela é a semanal antiga; a semana seguinte, publicada depois, fechou-a.
            mockFolha(DONO, "OPERADOR",
                    lote(LOTE, "SEMANAL", null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                            PUBLICADA_EM.minusDays(7)),
                    outraFolha("lote-2", "SEMANAL", null, LocalDate.of(2026, 6, 1),
                            LocalDate.of(2026, 6, 14), PUBLICADA_EM));

            List<Map<String, Object>> dias = lista(service.listarRetificacoes(PAG, DONO), "dias");

            assertEquals(7, dias.size());
            assertTrue(dias.stream().noneMatch(d -> Boolean.TRUE.equals(d.get("aberto"))));
        }

        @Test
        void oDiaMarcadoParaTodosVemComONomeDaOcorrencia() {
            mockFolha();
            PontoTipoMarcacao feriado = tipo("t-feriado", "Feriado", false, false);
            mockCatalogo(feriado);
            PontoDiaMarcacao marcacao = new PontoDiaMarcacao();
            marcacao.setData(DIA);
            marcacao.setTipoId(feriado.getId());
            when(diaMarcacaoRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INICIO, FIM.plusDays(1)))
                    .thenReturn(List.of(marcacao));

            List<Map<String, Object>> dias = lista(service.listarRetificacoes(PAG, DONO), "dias");

            assertEquals("Feriado", dias.get(14).get("marcacao_global"));
            assertNull(dias.get(13).get("marcacao_global"));
        }

        @Test
        void soOsTiposVisiveisAoFuncionarioViajam() {
            mockFolha();
            mockCatalogo(tipo(TIPO_BANCO, "Banco de horas", true, true),
                    tipo("t-atestado", "Atestado", false, false));

            List<Map<String, Object>> tipos = lista(service.listarRetificacoes(PAG, DONO), "tipos");

            assertEquals(1, tipos.size());
            assertEquals(Map.of("id", TIPO_BANCO, "nome", "Banco de horas"), tipos.get(0));
        }

        @Test
        void osTiposVemNaOrdemDoCatalogo() {
            mockFolha();
            mockCatalogo(tipo("t-2", "Ônibus", true, false), tipo("t-1", "Abono", true, false));

            List<Map<String, Object>> tipos = lista(service.listarRetificacoes(PAG, DONO), "tipos");

            assertEquals(List.of("Abono", "Ônibus"), tipos.stream().map(t -> t.get("nome")).toList());
        }

        @Test
        void aRetificacaoDeHorarioVemCampoACampo() {
            mockFolha();
            PontoRetificacao r = new PontoRetificacao();
            r.setId("ret-1");
            r.setData(DIA);
            r.setSai1("12:30");
            when(retificacaoRepo.findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                    DONO, "OPERADOR", INICIO, FIM)).thenReturn(List.of(r));

            Map<String, Object> item = lista(service.listarRetificacoes(PAG, DONO), "retificacoes").get(0);

            assertEquals("ret-1", item.get("id"));
            assertEquals("2026-06-15", item.get("data"));
            assertEquals("12:30", item.get("sai1"));
            assertNull(item.get("ent1"));
            assertNull(item.get("tipo_id"));
            assertNull(item.get("tipo_nome"));
            assertEquals(false, item.get("conta_folga"));
        }

        @Test
        void aRetificacaoDeTipoVemComONomeEOEfeitoDeFolga() {
            mockFolha();
            mockCatalogo(tipo(TIPO_BANCO, "Banco de horas", true, true));
            PontoRetificacao r = new PontoRetificacao();
            r.setId("ret-1");
            r.setData(DIA);
            r.setTipoId(TIPO_BANCO);
            when(retificacaoRepo.findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                    DONO, "OPERADOR", INICIO, FIM)).thenReturn(List.of(r));

            Map<String, Object> item = lista(service.listarRetificacoes(PAG, DONO), "retificacoes").get(0);

            assertEquals(TIPO_BANCO, item.get("tipo_id"));
            assertEquals("Banco de horas", item.get("tipo_nome"));
            assertEquals(true, item.get("conta_folga"));
        }

        @Test
        void aObservacaoNaoViajaMais() {
            mockFolha();
            PontoRetificacao r = new PontoRetificacao();
            r.setData(DIA);
            r.setEnt1("08:00");
            r.setObservacoes("esqueci de bater");
            when(retificacaoRepo.findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                    DONO, "OPERADOR", INICIO, FIM)).thenReturn(List.of(r));

            Map<String, Object> item = lista(service.listarRetificacoes(PAG, DONO), "retificacoes").get(0);

            assertFalse(item.containsKey("observacoes"));
        }

        @Test
        void aLeituraNaoTravaAPublicacao() {
            mockFolha();

            service.listarRetificacoes(PAG, DONO);

            verify(loteRepo).findById(LOTE);
            verify(loteRepo, never()).lockPorId(anyString());
        }

        @Test
        void aListagemLeAPessoaEOPeriodoDaPagina() {
            mockFolha("tec-7", "TECNICO",
                    lote(LOTE, "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM, PUBLICADA_EM));

            service.listarRetificacoes(PAG, "tec-7");

            verify(retificacaoRepo).findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                    "tec-7", "TECNICO", INICIO, FIM);
        }
    }

    @Nested
    class GravacaoDeCelula {

        @Test
        void gravaSoOCampoDigitado() {
            mockFolha();

            Map<String, Object> resposta = service.salvarCelula(PAG, DONO, corpoCelula(DIA, "sai1", "12:30"));

            PontoRetificacao salva = capturarSalva();
            assertEquals("12:30", salva.getSai1());
            assertNull(salva.getEnt1());
            assertNull(salva.getEnt2());
            assertNull(salva.getSai2());
            assertEquals(DIA, salva.getData());
            assertEquals(DONO, salva.getPessoaId());
            assertEquals("OPERADOR", salva.getPessoaTipo());
            assertEquals(PAG, salva.getPaginaId());
            assertEquals("12:30", resposta.get("sai1"));
        }

        @Test
        void aGravacaoTravaAPublicacaoAntesDeLerODia() {
            mockFolha();

            service.salvarCelula(PAG, DONO, corpoCelula(DIA, "ent1", "08:00"));

            verify(loteRepo).lockPorId(LOTE);
            verify(loteRepo, never()).findById(LOTE);
        }

        @Test
        void oPessoaTipoVemDaPagina() {
            mockFolha("tec-7", "TECNICO",
                    lote(LOTE, "MENSAL", PontoLote.CATEGORIA_PREVIA, INICIO, FIM, PUBLICADA_EM));

            service.salvarCelula(PAG, "tec-7", corpoCelula(DIA, "ent1", "08:00"));

            assertEquals("TECNICO", capturarSalva().getPessoaTipo());
        }

        @Test
        void osHorariosJaRetificadosDoDiaPermanecem() {
            mockFolha();
            mockRetificacaoDoDia(DIA, "08:00", null, null, null);

            service.salvarCelula(PAG, DONO, corpoCelula(DIA, "sai1", "12:30"));

            PontoRetificacao salva = capturarSalva();
            assertEquals("08:00", salva.getEnt1());
            assertEquals("12:30", salva.getSai1());
            assertEquals("pag-antiga", salva.getPaginaId(),
                    "a origem da retificação não muda: é por ela que a exclusão da folha sabe o que apagar");
        }

        @Test
        void digitarHorarioDesfazAOcorrenciaDeclarada() {
            mockFolha();
            PontoRetificacao existente = mockRetificacaoDoDia(DIA, null, null, null, null);
            existente.setTipoId(TIPO_BANCO);

            service.salvarCelula(PAG, DONO, corpoCelula(DIA, "ent1", "08:00"));

            PontoRetificacao salva = capturarSalva();
            assertNull(salva.getTipoId());
            assertEquals("08:00", salva.getEnt1());
        }

        @Test
        void celulaForaDasQuatroRecusa() {
            mockFolha();

            assertEquals("Célula inválida.", recusaAoSalvar(corpoCelula(DIA, "totaldia", "08:00")).getMessage());
            assertEquals("Célula inválida.", recusaAoSalvar(corpoCelula(DIA, null, "08:00")).getMessage());
            verify(retificacaoRepo, never()).saveAndFlush(any());
        }

        @Test
        void horarioMalformadoRecusaNomeandoODia() {
            mockFolha();

            for (String valor : List.of("8h", "24:00", "08:60", "8:00", "0800", "")) {
                assertEquals("Horário inválido no dia 15/06/2026.",
                        recusaAoSalvar(corpoCelula(DIA, "ent1", valor)).getMessage());
            }
            verify(retificacaoRepo, never()).saveAndFlush(any());
        }

        @Test
        void dataAusenteOuTortaRecusa() {
            mockFolha();

            assertEquals("Data obrigatória.",
                    recusaAoSalvar(corpoCelula((String) null, "ent1", "08:00")).getMessage());
            for (String data : List.of("15-06-2026", "2026-06-31")) {
                assertTrue(recusaAoSalvar(corpoCelula(data, "ent1", "08:00"))
                        .getMessage().contains("AAAA-MM-DD"));
            }
        }

        @Test
        void diaForaDoPeriodoDaFolhaRecusa() {
            mockFolha();

            assertEquals("O dia 01/07/2026 está fora do período da folha.",
                    recusaAoSalvar(corpoCelula(LocalDate.of(2026, 7, 1), "ent1", "08:00")).getMessage());
        }

        @Test
        void fimDeSemanaRecusa() {
            mockFolha();

            assertEquals("O dia 13/06/2026 não é dia útil.",
                    recusaAoSalvar(corpoCelula(SABADO, "ent1", "08:00")).getMessage());
            assertEquals("O dia 14/06/2026 não é dia útil.",
                    recusaAoSalvar(corpoCelula(SABADO.plusDays(1), "ent1", "08:00")).getMessage());
        }

        @Test
        void diaMarcadoParaTodosRecusaNomeandoAOcorrencia() {
            mockFolha();
            mockMarcacaoGlobal(DIA, tipo("t-feriado", "Feriado", false, false));

            assertEquals("O dia 15/06/2026 está marcado como Feriado.",
                    recusaAoSalvar(corpoCelula(DIA, "ent1", "08:00")).getMessage());
            verify(retificacaoRepo, never()).saveAndFlush(any());
        }

        @Test
        void diaFechadoPorFolhaMaisNovaRecusa() {
            mockFolha(DONO, "OPERADOR",
                    lote(LOTE, "SEMANAL", null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                            PUBLICADA_EM.minusDays(7)),
                    outraFolha("lote-2", "SEMANAL", null, LocalDate.of(2026, 6, 1),
                            LocalDate.of(2026, 6, 14), PUBLICADA_EM));

            assertEquals("O dia 01/06/2026 não pode mais ser retificado.",
                    recusaAoSalvar(corpoCelula(LocalDate.of(2026, 6, 1), "ent1", "08:00")).getMessage());
        }

        @Test
        void mesEncerradoPelaDefinitivaRecusaComOMotivoProprio() {
            mockSemanalComDefinitivaDoMes();

            assertEquals("Não é possível retificar. Folha definitiva já publicada.",
                    recusaAoSalvar(corpoCelula(DIA, "ent1", "08:00")).getMessage());
        }

        @Test
        void oTurnoQueAtravessaAMeiaNoiteEhAceito() {
            mockFolha();
            mockRetificacaoDoDia(DIA, "19:03", null, null, null);

            service.salvarCelula(PAG, DONO, corpoCelula(DIA, "sai1", "02:28"));

            PontoRetificacao salva = capturarSalva();
            assertEquals("19:03", salva.getEnt1());
            assertEquals("02:28", salva.getSai1());
        }

        @Test
        void aSegundaVoltaNoRelogioRecusa() {
            mockFolha();
            // O dia já vira a meia-noite entre a entrada e a saída; a célula digitada viraria de novo.
            mockRetificacaoDoDia(DIA, "19:00", "02:00", null, null);

            assertEquals("Os horários do dia 15/06/2026 estão fora de ordem.",
                    recusaAoSalvar(corpoCelula(DIA, "ent2", "01:00")).getMessage());
            verify(retificacaoRepo, never()).saveAndFlush(any());
        }

        @Test
        void aOrdemContaComOsHorariosQueAFolhaImprimiu() {
            mockFolha();
            mockLinhaDaFolha(PAG, DIA, "19:00", "02:00", null, null);

            assertEquals("Os horários do dia 15/06/2026 estão fora de ordem.",
                    recusaAoSalvar(corpoCelula(DIA, "ent2", "01:00")).getMessage());

            service.salvarCelula(PAG, DONO, corpoCelula(DIA, "ent2", "03:00"));
            assertEquals("03:00", capturarSalva().getEnt2());
        }

        @Test
        void aOrdemUsaAFolhaMaisRecenteQueCobreODia() {
            // A tela é a prévia do mês; quem representa o dia é a semanal publicada depois.
            mockFolha(outraFolha("lote-2", "SEMANAL", null, LocalDate.of(2026, 6, 15),
                    LocalDate.of(2026, 6, 21), PUBLICADA_EM.plusDays(1)));
            mockLinhaDaFolha("pag-lote-2", DIA, "19:00", "02:00", null, null);

            assertEquals("Os horários do dia 15/06/2026 estão fora de ordem.",
                    recusaAoSalvar(corpoCelula(DIA, "ent2", "01:00")).getMessage());
        }

        @Test
        void celulaDaFolhaQueNaoEhHorarioNaoAtrapalhaAOrdem() {
            mockFolha();
            mockLinhaDaFolha(PAG, DIA, "Falta", "Falta", "Falta", "Falta");

            service.salvarCelula(PAG, DONO, corpoCelula(DIA, "ent1", "08:00"));

            assertEquals("08:00", capturarSalva().getEnt1());
        }

        @Test
        void corridaNaChaveDoDiaViraRecusaAmigavel() {
            mockFolha();
            when(retificacaoRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                    "could not execute statement",
                    new SQLIntegrityConstraintViolationException(
                            "ORA-00001: unique constraint (NUSP.UK_PNT_RETIF_PESSOA_DIA) violated")));

            assertEquals("Não foi possível concluir a operação.",
                    recusaAoSalvar(corpoCelula(DIA, "ent1", "08:00")).getMessage());
        }

        @Test
        void violacaoQueNaoEhACaveDoDiaSobeIntacta() {
            mockFolha();
            DataIntegrityViolationException original = new DataIntegrityViolationException(
                    "could not execute statement",
                    new SQLException("ORA-02290: check constraint (NUSP.CK_PNT_RETIF_CONTEUDO) violated"));
            when(retificacaoRepo.saveAndFlush(any())).thenThrow(original);

            assertSame(original, assertThrows(DataIntegrityViolationException.class,
                    () -> service.salvarCelula(PAG, DONO, corpoCelula(DIA, "ent1", "08:00"))));
        }
    }

    @Nested
    class DeclaracaoDeTipo {

        @Test
        void gravaOTipoZerandoOsHorarios() {
            mockFolha();
            mockCatalogo(tipo(TIPO_BANCO, "Banco de horas", true, true));
            mockRetificacaoDoDia(DIA, "08:00", "12:00", "13:00", "17:00");

            Map<String, Object> resposta = service.salvarTipo(PAG, DONO, corpoTipo(DIA, TIPO_BANCO));

            PontoRetificacao salva = capturarSalva();
            assertEquals(TIPO_BANCO, salva.getTipoId());
            assertNull(salva.getEnt1());
            assertNull(salva.getSai1());
            assertNull(salva.getEnt2());
            assertNull(salva.getSai2());
            assertEquals("Banco de horas", resposta.get("tipo_nome"));
            assertEquals(true, resposta.get("conta_folga"));
        }

        @Test
        void tipoQueNaoEhVisivelAoFuncionarioRecusa() {
            mockFolha();
            mockCatalogo(tipo("t-atestado", "Atestado", false, false));

            ServiceValidationException e = assertThrows(ServiceValidationException.class,
                    () -> service.salvarTipo(PAG, DONO, corpoTipo(DIA, "t-atestado")));

            assertEquals("Tipo de ocorrência não disponível.", e.getMessage());
            verify(retificacaoRepo, never()).saveAndFlush(any());
        }

        @Test
        void tipoInexistenteRecusa() {
            mockFolha();
            when(tipoRepo.findById(anyString())).thenReturn(Optional.empty());

            assertEquals("Tipo de ocorrência não disponível.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.salvarTipo(PAG, DONO, corpoTipo(DIA, "nao-existe"))).getMessage());
        }

        @Test
        void oDiaBloqueadoTambemRecusaADeclaracao() {
            mockFolha();
            mockCatalogo(tipo(TIPO_BANCO, "Banco de horas", true, true));

            assertEquals("O dia 13/06/2026 não é dia útil.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.salvarTipo(PAG, DONO, corpoTipo(SABADO, TIPO_BANCO))).getMessage());
        }
    }

    /**
     * Apagar cobra menos que escrever: só a janela — o dia dentro do período da folha e ainda
     * aberto. O que impede de ESCREVER num dia (o fim de semana, a ocorrência marcada para todos)
     * pode ter chegado depois da correção, e desfazer o que já se escreveu não fica refém disso.
     */
    @Nested
    class Limpeza {

        @Test
        void apagaARetificacaoDoDia() {
            mockFolha();
            PontoRetificacao r = mockRetificacaoDoDia(DIA, "08:00", null, null, null);

            Map<String, Object> resposta = service.limpar(PAG, DONO, DIA.toString());

            verify(retificacaoRepo).delete(r);
            assertEquals("2026-06-15", resposta.get("data"));
        }

        @Test
        void oFimDeSemanaNaoImpedeApagar() {
            mockFolha();
            PontoRetificacao r = mockRetificacaoDoDia(SABADO, "08:00", null, null, null);

            service.limpar(PAG, DONO, SABADO.toString());

            verify(retificacaoRepo).delete(r);
        }

        @Test
        void oDiaMarcadoParaTodosNaoImpedeApagar() {
            mockFolha();
            mockMarcacaoGlobal(DIA, tipo("t-feriado", "Feriado", false, false));
            PontoRetificacao r = mockRetificacaoDoDia(DIA, "08:00", null, null, null);

            service.limpar(PAG, DONO, DIA.toString());

            verify(retificacaoRepo).delete(r);
        }

        @Test
        void diaSemRetificacaoNaoExiste() {
            mockFolha();

            ServiceValidationException e = assertThrows(ServiceValidationException.class,
                    () -> service.limpar(PAG, DONO, DIA.toString()));

            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
            verify(retificacaoRepo, never()).delete(any());
        }

        @Test
        void oDiaFechadoNaoSeLimpa() {
            mockSemanalComDefinitivaDoMes();

            assertEquals("Não é possível retificar. Folha definitiva já publicada.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.limpar(PAG, DONO, DIA.toString())).getMessage());
            verify(retificacaoRepo, never()).delete(any());
        }

        @Test
        void oDiaForaDoPeriodoDaFolhaNaoSeLimpa() {
            mockFolha();

            assertEquals("O dia 01/07/2026 está fora do período da folha.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.limpar(PAG, DONO, "2026-07-01")).getMessage());
            verify(retificacaoRepo, never()).delete(any());
        }

        @Test
        void dataTortaRecusa() {
            mockFolha();

            assertEquals("Data obrigatória.",
                    assertThrows(ServiceValidationException.class,
                            () -> service.limpar(PAG, DONO, null)).getMessage());
        }
    }
}
