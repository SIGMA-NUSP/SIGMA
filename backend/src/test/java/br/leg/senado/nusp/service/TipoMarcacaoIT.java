package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacaoExclusaoLog;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.Causas;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoExclusaoLogRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import jakarta.persistence.EntityManager;

/**
 * Catálogo de tipos de ocorrência contra o Oracle real: é o banco que garante a
 * unicidade do nome e da badge (pela forma normalizada, entre TODOS os escopos),
 * que os acentos cabem nos tetos de 20 e 3 caracteres, e que nenhuma marcação
 * sobrevive ao tipo que a nomeia.
 */
@OracleIT
class TipoMarcacaoIT {

    private static final String MASTER = "master.teste";

    @Autowired private TestEntityManager em;
    @Autowired private PontoTipoMarcacaoRepository tipoRepo;
    @Autowired private PontoDiaMarcacaoRepository diaRepo;
    @Autowired private PontoPessoaMarcacaoRepository pessoaRepo;
    @Autowired private PontoTipoMarcacaoExclusaoLogRepository trilhaRepo;
    @Autowired private OperadorRepository operadorRepo;
    @Autowired private TecnicoRepository tecnicoRepo;
    @Autowired private AdministradorRepository adminRepo;

    private TipoMarcacaoService service;
    private Administrador admin;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        service = new TipoMarcacaoService(tipoRepo, diaRepo, pessoaRepo, trilhaRepo,
                new PessoaCadastroLookup(operadorRepo, tecnicoRepo, adminRepo), new ObjectMapper());
        ReflectionTestUtils.setField(service, "masterUsername", MASTER);
        admin = CenarioFactory.novoAdministrador(emReal());
    }

    private PontoDiaMarcacao marcarDia(LocalDate data, PontoTipoMarcacao tipo) {
        PontoDiaMarcacao m = new PontoDiaMarcacao();
        m.setData(data);
        m.setTipoId(tipo.getId());
        m.setCriadoPorId(admin.getId());
        em.persist(m);
        em.flush();
        return m;
    }

    private PontoPessoaMarcacao marcarPessoa(Operador op, LocalDate data, PontoTipoMarcacao tipo) {
        PontoPessoaMarcacao m = new PontoPessoaMarcacao();
        m.setPessoaId(op.getId());
        m.setPessoaTipo("OPERADOR");
        m.setData(data);
        m.setTipoId(tipo.getId());
        m.setCriadoPorId(admin.getId());
        em.persist(m);
        em.flush();
        return m;
    }

    @Test
    @DisplayName("nome de 20 caracteres acentuados e badge de 3 cabem — os tetos são de caracteres, não de bytes")
    void acentosCabemNosTetos() {
        String nomeCheio = "Licençá àéíóúãõâêô";   // 18 caracteres, 26 bytes em UTF-8
        PontoTipoMarcacao tipo = new PontoTipoMarcacao();
        tipo.setNome(nomeCheio);
        tipo.setNomeNorm(TipoMarcacaoService.normalizar(nomeCheio));
        tipo.setBadge("Fér");                      // 3 caracteres, 4 bytes
        tipo.setBadgeNorm(TipoMarcacaoService.normalizar("Fér"));
        tipo.setEscopo(PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
        em.persist(tipo);
        em.flush();
        em.clear();

        PontoTipoMarcacao lido = tipoRepo.findById(tipo.getId()).orElseThrow();
        assertEquals(nomeCheio, lido.getNome());
        assertEquals("Fér", lido.getBadge());
        assertEquals("FER", lido.getBadgeNorm());
    }

    @Test
    @DisplayName("o banco recusa dois tipos cujo NOME normalizado é o mesmo, ainda que em escopos diferentes")
    void nomeNormalizadoEUnicoNoBanco() {
        PontoTipoMarcacao existente = CenarioFactory.novoTipoMarcacao(
                emReal(), "Férias", "Fé", PontoTipoMarcacao.ESCOPO_INDIVIDUAL);

        PontoTipoMarcacao clone = new PontoTipoMarcacao();
        clone.setNome(existente.getNome().toUpperCase());
        clone.setNomeNorm(existente.getNomeNorm());       // a mesma forma normalizada
        clone.setBadge("Zzz");
        clone.setBadgeNorm("ZZZ");
        clone.setEscopo(PontoTipoMarcacao.ESCOPO_GLOBAL);  // outro escopo não abre exceção

        Exception ex = assertThrows(Exception.class, () -> {
            em.persist(clone);
            em.flush();
        });
        assertTrue(Causas.contem(ex, "UK_PNT_TIPOMARC_NOME"),
                "esperava a violação de UK_PNT_TIPOMARC_NOME, veio: " + ex.getMessage());
    }

    @Test
    @DisplayName("o banco recusa duas BADGES normalizadas iguais — é por isso que Feriado e Férias não podem ser ambos \"Fer\"")
    void badgeNormalizadaEUnicaNoBanco() {
        PontoTipoMarcacao existente = CenarioFactory.novoTipoMarcacao(
                emReal(), "Feriado", "Fer", PontoTipoMarcacao.ESCOPO_GLOBAL);

        PontoTipoMarcacao clone = new PontoTipoMarcacao();
        clone.setNome("Outro nome");
        clone.setNomeNorm("OUTRO NOME");
        clone.setBadge(existente.getBadge().toUpperCase());
        clone.setBadgeNorm(existente.getBadgeNorm());
        clone.setEscopo(PontoTipoMarcacao.ESCOPO_INDIVIDUAL);

        Exception ex = assertThrows(Exception.class, () -> {
            em.persist(clone);
            em.flush();
        });
        assertTrue(Causas.contem(ex, "UK_PNT_TIPOMARC_BADGE"),
                "esperava a violação de UK_PNT_TIPOMARC_BADGE, veio: " + ex.getMessage());
    }

    @Test
    @DisplayName("marcação com tipo inexistente é barrada pela FK — nenhum dia aponta para um tipo que não existe")
    void marcacaoExigeTipoExistente() {
        PontoDiaMarcacao orfa = new PontoDiaMarcacao();
        orfa.setData(LocalDate.of(2026, 7, 9));
        orfa.setTipoId("tipo-que-nunca-existiu");
        orfa.setCriadoPorId(admin.getId());

        Exception ex = assertThrows(Exception.class, () -> {
            em.persist(orfa);
            em.flush();
        });
        assertTrue(Causas.contem(ex, "FK_PNT_DIAMARC_TIPO"),
                "esperava a violação de FK_PNT_DIAMARC_TIPO, veio: " + ex.getMessage());
    }

    @Test
    @DisplayName("apagar o tipo por fora, com marcação viva, esbarra na FK — a exclusão precisa passar pelo serviço")
    void tipoComMarcacaoNaoSaiSozinho() {
        PontoTipoMarcacao tipo = CenarioFactory.novoTipoMarcacao(
                emReal(), "Feriado", "Fer", PontoTipoMarcacao.ESCOPO_GLOBAL);
        marcarDia(LocalDate.of(2026, 7, 9), tipo);

        Exception ex = assertThrows(Exception.class, () -> {
            tipoRepo.delete(tipo);
            em.flush();
        });
        assertTrue(Causas.contem(ex, "FK_PNT_DIAMARC_TIPO"),
                "esperava a violação de FK_PNT_DIAMARC_TIPO, veio: " + ex.getMessage());
    }

    @Test
    @DisplayName("excluir um tipo individual leva as marcações dele e grava a trilha com as contagens reais")
    void exclusaoLevaMarcacoesEGravaTrilha() {
        PontoTipoMarcacao tipo = CenarioFactory.novoTipoMarcacao(
                emReal(), "Férias", "Fé", PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
        Operador op = CenarioFactory.novoOperador(emReal());
        Operador outro = CenarioFactory.novoOperador(emReal());
        marcarPessoa(op, LocalDate.of(2026, 7, 6), tipo);
        marcarPessoa(op, LocalDate.of(2026, 7, 7), tipo);
        marcarPessoa(outro, LocalDate.of(2026, 7, 8), tipo);

        Map<String, Object> preview = service.previewExclusao(tipo.getId(), MASTER);
        assertEquals(3, preview.get("marcacoes"));
        assertEquals(2, preview.get("pessoas_afetadas"));

        Map<String, Object> resumo = service.excluir(tipo.getId(), MASTER, admin.getId());
        em.flush();
        em.clear();

        assertEquals(3, resumo.get("marcacoes"));
        assertTrue(pessoaRepo.findByTipoIdOrderByData(tipo.getId()).isEmpty());
        assertFalse(tipoRepo.findById(tipo.getId()).isPresent());

        List<PontoTipoMarcacaoExclusaoLog> trilha =
                trilhaRepo.findByTipoIdOrderByExcluidoEmDesc(tipo.getId());
        assertEquals(1, trilha.size());
        assertEquals(tipo.getNome(), trilha.get(0).getNome());
        assertEquals(admin.getId(), trilha.get(0).getExcluidoPorId());
        assertTrue(trilha.get(0).getResumo().contains("\"marcacoes\":3"));
    }

    @Test
    @DisplayName("excluir um tipo geral leva as marcações de dia e não toca nas marcações de outro tipo")
    void exclusaoNaoTocaMarcacoesDeOutroTipo() {
        PontoTipoMarcacao alvo = CenarioFactory.novoTipoMarcacao(
                emReal(), "Feriado", "Fer", PontoTipoMarcacao.ESCOPO_GLOBAL);
        PontoTipoMarcacao vizinho = CenarioFactory.novoTipoMarcacao(
                emReal(), "P. Facultativo", "PF", PontoTipoMarcacao.ESCOPO_GLOBAL);
        marcarDia(LocalDate.of(2026, 7, 9), alvo);
        marcarDia(LocalDate.of(2026, 7, 10), vizinho);

        service.excluir(alvo.getId(), MASTER, admin.getId());
        em.flush();
        em.clear();

        assertTrue(diaRepo.findByTipoIdOrderByData(alvo.getId()).isEmpty());
        assertEquals(1, diaRepo.findByTipoIdOrderByData(vizinho.getId()).size());
        assertTrue(tipoRepo.findById(vizinho.getId()).isPresent());
    }

    @Test
    @DisplayName("o cadastro recusa o nome que já existe com outra grafia, e nada é gravado")
    void cadastroRecusaDuplicadoNormalizado() {
        PontoTipoMarcacao existente = CenarioFactory.novoTipoMarcacao(
                emReal(), "Recesso", "Rec", PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
        long antes = tipoRepo.count();

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.criar(Map.of("tipos", List.of(
                        Map.of("nome", existente.getNome().toLowerCase(), "badge", "Zzz",
                                "escopo", "GLOBAL"))), MASTER, admin.getId()));

        assertEquals("Já existe um tipo com o nome \"" + existente.getNome() + "\".", ex.getMessage());
        assertEquals(antes, tipoRepo.count());
    }

    @Test
    @DisplayName("o cadastro em lote é tudo ou nada: o segundo tipo inválido impede o primeiro de nascer")
    void cadastroEmLoteTudoOuNada() {
        long antes = tipoRepo.count();

        assertThrows(ServiceValidationException.class,
                () -> service.criar(Map.of("tipos", List.of(
                        Map.of("nome", "Luto oficial", "badge", "Lut", "escopo", "GLOBAL"),
                        Map.of("nome", "Nome bom", "badge", "Badge grande demais", "escopo", "GLOBAL"))),
                        MASTER, admin.getId()));

        assertEquals(antes, tipoRepo.count());
    }
}
