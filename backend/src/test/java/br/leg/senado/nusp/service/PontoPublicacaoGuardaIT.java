package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import jakarta.persistence.EntityManager;

/**
 * IT da guarda da folha MENSAL na publicação, contra Oracle real: a regra depende de uma consulta
 * que cruza PNT_LOTE_PAGINA com PNT_LOTE (status PUBLICADO + tipo MENSAL + janela de competência),
 * e é o banco — não o mock — que diz se ela casa as folhas certas.
 *
 * <p>Service construído à mão: repositórios REAIS — são eles que a guarda usa — e mocks nos
 * colaboradores que a publicação apenas dispara depois (aviso, re-âncora), que aqui servem de
 * sensor: num lote recusado eles não podem ser tocados. Cada teste semeia o próprio grafo e o
 * rollback do {@code @DataJpaTest} limpa; o PDF das páginas não existe em disco (BANCO_FINAL_MIN
 * fica nulo, com WARN — a publicação nunca aborta por isso).
 */
@OracleIT
class PontoPublicacaoGuardaIT {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PontoLoteRepository loteRepo;

    @Autowired
    private PontoLotePaginaRepository paginaRepo;

    @Autowired
    private OperadorRepository operadorRepo;

    @Autowired
    private TecnicoRepository tecnicoRepo;

    @Autowired
    private AdministradorRepository administradorRepo;

    private final AvisoService avisoService = mock(AvisoService.class);
    private final SaldoAberturaService saldoAberturaService = mock(SaldoAberturaService.class);

