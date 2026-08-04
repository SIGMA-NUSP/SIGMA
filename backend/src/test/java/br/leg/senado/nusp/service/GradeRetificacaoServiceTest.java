package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.service.GradeRetificacaoService.Celula;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A grade resolve cada célula por uma precedência de exibição: horários da retificação →
 * "Banco de horas" (folga aprovada) → ocorrência geral do dia → ocorrência do funcionário →
 * vazia. A ordem importa porque a geral vale para TODOS: com um feriado no dia, é o feriado
 * que a coluna inteira mostra, e a ocorrência individual daquele funcionário fica escondida
 * (gravada, e de volta à tela assim que a geral sair). Retificação e folga aprovada vencem as
 * duas — são fatos do ponto, não marcação de calendário.
 *
 * <p>O payload leva também o TIPO de cada ocorrência (o da célula e o do dia), que é o que
 * permite à tela abrir a lista já no valor atual e saber quais dias estão sob uma geral.
 *
 * <p>Cenário: 09/07 é feriado para todos; Ana tem "Atestado" nesse mesmo dia (escondido) e
 * outro em 10/07 (visível); Bruno tem retificação em 09/07; Carla tem folga aprovada em 09/07.
 */
@ExtendWith(MockitoExtension.class)
class GradeRetificacaoServiceTest {

    @Mock private PontoRetificacaoRepository retificacaoRepo;
    @Mock private PontoSolicitacaoFolgaRepository folgaRepo;
    @Mock private PontoDiaMarcacaoRepository diaRepo;
    @Mock private PontoPessoaMarcacaoRepository pessoaRepo;
    @Mock private PontoTipoMarcacaoRepository tipoRepo;
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

    private static final PontoTipoMarcacao FERIADO =
            tipo("tipo-feriado", "Feriado", PontoTipoMarcacao.ESCOPO_GLOBAL);
    private static final PontoTipoMarcacao ATESTADO =
            tipo("tipo-atestado", "Atestado", PontoTipoMarcacao.ESCOPO_INDIVIDUAL);

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
                .thenReturn(List.of(retificacao("op-bruno", DIA_FERIADO, "08:00", "12:00")));
        when(folgaRepo.findPorStatusECategoriaNoRange(
                StatusSolicitacaoFolga.APROVADO, "OPERADOR", INI_JUL, INI_AGO))
                .thenReturn(List.of(folga("op-carla", DIA_FERIADO)));
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

    // ── helpers do cenário ──────────────────────────────────────

    private Celula celula(String pessoaId, int dia) {
        return service.montarGrade("operadores", 2026, 7).celulas().get(pessoaId).get(dia);
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

    private static Operador operador(String id, String nome) {
        Operador o = new Operador();
        o.setId(id);
        o.setNomeCompleto(nome);
        return o;
    }

    private static PontoTipoMarcacao tipo(String id, String nome, String escopo) {
        PontoTipoMarcacao t = new PontoTipoMarcacao();
        t.setId(id);
        t.setNome(nome);
        t.setEscopo(escopo);
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

    private static PontoRetificacao retificacao(String pessoaId, int dia, String ent1, String sai1) {
        PontoRetificacao r = new PontoRetificacao();
        r.setPessoaId(pessoaId);
        r.setPessoaTipo("OPERADOR");
        r.setData(INI_JUL.withDayOfMonth(dia));
        r.setEnt1(ent1);
        r.setSai1(sai1);
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
}
