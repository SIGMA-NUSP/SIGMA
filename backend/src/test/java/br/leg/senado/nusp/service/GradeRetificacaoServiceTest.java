package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.service.GradeRetificacaoService.Celula;
import br.leg.senado.nusp.service.GradeRetificacaoService.Funcionario;
import br.leg.senado.nusp.service.GradeRetificacaoService.Grade;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * A grade resolve cada célula por uma precedência de exibição: retificação → "Banco de horas"
 * (folga aprovada) → ocorrência geral do dia → ocorrência do funcionário → vazia. A ordem importa
 * porque a geral vale para TODOS: com um feriado no dia, é o feriado que a coluna inteira mostra,
 * e a ocorrência individual daquele funcionário fica escondida (gravada, e de volta à tela assim
 * que a geral sair). Retificação e folga aprovada vencem as duas — são fatos do ponto, não
 * marcação de calendário.
 *
 * <p>O payload leva também o TIPO de cada ocorrência (o da célula e o do dia), que é o que permite
 * à tela abrir a lista já no valor atual e saber quais dias estão sob uma geral. A célula que nasce
 * de uma retificação nunca leva tipo: o administrador não marca ocorrência por cima dela.
 *
 * <p>A retificação chega de duas formas, e cada uma tem seu grupo aqui: a ocorrência declarada para
 * o dia inteiro (que pode valer como folga do banco) e os horários corrigidos, que a grade mostra
 * mesclados ao que a folha publicada imprimiu naquele dia.
 */
@ExtendWith(MockitoExtension.class)
class GradeRetificacaoServiceTest {

    @Mock private PontoRetificacaoRepository retificacaoRepo;
    @Mock private PontoSolicitacaoFolgaRepository folgaRepo;
    @Mock private PontoDiaMarcacaoRepository diaRepo;
    @Mock private PontoPessoaMarcacaoRepository pessoaRepo;
    @Mock private PontoTipoMarcacaoRepository tipoRepo;
    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private PontoFolhaLinhaRepository folhaLinhaRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private AdministradorRepository administradorRepo;

    @InjectMocks
    private GradeRetificacaoService service;

    private static final LocalDate INI_JUL = LocalDate.of(2026, 7, 1);
    private static final LocalDate INI_AGO = LocalDate.of(2026, 8, 1);
    private static final int DIA_FERIADO = 9;
    private static final int DIA_SO_INDIVIDUAL = 10;
    private static final int DIA_TIPO_FORA_DO_CATALOGO = 11;
    /** Dias úteis sem nada no calendário — o cenário deles é só o que cada teste lança. */
    private static final int DIA_UTIL = 6;
    private static final int OUTRO_DIA_UTIL = 7;

