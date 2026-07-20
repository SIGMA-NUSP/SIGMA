package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.SubtipoAviso;
import br.leg.senado.nusp.enums.TipoAviso;
import br.leg.senado.nusp.exception.ServiceValidationException;
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
 * IT do aviso de AGENDA e do card "Pessoal" (modos "Um grupo" e "Pessoas específicas") contra Oracle
 * real — o que o mock não alcança: o "visto" persistente da agenda filtrado no servidor (reusa
 * FRM_AVISO_CIENCIA), os rótulos da coluna "Tipo de Aviso" derivados do SUBTIPO via CASE, o status
 * "—" do AGENDA na listagem (com as facetas do GROUPING SETS vigiadas) e a visibilidade dos alvos
 * coletivos/mistos. A gravação de ciência/visto é semeada direto (o REQUIRES_NEW do writer commitaria
 * e furaria o rollback do teste — mesmo idioma do AvisoEscalaIT).
 */
@OracleIT
class AvisoAgendaPessoalIT {

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

    // ═══ Fábricas via a via real (service.criar) ════════════════

    private String criarAgenda(String... mensagens) {
        var req = new AvisoService.CriarAvisoRequest("AGENDA", null, null, null,
                List.of(mensagens), null, null, null, null, null, null);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    private String criarGrupo(String alvoTipo, String... mensagens) {
        var req = new AvisoService.CriarAvisoRequest("GERAL", null, null, null,
                List.of(mensagens), alvoTipo, null, null, null, null, null);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    private String criarPessoas(List<String> operadorIds, List<String> tecnicoIds, List<String> adminIds,
                                boolean manter, String... mensagens) {
        var req = new AvisoService.CriarAvisoRequest("PESSOAL", null, null, manter,
                List.of(mensagens), "PESSOAS", null, operadorIds, tecnicoIds, adminIds, null);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    /** Semeia um registro de ciência/visto (sem sala) direto no banco — sem o REQUIRES_NEW do writer. */
    private void darCiencia(String cadastroId, PapelPessoa papel, String pessoaId) {
        AvisoCiencia c = new AvisoCiencia();
        c.setCadastroId(cadastroId);
        switch (papel) {
            case OPERADOR -> c.setOperadorId(pessoaId);
            case TECNICO -> c.setTecnicoId(pessoaId);
            case ADMIN -> c.setAdminId(pessoaId);
        }
        c.setCienteEm(LocalDateTime.now());
        emReal().persist(c);
        emReal().flush();
    }

    private boolean vePendente(String pessoaId, PapelPessoa papel, TipoAviso tipo, String cadastroId) {
        return service.buscarPendentes(pessoaId, papel, List.of(tipo)).stream()
                .anyMatch(m -> cadastroId.equals(m.get("cadastro_id")));
    }

    private Map<String, Object> linhaListagem(String cadastroId) {
        return service.listarTodosPaginado(1, 100, "", "data", "desc", null).data().stream()
                .filter(m -> cadastroId.equals(m.get("id")))
                .findFirst().orElseThrow();
    }

    // ═══ AGENDA ═════════════════════════════════════════════════

    @Nested
    @DisplayName("AGENDA — alvo TODOS forçado, visto persistente por usuário, status '—'")
    class Agenda {

        @Test
        @DisplayName("criar: grava tipo/subtipo AGENDA, permanente sem expira, e 1 alvo coletivo TODOS")
        void criarShape() {
            String id = criarAgenda("Confira a agenda de hoje");

            var cad = cadastroRepo.findById(id).orElseThrow();
            assertEquals(TipoAviso.AGENDA, cad.getTipo());
            assertEquals(SubtipoAviso.AGENDA, cad.getSubtipo());
            assertTrue(cad.getPermanente());
            assertNull(cad.getExpiraEm());
            assertNull(cad.getDuracaoDias());

            var alvos = alvoRepo.findByCadastroId(id);
            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.TODOS, alvos.get(0).getAlvoTipo());
            assertNull(alvos.get(0).getSalaId());
        }

        @Test
        @DisplayName("operadores e técnicos veem (alvo TODOS); administradores não")
        void quemVe() {
            Operador op = CenarioFactory.novoOperador(emReal());
            Tecnico tec = CenarioFactory.novoTecnico(emReal());
            String id = criarAgenda("x");

            assertTrue(vePendente(op.getId(), PapelPessoa.OPERADOR, TipoAviso.AGENDA, id));
            assertTrue(vePendente(tec.getId(), PapelPessoa.TECNICO, TipoAviso.AGENDA, id));
            assertFalse(vePendente(admin.getId(), PapelPessoa.ADMIN, TipoAviso.AGENDA, id),
                    "TODOS não atinge administradores");
        }

        @Test
        @DisplayName("aparece no contexto 'agenda' e NÃO no contexto 'geral' (telas comuns)")
        void soNoContextoAgenda() {
            Operador op = CenarioFactory.novoOperador(emReal());
            String id = criarAgenda("x");

            boolean noGeral = service.buscarPendentes(op.getId(), PapelPessoa.OPERADOR, "geral").stream()
                    .anyMatch(m -> id.equals(m.get("cadastro_id")));
            boolean naAgenda = service.buscarPendentes(op.getId(), PapelPessoa.OPERADOR, "agenda").stream()
                    .anyMatch(m -> id.equals(m.get("cadastro_id")));
            assertFalse(noGeral, "AGENDA não entra nas telas comuns");
            assertTrue(naAgenda, "AGENDA entra nas telas de agenda");
        }

        @Test
        @DisplayName("visto: quem já viu não vê de novo (em qualquer login); outro usuário segue vendo")
        void vistoImpedeReaparecer() {
            Operador a = CenarioFactory.novoOperador(emReal());
            Operador b = CenarioFactory.novoOperador(emReal());
            String id = criarAgenda("x");
            assertTrue(vePendente(a.getId(), PapelPessoa.OPERADOR, TipoAviso.AGENDA, id));
            assertTrue(vePendente(b.getId(), PapelPessoa.OPERADOR, TipoAviso.AGENDA, id));

            darCiencia(id, PapelPessoa.OPERADOR, a.getId());   // "visto" de A

            assertFalse(vePendente(a.getId(), PapelPessoa.OPERADOR, TipoAviso.AGENDA, id), "A já viu");
            assertTrue(vePendente(b.getId(), PapelPessoa.OPERADOR, TipoAviso.AGENDA, id), "B ainda não viu");
        }

        @Test
        @DisplayName("listagem: tipo 'Agenda', status '—' quando ativo, 'Desativado' quando desativado")
        void listagemStatus() {
            String id = criarAgenda("x");
            var linha = linhaListagem(id);
            assertEquals("Agenda", linha.get("tipo"));
            assertEquals("—", linha.get("status"));

            service.desativar(id);
            assertEquals("Desativado", linhaListagem(id).get("status"));
        }

        @Test
        @DisplayName("detalhe: os vistos aparecem em 'exibido_para'; 'cientes' fica vazio (AGENDA não tem ciência)")
        void detalheExibidoPara() {
            Operador a = CenarioFactory.novoOperador(emReal());
            String id = criarAgenda("x");
            darCiencia(id, PapelPessoa.OPERADOR, a.getId());

            Map<String, Object> det = service.obterDetalhe(id);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> exibido = (List<Map<String, Object>>) det.get("exibido_para");
            assertEquals(1, exibido.size());
            assertEquals("Operador", exibido.get(0).get("papel"));
            assertTrue(((List<?>) det.get("cientes")).isEmpty(), "AGENDA não exige ciência");
        }

        @Test
        @DisplayName("registrarVisto rejeita tipo que não é AGENDA (ex.: um grupo GERAL)")
        void vistoSoAgenda() {
            String idGeral = criarGrupo("TODOS_OPERADORES", "x");
            Operador op = CenarioFactory.novoOperador(emReal());

            var ex = assertThrows(ServiceValidationException.class,
                    () -> service.registrarVisto(idGeral, op.getId(), PapelPessoa.OPERADOR));
            assertEquals("Este tipo de aviso não registra visualização.", ex.getMessage());
        }
    }

    // ═══ Card Pessoal — modo "Um grupo" (GERAL) ════════════════

    @Nested
    @DisplayName("Grupo (GERAL) — subtipo GRUPO_*, sem ciência, rótulos da tabela por SUBTIPO")
    class Grupo {

        @Test
        @DisplayName("cada coletivo grava o subtipo GRUPO_* e 1 alvo coletivo correspondente")
        void subtipoEAlvo() {
            String id = criarGrupo("TODOS_OPERADORES", "Comunicado geral");
            var cad = cadastroRepo.findById(id).orElseThrow();
            assertEquals(TipoAviso.GERAL, cad.getTipo());
            assertEquals(SubtipoAviso.GRUPO_OPERADORES, cad.getSubtipo());

            var alvos = alvoRepo.findByCadastroId(id);
            assertEquals(1, alvos.size());
            assertEquals(AlvoTipoAviso.TODOS_OPERADORES, alvos.get(0).getAlvoTipo());
            assertNull(alvos.get(0).getOperadorId());
        }

        @Test
        @DisplayName("GERAL não exige ciência: sempre retorna para o operador enquanto ativo (dispensa por sessão no front)")
        void semCienciaSempreRetorna() {
            Operador op = CenarioFactory.novoOperador(emReal());
            String id = criarGrupo("TODOS_OPERADORES", "x");
            assertTrue(vePendente(op.getId(), PapelPessoa.OPERADOR, TipoAviso.GERAL, id));
            // Mesmo com um registro na tabela de ciência, GERAL continua retornando (não filtra por visto).
            darCiencia(id, PapelPessoa.OPERADOR, op.getId());
            assertTrue(vePendente(op.getId(), PapelPessoa.OPERADOR, TipoAviso.GERAL, id));
        }

        @Test
        @DisplayName("rótulos da coluna 'Tipo de Aviso' vêm do SUBTIPO (§2), um por coletivo")
        void rotulosPorSubtipo() {
            assertEquals("Operadores", linhaListagem(criarGrupo("TODOS_OPERADORES", "x")).get("tipo"));
            assertEquals("Técnicos", linhaListagem(criarGrupo("TODOS_TECNICOS", "x")).get("tipo"));
            assertEquals("Operadores e Técnicos", linhaListagem(criarGrupo("TODOS", "x")).get("tipo"));
            assertEquals("Administradores", linhaListagem(criarGrupo("TODOS_ADMIN", "x")).get("tipo"));
        }
    }

    // ═══ Card Pessoal — modo "Pessoas específicas" (PESSOAL) ═══

    @Nested
    @DisplayName("Pessoas específicas (PESSOAL) — alvos mistos op/téc/adm, com ciência")
    class Pessoas {

        @Test
        @DisplayName("cadastro misto: um alvo por pessoa (operador, técnico e administrador), subtipo PESSOAL")
        void cadastroMisto() {
            Operador op = CenarioFactory.novoOperador(emReal());
            Tecnico tec = CenarioFactory.novoTecnico(emReal());
            Administrador adm2 = CenarioFactory.novoAdministrador(emReal());

            String id = criarPessoas(List.of(op.getId()), List.of(tec.getId()), List.of(adm2.getId()),
                    false, "Aviso pessoal");

            var cad = cadastroRepo.findById(id).orElseThrow();
            assertEquals(TipoAviso.PESSOAL, cad.getTipo());
            assertEquals(SubtipoAviso.PESSOAL, cad.getSubtipo());
            assertEquals(3, alvoRepo.findByCadastroId(id).size());

            assertTrue(vePendente(op.getId(), PapelPessoa.OPERADOR, TipoAviso.PESSOAL, id));
            assertTrue(vePendente(tec.getId(), PapelPessoa.TECNICO, TipoAviso.PESSOAL, id));
            assertTrue(vePendente(adm2.getId(), PapelPessoa.ADMIN, TipoAviso.PESSOAL, id));
        }

        @Test
        @DisplayName("administrador é destinatário e a ciência dele (sem sala) tira o aviso da sua lista")
        void adminDaCiencia() {
            Administrador adm2 = CenarioFactory.novoAdministrador(emReal());
            String id = criarPessoas(List.of(), List.of(), List.of(adm2.getId()), false, "Aviso ao admin");
            assertTrue(vePendente(adm2.getId(), PapelPessoa.ADMIN, TipoAviso.PESSOAL, id));

            darCiencia(id, PapelPessoa.ADMIN, adm2.getId());
            assertFalse(vePendente(adm2.getId(), PapelPessoa.ADMIN, TipoAviso.PESSOAL, id),
                    "após a ciência (sem manter), some para o admin");
        }

        @Test
        @DisplayName("listagem: o cadastro do modo pessoas exibe 'Pessoal' na coluna de tipo")
        void rotuloPessoal() {
            Operador op = CenarioFactory.novoOperador(emReal());
            String id = criarPessoas(List.of(op.getId()), List.of(), List.of(), false, "x");
            assertEquals("Pessoal", linhaListagem(id).get("tipo"));
        }
    }
}
