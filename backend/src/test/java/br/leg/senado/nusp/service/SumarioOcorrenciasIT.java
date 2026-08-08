package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoFolhaLinha;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O sumário contra o banco real: as duas consultas dele — as folhas publicadas que tocam a janela e
 * os dias com status dessas folhas — só provam o que valem contra o Oracle, com o vínculo
 * polimórfico (operador e técnico na mesma tabela de páginas) e o período de verdade.
 *
 * <p>O cenário é o do mês fechado por etapas: a semanal cumulativa, a prévia e a definitiva do mesmo
 * mês, todas publicadas e todas repetindo os mesmos dias. Só a última pode contar.
 */
@OracleIT
class SumarioOcorrenciasIT {

    private static final String OPERADOR = "OPERADOR";
    private static final String TECNICO = "TECNICO";
    private static final LocalDate INICIO_JULHO = LocalDate.of(2026, 7, 1);
    private static final LocalDate FIM_JULHO = LocalDate.of(2026, 7, 31);

    @Autowired private TestEntityManager em;
    @Autowired private PontoLotePaginaRepository paginaRepo;
    @Autowired private PontoFolhaLinhaRepository folhaLinhaRepo;
    @Autowired private OperadorRepository operadorRepo;
    @Autowired private TecnicoRepository tecnicoRepo;
    @Autowired private AdministradorRepository administradorRepo;

