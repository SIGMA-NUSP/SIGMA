package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
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
import org.springframework.http.HttpStatus;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.EscalaOperador;
import br.leg.senado.nusp.entity.EscalaSemanal;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusAviso;
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
import br.leg.senado.nusp.repository.EscalaFuncaoRepository;
import br.leg.senado.nusp.repository.EscalaOperadorRepository;
import br.leg.senado.nusp.repository.EscalaSemanalRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import jakarta.persistence.EntityManager;

/**
 * IT do aviso de ESCALA contra Oracle real — o que nenhum mock alcança: a visibilidade é DERIVADA
 * (cruza o cadastro com OPR_ESCALA_OPERADOR e a janela DATA_INICIO..DATA_FIM da escala), o status é
 * CALCULADO das datas (Pendente/Ativo/Expirado), o "Expira em" vem do DATA_FIM da escala e a
 * exclusão da escala leva o cadastro por cascade do banco. Datas relativas a hoje (SYSDATE do
 * container = BRT, igual ao pin da JVM de teste).
 */
@OracleIT
class AvisoEscalaIT {

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
    @Autowired private EscalaFuncaoRepository escalaFuncaoRepo;

    private static final LocalDate HOJE = LocalDate.now();

    private AvisoService service;
    private EscalaSemanalService escalaService;
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
        escalaService = new EscalaSemanalService(escalaRepo, escalaOpRepo, escalaFuncaoRepo,
                salaRepo, operadorRepo, adminRepo, service);
        admin = CenarioFactory.novoAdministrador(emReal());
        vigiaDeFacetas.instalar();
    }

    @AfterEach
    void nenhumWarnDeFaceta() {
        vigiaDeFacetas.exigirZeroWarns();
    }

    // ═══ Seed helpers ═══════════════════════════════════════════

    private EscalaSemanal novaEscala(LocalDate ini, LocalDate fim) {
        EscalaSemanal e = new EscalaSemanal();
        e.setDataInicio(ini);
        e.setDataFim(fim);
        e.setCriadoPor("adm.it");
        emReal().persist(e);
        emReal().flush();
        return e;
    }

    private void vincular(Long escalaId, Integer salaId, String operadorId, String turno) {
        EscalaOperador eo = new EscalaOperador();
        eo.setEscalaId(escalaId);
        eo.setSalaId(salaId);
        eo.setOperadorId(operadorId);
        eo.setTurno(turno);
        emReal().persist(eo);
        emReal().flush();
    }

    /** Cria o aviso de escala pela via real (service.criar) e devolve o id do cadastro. */
    private String criarAvisoEscala(Long escalaId, List<Integer> salaIds, boolean manter, String... mensagens) {
        var req = new AvisoService.CriarAvisoRequest("ESCALA", null, null, manter,
                List.of(mensagens), null, salaIds, List.of(), List.of(), List.of(), escalaId);
        return (String) service.criar(req, admin.getId()).get("id");
    }

    private void darCiencia(String cadastroId, String operadorId) {
        AvisoCiencia c = new AvisoCiencia();
        c.setCadastroId(cadastroId);
        c.setOperadorId(operadorId);
        c.setCienteEm(LocalDateTime.now());
        emReal().persist(c);
        emReal().flush();
    }

    private boolean vePendente(String operadorId, String cadastroId) {
        return service.buscarPendentes(operadorId, PapelPessoa.OPERADOR, List.of(TipoAviso.ESCALA)).stream()
                .anyMatch(m -> cadastroId.equals(m.get("cadastro_id")));
    }

    // ═══ Criação / trava ════════════════════════════════════════

    @Nested
    @DisplayName("criar (ESCALA) — vínculo, alvo SALA nos plenários e trava 1-por-escala")
    class CriarEscala {

        @Test
        @DisplayName("escalaId nulo é rejeitado")
        void semEscalaId() {
            var req = new AvisoService.CriarAvisoRequest("ESCALA", null, null, false,
                    List.of("Confira sua escala"), null, List.of(1), List.of(), List.of(), List.of(), null);
            var ex = assertThrows(ServiceValidationException.class, () -> service.criar(req, admin.getId()));
            assertEquals("Selecione a escala do aviso.", ex.getMessage());
        }

        @Test
        @DisplayName("escala inexistente responde 404")
        void escalaInexistente() {
            var req = new AvisoService.CriarAvisoRequest("ESCALA", null, null, false,
                    List.of("x"), null, List.of(1), List.of(), List.of(), List.of(), 999999L);
            var ex = assertThrows(ServiceValidationException.class, () -> service.criar(req, admin.getId()));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        }

        @Test
        @DisplayName("plenário fora da escala é rejeitado (só plenários com operador vinculado entram)")
        void salaForaDaEscala() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Sala outro = CenarioFactory.novaSala(emReal(), "Plenario99");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE, HOJE.plusDays(4));
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");

            var req = new AvisoService.CriarAvisoRequest("ESCALA", null, null, false,
                    List.of("x"), null, List.of(outro.getId()), List.of(), List.of(), List.of(), esc.getId());
            var ex = assertThrows(ServiceValidationException.class, () -> service.criar(req, admin.getId()));
            assertTrue(ex.getMessage().contains("fora da escala"), ex.getMessage());
        }

        @Test
        @DisplayName("escala sem operador em nenhum plenário é rejeitada")
        void escalaSemPlenarios() {
            EscalaSemanal esc = novaEscala(HOJE, HOJE.plusDays(4));
            var req = new AvisoService.CriarAvisoRequest("ESCALA", null, null, false,
                    List.of("x"), null, List.of(1), List.of(), List.of(), List.of(), esc.getId());
            var ex = assertThrows(ServiceValidationException.class, () -> service.criar(req, admin.getId()));
            assertTrue(ex.getMessage().contains("nenhum plenário"), ex.getMessage());
        }

        @Test
        @DisplayName("sucesso: grava tipo ESCALA, subtipo ESCALA, escala_id, permanente sem expira, e alvos SALA")
        void sucessoShape() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE, HOJE.plusDays(4));
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");

            String id = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), true, "Confira sua escala");

            var cad = cadastroRepo.findById(id).orElseThrow();
            assertEquals(TipoAviso.ESCALA, cad.getTipo());
            assertEquals(SubtipoAviso.ESCALA, cad.getSubtipo());
            assertEquals(esc.getId(), cad.getEscalaId());
            assertTrue(cad.getPermanente());
            assertNull(cad.getExpiraEm());
            assertNull(cad.getDuracaoDias());
            assertTrue(cad.getManterAposCiencia());
            assertEquals(StatusAviso.ATIVO, cad.getStatus());

            var alvos = alvoRepo.findByCadastroId(id);
            assertEquals(1, alvos.size());
            assertEquals(pleno.getId(), alvos.get(0).getSalaId());
        }

        @Test
        @DisplayName("segundo cadastro na mesma escala é rejeitado enquanto o primeiro não é desativado")
        void travaUmPorEscala() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE, HOJE.plusDays(4));
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");

            criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Primeiro");

            var req = new AvisoService.CriarAvisoRequest("ESCALA", null, null, false,
                    List.of("Segundo"), null, List.of(pleno.getId()), List.of(), List.of(), List.of(), esc.getId());
            var ex = assertThrows(ServiceValidationException.class, () -> service.criar(req, admin.getId()));
            assertTrue(ex.getMessage().contains("já possui um aviso"), ex.getMessage());
        }

        @Test
        @DisplayName("desativado libera a escala para um novo cadastro")
        void desativadoLibera() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE, HOJE.plusDays(4));
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");

            String primeiro = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Primeiro");
            service.desativar(primeiro);

            // Agora a escala está livre — o segundo cadastro passa.
            String segundo = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Segundo");
            assertNotNull(segundo);
        }
    }

    // ═══ Visibilidade dinâmica (quem vê e quando) ═══════════════

    @Nested
    @DisplayName("buscarPendentes (ESCALA) — destinatário e janela derivados da escala")
    class VisibilidadeDinamica {

        @Test
        @DisplayName("escala vigente: o operador vinculado ao plenário vê; um não-vinculado não vê")
        void vigenteSoQuemEstaNaEscala() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador escalado = CenarioFactory.novoOperador(emReal());
            Operador deFora = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE.minusDays(1), HOJE.plusDays(1));
            vincular(esc.getId(), pleno.getId(), escalado.getId(), "M");

            String id = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Confira sua escala");

            assertTrue(vePendente(escalado.getId(), id));
            assertFalse(vePendente(deFora.getId(), id));
        }

        @Test
        @DisplayName("escala futura: ninguém vê antes do DATA_INICIO")
        void futuraNinguemVe() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador escalado = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE.plusDays(2), HOJE.plusDays(6));
            vincular(esc.getId(), pleno.getId(), escalado.getId(), "M");

            String id = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Confira sua escala");

            assertFalse(vePendente(escalado.getId(), id));
        }

        @Test
        @DisplayName("escala encerrada: o aviso some depois do DATA_FIM")
        void passadaSome() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador escalado = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE.minusDays(6), HOJE.minusDays(2));
            vincular(esc.getId(), pleno.getId(), escalado.getId(), "M");

            String id = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Confira sua escala");

            assertFalse(vePendente(escalado.getId(), id));
        }

        @Test
        @DisplayName("troca de operador na escala: o novo passa a ver, o antigo deixa de ver — sem tocar no aviso")
        void trocaDeOperador() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador antigo = CenarioFactory.novoOperador(emReal());
            Operador novo = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE.minusDays(1), HOJE.plusDays(1));
            vincular(esc.getId(), pleno.getId(), antigo.getId(), "M");

            String id = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Confira sua escala");
            assertTrue(vePendente(antigo.getId(), id));

            // Troca: remove o antigo, entra o novo (o aviso não é tocado).
            escalaOpRepo.deleteByEscalaId(esc.getId());
            emReal().flush();
            vincular(esc.getId(), pleno.getId(), novo.getId(), "M");

            assertTrue(vePendente(novo.getId(), id));
            assertFalse(vePendente(antigo.getId(), id));
        }

        @Test
        @DisplayName("técnico nunca vê aviso de escala, mesmo com escala vigente")
        void tecnicoNaoVe() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador escalado = CenarioFactory.novoOperador(emReal());
            Tecnico tec = CenarioFactory.novoTecnico(emReal());
            EscalaSemanal esc = novaEscala(HOJE.minusDays(1), HOJE.plusDays(1));
            vincular(esc.getId(), pleno.getId(), escalado.getId(), "M");

            criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Confira sua escala");

            assertTrue(service.buscarPendentes(tec.getId(), PapelPessoa.TECNICO,
                    List.of(TipoAviso.ESCALA, TipoAviso.PESSOAL, TipoAviso.GERAL)).isEmpty());
        }

        @Test
        @DisplayName("ciência sem 'manter' tira o aviso da pessoa; com 'manter' ele reaparece")
        void cienciaEManter() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE.minusDays(1), HOJE.plusDays(1));
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");

            String semManter = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Sem manter");
            darCiencia(semManter, op.getId());
            assertFalse(vePendente(op.getId(), semManter), "sem manter: some após a ciência");

            // Nova escala/plenário para um cadastro COM manter (a trava é 1-por-escala).
            Sala pleno2 = CenarioFactory.novaSala(emReal(), "Plenario03");
            EscalaSemanal esc2 = novaEscala(HOJE.minusDays(1), HOJE.plusDays(1));
            vincular(esc2.getId(), pleno2.getId(), op.getId(), "M");
            String comManter = criarAvisoEscala(esc2.getId(), List.of(pleno2.getId()), true, "Com manter");
            darCiencia(comManter, op.getId());
            assertTrue(vePendente(op.getId(), comManter), "com manter: reaparece mesmo após a ciência");
        }
    }

    // ═══ Listagem: status e "expira" calculados ═════════════════

    @Nested
    @DisplayName("listagem admin — status calculado das datas e 'Expira em' = DATA_FIM da escala")
    class ListagemStatusExpira {

        private Map<String, Object> linhaDe(String cadastroId) {
            return service.listarTodosPaginado(1, 100, "", "data", "desc", null).data().stream()
                    .filter(m -> cadastroId.equals(m.get("id")))
                    .findFirst().orElseThrow();
        }

        private String semearAviso(LocalDate ini, LocalDate fim) {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(ini, fim);
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");
            return criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Confira sua escala");
        }

        @Test
        @DisplayName("escala futura → status Pendente; expira em = DATA_FIM")
        void pendente() {
            LocalDate fim = HOJE.plusDays(6);
            var linha = linhaDe(semearAviso(HOJE.plusDays(2), fim));
            assertEquals("Pendente", linha.get("status"));
            assertTrue(String.valueOf(linha.get("expira_em")).startsWith(fim.toString()),
                    "expira_em = " + linha.get("expira_em"));
        }

        @Test
        @DisplayName("escala vigente → status Ativo")
        void ativo() {
            var linha = linhaDe(semearAviso(HOJE.minusDays(1), HOJE.plusDays(1)));
            assertEquals("Ativo", linha.get("status"));
        }

        @Test
        @DisplayName("escala encerrada → status Expirado")
        void expirado() {
            var linha = linhaDe(semearAviso(HOJE.minusDays(6), HOJE.minusDays(2)));
            assertEquals("Expirado", linha.get("status"));
        }

        @Test
        @DisplayName("desativado gravado prevalece sobre o cálculo (mesmo com escala vigente)")
        void desativadoPrevalece() {
            String id = semearAviso(HOJE.minusDays(1), HOJE.plusDays(1));
            service.desativar(id);
            assertEquals("Desativado", linhaDe(id).get("status"));
        }
    }

    // ═══ Exclusão da escala leva o cadastro (F59) ═══════════════

    @Nested
    @DisplayName("EscalaSemanalService.excluirEscala — leva o aviso vinculado (mensagens/alvos/ciências)")
    class ExclusaoLevaAviso {

        @Test
        @DisplayName("excluir a escala apaga fisicamente o cadastro do aviso e seus filhos")
        void exclusaoProfunda() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE.minusDays(1), HOJE.plusDays(1));
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");

            String id = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Msg 1", "Msg 2");
            darCiencia(id, op.getId());
            assertTrue(cadastroRepo.findById(id).isPresent());

            escalaService.excluirEscala(esc.getId());
            emReal().flush();
            emReal().clear();

            assertTrue(cadastroRepo.findById(id).isEmpty(), "o cadastro morre com a escala");
            assertTrue(mensagemRepo.findByCadastroIdOrderByOrdem(id).isEmpty(), "mensagens somem (cascade)");
            assertTrue(alvoRepo.findByCadastroId(id).isEmpty(), "alvos somem (cascade)");
            assertTrue(escalaRepo.findById(esc.getId()).isEmpty(), "a escala também sai");
        }
    }

    // ═══ Endpoint de apoio ao painel ════════════════════════════

    @Nested
    @DisplayName("escalasDisponiveis — atual+futuras, ocupação e plenários para o painel")
    class EscalasDisponiveis {

        @Test
        @DisplayName("lista atual e futuras (não as passadas), com plenários vinculados e a ocupação do cadastro")
        void listaComOcupacaoEPlenarios() {
            Sala pleno2 = CenarioFactory.novaSala(emReal(), "Plenario02");
            Sala pleno3 = CenarioFactory.novaSala(emReal(), "Plenario03");
            Operador op = CenarioFactory.novoOperador(emReal());

            EscalaSemanal atual = novaEscala(HOJE.minusDays(1), HOJE.plusDays(1));
            vincular(atual.getId(), pleno2.getId(), op.getId(), "M");
            vincular(atual.getId(), pleno3.getId(), op.getId(), "V");
            EscalaSemanal futura = novaEscala(HOJE.plusDays(3), HOJE.plusDays(7));
            vincular(futura.getId(), pleno2.getId(), op.getId(), "M");
            EscalaSemanal passada = novaEscala(HOJE.minusDays(9), HOJE.minusDays(5));
            vincular(passada.getId(), pleno2.getId(), op.getId(), "M");

            criarAvisoEscala(atual.getId(), List.of(pleno2.getId()), false, "Confira sua escala");

            var lista = service.escalasDisponiveis();
            var ids = lista.stream().map(m -> m.get("id")).toList();
            assertTrue(ids.contains(atual.getId()));
            assertTrue(ids.contains(futura.getId()));
            assertFalse(ids.contains(passada.getId()), "escala encerrada não entra no painel");

            var mAtual = lista.stream().filter(m -> atual.getId().equals(m.get("id"))).findFirst().orElseThrow();
            assertNotNull(mAtual.get("cadastro_numero"), "escala ocupada → número do cadastro que a trava");
            @SuppressWarnings("unchecked")
            var plenarios = (List<Map<String, Object>>) mAtual.get("plenarios");
            assertEquals(2, plenarios.size(), "os 2 plenários vinculados à escala");

            var mFutura = lista.stream().filter(m -> futura.getId().equals(m.get("id"))).findFirst().orElseThrow();
            assertNull(mFutura.get("cadastro_numero"), "escala livre → sem número de cadastro");
        }
    }

    // ═══ Detalhe: bloco 'escala' (período + plenários) ══════════

    @Nested
    @DisplayName("obterDetalhe (ESCALA) — bloco 'escala' com período e plenários, para a futura tela de detalhe")
    class Detalhe {

        @Test
        @DisplayName("detalhe traz o período da escala e os plenários selecionados; 'cientes' lista quem deu ciência")
        void detalheComBlocoEscala() {
            Sala pleno = CenarioFactory.novaSala(emReal(), "Plenario02");
            Operador op = CenarioFactory.novoOperador(emReal());
            EscalaSemanal esc = novaEscala(HOJE.minusDays(1), HOJE.plusDays(3));
            vincular(esc.getId(), pleno.getId(), op.getId(), "M");

            String id = criarAvisoEscala(esc.getId(), List.of(pleno.getId()), false, "Confira sua escala");
            darCiencia(id, op.getId());

            Map<String, Object> det = service.obterDetalhe(id);
            @SuppressWarnings("unchecked")
            Map<String, Object> escala = (Map<String, Object>) det.get("escala");
            assertNotNull(escala, "aviso de ESCALA traz o bloco 'escala'");
            assertEquals(esc.getId(), escala.get("id"));
            assertEquals(esc.getDataInicio().toString(), escala.get("data_inicio"));
            assertEquals(esc.getDataFim().toString(), escala.get("data_fim"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> plenarios = (List<Map<String, Object>>) escala.get("plenarios");
            assertEquals(1, plenarios.size());
            assertEquals(pleno.getId(), plenarios.get(0).get("sala_id"));

            // ESCALA exige ciência → 'cientes' lista quem deu ciência (o operador escalado).
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cientes = (List<Map<String, Object>>) det.get("cientes");
            assertEquals(1, cientes.size());
        }
    }
}