    private PontoService service;
    private Administrador admin;
    private Operador operador;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        // O lookup de existência é montado sobre os MESMOS repositórios reais: a checagem do
        // vínculo continua batendo no Oracle, não num mock.
        service = new PontoService(loteRepo, paginaRepo, operadorRepo, tecnicoRepo, administradorRepo,
                avisoService, saldoAberturaService, mock(RetificacaoService.class),
                new PessoaCadastroLookup(operadorRepo, tecnicoRepo, administradorRepo));
        // Diretório inexistente de propósito: a extração do BANCO falha com WARN e não aborta nada.
        ReflectionTestUtils.setField(service, "filesDir", "/tmp/nusp-test-files-inexistente");
        admin = CenarioFactory.novoAdministrador(emReal());
        operador = CenarioFactory.novoOperador(emReal());
    }

    /** Lote com UMA página vinculada ao operador do cenário. */
    private PontoLote loteDoOperador(String tipo, LocalDate inicio, LocalDate fim) {
        PontoLote lote = CenarioFactory.novoLotePonto(emReal(), tipo, inicio, fim, admin);
        CenarioFactory.novaPaginaLote(emReal(), lote, 1, operador.getId(), "OPERADOR");
        return lote;
    }

    /** Relê o status direto do banco (o cache de 1º nível mentiria sobre o que foi gravado). */
    private String statusNoBanco(PontoLote lote) {
        emReal().flush();
        emReal().clear();
        return loteRepo.findById(lote.getId()).orElseThrow().getStatus();
    }

    private static String mensagemDoAdmin(ServiceValidationException ex) {
        return String.valueOf(ex.getExtraFields().get("message"));
    }

    @Test
    @DisplayName("2ª folha MENSAL da pessoa no mesmo mês: 400 nomeando a pessoa e lote intacto (REVISAO)")
    void segundaMensalDoMesmoMesRecusada() {
        PontoLote junho = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        service.publicar(junho.getId(), false);
        assertEquals("PUBLICADO", statusNoBanco(junho));

        PontoLote junhoDeNovo = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.publicar(junhoDeNovo.getId(), true));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        String msg = mensagemDoAdmin(ex);
        assertEquals(ex.getMessage(), msg, "a frase tem de estar nos dois campos do erro");
        assertTrue(msg.contains(operador.getNomeCompleto()),
                () -> "a recusa precisa nomear quem está em conflito (é por ele que o admin acha a página): " + msg);
        assertTrue(msg.contains("06/2026"), () -> "e dizer a competência já ocupada: " + msg);

        assertEquals("REVISAO", statusNoBanco(junhoDeNovo), "o lote recusado não pode ter sido publicado");
        verifyNoInteractions(avisoService);
    }

    @Test
    @DisplayName("SEMANAL atrasada de mês já fechado pela MENSAL publicada: 400 e lote intacto (REVISAO)")
    void semanalDeMesFechadoRecusada() {
        service.publicar(loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).getId(), false);

        PontoLote semanalAtrasada = loteDoOperador("SEMANAL", LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.publicar(semanalAtrasada.getId(), true));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(mensagemDoAdmin(ex).contains("já foi fechado por folha mensal publicada"), mensagemDoAdmin(ex));
        assertTrue(mensagemDoAdmin(ex).contains("06/2026"), mensagemDoAdmin(ex));
        assertEquals("REVISAO", statusNoBanco(semanalAtrasada));
        verifyNoInteractions(avisoService);
    }

    @Test
    @DisplayName("duas folhas MENSAIS da mesma pessoa DENTRO do próprio lote: 400 e lote intacto (REVISAO)")
    void mensalDuplicadaNoProprioLoteRecusada() {
        PontoLote lote = CenarioFactory.novoLotePonto(emReal(), "MENSAL",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin);
        CenarioFactory.novaPaginaLote(emReal(), lote, 1, operador.getId(), "OPERADOR");
        CenarioFactory.novaPaginaLote(emReal(), lote, 2, operador.getId(), "OPERADOR");

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.publicar(lote.getId(), true));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(mensagemDoAdmin(ex).contains("mais de uma folha mensal"), mensagemDoAdmin(ex));
        assertTrue(mensagemDoAdmin(ex).contains(operador.getNomeCompleto()), mensagemDoAdmin(ex));
        assertEquals("REVISAO", statusNoBanco(lote), "o lote recusado não pode ter sido publicado");
        verifyNoInteractions(avisoService);
    }

    @Test
    @DisplayName("a MENSAL de OUTRA pessoa não fecha o mês de quem não tem folha")
    void mensalDeOutraPessoaNaoFechaOMesDosDemais() {
        Operador colega = CenarioFactory.novoOperador(emReal(), "Colega Sem Conflito");
        PontoLote junhoDoColega = CenarioFactory.novoLotePonto(emReal(), "MENSAL",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), admin);
        CenarioFactory.novaPaginaLote(emReal(), junhoDoColega, 1, colega.getId(), "OPERADOR");
        service.publicar(junhoDoColega.getId(), false);

        PontoLote junhoDoOperador = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        service.publicar(junhoDoOperador.getId(), false);

        assertEquals("PUBLICADO", statusNoBanco(junhoDoOperador));
    }

    @Test
    @DisplayName("SEMANAIS cumulativas do mesmo mês (01–05, 01–12) publicam: sobrepor período é o normal")
    void semanaisCumulativasPublicam() {
        service.publicar(loteDoOperador("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5)).getId(), false);
        PontoLote ate12 = loteDoOperador("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12));

        service.publicar(ate12.getId(), false);

        assertEquals("PUBLICADO", statusNoBanco(ate12),
                "a 2ª semanal reengloba os dias da 1ª — é assim que as folhas semanais funcionam");
    }

    @Test
    @DisplayName("a MENSAL de junho não impede a MENSAL de julho da mesma pessoa")
    void mensalDeOutroMesPublica() {
        service.publicar(loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).getId(), false);
        PontoLote julho = loteDoOperador("MENSAL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        service.publicar(julho.getId(), false);

        assertEquals("PUBLICADO", statusNoBanco(julho));
    }

    @Test
    @DisplayName("MENSAL de mês ainda aberto publica normalmente (a guarda não barra a 1ª folha)")
    void primeiraMensalPublica() {
        PontoLote junho = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        service.publicar(junho.getId(), false);

        assertEquals("PUBLICADO", statusNoBanco(junho));
        List<Object[]> pessoasComFolha = paginaRepo.findPessoasComFolhaPublicada();
        assertTrue(pessoasComFolha.stream().anyMatch(par -> operador.getId().equals(par[0])),
                "a folha publicada tem de aparecer como folha da pessoa");
    }
}
