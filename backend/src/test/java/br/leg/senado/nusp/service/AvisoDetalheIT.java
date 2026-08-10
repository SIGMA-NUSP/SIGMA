package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.enums.TipoAviso;
import br.leg.senado.nusp.it.support.Causas;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.it.support.VigiaDeFacetas;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AvisoAlvoRepository;
import br.leg.senado.nusp.repository.AvisoCadastroRepository;
import br.leg.senado.nusp.repository.AvisoCienciaRepository;
import br.leg.senado.nusp.repository.AvisoMensagemRepository;
import br.leg.senado.nusp.repository.EscalaOperadorRepository;
import br.leg.senado.nusp.repository.EscalaSemanalRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import jakarta.persistence.EntityManager;

/**
 * IT dos campos do {@code obterDetalhe} que não são exclusivos de escala/agenda/pessoal (esses
 * vivem no {@code AvisoEscalaIT}/{@code AvisoAgendaPessoalIT}): a sala que a ciência da Verificação
 * carrega, o "Local" que a listagem monta com as salas do cadastro, o fallback de rótulo do legado
 * sem subtipo e a garantia de que o payload só GANHOU campos — nenhum preexistente sumiu. Contra
 * Oracle real porque tanto o detalhe quanto a listagem cruzam cadastro + alvos + ciências reais.
 */
@OracleIT
class AvisoDetalheIT {

    @Autowired private TestEntityManager em;
    @Autowired private AvisoCadastroRepository cadastroRepo;
    @Autowired private AvisoMensagemRepository mensagemRepo;
    @Autowired private AvisoAlvoRepository alvoRepo;
    @Autowired private AvisoCienciaRepository cienciaRepo;
    @Autowired private SalaRepository salaRepo;
    @Autowired private OperadorRepository operadorRepo;
    @Autowired private TecnicoRepository tecnicoRepo;
    @Autowired private AdministradorRepository adminRepo;
    @Autowired private EscalaSemanalRepository escalaRepo;
    @Autowired private EscalaOperadorRepository escalaOpRepo;

