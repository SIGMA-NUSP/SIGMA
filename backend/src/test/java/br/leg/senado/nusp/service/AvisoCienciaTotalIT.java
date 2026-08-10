package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.EscalaOperador;
import br.leg.senado.nusp.entity.EscalaSemanal;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
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
 * IT da comunicação que se encerra sozinha quando o último destinatário registra ciência. A conta
 * de quem falta depende do tipo — pessoas nomeadas na mensagem, público atual no comunicado a um
 * grupo —, e escala e verificação de sala ficam de fora da regra; por isso vive contra Oracle real,
 * com as ciências gravadas pela via verdadeira ({@code registrarCiencia}).
 *
 * <p>O {@code AvisoCienciaWriter} é instanciado à mão (sem o proxy do Spring): o REQUIRES_NEW não se
 * aplica, tudo corre na transação do teste e o rollback leva os dados embora — mesmo idioma dos
 * demais ITs de aviso.
 */
@OracleIT
class AvisoCienciaTotalIT {

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

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        service = new AvisoService(cadastroRepo, mensagemRepo, alvoRepo, cienciaRepo, salaRepo,
                operadorRepo, tecnicoRepo, adminRepo, escalaRepo, escalaOpRepo,
                new AvisoCienciaWriter(cienciaRepo), emReal());
        admin = CenarioFactory.novoAdministrador(emReal());
    }

    // ═══ Fábricas ═══════════════════════════════════════════════

    private String criarPessoal(List<String> operadorIds, List<String> tecnicoIds, boolean manter) {
        var req = new AvisoService.CriarAvisoRequest("PESSOAL", null, null, manter,
                List.of("Comparecer à reunião"), "PESSOAS", null, operadorIds, tecnicoIds, List.of(), null, true);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    private String criarGrupo(String alvoTipo) {
        var req = new AvisoService.CriarAvisoRequest("GERAL", null, null, false,
                List.of("Comunicado a todos"), alvoTipo, null, null, null, null, null, true);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    private String criarVerificacao(List<Integer> salaIds) {
        var req = new AvisoService.CriarAvisoRequest("VERIFICACAO", null, null, false,
                List.of("Conferir o microfone"), "SALA", salaIds, List.of(), List.of(), List.of(), null, null);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    private String criarEscala(List<Integer> salaIds, Long escalaId) {
        var req = new AvisoService.CriarAvisoRequest("ESCALA", null, null, false,
                List.of("Aviso da escala"), null, salaIds, List.of(), List.of(), List.of(), escalaId, true);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    /** Status do cadastro lido do banco, e não do cache: a desativação automática é um UPDATE nativo. */
    private StatusAviso status(String cadastroId) {
        emReal().flush();
        emReal().clear();
        return cadastroRepo.findById(cadastroId).orElseThrow().getStatus();
    }

    // ═══ Mensagem a pessoas nomeadas ════════════════════════════

    @Test
    @DisplayName("um destinatário: a ciência dele encerra a comunicação")
    void umDestinatario() {
        Operador op = CenarioFactory.novoOperador(emReal(), "Ana Unica");
        String id = criarPessoal(List.of(op.getId()), List.of(), false);
        assertEquals(StatusAviso.ATIVO, status(id));

        service.registrarCiencia(id, null, op.getId(), PapelPessoa.OPERADOR);

        assertEquals(StatusAviso.DESATIVADO, status(id));
    }

    @Test
    @DisplayName("vários destinatários: só a última ciência encerra — as anteriores deixam a comunicação de pé")
    void variosDestinatarios() {
        Operador op1 = CenarioFactory.novoOperador(emReal(), "Bruno Primeiro");
        Operador op2 = CenarioFactory.novoOperador(emReal(), "Carla Segunda");
        Tecnico tec = CenarioFactory.novoTecnico(emReal(), "Dario Terceiro");
        String id = criarPessoal(List.of(op1.getId(), op2.getId()), List.of(tec.getId()), false);

        service.registrarCiencia(id, null, op1.getId(), PapelPessoa.OPERADOR);
        assertEquals(StatusAviso.ATIVO, status(id));

        service.registrarCiencia(id, null, tec.getId(), PapelPessoa.TECNICO);
        assertEquals(StatusAviso.ATIVO, status(id));

        service.registrarCiencia(id, null, op2.getId(), PapelPessoa.OPERADOR);
        assertEquals(StatusAviso.DESATIVADO, status(id));
    }

    @Test
    @DisplayName("\"manter após ciência\": a comunicação continua ativa mesmo com todos cientes")
    void manterAposCienciaNaoEncerra() {
        Operador op = CenarioFactory.novoOperador(emReal(), "Elisa Mantida");
        String id = criarPessoal(List.of(op.getId()), List.of(), true);

        service.registrarCiencia(id, null, op.getId(), PapelPessoa.OPERADOR);

        assertEquals(StatusAviso.ATIVO, status(id));
    }

    // ═══ Comunicado a um grupo ══════════════════════════════════

    @Test
    @DisplayName("grupo: encerra quando o público ATUAL inteiro deu ciência; quem entra antes da última conta")
    void grupoContraPublicoAtual() {
        Operador op1 = CenarioFactory.novoOperador(emReal(), "Fabio Grupo");
        Operador op2 = CenarioFactory.novoOperador(emReal(), "Gisela Grupo");
        String id = criarGrupo("TODOS_OPERADORES");

        service.registrarCiencia(id, null, op1.getId(), PapelPessoa.OPERADOR);
        assertEquals(StatusAviso.ATIVO, status(id));

        // Operador cadastrado depois do aviso entra na conta: ele também recebe o comunicado.
        Operador op3 = CenarioFactory.novoOperador(emReal(), "Heitor Novato");
        service.registrarCiencia(id, null, op2.getId(), PapelPessoa.OPERADOR);
        assertEquals(StatusAviso.ATIVO, status(id));

        service.registrarCiencia(id, null, op3.getId(), PapelPessoa.OPERADOR);
        assertEquals(StatusAviso.DESATIVADO, status(id));
    }

    // ═══ Verificação de sala: fora da regra ═════════════════════

    @Test
    @DisplayName("verificação de sala: a ciência de todas as salas não desativa — uma pessoa responde pela sala inteira")
    void verificacaoNaoEncerraPorCiencia() {
        Sala s1 = CenarioFactory.novaSala(emReal(), "SALA_CIENCIA_A");
        Sala s2 = CenarioFactory.novaSala(emReal(), "SALA_CIENCIA_B");
        Operador op = CenarioFactory.novoOperador(emReal(), "Iara Verificadora");
        String id = criarVerificacao(List.of(s1.getId(), s2.getId()));

        service.registrarCiencia(id, s1.getId(), op.getId(), PapelPessoa.OPERADOR);
        assertEquals(StatusAviso.ATIVO, status(id));

        service.registrarCiencia(id, s2.getId(), op.getId(), PapelPessoa.OPERADOR);
        assertEquals(StatusAviso.ATIVO, status(id));
    }

    // ═══ Escala: fora da regra ══════════════════════════════════

    @Test
    @DisplayName("escala: quem a encerra é a data — a ciência de todos os escalados não desativa")
    void escalaNaoEncerraPorCiencia() {
        Sala sala = CenarioFactory.novaSala(emReal(), "SALA_CIENCIA_ESC");
        Operador op = CenarioFactory.novoOperador(emReal(), "Joana Escalada");
        EscalaSemanal escala = new EscalaSemanal();
        escala.setDataInicio(LocalDate.now().minusDays(1));
        escala.setDataFim(LocalDate.now().plusDays(5));
        escala.setCriadoPor("adm.it");
        emReal().persist(escala);
        EscalaOperador vinculo = new EscalaOperador();
        vinculo.setEscalaId(escala.getId());
        vinculo.setSalaId(sala.getId());
        vinculo.setOperadorId(op.getId());
        vinculo.setTurno("M");
        emReal().persist(vinculo);
        emReal().flush();

        String id = criarEscala(List.of(sala.getId()), escala.getId());
        service.registrarCiencia(id, null, op.getId(), PapelPessoa.OPERADOR);

        assertEquals(StatusAviso.ATIVO, status(id));
    }

    // ═══ Comunicação sem ciência ════════════════════════════════

    @Test
    @DisplayName("sem ciência exigida: o visto de todos não encerra a comunicação")
    void vistoNaoEncerra() {
        Operador op = CenarioFactory.novoOperador(emReal(), "Karla Vista");
        var req = new AvisoService.CriarAvisoRequest("PESSOAL", null, null, false,
                List.of("Só para ler"), "PESSOAS", null, List.of(op.getId()), List.of(), List.of(), null, false);
        String id = (String) service.criar(req, admin.getId()).get("id");

        service.registrarVisto(id, op.getId(), PapelPessoa.OPERADOR);

        assertEquals(StatusAviso.ATIVO, status(id));
    }
}