    private SumarioOcorrenciasService service;
    private Administrador admin;
    private Operador ana;
    private Tecnico bruno;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        service = new SumarioOcorrenciasService(paginaRepo, folhaLinhaRepo,
                new PessoaCadastroLookup(operadorRepo, tecnicoRepo, administradorRepo));
        admin = CenarioFactory.novoAdministrador(emReal());
        ana = CenarioFactory.novoOperador(emReal(), "Ana Sumario");
        bruno = CenarioFactory.novoTecnico(emReal(), "Bruno Sumario");
    }

    // ── Cenário ──

    private PontoLotePagina folhaPublicada(String tipo, String categoria, LocalDate inicio, LocalDate fim,
                                           String pessoaId, String pessoaTipo) {
        PontoLote lote = CenarioFactory.novoLotePontoPublicado(emReal(), tipo, categoria, inicio, fim, admin);
        return CenarioFactory.novaPaginaLote(emReal(), lote, 1, pessoaId, pessoaTipo);
    }

    /** Um dia de status na tabela da folha, como a publicação o grava. */
    private void diaDeStatus(PontoLotePagina pagina, int ordem, LocalDate data, String ocorrencia) {
        PontoFolhaLinha linha = new PontoFolhaLinha();
        linha.setPaginaId(pagina.getId());
        linha.setOrdem(ordem);
        linha.setDiaVerbatim(data == null ? "??/??/??" : data.getDayOfMonth() + "/07/26 - seg");
        linha.setEnt1(ocorrencia);
        linha.setSai1(ocorrencia);
        linha.setData(data);
        linha.setOcorrencia(ocorrencia);
        emReal().persist(linha);
        emReal().flush();
    }

    private Map<String, Object> sumarioDeJulho() {
        emReal().flush();
        emReal().clear();
        return service.sumario("2026-07", "2026-07");
    }

    // ── Leitura da resposta ──

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lista(Map<String, Object> resposta, String chave) {
        return (List<Map<String, Object>>) resposta.get(chave);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contagensDe(Map<String, Object> resposta, String nome) {
        return lista(resposta, "funcionarios").stream()
                .filter(m -> nome.equals(m.get("nome")))
                .map(m -> (Map<String, Object>) m.get("contagens"))
                .findFirst().orElseThrow(() -> new AssertionError("funcionário ausente do sumário: " + nome));
    }

    private static List<String> nomes(Map<String, Object> resposta) {
        return lista(resposta, "funcionarios").stream().map(m -> String.valueOf(m.get("nome"))).toList();
    }

    private static List<String> codigos(Map<String, Object> resposta) {
        return lista(resposta, "ocorrencias").stream().map(m -> String.valueOf(m.get("codigo"))).toList();
    }

    // ── Testes ──

    @Test
    @DisplayName("semanal + prévia + definitiva do mesmo mês: só a definitiva conta, e o técnico entra pela semanal")
    void definitivaRepresentaOMesEOSumarioCruzaOsDoisCadastros() {
        PontoLotePagina semanalAna = folhaPublicada("SEMANAL", null,
                INICIO_JULHO, LocalDate.of(2026, 7, 14), ana.getId(), OPERADOR);
        diaDeStatus(semanalAna, 1, LocalDate.of(2026, 7, 6), "FERNC");

        PontoLotePagina previaAna = folhaPublicada("MENSAL", PontoLote.CATEGORIA_PREVIA,
                INICIO_JULHO, FIM_JULHO, ana.getId(), OPERADOR);
        diaDeStatus(previaAna, 1, LocalDate.of(2026, 7, 6), "FERNC");
        diaDeStatus(previaAna, 2, LocalDate.of(2026, 7, 7), "FERNC");

        PontoLotePagina definitivaAna = folhaPublicada("MENSAL", PontoLote.CATEGORIA_DEFINITIVA,
                INICIO_JULHO, FIM_JULHO, ana.getId(), OPERADOR);
        diaDeStatus(definitivaAna, 1, LocalDate.of(2026, 7, 6), "FERNC");
        diaDeStatus(definitivaAna, 2, LocalDate.of(2026, 7, 7), "FERNC");
        diaDeStatus(definitivaAna, 3, LocalDate.of(2026, 7, 8), "Falta");

        PontoLotePagina semanalBruno = folhaPublicada("SEMANAL", null,
                INICIO_JULHO, LocalDate.of(2026, 7, 21), bruno.getId(), TECNICO);
        diaDeStatus(semanalBruno, 1, LocalDate.of(2026, 7, 13), "Atecc");

        Map<String, Object> resposta = sumarioDeJulho();

        assertEquals(List.of(ana.getNomeCompleto(), bruno.getNomeCompleto()), nomes(resposta),
                "operador e técnico saem na mesma lista, em ordem alfabética");
        assertEquals(List.of("FERNC", "Atecc", "Falta"), codigos(resposta),
                "a coluna mais frequente vem primeiro; empate no total é desempatado pelo código");
        assertEquals(2, contagensDe(resposta, ana.getNomeCompleto()).get("FERNC"),
                "os dois dias da definitiva — os da prévia e da semanal são os mesmos, não somam");
        assertEquals(1, contagensDe(resposta, ana.getNomeCompleto()).get("Falta"));
        assertEquals(1, contagensDe(resposta, bruno.getNomeCompleto()).get("Atecc"));
    }

    @Test
    @DisplayName("feriado, ponto facultativo e dispensa não viram coluna; folha em revisão e dia sem data não contam")
    void oQueFicaDeForaDoSumario() {
        PontoLotePagina previa = folhaPublicada("MENSAL", PontoLote.CATEGORIA_PREVIA,
                INICIO_JULHO, FIM_JULHO, ana.getId(), OPERADOR);
        diaDeStatus(previa, 1, LocalDate.of(2026, 7, 9), "Feriado");
        diaDeStatus(previa, 2, LocalDate.of(2026, 7, 10), "P.facul");
        diaDeStatus(previa, 3, LocalDate.of(2026, 7, 13), "Atecc");
        diaDeStatus(previa, 4, null, "Atecc");   // dia ilegível na folha: sem data, sem mês a que pertencer
        diaDeStatus(previa, 5, LocalDate.of(2026, 7, 11), "DISPOSI");

        PontoLote emRevisao = CenarioFactory.novoLotePonto(emReal(), "MENSAL", PontoLote.CATEGORIA_PREVIA,
                INICIO_JULHO, FIM_JULHO, admin);
        PontoLotePagina naoPublicada = CenarioFactory.novaPaginaLote(emReal(), emRevisao, 1, bruno.getId(), TECNICO);
        diaDeStatus(naoPublicada, 1, LocalDate.of(2026, 7, 15), "Atjus");

        Map<String, Object> resposta = sumarioDeJulho();

        assertEquals(List.of("Atecc"), codigos(resposta));
        assertEquals(1, contagensDe(resposta, ana.getNomeCompleto()).get("Atecc"),
                "o dia sem data não entra na contagem do mês");
        assertEquals(List.of(ana.getNomeCompleto()), nomes(resposta),
                "quem só tem folha em revisão ainda não está no sumário");
    }

    @Test
    @DisplayName("fora da janela pedida, a folha não é consultada nem conta")
    void periodoLimitaOQueEntra() {
        PontoLotePagina junho = folhaPublicada("MENSAL", PontoLote.CATEGORIA_PREVIA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), ana.getId(), OPERADOR);
        diaDeStatus(junho, 1, LocalDate.of(2026, 6, 15), "Atesc");

        Map<String, Object> julho = sumarioDeJulho();
        assertTrue(nomes(julho).isEmpty(), "julho não enxerga a folha de junho");
        assertTrue(codigos(julho).isEmpty());

        Map<String, Object> semestre = service.sumario("2026-06", "2026-07");
        assertEquals(1, contagensDe(semestre, ana.getNomeCompleto()).get("Atesc"));
        assertNull(contagensDe(semestre, ana.getNomeCompleto()).get("FERNC"));
    }
}