    private static final PontoTipoMarcacao FERIADO =
            tipo("tipo-feriado", "Feriado", PontoTipoMarcacao.ESCOPO_GLOBAL);
    private static final PontoTipoMarcacao ATESTADO =
            tipo("tipo-atestado", "Atestado", PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
    /** O tipo que o funcionário declara quando o dia saiu do banco de horas. */
    private static final PontoTipoMarcacao FOLGA_BANCO = tipoDeFolga("tipo-folga", "Folga do banco");

    @Nested
    @DisplayName("Precedência de exibição da célula")
    class Precedencia {

        /**
         * 09/07 é feriado para todos; Ana tem "Atestado" nesse mesmo dia (escondido) e outro em
         * 10/07 (visível); Bruno tem retificação em 09/07; Carla tem folga aprovada em 09/07.
         * Nenhuma folha publicada no mês: as células saem só de retificações e marcações.
         */
        @BeforeEach
        void cenarioDoMes() {
            when(operadorRepo.findAll()).thenReturn(List.of(
                    operador("op-ana", "Ana Souza"),
                    operador("op-bruno", "Bruno Lima"),
                    operador("op-carla", "Carla Dias")));
            when(tipoRepo.findAll()).thenReturn(List.of(FERIADO, ATESTADO));
            when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                    .thenReturn(List.of(global(DIA_FERIADO, FERIADO.getId())));
            when(pessoaRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan("OPERADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of(
                            pessoal("op-ana", DIA_FERIADO, ATESTADO.getId()),
                            pessoal("op-ana", DIA_SO_INDIVIDUAL, ATESTADO.getId()),
                            pessoal("op-ana", DIA_TIPO_FORA_DO_CATALOGO, "tipo-que-saiu-do-catalogo")));
            when(retificacaoRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan("OPERADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of(horariosCorrigidos("op-bruno", DIA_FERIADO, "08:00", "12:00", null, null)));
            when(folgaRepo.findPorStatusECategoriaNoRange(
                    StatusSolicitacaoFolga.APROVADO, "OPERADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of(folga("op-carla", DIA_FERIADO)));
            semFolhaPublicada();
        }

        @Test
        @DisplayName("no dia com ocorrência geral, a coluna mostra a geral — a individual do mesmo dia fica escondida")
        void geralVenceIndividual() {
            Celula cel = celula("op-ana", DIA_FERIADO);

            assertEquals("marcacao_global", cel.tipo());
            assertEquals("Feriado", cel.texto());
            assertEquals(FERIADO.getId(), cel.tipoId());
        }

        @Test
        @DisplayName("sem geral no dia, a ocorrência do funcionário aparece normalmente")
        void individualApareceQuandoNaoHaGeral() {
            Celula cel = celula("op-ana", DIA_SO_INDIVIDUAL);

            assertEquals("marcacao_pessoa", cel.tipo());
            assertEquals("Atestado", cel.texto());
            assertEquals(ATESTADO.getId(), cel.tipoId());
        }

        @Test
        @DisplayName("retificação vence a ocorrência geral: quem tem horários lançados no feriado mostra os horários")
        void retificacaoVenceGeral() {
            Celula cel = celula("op-bruno", DIA_FERIADO);

            assertEquals("horarios", cel.tipo());
            assertEquals("08:00 12:00", cel.texto());
            assertNull(cel.tipoId(), "célula de retificação não vem do catálogo de ocorrências");
        }

        @Test
        @DisplayName("folga aprovada vence a ocorrência geral: no feriado, quem folgou mostra \"Banco de horas\"")
        void folgaVenceGeral() {
            Celula cel = celula("op-carla", DIA_FERIADO);

            assertEquals("banco", cel.tipo());
            assertEquals(GradeRetificacaoService.TEXTO_BANCO_DE_HORAS, cel.texto());
            assertNull(cel.tipoId());
        }

        @Test
        @DisplayName("marcação cujo tipo saiu do catálogo não vira célula — sem nome não há o que exibir")
        void tipoForaDoCatalogoNaoVirouCelula() {
            Map<Integer, Celula> linhaDaAna =
                    service.montarGrade("operadores", 2026, 7).celulas().get("op-ana");

            assertFalse(linhaDaAna.containsKey(DIA_TIPO_FORA_DO_CATALOGO),
                    "célula sem texto ficaria pintada na grade e vazia no XLSX: " + linhaDaAna);
        }

        @Test
        @DisplayName("o payload leva o tipo da ocorrência da célula, e só nela")
        void payloadDaCelulaTrazTipo() {
            Map<String, Object> celAna = celulaDoPayload("op-ana", DIA_SO_INDIVIDUAL);
            assertEquals(ATESTADO.getId(), celAna.get("tipo_id"));

            Map<String, Object> celBruno = celulaDoPayload("op-bruno", DIA_FERIADO);
            assertFalse(celBruno.containsKey("tipo_id"), "horários não têm tipo de ocorrência: " + celBruno);
        }

        @Test
        @DisplayName("cada dia leva o nome E o tipo da ocorrência geral — nulos nos dias sem geral")
        void payloadDoDiaTrazTipoDaGeral() {
            Map<String, Object> feriado = dia(DIA_FERIADO);
            assertEquals("Feriado", feriado.get("marcacao_global"));
            assertEquals(FERIADO.getId(), feriado.get("marcacao_global_id"));

            Map<String, Object> comum = dia(DIA_SO_INDIVIDUAL);
            assertTrue(comum.containsKey("marcacao_global_id"), "a chave existe em todo dia: " + comum);
            assertNull(comum.get("marcacao_global"));
            assertNull(comum.get("marcacao_global_id"));
        }
    }

    /**
     * A retificação em que o funcionário declara uma ocorrência para o dia inteiro. Toda declaração
     * aparece pelo nome do tipo; a de tipo que conta folga entra na mesma contagem da folga
     * aprovada — assim como a marcação do administrador feita com um tipo desses.
     */
    @Nested
    @DisplayName("Retificação de ocorrência declarada")
    class OcorrenciaDeclarada {

        @BeforeEach
        void equipeECatalogo() {
            when(operadorRepo.findAll()).thenReturn(List.of(operador("op-ana", "Ana Souza")));
            when(tipoRepo.findAll()).thenReturn(List.of(FERIADO, ATESTADO, FOLGA_BANCO));
            semFolhaPublicada();
        }

        @Test
        @DisplayName("o dia declarado com tipo que conta folga mostra o nome do tipo e entra na contagem")
        void declaracaoDeFolgaEntraNaContagem() {
            retificacoes(ocorrenciaDeclarada("op-ana", DIA_UTIL, FOLGA_BANCO.getId()));

            Celula cel = celula("op-ana", DIA_UTIL);

            assertEquals("banco", cel.tipo());
            assertEquals(FOLGA_BANCO.getNome(), cel.texto(),
                    "a célula mostra o nome do tipo declarado, não o texto fixo da folga aprovada");
            assertNull(cel.tipoId());
            assertEquals(1, folgas("op-ana"));
        }

        @Test
        @DisplayName("a marcação do administrador com tipo que conta folga entra na contagem")
        void marcacaoDoAdminComTipoDeFolgaEntraNaContagem() {
            when(pessoaRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan("OPERADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of(pessoal("op-ana", DIA_UTIL, FOLGA_BANCO.getId())));

            Celula cel = celula("op-ana", DIA_UTIL);

            // a célula continua sendo a marcação comum — clicável, com o tipo no payload —,
            // e é o TEXTO dela que a coloca na conta de folgas, o mesmo critério da planilha
            assertEquals("marcacao_pessoa", cel.tipo());
            assertEquals(FOLGA_BANCO.getNome(), cel.texto());
            assertEquals(FOLGA_BANCO.getId(), cel.tipoId());
            assertEquals(1, folgas("op-ana"));
        }

        @Test
        @DisplayName("folga aprovada e declaração no mesmo dia contam um dia só")
        void folgaEDeclaracaoNoMesmoDiaContamUmaVez() {
            retificacoes(ocorrenciaDeclarada("op-ana", DIA_UTIL, FOLGA_BANCO.getId()));
            when(folgaRepo.findPorStatusECategoriaNoRange(
                    StatusSolicitacaoFolga.APROVADO, "OPERADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of(folga("op-ana", DIA_UTIL), folga("op-ana", OUTRO_DIA_UTIL)));

            assertEquals(2, folgas("op-ana"),
                    "a contagem é de DIAS: o dia que tem a folga e a declaração conta uma vez");
        }

        @Test
        @DisplayName("o dia de folga que o funcionário corrigiu com horários deixa de contar como folga")
        void folgaCobertaPorHorariosNaoContaMais() {
            retificacoes(horariosCorrigidos("op-ana", DIA_UTIL, "08:00", "12:00", null, null));
            when(folgaRepo.findPorStatusECategoriaNoRange(
                    StatusSolicitacaoFolga.APROVADO, "OPERADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of(folga("op-ana", DIA_UTIL)));

            assertEquals("horarios", celula("op-ana", DIA_UTIL).tipo());
            assertEquals(0, folgas("op-ana"),
                    "a contagem segue o que a célula mostra, como a planilha faz: quem trabalhou no dia"
                            + " da folga não folgou");
        }

        @Test
        @DisplayName("o tipo comum declarado aparece pelo nome, e o payload não leva o tipo dele")
        void declaracaoComumViraOcorrencia() {
            retificacoes(ocorrenciaDeclarada("op-ana", DIA_UTIL, ATESTADO.getId()));

            Celula cel = celula("op-ana", DIA_UTIL);
            assertEquals("ocorrencia", cel.tipo());
            assertEquals("Atestado", cel.texto());
            assertNull(cel.tipoId());

            Map<String, Object> payload = celulaDoPayload("op-ana", DIA_UTIL);
            assertFalse(payload.containsKey("tipo_id"),
                    "a ocorrência é do funcionário: o administrador não a troca pela grade: " + payload);
        }

        @Test
        @DisplayName("declaração cujo tipo saiu do catálogo não vira célula — o dia segue a precedência")
        void declaracaoDeTipoForaDoCatalogoSegueAPrecedencia() {
            retificacoes(ocorrenciaDeclarada("op-ana", DIA_UTIL, "tipo-que-saiu-do-catalogo"));
            when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                    .thenReturn(List.of(global(DIA_UTIL, FERIADO.getId())));

            Celula cel = celula("op-ana", DIA_UTIL);

            assertEquals("marcacao_global", cel.tipo(), "sem nome para exibir, é como se a declaração não existisse");
            assertEquals("Feriado", cel.texto());
            assertEquals(0, folgas("op-ana"), "tipo fora do catálogo não vale como folga do banco");
        }
    }

    /**
     * A retificação de horários guarda só o que o funcionário corrigiu — a grade mostra o dia
     * EFETIVO: o que ele digitou por cima do que a folha publicada imprimiu naquele dia, em texto
     * liso, sem dizer de onde veio cada horário.
     */
    @Nested
    @DisplayName("Retificação de horários sobre a folha publicada")
    class HorariosSobreAFolha {

        @BeforeEach
        void equipe() {
            when(operadorRepo.findAll()).thenReturn(List.of(operador("op-ana", "Ana Souza")));
        }

        @Test
        @DisplayName("a célula mostra os horários corrigidos mesclados aos que a folha imprimiu")
        void horariosMesclamOQueAFolhaImprimiu() {
            PontoLote folha = semanal("l-01a09", 1, 9, 1);
            folhasPublicadas(folhaDe("p-ana", folha, "op-ana"));
            celulasDaFolha(linhaDaFolha("p-ana", DIA_UTIL, "08:00", "12:00", "13:00", "17:00"));
            retificacoes(horariosCorrigidos("op-ana", DIA_UTIL, null, null, null, "18:00"));

            Celula cel = celula("op-ana", DIA_UTIL);

            assertEquals("horarios", cel.tipo());
            assertEquals("08:00 12:00 13:00 18:00", cel.texto(),
                    "o que ele não tocou continua valendo o que veio na folha");
        }

        /**
         * As semanais são cumulativas (01–09, 01–16) e convivem: as duas cobrem o mesmo dia. Vale a
         * publicada por último — é a versão que o funcionário tem à mão e a que ele conferiu ao
         * digitar a correção.
         */
        @Test
        @DisplayName("o horário corrigido guarda o lugar dele: a saída da tarde não vira entrada")
        void aPosicaoDoHorarioNaoSePerde() {
            PontoLote folha = semanal("l-01a09", 1, 9, 1);
            folhasPublicadas(folhaDe("p-ana", folha, "op-ana"));
            celulasDaFolha(linhaDaFolha("p-ana", DIA_UTIL, null, null, null, null));
            retificacoes(horariosCorrigidos("op-ana", DIA_UTIL, null, null, null, "18:07"));

            assertEquals("-- -- -- 18:07", celula("op-ana", DIA_UTIL).texto(),
                    "quem lê a célula conta a posição para saber o que é entrada e o que é saída");
        }

        @Test
        @DisplayName("com duas folhas vigentes no mesmo dia, o dia vem da publicada por último")
        void folhaPublicadaPorUltimoDecideOMerge() {
            PontoLote antiga = semanal("l-01a09", 1, 9, 1);
            PontoLote nova = semanal("l-01a16", 1, 16, 2);
            folhasPublicadas(folhaDe("p-nova", nova, "op-ana"), folhaDe("p-antiga", antiga, "op-ana"));
            celulasDaFolha(
                    linhaDaFolha("p-nova", DIA_UTIL, "09:00", "13:00", null, null),
                    linhaDaFolha("p-antiga", DIA_UTIL, "07:00", "11:00", null, null));
            retificacoes(horariosCorrigidos("op-ana", DIA_UTIL, null, null, null, "18:00"));

            assertEquals("09:00 13:00 -- 18:00", celula("op-ana", DIA_UTIL).texto());
        }

        @Test
        @DisplayName("célula da folha que não é horário fica de fora: o status do dia não se mescla a batida")
        void statusDaFolhaNaoEntraNoMerge() {
            PontoLote folha = semanal("l-01a09", 1, 9, 1);
            folhasPublicadas(folhaDe("p-ana", folha, "op-ana"));
            celulasDaFolha(linhaDeStatus("p-ana", DIA_UTIL, "Falta"));
            retificacoes(horariosCorrigidos("op-ana", DIA_UTIL, null, "12:00", null, null));

            // A entrada continua sem ninguém: a marca guarda o lugar dela para que o 12:00 seja lido
            // como a saída que é.
            assertEquals("-- 12:00", celula("op-ana", DIA_UTIL).texto());
        }
    }

    @Nested
    @DisplayName("Grade geral — as três categorias numa tabela única")
    class GradeGeral {

        @BeforeEach
        void pessoasDasTresCategorias() {
            when(operadorRepo.findAll()).thenReturn(List.of(
                    operador("op-carla", "Carla Dias"),
                    operador("op-ana", "Ana Souza")));
            when(tecnicoRepo.findAll()).thenReturn(List.of(tecnico("tec-bruno", "Bruno Lima")));
            when(administradorRepo.findAll()).thenReturn(List.of(
                    administrador("adm-diego", "Diego Costa", false),
                    administrador("adm-servidor", "Alberto Servidor", true)));
        }

        @Test
        @DisplayName("funcionários das três categorias saem numa ordem alfabética única")
        void ordemAlfabeticaUnica() {
            Grade g = service.montarGradeGeral(2026, 7);

            assertEquals("geral", g.categoria());
            // Bruno (técnico) entre Ana e Carla (operadoras): a ordem é pelo nome, não por categoria.
            assertEquals(List.of("Ana Souza", "Bruno Lima", "Carla Dias", "Diego Costa"),
                    g.funcionarios().stream().map(Funcionario::nome).toList());
        }

        @Test
        @DisplayName("servidor público fica de fora da grade geral, como na categoria isolada")
        void servidorPublicoFora() {
            assertTrue(service.montarGradeGeral(2026, 7).funcionarios().stream()
                    .noneMatch(f -> "adm-servidor".equals(f.id())));
        }

        @Test
        @DisplayName("cada célula continua com o dono: a ocorrência do técnico não vaza para os demais")
        void celulasPorPessoa() {
            when(tipoRepo.findAll()).thenReturn(List.of(ATESTADO));
            PontoPessoaMarcacao doTecnico = pessoal("tec-bruno", DIA_UTIL, ATESTADO.getId());
            doTecnico.setPessoaTipo("TECNICO");
            // As três categorias são consultadas — stub explícito por causa do strict stubbing.
            when(pessoaRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan("OPERADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of());
            when(pessoaRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan("TECNICO", INI_JUL, INI_AGO))
                    .thenReturn(List.of(doTecnico));
            when(pessoaRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan("ADMINISTRADOR", INI_JUL, INI_AGO))
                    .thenReturn(List.of());

            Grade g = service.montarGradeGeral(2026, 7);

            assertEquals("Atestado", g.celulas().get("tec-bruno").get(DIA_UTIL).texto());
            assertNull(g.celulas().get("op-ana"));   // sem célula, a pessoa nem entra no mapa
        }
    }

    // ── helpers do cenário ──────────────────────────────────────

    private Celula celula(String pessoaId, int dia) {
        return service.montarGrade("operadores", 2026, 7).celulas().get(pessoaId).get(dia);
    }

    /** Os dias de folga contados para o funcionário no mês. */
    private int folgas(String pessoaId) {
        return service.montarGrade("operadores", 2026, 7).funcionarios().stream()
                .filter(f -> pessoaId.equals(f.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("funcionário ausente da grade: " + pessoaId))
                .folgas();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> celulaDoPayload(String pessoaId, int dia) {
        Map<String, Object> celulas = (Map<String, Object>) service.montar("operadores", 2026, 7).get("celulas");
        Map<String, Object> linha = (Map<String, Object>) celulas.get(pessoaId);
        return (Map<String, Object>) linha.get(String.valueOf(dia));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dia(int dia) {
        List<Map<String, Object>> dias =
                (List<Map<String, Object>>) service.montar("operadores", 2026, 7).get("dias");
        return dias.get(dia - 1);
    }

    // ── stubs das consultas do mês ──────────────────────────────

    private void retificacoes(PontoRetificacao... retificacoes) {
        when(retificacaoRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan("OPERADOR", INI_JUL, INI_AGO))
                .thenReturn(List.of(retificacoes));
    }

    /** Mês sem folha publicada: nada para mesclar aos horários corrigidos. */
    private void semFolhaPublicada() {
        when(paginaRepo.findFolhasPublicadasDaCategoria("OPERADOR", INI_JUL, INI_AGO)).thenReturn(List.of());
    }

    private void folhasPublicadas(Object[]... folhas) {
        when(paginaRepo.findFolhasPublicadasDaCategoria("OPERADOR", INI_JUL, INI_AGO))
                .thenReturn(List.of(folhas));
    }

    private void celulasDaFolha(Object[]... linhas) {
        when(folhaLinhaRepo.findCelulasDasFolhas(anyCollection())).thenReturn(List.of(linhas));
    }

    // ── fixtures ────────────────────────────────────────────────

    private static Operador operador(String id, String nome) {
        Operador o = new Operador();
        o.setId(id);
        o.setNomeCompleto(nome);
        return o;
    }

    private static Tecnico tecnico(String id, String nome) {
        Tecnico t = new Tecnico();
        t.setId(id);
        t.setNomeCompleto(nome);
        return t;
    }

    private static Administrador administrador(String id, String nome, boolean servidorPublico) {
        Administrador a = new Administrador();
        a.setId(id);
        a.setNomeCompleto(nome);
        a.setServidorPublico(servidorPublico);
        return a;
    }

    private static PontoTipoMarcacao tipo(String id, String nome, String escopo) {
        PontoTipoMarcacao t = new PontoTipoMarcacao();
        t.setId(id);
        t.setNome(nome);
        t.setEscopo(escopo);
        return t;
    }

    /** Tipo que o funcionário declara e que vale como folga do banco de horas. */
    private static PontoTipoMarcacao tipoDeFolga(String id, String nome) {
        PontoTipoMarcacao t = tipo(id, nome, PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
        t.setVisivelFuncionario(true);
        t.setContaFolga(true);
        return t;
    }

    private static PontoDiaMarcacao global(int dia, String tipoId) {
        PontoDiaMarcacao m = new PontoDiaMarcacao();
        m.setData(INI_JUL.withDayOfMonth(dia));
        m.setTipoId(tipoId);
        return m;
    }

    private static PontoPessoaMarcacao pessoal(String pessoaId, int dia, String tipoId) {
        PontoPessoaMarcacao m = new PontoPessoaMarcacao();
        m.setPessoaId(pessoaId);
        m.setPessoaTipo("OPERADOR");
        m.setData(INI_JUL.withDayOfMonth(dia));
        m.setTipoId(tipoId);
        return m;
    }

    private static PontoRetificacao retificacao(String pessoaId, int dia) {
        PontoRetificacao r = new PontoRetificacao();
        r.setPessoaId(pessoaId);
        r.setPessoaTipo("OPERADOR");
        r.setData(INI_JUL.withDayOfMonth(dia));
        return r;
    }

    /** Os horários que o funcionário corrigiu no dia; o que ele não tocou fica nulo. */
    private static PontoRetificacao horariosCorrigidos(String pessoaId, int dia,
                                                       String ent1, String sai1, String ent2, String sai2) {
        PontoRetificacao r = retificacao(pessoaId, dia);
        r.setEnt1(ent1);
        r.setSai1(sai1);
        r.setEnt2(ent2);
        r.setSai2(sai2);
        return r;
    }

    /** A ocorrência que o funcionário declarou para o dia inteiro. */
    private static PontoRetificacao ocorrenciaDeclarada(String pessoaId, int dia, String tipoId) {
        PontoRetificacao r = retificacao(pessoaId, dia);
        r.setTipoId(tipoId);
        return r;
    }

    private static PontoSolicitacaoFolga folga(String pessoaId, int dia) {
        PontoSolicitacaoFolga s = new PontoSolicitacaoFolga();
        s.setPessoaId(pessoaId);
        s.setPessoaTipo("OPERADOR");
        s.setDataFolga(INI_JUL.withDayOfMonth(dia));
        s.setStatus(StatusSolicitacaoFolga.APROVADO);
        return s;
    }

    /** Folha semanal publicada do mês, com a ordem em que foi publicada. */
    private static PontoLote semanal(String id, int diaInicio, int diaFim, int ordemDePublicacao) {
        PontoLote l = new PontoLote();
        l.setId(id);
        l.setTipo("SEMANAL");
        l.setDataInicio(INI_JUL.withDayOfMonth(diaInicio));
        l.setDataFim(INI_JUL.withDayOfMonth(diaFim));
        l.setStatus("PUBLICADO");
        l.setCriadoEm(LocalDateTime.of(2026, 8, 1, 9, 0).plusMinutes(ordemDePublicacao));
        l.setPublicadoEm(LocalDateTime.of(2026, 8, 2, 9, 0).plusMinutes(ordemDePublicacao));
        return l;
    }

    /** Par [página, lote] como a consulta das folhas publicadas da categoria o devolve. */
    private static Object[] folhaDe(String paginaId, PontoLote lote, String pessoaId) {
        PontoLotePagina p = new PontoLotePagina();
        p.setId(paginaId);
        p.setLoteId(lote.getId());
        p.setNumeroPagina(1);
        p.setPessoaId(pessoaId);
        p.setPessoaTipo("OPERADOR");
        p.setArquivoPagina("ponto/paginas/" + paginaId + ".pdf");
        return new Object[]{p, lote};
    }

    /**
     * Óctupla [paginaId, ocorrencia, data, diaVerbatim, ent1, sai1, ent2, sai2] — a linha-dia como
     * a folha a imprimiu.
     */
    private static Object[] linhaDaFolha(String paginaId, int dia,
                                         String ent1, String sai1, String ent2, String sai2) {
        LocalDate data = INI_JUL.withDayOfMonth(dia);
        return new Object[]{paginaId, null, data, String.format("%02d/07/26", dia), ent1, sai1, ent2, sai2};
    }

    /** A linha-dia de status: a folha imprime o status na primeira célula, no lugar da batida. */
    private static Object[] linhaDeStatus(String paginaId, int dia, String status) {
        Object[] linha = linhaDaFolha(paginaId, dia, status, null, null, null);
        linha[1] = status;
        return linha;
    }
}
