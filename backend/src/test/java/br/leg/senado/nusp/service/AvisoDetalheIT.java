package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import br.leg.senado.nusp.enums.TipoAviso;
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
 * IT dos campos NOVOS do {@code obterDetalhe} que não são exclusivos de escala/agenda/pessoal
 * (esses vivem no {@code AvisoEscalaIT}/{@code AvisoAgendaPessoalIT}): a coluna "Local" da
 * Verificação (sala na ciência, §5.c), o fallback de rótulo do legado sem subtipo (§5.a) e a
 * garantia de que o payload só GANHOU campos — nenhum preexistente sumiu. Contra Oracle real
 * porque o detalhe cruza cadastro + alvos + ciências reais.
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
        var req = new AvisoService.CriarAvisoRequest("VERIFICACAO", true, null, false,
                List.of(mensagens), "SALA", salaIds, List.of(), List.of(), List.of(), null);
        return (String) service.criar(req, admin.getId()).get("id");
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
    @DisplayName("Verificação: a ciência é por sala → 'cientes' traz sala_id/sala_nome (coluna Local); sem subtipo, tipo_tabela = 'Verificação'; sem destinatários")
    void verificacaoSalaNaCiencia() {
        Sala sala = CenarioFactory.novaSala(emReal(), "Plenario02");
        Operador op = CenarioFactory.novoOperador(emReal());
        String id = criarVerificacao(List.of(sala.getId()), "Confira a sala");
        darCienciaComSala(id, sala.getId(), op.getId());

        Map<String, Object> det = service.obterDetalhe(id);
        assertNull(det.get("subtipo"), "Verificação não tem subtipo");
        assertEquals("Verificação", det.get("tipo_tabela"), "fallback no label do tipo");
        assertNull(det.get("destinatarios"), "Verificação não tem bloco de destinatários (público aberto)");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cientes = (List<Map<String, Object>>) det.get("cientes");
        assertEquals(1, cientes.size());
        assertEquals(sala.getId(), cientes.get(0).get("sala_id"));
        assertEquals(sala.getNome(), cientes.get(0).get("sala_nome"));
        assertEquals("Operador", cientes.get(0).get("papel"));
    }

    @Test
    @DisplayName("legado PESSOAL sem subtipo: subtipo nulo e tipo_tabela = 'Pessoal' (fallback no label do tipo — nunca quebra)")
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
        assertEquals("Pessoal", det.get("tipo_tabela"));
    }

    @Test
    @DisplayName("o payload do detalhe só GANHOU campos: as chaves preexistentes continuam todas presentes")
    void contratoAditivoPreservaCamposExistentes() {
        Sala sala = CenarioFactory.novaSala(emReal(), "Plenario02");
        String id = criarVerificacao(List.of(sala.getId()), "Confira a sala");
        Map<String, Object> det = service.obterDetalhe(id);
        for (String chave : List.of("id", "numero", "tipo", "tipo_label", "permanente", "duracao_dias",
                "manter_apos_ciencia", "status", "criado_em", "expira_em", "criado_por", "mensagens",
                "alvos", "cientes")) {
            assertTrue(det.containsKey(chave), "campo preexistente ausente: " + chave);
        }
    }
}
