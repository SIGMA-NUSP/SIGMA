package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
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
    private PontoFolhaLinhaRepository folhaLinhaRepo;

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
        service = new PontoService(loteRepo, paginaRepo, folhaLinhaRepo, operadorRepo, tecnicoRepo,
                administradorRepo,
                avisoService, saldoAberturaService, mock(RetificacaoService.class),
                new PessoaCadastroLookup(operadorRepo, tecnicoRepo, administradorRepo));
        // Diretório inexistente de propósito: a extração do BANCO falha com WARN e não aborta nada.
        ReflectionTestUtils.setField(service, "filesDir", "/tmp/nusp-test-files-inexistente");
        admin = CenarioFactory.novoAdministrador(emReal());
        operador = CenarioFactory.novoOperador(emReal());
    }

    /** Lote com UMA página vinculada ao operador do cenário (mensal prévia, salvo escolha). */
    private PontoLote loteDoOperador(String tipo, LocalDate inicio, LocalDate fim) {
        return comPaginaDoOperador(CenarioFactory.novoLotePonto(emReal(), tipo, inicio, fim, admin));
    }

    /** Como o anterior, com a natureza da folha mensal escolhida (a semanal não tem categoria). */
    private PontoLote loteDoOperador(String tipo, String categoria, LocalDate inicio, LocalDate fim) {
        return comPaginaDoOperador(CenarioFactory.novoLotePonto(emReal(), tipo, categoria, inicio, fim, admin));
    }

    private PontoLote comPaginaDoOperador(PontoLote lote) {
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
    @DisplayName("2ª folha MENSAL da pessoa no mesmo mês: o 1º passo conta a substituição e não publica")
    void segundaMensalDoMesmoMesSubstitui() {
        PontoLote junho = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        service.publicar(junho.getId(), false, false);
        assertEquals("PUBLICADO", statusNoBanco(junho));

        PontoLote junhoDeNovo = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        Map<String, Object> pedido = service.publicar(junhoDeNovo.getId(), true, false);

        assertEquals(Boolean.TRUE, pedido.get("requer_confirmacao"));
        assertEquals(1, pedido.get("substituicoes"), "uma pessoa tem folha do mês para ser substituída");
        assertEquals("REVISAO", statusNoBanco(junhoDeNovo), "o 1º passo não publica nada");
        verifyNoInteractions(avisoService);

        service.publicar(junhoDeNovo.getId(), false, true);

        assertEquals("PUBLICADO", statusNoBanco(junhoDeNovo), "confirmada, a folha nova entra");
        assertEquals("PUBLICADO", statusNoBanco(junho), "a substituída continua no histórico do admin");
    }

    @Test
    @DisplayName("SEMANAL atrasada de mês já ocupado pela MENSAL publicada: 400 e lote intacto (REVISAO)")
    void semanalDeMesFechadoRecusada() {
        service.publicar(loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).getId(), false, false);

        PontoLote semanalAtrasada = loteDoOperador("SEMANAL", LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.publicar(semanalAtrasada.getId(), true, false));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(ex.getMessage(), mensagemDoAdmin(ex), "a frase tem de estar nos dois campos do erro");
        assertTrue(mensagemDoAdmin(ex).contains("Já existe folha prévia de Junho/2026 publicada."),
                mensagemDoAdmin(ex));
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
                () -> service.publicar(lote.getId(), true, false));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        // Uma página não substitui a outra: aqui o nome fica, porque é ele que diz qual vínculo desfazer.
        assertTrue(mensagemDoAdmin(ex).contains("mais de uma folha"), mensagemDoAdmin(ex));
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
        service.publicar(junhoDoColega.getId(), false, false);

        PontoLote junhoDoOperador = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        service.publicar(junhoDoOperador.getId(), false, false);

        assertEquals("PUBLICADO", statusNoBanco(junhoDoOperador));
    }

    @Test
    @DisplayName("SEMANAIS cumulativas do mesmo mês (01–05, 01–12) publicam: sobrepor período é o normal")
    void semanaisCumulativasPublicam() {
        service.publicar(loteDoOperador("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5)).getId(), false, false);
        PontoLote ate12 = loteDoOperador("SEMANAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12));

        service.publicar(ate12.getId(), false, false);

        assertEquals("PUBLICADO", statusNoBanco(ate12),
                "a 2ª semanal reengloba os dias da 1ª — é assim que as folhas semanais funcionam");
    }

    @Test
    @DisplayName("a MENSAL de junho não impede a MENSAL de julho da mesma pessoa")
    void mensalDeOutroMesPublica() {
        service.publicar(loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).getId(), false, false);
        PontoLote julho = loteDoOperador("MENSAL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        service.publicar(julho.getId(), false, false);

        assertEquals("PUBLICADO", statusNoBanco(julho));
    }

    @Test
    @DisplayName("MENSAL de mês ainda aberto publica normalmente (a guarda não barra a 1ª folha)")
    void primeiraMensalPublica() {
        PontoLote junho = loteDoOperador("MENSAL", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        service.publicar(junho.getId(), false, false);

        assertEquals("PUBLICADO", statusNoBanco(junho));
        List<Object[]> pessoasComFolha = paginaRepo.findPessoasComFolhaPublicada();
        assertTrue(pessoasComFolha.stream().anyMatch(par -> operador.getId().equals(par[0])),
                "a folha publicada tem de aparecer como folha da pessoa");
    }

    // ══════════════════════════════════════════════════════════════
    // A matriz da substituição contra o banco real
    // ══════════════════════════════════════════════════════════════

    /**
     * O que NÃO passa, por [natureza já publicada no mês, tipo do lote novo, natureza dele, natureza
     * que a recusa precisa citar]. Sobraram duas famílias: a semanal atrasada de mês que uma mensal
     * já ocupa (seja ela prévia ou definitiva) e a prévia tentando entrar depois da definitiva.
     */
    private static Stream<Arguments> combinacoesRecusadas() {
        return Stream.of(
                Arguments.of(PontoLote.CATEGORIA_PREVIA, "SEMANAL", null, "prévia"),
                Arguments.of(PontoLote.CATEGORIA_DEFINITIVA, "SEMANAL", null, "definitiva"),
                Arguments.of(PontoLote.CATEGORIA_DEFINITIVA, "MENSAL", PontoLote.CATEGORIA_PREVIA, "definitiva"));
    }

    @ParameterizedTest(name = "{1} {2} contra mensal {0} publicada")
    @MethodSource("combinacoesRecusadas")
    @DisplayName("matriz da recusa: 400 dizendo a competência ocupada e a natureza que a ocupa")
    void matrizDeRecusaPorNatureza(String jaPublicada, String tipoNovo, String categoriaNova, String natureza) {
        service.publicar(loteDoOperador("MENSAL", jaPublicada,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).getId(), false, false);

        // A semanal atrasada cai DENTRO do mês já ocupado; a mensal disputa a competência inteira.
        PontoLote candidato = "SEMANAL".equals(tipoNovo)
                ? loteDoOperador(tipoNovo, categoriaNova, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28))
                : loteDoOperador(tipoNovo, categoriaNova, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.publicar(candidato.getId(), true, false));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        String msg = mensagemDoAdmin(ex);
        assertTrue(msg.contains("Já existe folha " + natureza + " de Junho/2026 publicada."),
                () -> "a recusa tem de dizer a natureza do que ocupa o mês (" + natureza + "): " + msg);
        assertFalse(msg.contains(operador.getNomeCompleto()),
                () -> "e não nomeia ninguém: a ação do admin é descartar o lote: " + msg);
        assertEquals("REVISAO", statusNoBanco(candidato), "o lote recusado não pode ter sido publicado");
        verifyNoInteractions(avisoService);
    }

    /** O que SUBSTITUI, por [natureza já publicada no mês, natureza do lote mensal novo]. */
    private static Stream<Arguments> combinacoesSubstituidas() {
        return Stream.of(
                Arguments.of(PontoLote.CATEGORIA_PREVIA, PontoLote.CATEGORIA_PREVIA),
                Arguments.of(PontoLote.CATEGORIA_PREVIA, PontoLote.CATEGORIA_DEFINITIVA),
                Arguments.of(PontoLote.CATEGORIA_DEFINITIVA, PontoLote.CATEGORIA_DEFINITIVA));
    }

    @ParameterizedTest(name = "mensal {1} sobre mensal {0} publicada")
    @MethodSource("combinacoesSubstituidas")
    @DisplayName("matriz da substituição: o 1º passo conta a pessoa, o 2º publica e a antiga fica no histórico")
    void matrizDeSubstituicaoPorNatureza(String jaPublicada, String categoriaNova) {
        PontoLote publicada = loteDoOperador("MENSAL", jaPublicada,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        service.publicar(publicada.getId(), false, false);

        PontoLote candidato = loteDoOperador("MENSAL", categoriaNova,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        Map<String, Object> pedido = service.publicar(candidato.getId(), true, false);
        assertEquals(Boolean.TRUE, pedido.get("requer_confirmacao"));
        assertEquals(1, pedido.get("substituicoes"));
        assertEquals("REVISAO", statusNoBanco(candidato));
        verifyNoInteractions(avisoService);

        service.publicar(candidato.getId(), false, true);

        assertEquals("PUBLICADO", statusNoBanco(candidato));
        assertEquals("PUBLICADO", statusNoBanco(publicada),
                "a folha substituída não é apagada nem despublicada: ela só some da vista do dono");
    }

    @Test
    @DisplayName("SEMANAL do período EXATAMENTE igual substitui; a de período diferente convive")
    void semanalSobreSemanalDoMesmoPeriodo() {
        LocalDate inicio = LocalDate.of(2026, 6, 1);
        LocalDate fim = LocalDate.of(2026, 6, 5);
        service.publicar(loteDoOperador("SEMANAL", inicio, fim).getId(), false, false);

        PontoLote outroPeriodo = loteDoOperador("SEMANAL", inicio, LocalDate.of(2026, 6, 12));
        service.publicar(outroPeriodo.getId(), false, false);
        assertEquals("PUBLICADO", statusNoBanco(outroPeriodo), "01–12 reengloba 01–05, e as duas convivem");

        PontoLote mesmoPeriodo = loteDoOperador("SEMANAL", inicio, fim);

        Map<String, Object> pedido = service.publicar(mesmoPeriodo.getId(), true, false);

        assertEquals(Boolean.TRUE, pedido.get("requer_confirmacao"), () -> "período idêntico substitui: " + pedido);
        assertEquals(1, pedido.get("substituicoes"));
        assertEquals("REVISAO", statusNoBanco(mesmoPeriodo));
    }

    @Test
    @DisplayName("MENSAL definitiva publica por cima da prévia do mesmo mês: é assim que o mês fecha")
    void definitivaEntraPorCimaDaPrevia() {
        PontoLote previa = loteDoOperador("MENSAL", PontoLote.CATEGORIA_PREVIA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        service.publicar(previa.getId(), false, false);

        PontoLote definitiva = loteDoOperador("MENSAL", PontoLote.CATEGORIA_DEFINITIVA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        service.publicar(definitiva.getId(), false, true);

        assertEquals("PUBLICADO", statusNoBanco(definitiva));
        assertEquals("PUBLICADO", statusNoBanco(previa),
                "a prévia não é apagada nem despublicada: ela só some da vista do dono");
        assertEquals(1L, paginaRepo.contarDefinitivasPublicadas(operador.getId(), "OPERADOR",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                "com a definitiva publicada, o mês da pessoa passa a contar como fechado");
    }

    @Test
    @DisplayName("contagem de definitivas: acha pela competência do mês e só pelo par pessoa+tipo")
    void contagemDeDefinitivasPorCompetenciaEPessoa() {
        YearMonth julho = YearMonth.of(2026, 7);
        YearMonth agosto = YearMonth.of(2026, 8);
        Tecnico tecnico = CenarioFactory.novoTecnico(emReal());

        PontoLote definitivaJulho = CenarioFactory.novoLotePontoPublicado(emReal(), "MENSAL",
                PontoLote.CATEGORIA_DEFINITIVA, julho.atDay(1), julho.atEndOfMonth(), admin);
        CenarioFactory.novaPaginaLote(emReal(), definitivaJulho, 1, operador.getId(), "OPERADOR");
        CenarioFactory.novaPaginaLote(emReal(), definitivaJulho, 2, tecnico.getId(), "TECNICO");

        // Prévia publicada do MESMO mês: ela abre o mês, não fecha — não pode somar na contagem.
        PontoLote previaJulho = CenarioFactory.novoLotePontoPublicado(emReal(), "MENSAL",
                PontoLote.CATEGORIA_PREVIA, julho.atDay(1), julho.atEndOfMonth(), admin);
        CenarioFactory.novaPaginaLote(emReal(), previaJulho, 1, operador.getId(), "OPERADOR");

        // Definitiva de agosto ainda em revisão: mês só fecha quando a folha é publicada.
        PontoLote agostoEmRevisao = CenarioFactory.novoLotePonto(emReal(), "MENSAL",
                PontoLote.CATEGORIA_DEFINITIVA, agosto.atDay(1), agosto.atEndOfMonth(), admin);
        CenarioFactory.novaPaginaLote(emReal(), agostoEmRevisao, 1, operador.getId(), "OPERADOR");

        assertEquals(1L, definitivasNoMes(operador.getId(), "OPERADOR", julho),
                "só a definitiva conta — a prévia do mesmo mês fica de fora");
        assertEquals(0L, definitivasNoMes(operador.getId(), "OPERADOR", YearMonth.of(2026, 6)),
                "a folha de julho não alcança a competência de junho");
        assertEquals(0L, definitivasNoMes(operador.getId(), "OPERADOR", agosto),
                "lote em revisão não fecha mês nenhum");
        assertEquals(0L, definitivasNoMes(operador.getId(), "TECNICO", julho),
                "o tipo integra a chave da pessoa: o mesmo id com outro tipo é outra pessoa");
        assertEquals(1L, definitivasNoMes(tecnico.getId(), "TECNICO", julho),
                "o técnico vinculado à mesma folha tem o próprio mês fechado");
        assertEquals(0L, definitivasNoMes(tecnico.getId(), "OPERADOR", julho));
    }

    /** A pergunta do mês fechado como a produção a faz: a janela é a competência inteira. */
    private long definitivasNoMes(String pessoaId, String pessoaTipo, YearMonth mes) {
        return paginaRepo.contarDefinitivasPublicadas(pessoaId, pessoaTipo, mes.atDay(1), mes.atEndOfMonth());
    }
}