    private AvisoService service;
    private Administrador admin;
    private final VigiaDeFacetas vigiaDeFacetas = new VigiaDeFacetas();

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        service = new AvisoService(cadastroRepo, mensagemRepo, alvoRepo, cienciaRepo, salaRepo,
                operadorRepo, tecnicoRepo, adminRepo, escalaRepo, escalaOpRepo,
                new AvisoCienciaWriter(cienciaRepo), emReal());
        admin = CenarioFactory.novoAdministrador(emReal());
        vigiaDeFacetas.instalar();
    }

    @AfterEach
    void nenhumWarnDeFaceta() {
        vigiaDeFacetas.exigirZeroWarns();
    }

    /** Cria um aviso de VERIFICACAO (público SALA) pela via real e devolve o id do cadastro. */
    private String criarVerificacao(List<Integer> salaIds, String... mensagens) {
        return criarVerificacao(salaIds, null, mensagens);
    }

    /** Idem, com a escolha de ciência do payload — que a Verificação ignora. */
    private String criarVerificacao(List<Integer> salaIds, Boolean exigeCiencia, String... mensagens) {
        var req = new AvisoService.CriarAvisoRequest("VERIFICACAO", true, null, false,
                List.of(mensagens), "SALA", salaIds, List.of(), List.of(), List.of(), null, exigeCiencia);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    /** Linha da listagem do admin correspondente a um cadastro. */
    private Map<String, Object> linhaListagem(String cadastroId) {
        return service.listarTodosPaginado(1, 100, "", "data", "desc", null).data().stream()
                .filter(m -> cadastroId.equals(m.get("id")))
                .findFirst().orElseThrow();
    }

    /** Semeia a ciência de verificação (com sala) direto no banco — sem o REQUIRES_NEW do writer. */
    private void darCienciaComSala(String cadastroId, Integer salaId, String operadorId) {
        AvisoCiencia c = new AvisoCiencia();
        c.setCadastroId(cadastroId);
        c.setSalaId(salaId);
        c.setOperadorId(operadorId);
        c.setCienteEm(LocalDateTime.now());
        emReal().persist(c);
        emReal().flush();
    }

    @Test
    @DisplayName("Verificação: a ciência é por sala → 'cientes' traz sala_id/sala_nome (coluna Local); sem subtipo, é Aviso com contexto 'Verificação'; sem destinatários")
    void verificacaoSalaNaCiencia() {
        Sala sala = CenarioFactory.novaSala(emReal(), "Plenario02");
        Operador op = CenarioFactory.novoOperador(emReal());
        String id = criarVerificacao(List.of(sala.getId()), "Confira a sala");
        darCienciaComSala(id, sala.getId(), op.getId());

        Map<String, Object> det = service.obterDetalhe(id);
        assertNull(det.get("subtipo"), "Verificação não tem subtipo");
        assertEquals("AVISO", det.get("categoria"));
        assertEquals("Verificação", det.get("tipo_tabela"), "contexto do próprio tipo");
        assertNull(det.get("destinatarios"), "Verificação não tem bloco de destinatários (público aberto)");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cientes = (List<Map<String, Object>>) det.get("cientes");
        assertEquals(1, cientes.size());
        assertEquals(sala.getId(), cientes.get(0).get("sala_id"));
        assertEquals(sala.getNome(), cientes.get(0).get("sala_nome"));
        assertEquals("Operador", cientes.get(0).get("papel"));
    }

    @Test
    @DisplayName("listagem: a coluna Local traz as salas da verificação em ordem alfabética; os demais tipos ficam em branco")
    void localDaListagem() {
        Sala nove = CenarioFactory.novaSala(emReal(), "Plenario09");
        Sala dois = CenarioFactory.novaSala(emReal(), "Plenario02");
        Operador op = CenarioFactory.novoOperador(emReal());
        String verificacao = criarVerificacao(List.of(nove.getId(), dois.getId()), "Confira a sala");
        service.criarPessoalIndividual(
                List.of(new AvisoService.DestinatarioAviso(op.getId(), PapelPessoa.OPERADOR)),
                "Aviso pessoal", admin.getId(), null);
        String pessoal = cadastroRepo.findAll().stream()
                .filter(c -> c.getTipo() == TipoAviso.PESSOAL)
                .findFirst().orElseThrow().getId();

        assertEquals(dois.getNome() + ", " + nove.getNome(), linhaListagem(verificacao).get("local"),
                "as salas do cadastro, em ordem alfabética");
        assertNull(linhaListagem(pessoal).get("local"), "só a verificação de sala tem local");
    }

    @Test
    @DisplayName("legado PESSOAL sem subtipo: subtipo nulo, categoria MENSAGEM e contexto vazio (nunca quebra)")
    void legadoPessoalSemSubtipoUsaFallback() {
        Operador op = CenarioFactory.novoOperador(emReal());
        // Espelha os avisos legados de homolog: PESSOAL gravado SEM subtipo.
        service.criarPessoalIndividual(
                List.of(new AvisoService.DestinatarioAviso(op.getId(), PapelPessoa.OPERADOR)),
                "Aviso legado", admin.getId(), null);
        AvisoCadastro cad = cadastroRepo.findAll().stream()
                .filter(c -> c.getTipo() == TipoAviso.PESSOAL && c.getSubtipo() == null)
                .findFirst().orElseThrow();

        Map<String, Object> det = service.obterDetalhe(cad.getId());
        assertNull(det.get("subtipo"));
        assertEquals("MENSAGEM", det.get("categoria"));
        assertEquals("", det.get("tipo_tabela"), "Mensagem se identifica pelo selo — sem contexto ao lado");
    }

    @Test
    @DisplayName("o payload do detalhe só GANHOU campos: as chaves preexistentes continuam todas presentes")
    void contratoAditivoPreservaCamposExistentes() {
        Sala sala = CenarioFactory.novaSala(emReal(), "Plenario02");
        String id = criarVerificacao(List.of(sala.getId()), "Confira a sala");
        Map<String, Object> det = service.obterDetalhe(id);
        for (String chave : List.of("id", "numero", "tipo", "tipo_label", "tipo_tabela", "subtipo",
                "permanente", "duracao_dias", "manter_apos_ciencia", "status", "criado_em", "expira_em",
                "criado_por", "mensagens", "alvos", "cientes")) {
            assertTrue(det.containsKey(chave), "campo preexistente ausente: " + chave);
        }
    }

    @Test
    @DisplayName("Verificação ignora a escolha de ciência do payload: grava e devolve sempre 'exige ciência'")
    void verificacaoIgnoraEscolhaDeCiencia() {
        Sala sala = CenarioFactory.novaSala(emReal(), "Plenario02");
        String id = criarVerificacao(List.of(sala.getId()), Boolean.FALSE, "Confira a sala");

        assertTrue(cadastroRepo.findById(id).orElseThrow().getExigeCiencia());
        assertEquals(true, service.obterDetalhe(id).get("exige_ciencia"));
    }

    /**
     * Cadastro cru (sem passar pelo service) para exercitar as constraints da tabela. O número vem da
     * mesma sequence do service — NUMERO é NUMBER(10) e tem índice único.
     */
    private AvisoCadastro cadastroCru(TipoAviso tipo, boolean exigeCiencia) {
        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(((Number) emReal()
                .createNativeQuery("SELECT SEQ_FRM_AVISO_CADASTRO.NEXTVAL FROM DUAL")
                .getSingleResult()).longValue());
        cad.setTipo(tipo);
        cad.setPermanente(true);
        cad.setManterAposCiencia(false);
        cad.setExigeCiencia(exigeCiencia);
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(admin.getId());
        return cad;
    }

    @Test
    @DisplayName("o banco é a rede de segurança: Verificação sem ciência viola CK_FRM_AVISO_CAD_CIENCIA")
    void checkRecusaVerificacaoSemCiencia() {
        AvisoCadastro cad = cadastroCru(TipoAviso.VERIFICACAO, false);
        Exception ex = assertThrows(Exception.class, () -> {
            emReal().persist(cad);
            emReal().flush();
        });
        assertTrue(Causas.contem(ex, "CK_FRM_AVISO_CAD_CIENCIA"), ex.getMessage());
    }

    @Test
    @DisplayName("o banco é a rede de segurança: Agenda com ciência viola CK_FRM_AVISO_CAD_CIENCIA")
    void checkRecusaAgendaComCiencia() {
        AvisoCadastro cad = cadastroCru(TipoAviso.AGENDA, true);
        Exception ex = assertThrows(Exception.class, () -> {
            emReal().persist(cad);
            emReal().flush();
        });
        assertTrue(Causas.contem(ex, "CK_FRM_AVISO_CAD_CIENCIA"), ex.getMessage());
    }
}
