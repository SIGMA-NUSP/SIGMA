package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.enums.TipoDiaMarcacao;
import br.leg.senado.nusp.enums.TipoPessoaMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unitários do {@link BancoHorasService} com o relógio INJETADO (T26a): o SUT lê o
 * "hoje" de um {@link Clock#fixed} em data controlada, e não do dia da execução.
 * Consequências diretas (§2.4 do plano): os 4 skips condicionais sumiram — nenhum
 * caso pode mais ser PULADO em silêncio porque o calendário do dia não colaborou —
 * e as bordas antes inalcançáveis (fim de mês, virada de ano) viraram testes.
 *
 * <p>A zona do clock é EXPLÍCITA ({@code America/Sao_Paulo}) — nenhum teste pode
 * depender da zona da máquina. O F7 (o "hoje" escorregando para o dia seguinte entre
 * 21h e 00h BRT) foi CURADO no runtime pelo C17: os containers passaram a declarar
 * {@code TZ=America/Sao_Paulo} e o {@link br.leg.senado.nusp.config.ClockConfig}
 * (systemDefaultZone) resolve para BRT. O teste {@link #caracterizaF7_hojeSegueAZonaDoClock()}
 * segue aqui como demonstração AGNÓSTICA do mecanismo (o "hoje" acompanha a zona do Clock
 * injetado) — é justamente por isso que fixar a zona, via TZ, resolve o transversal.
 */
@ExtendWith(MockitoExtension.class)
class BancoHorasServiceTest {

    @Mock private EntityManager em;
    @Mock private PontoBancoSaldoRepository saldoRepo;
    @Mock private PontoSolicitacaoFolgaRepository solicitacaoRepo;
    @Mock private PontoDiaMarcacaoRepository diaMarcacaoRepo;
    @Mock private PontoPessoaMarcacaoRepository pessoaMarcacaoRepo;
    @Mock private SaldoAberturaService saldoAberturaService;
    @Mock private AvisoService avisoService;
    @Mock private OperadorRepository operadorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private AdministradorRepository administradorRepo;

    private BancoHorasService service;

    private static final String OP = "op-1";
    private static final String ROLE = "operador";
    private static final String ADMIN = "adm-1";

    /** Zona explícita: o "hoje" do SUT não pode depender da zona da máquina (gotcha 13). */
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    /** Quarta-feira, meio do mês: sobram dias úteis futuros em julho (16, 17, 20, 21, …). */
    private static final LocalDate HOJE = LocalDate.of(2026, 7, 15);

    /** Instante canônico dos testes — o que {@code LocalDateTime.now(clock)} devolve. */
    private static final LocalDateTime AGORA = HOJE.atTime(12, 0);

    // ── infra dos cenários ────────────────────────────────────────

    @BeforeEach
    void setUp() {
        service = criarService(clockEm(HOJE));
    }

    /** Construção manual (sem {@code @InjectMocks}): é ela que permite variar o Clock por teste. */
    private BancoHorasService criarService(Clock clock) {
        return new BancoHorasService(em, saldoRepo, solicitacaoRepo, diaMarcacaoRepo, pessoaMarcacaoRepo,
                saldoAberturaService, avisoService, operadorRepo, tecnicoRepo, administradorRepo, clock);
    }

    /** Clock parado ao meio-dia do {@code dia}, na zona de Brasília. */
    private static Clock clockEm(LocalDate dia) {
        return Clock.fixed(dia.atTime(12, 0).atZone(ZONA).toInstant(), ZONA);
    }

    private void mockCarga(Integer carga) {
        Operador o = new Operador();
        o.setCargaHoraria(carga);
        when(operadorRepo.findById(OP)).thenReturn(Optional.of(o));
    }

    private PontoBancoSaldo saldoDe(int min, LocalDate ancora) {
        PontoBancoSaldo s = new PontoBancoSaldo();
        s.setPessoaId(OP);
        s.setPessoaTipo("OPERADOR");
        s.setSaldoAberturaMin(min);
        s.setSaldoBancoMin(min);
        s.setAncoraData(ancora);
        return s;
    }

    private PontoSolicitacaoFolga solicitacao(LocalDate dia, StatusSolicitacaoFolga status) {
        return solicitacao(OP, "OPERADOR", dia, status);
    }

    private PontoSolicitacaoFolga solicitacao(String pessoaId, String pessoaTipo,
                                              LocalDate dia, StatusSolicitacaoFolga status) {
        PontoSolicitacaoFolga s = new PontoSolicitacaoFolga();
        s.setPessoaId(pessoaId);
        s.setPessoaTipo(pessoaTipo);
        s.setDataFolga(dia);
        s.setMinutosDebitados(480);
        s.setStatus(status);
        return s;
    }

    private static Administrador admin(Boolean servidorPublico) {
        Administrador a = new Administrador();
        a.setServidorPublico(servidorPublico);
        return a;
    }

    /** Admin COM folha de ponto (SERVIDOR_PUBLICO=0 usa os mesmos endpoints do funcionário). */
    private static Administrador adminComCarga(int carga) {
        Administrador a = new Administrador();
        a.setServidorPublico(false);
        a.setCargaHoraria(carga);
        return a;
    }

    private Map<String, Object> bodyDias(String... dias) {
        Map<String, Object> m = new HashMap<>();
        m.put("dias", List.of((Object[]) dias));
        return m;
    }

    /** Próximos n dias ÚTEIS estritamente futuros do mês do {@code hoje} do clock. */
    private static List<LocalDate> diasUteisFuturosNoMes(LocalDate hoje, int n) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = hoje.plusDays(1); d.getMonth() == hoje.getMonth() && out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) out.add(d);
        }
        return out;
    }

    /** Dias úteis futuros do mês canônico (16, 17, 20, 21/07/2026 — o clock fixo garante que existem). */
    private static List<LocalDate> uteis(int n) {
        List<LocalDate> dias = diasUteisFuturosNoMes(HOJE, n);
        assertEquals(n, dias.size(), "fixture do clock fixo: julho/2026 tem " + n + " dias úteis após 15/07");
        return dias;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bloqueados(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("dias_bloqueados");
    }

    private static String motivoDe(Map<String, Object> out, LocalDate dia) {
        return bloqueados(out).stream()
                .filter(b -> dia.toString().equals(b.get("data")))
                .map(b -> (String) b.get("motivo"))
                .findFirst().orElse(null);
    }

    // ── formatarSaldo (Q23) ───────────────────────────────────────

    @Test
    @DisplayName("formatarSaldo: ±HH:MM com sinal explícito e zero positivo")
    void formatarSaldo() {
        assertEquals("+26:10", BancoHorasService.formatarSaldo(1570));
        assertEquals("-02:03", BancoHorasService.formatarSaldo(-123));
        assertEquals("+00:00", BancoHorasService.formatarSaldo(0));
    }

    // ── GET /api/ponto/banco ──────────────────────────────────────

    @Test
    @DisplayName("consultar: CARGA_HORARIA NULL → 409 com a mensagem da Gestão de Pessoas (Q17)")
    void consultarCargaNula() {
        mockCarga(null);
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.consultar(OP, ROLE, HOJE.getYear(), HOJE.getMonthValue()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("Gestão de Pessoas"));
    }

    @Test
    @DisplayName("consultar: carga fora de {30, 40} → 409 (nunca assumir jornada)")
    void consultarCargaInvalida() {
        mockCarga(20);
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.consultar(OP, ROLE, HOJE.getYear(), HOJE.getMonthValue()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("consultar: role desconhecido → 403")
    void consultarRoleInvalido() {
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.consultar(OP, "master", 2026, 7));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("consultar mês corrente: saldo do cache, folgas só APROVADAS (Q13), hoje bloqueado")
    void consultarMesCorrente() {
        mockCarga(40);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR"))
                .thenReturn(Optional.of(saldoDe(480, HOJE.minusDays(30))));
        when(solicitacaoRepo.findPorStatusNoRange(eq(OP), eq("OPERADOR"), anyCollection(), any(), any()))
                .thenReturn(List.of(
                        solicitacao(HOJE.withDayOfMonth(1), StatusSolicitacaoFolga.APROVADO),
                        solicitacao(HOJE.withDayOfMonth(2), StatusSolicitacaoFolga.PENDENTE)));

        Map<String, Object> out = service.consultar(OP, ROLE, HOJE.getYear(), HOJE.getMonthValue());

        assertEquals(480, out.get("saldo_min"));
        assertEquals("+08:00", out.get("saldo_fmt"));
        assertFalse(out.containsKey("sem_folha_oficial"));   // chave ausente ≠ chave false
        assertEquals(40, out.get("carga_horaria"));
        assertEquals(1L, out.get("folgas_mes"));
        assertEquals("Dia já transcorrido", motivoDe(out, HOJE));   // passado/hoje (Q12)
    }

    @Test
    @DisplayName("consultar: sem linha de saldo → saldo 0 e sem_folha_oficial")
    void consultarSemFolha() {
        mockCarga(30);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());

        Map<String, Object> out = service.consultar(OP, ROLE, HOJE.getYear(), HOJE.getMonthValue());

        assertEquals(0, out.get("saldo_min"));
        assertEquals("+00:00", out.get("saldo_fmt"));
        assertEquals(Boolean.TRUE, out.get("sem_folha_oficial"));
        assertEquals(30, out.get("carga_horaria"));
    }

    @Test
    @DisplayName("consultar mês ≠ corrente: mês inteiro bloqueado como fora do mês (Q12)")
    void consultarMesNaoCorrente() {
        mockCarga(40);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
        YearMonth anterior = YearMonth.from(HOJE).minusMonths(1);   // junho/2026

        Map<String, Object> out = service.consultar(OP, ROLE, anterior.getYear(), anterior.getMonthValue());

        List<Map<String, Object>> dias = bloqueados(out);
        assertEquals(anterior.lengthOfMonth(), dias.size());
        assertTrue(dias.stream().allMatch(b -> "Fora do mês corrente".equals(b.get("motivo"))));
    }

    @Test
    @DisplayName("consultar: marcação global/pessoal e pedido vivo bloqueiam com o rótulo certo (Q12/F#4)")
    void consultarBloqueiosDoMes() {
        List<LocalDate> dias = uteis(4);   // 16, 17, 20, 21/07 — com o clock fixo, sempre existem
        mockCarga(40);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());

        PontoDiaMarcacao feriado = new PontoDiaMarcacao();
        feriado.setData(dias.get(0));
        feriado.setTipo(TipoDiaMarcacao.FERIADO);
        when(diaMarcacaoRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(any(), any()))
                .thenReturn(List.of(feriado));

        PontoPessoaMarcacao ferias = new PontoPessoaMarcacao();
        ferias.setPessoaId(OP);
        ferias.setPessoaTipo("OPERADOR");
        ferias.setData(dias.get(1));
        ferias.setTipo(TipoPessoaMarcacao.FERIAS);
        when(pessoaMarcacaoRepo.findByPessoaIdAndPessoaTipoAndDataGreaterThanEqualAndDataLessThan(
                eq(OP), eq("OPERADOR"), any(), any())).thenReturn(List.of(ferias));

        when(solicitacaoRepo.findPorStatusNoRange(eq(OP), eq("OPERADOR"), anyCollection(), any(), any()))
                .thenReturn(List.of(
                        solicitacao(dias.get(2), StatusSolicitacaoFolga.PENDENTE),
                        solicitacao(dias.get(3), StatusSolicitacaoFolga.APROVADO)));

        Map<String, Object> out = service.consultar(OP, ROLE, HOJE.getYear(), HOJE.getMonthValue());

        assertEquals("Feriado", motivoDe(out, dias.get(0)));
        assertEquals("Férias", motivoDe(out, dias.get(1)));
        assertEquals("Solicitação pendente", motivoDe(out, dias.get(2)));
        assertEquals("Folga aprovada", motivoDe(out, dias.get(3)));
        // O sábado e o domingo entre os dias úteis acima: única asserção do ramo FDS no calendário.
        assertEquals("Fim de semana", motivoDe(out, LocalDate.of(2026, 7, 18)));
        assertEquals("Fim de semana", motivoDe(out, LocalDate.of(2026, 7, 19)));
    }

    @Test
    @DisplayName("consultar: linha de saldo SEM âncora → saldo do cache + sem_folha_oficial (pessoa nunca ancorada)")
    void consultarComSaldoSemAncora() {
        mockCarga(40);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR"))
                .thenReturn(Optional.of(saldoDe(120, null)));   // linha existe, ANCORA_DATA nula

        Map<String, Object> out = service.consultar(OP, ROLE, HOJE.getYear(), HOJE.getMonthValue());

        assertEquals(120, out.get("saldo_min"), "o saldo do cache vale mesmo sem folha oficial");
        assertEquals(Boolean.TRUE, out.get("sem_folha_oficial"),
                "sem folha oficial é 'não tem linha' OU 'tem linha sem âncora' — o 2º ramo da condição");
    }

    @Test
    @DisplayName("consultar: mesmo mês de OUTRO ano → mês inteiro fora do mês corrente (o ano conta, não só o mês)")
    void consultarMesmoMesDeOutroAno() {
        mockCarga(40);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());

        Map<String, Object> out = service.consultar(OP, ROLE, 2027, 7);   // julho de 2027, clock em julho/2026

        List<Map<String, Object>> dias = bloqueados(out);
        assertEquals(31, dias.size());
        assertTrue(dias.stream().allMatch(b -> "Fora do mês corrente".equals(b.get("motivo"))),
                "julho do ano que vem não é o mês corrente — sem o ano na comparação, o calendário o liberaria");
    }

    @Test
    @DisplayName("consultar como TÉCNICO: o dono resolve para PESSOA_TIPO=TECNICO em todas as leituras (gotcha 5)")
    void consultarComoTecnico() {
        Tecnico t = new Tecnico();
        t.setCargaHoraria(40);
        when(tecnicoRepo.findById("tec-1")).thenReturn(Optional.of(t));
        PontoBancoSaldo saldo = new PontoBancoSaldo();
        saldo.setPessoaId("tec-1");
        saldo.setPessoaTipo("TECNICO");
        saldo.setSaldoAberturaMin(300);
        saldo.setSaldoBancoMin(300);
        saldo.setAncoraData(HOJE.minusDays(10));
        when(saldoRepo.findByPessoaIdAndPessoaTipo("tec-1", "TECNICO")).thenReturn(Optional.of(saldo));

        Map<String, Object> out = service.consultar("tec-1", "tecnico", HOJE.getYear(), HOJE.getMonthValue());

        assertEquals(300, out.get("saldo_min"));
        verify(solicitacaoRepo).findPorStatusNoRange(eq("tec-1"), eq("TECNICO"), anyCollection(), any(), any());
        verifyNoInteractions(operadorRepo);   // o papel do principal escolhe a tabela, não o payload
    }

    @Test
    @DisplayName("consultar como ADMINISTRADOR com folha: o dono resolve para PESSOA_TIPO=ADMINISTRADOR")
    void consultarComoAdministrador() {
        when(administradorRepo.findById(ADMIN)).thenReturn(Optional.of(adminComCarga(30)));
        PontoBancoSaldo saldo = new PontoBancoSaldo();
        saldo.setPessoaId(ADMIN);
        saldo.setPessoaTipo("ADMINISTRADOR");
        saldo.setSaldoAberturaMin(90);
        saldo.setSaldoBancoMin(90);
        saldo.setAncoraData(HOJE.minusDays(5));
        when(saldoRepo.findByPessoaIdAndPessoaTipo(ADMIN, "ADMINISTRADOR")).thenReturn(Optional.of(saldo));

        Map<String, Object> out = service.consultar(ADMIN, "administrador", HOJE.getYear(), HOJE.getMonthValue());

        assertEquals(90, out.get("saldo_min"));
        assertEquals(30, out.get("carga_horaria"));
        verify(solicitacaoRepo).findPorStatusNoRange(eq(ADMIN), eq("ADMINISTRADOR"), anyCollection(), any(), any());
        verifyNoInteractions(operadorRepo, tecnicoRepo);
    }

    // ── POST /api/ponto/banco/solicitar ───────────────────────────

    @Test
    @DisplayName("solicitar: 2 dias úteis futuros → 2 PENDENTEs com débito congelado 480 (Q3) + cache 2× (Q10)")
    void solicitarSucesso() {
        List<LocalDate> dias = uteis(2);
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR"))
                .thenReturn(saldoDe(1000, null), saldoDe(40, null));

        Map<String, Object> out = service.solicitar(OP, ROLE,
                bodyDias(dias.get(0).toString(), dias.get(1).toString()));

        verify(solicitacaoRepo).saveAllAndFlush(argThat((List<PontoSolicitacaoFolga> novas) ->
                novas.size() == 2
                        && novas.stream().allMatch(s -> s.getMinutosDebitados() == 480
                        && s.getStatus() == StatusSolicitacaoFolga.PENDENTE
                        && OP.equals(s.getPessoaId()) && "OPERADOR".equals(s.getPessoaTipo()))));
        // O lock não basta existir: ele tem de vir ANTES do recálculo do saldo e do insert — sem isso,
        // dois pedidos simultâneos da mesma pessoa validariam o MESMO saldo vigente (Q10/gotcha 3).
        InOrder ordem = inOrder(saldoRepo, saldoAberturaService, solicitacaoRepo);
        ordem.verify(saldoRepo).lockPorPessoa(OP, "OPERADOR");
        ordem.verify(saldoAberturaService).reancorar(OP, "OPERADOR");
        ordem.verify(solicitacaoRepo).saveAllAndFlush(anyList());
        verify(saldoAberturaService, times(2)).reancorar(OP, "OPERADOR");
        assertEquals(40, out.get("saldo_min"));
        assertEquals("+00:40", out.get("saldo_fmt"));
        assertEquals(2, ((List<?>) out.get("criadas")).size());
    }

    @Test
    @DisplayName("solicitar com carga 30: débito congelado 360/dia (Q3) — 2 dias cabem em 800 min de saldo")
    void solicitarCarga30() {
        List<LocalDate> dias = uteis(2);
        mockCarga(30);
        // 800 discrimina as duas jornadas: 2×360 = 720 passa; 2×480 = 960 estouraria o saldo.
        when(saldoAberturaService.reancorar(OP, "OPERADOR"))
                .thenReturn(saldoDe(800, null), saldoDe(80, null));

        Map<String, Object> out = service.solicitar(OP, ROLE,
                bodyDias(dias.get(0).toString(), dias.get(1).toString()));

        verify(solicitacaoRepo).saveAllAndFlush(argThat((List<PontoSolicitacaoFolga> novas) ->
                novas.size() == 2 && novas.stream().allMatch(s -> s.getMinutosDebitados() == 360)));
        assertEquals(80, out.get("saldo_min"));
    }

    @Test
    @DisplayName("solicitar: sábado → 400 fim de semana (só dia útil é solicitável — Q12)")
    void solicitarFimDeSemana() {
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(1000, null));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias("2026-07-18")));   // sábado, futuro, mês corrente

        assertTrue(ex.getMessage().contains("Fim de semana"));
        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("solicitar: saldo insuficiente para a soma dos dias → 400 e nada salvo (Q10)")
    void solicitarSaldoInsuficiente() {
        List<LocalDate> dias = uteis(2);
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(700, null)); // < 960

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias(dias.get(0).toString(), dias.get(1).toString())));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("Saldo insuficiente"));
        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("solicitar: dia de outro mês → 400 fora do mês corrente (Q12)")
    void solicitarForaDoMes() {
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(1000, null));
        LocalDate outroMes = HOJE.plusMonths(1).withDayOfMonth(15);

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias(outroMes.toString())));
        assertTrue(ex.getMessage().contains("Fora do mês corrente"));
        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("solicitar: dia já transcorrido → 400 com o motivo (Q12)")
    void solicitarDiaPassado() {
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(1000, null));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias(HOJE.toString())));
        assertTrue(ex.getMessage().contains("Dia já transcorrido"));
        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("solicitar: lista vazia/ausente → 400")
    void solicitarSemDias() {
        assertThrows(ServiceValidationException.class, () -> service.solicitar(OP, ROLE, Map.of()));
        assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, Map.of("dias", List.of())));
        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("solicitar: data mal formatada → 400")
    void solicitarDataInvalida() {
        assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias("15/07/2026")));
        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("solicitar: dia repetido no payload → 400")
    void solicitarDiaRepetido() {
        String dia = HOJE.plusDays(1).toString();
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias(dia, dia)));
        assertTrue(ex.getMessage().contains("repetido"));
        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("solicitar: carga NULL → 409 sem tocar no saldo (Q17)")
    void solicitarCargaNula() {
        mockCarga(null);
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias(HOJE.plusDays(1).toString())));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(saldoAberturaService, never()).reancorar(any(), any());
    }

    @Test
    @DisplayName("solicitar: corrida de dia duplicado morre na FBI → 400 amigável (gotcha 3)")
    void solicitarCorridaFbi() {
        List<LocalDate> dias = uteis(1);
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(1000, null));
        when(solicitacaoRepo.saveAllAndFlush(anyList())).thenThrow(new DataIntegrityViolationException("FBI"));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.solicitar(OP, ROLE, bodyDias(dias.get(0).toString())));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("Já existe solicitação"));
    }

    // ── bordas de calendário (só alcançáveis com o Clock fixo — T26a) ──

    @Test
    @DisplayName("solicitar em 30/07 (penúltimo dia útil do mês): o último dia útil ainda é solicitável")
    void solicitarNoPenultimoDiaDoMes() {
        BancoHorasService fimDeMes = criarService(clockEm(LocalDate.of(2026, 7, 30)));   // quinta
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR"))
                .thenReturn(saldoDe(1000, null), saldoDe(520, null));

        Map<String, Object> out = fimDeMes.solicitar(OP, ROLE, bodyDias("2026-07-31"));   // sexta

        verify(solicitacaoRepo).saveAllAndFlush(argThat((List<PontoSolicitacaoFolga> novas) ->
                novas.size() == 1 && LocalDate.of(2026, 7, 31).equals(novas.get(0).getDataFolga())));
        assertEquals(1, ((List<?>) out.get("criadas")).size());
    }

    @Test
    @DisplayName("solicitar em 31/07 (último dia do mês): não há dia solicitável — hoje é passado e agosto é fora do mês")
    void solicitarNoUltimoDiaDoMes() {
        BancoHorasService ultimoDia = criarService(clockEm(LocalDate.of(2026, 7, 31)));
        mockCarga(40);
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(1000, null));

        ServiceValidationException hoje = assertThrows(ServiceValidationException.class,
                () -> ultimoDia.solicitar(OP, ROLE, bodyDias("2026-07-31")));
        assertTrue(hoje.getMessage().contains("Dia já transcorrido"));

        ServiceValidationException amanha = assertThrows(ServiceValidationException.class,
                () -> ultimoDia.solicitar(OP, ROLE, bodyDias("2026-08-03")));   // 1ª segunda de agosto
        assertTrue(amanha.getMessage().contains("Fora do mês corrente"),
                "no último dia do mês, o mês seguinte continua bloqueado (Q12) — a janela fecha");

        verify(solicitacaoRepo, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("virada de ano (31/12): dezembro inteiro bloqueado no consultar e janeiro/2027 fora do mês no solicitar")
    void bordaViradaDeAno() {
        BancoHorasService reveillon = criarService(clockEm(LocalDate.of(2026, 12, 31)));
        mockCarga(40);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(1000, null));

        Map<String, Object> dezembro = reveillon.consultar(OP, ROLE, 2026, 12);
        assertEquals(31, bloqueados(dezembro).size(),
                "no último dia do ano nenhum dia de dezembro sobra — todos já transcorreram");
        assertEquals("Dia já transcorrido", motivoDe(dezembro, LocalDate.of(2026, 12, 31)));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> reveillon.solicitar(OP, ROLE, bodyDias("2027-01-04")));   // 1ª segunda de 2027
        assertTrue(ex.getMessage().contains("Fora do mês corrente"),
                "o mês corrente é o do CLOCK — janeiro do ano seguinte não é solicitável em dezembro");
    }

    @Test
    @DisplayName("F7 (curado no runtime pelo C17) — 'hoje' segue a zona do Clock: o mesmo instante é 16/07 em UTC e 15/07 em BRT")
    void caracterizaF7_hojeSegueAZonaDoClock() {
        // F7 (§5 do plano): o "hoje" do service acompanha a zona do Clock. Enquanto os containers
        // rodaram em UTC, entre 21h e 00h BRT o "hoje" já era o dia seguinte, e um dia útil legítimo
        // aparecia como "transcorrido". O C17 curou isso declarando TZ=BRT nos containers (o bean é
        // systemDefaultZone() → resolve para BRT). Este teste é agnóstico: injeta o Clock nos dois
        // fusos e mostra o mecanismo — roda igual em qualquer ambiente.
        Instant instante = Instant.parse("2026-07-16T02:30:00Z");   // = 15/07 23:30 em Brasília
        LocalDate quintaFeira = LocalDate.of(2026, 7, 16);
        mockCarga(40);
        when(saldoRepo.findByPessoaIdAndPessoaTipo(OP, "OPERADOR")).thenReturn(Optional.empty());

        Map<String, Object> emUtc = criarService(Clock.fixed(instante, ZoneOffset.UTC))
                .consultar(OP, ROLE, 2026, 7);
        Map<String, Object> emBrt = criarService(Clock.fixed(instante, ZONA))
                .consultar(OP, ROLE, 2026, 7);

        assertEquals("Dia já transcorrido", motivoDe(emUtc, quintaFeira),
                "em UTC o 'hoje' já virou 16/07 — o dia útil de amanhã (BRT) nasce bloqueado");
        assertNull(motivoDe(emBrt, quintaFeira),
                "no fuso de Brasília, no mesmo instante, 16/07 ainda é futuro e continua solicitável");
    }

    // ── PATCH /api/ponto/banco/solicitacao/{id}/cancelar ──────────

    @Test
    @DisplayName("cancelar: PENDENTE do dono → CANCELADO + recálculo do cache (Q19)")
    void cancelarSucesso() {
        PontoSolicitacaoFolga s = solicitacao(HOJE.plusDays(3), StatusSolicitacaoFolga.PENDENTE);
        when(solicitacaoRepo.findById("sol-1")).thenReturn(Optional.of(s));
        when(saldoAberturaService.reancorar(OP, "OPERADOR")).thenReturn(saldoDe(960, null));

        Map<String, Object> out = service.cancelar("sol-1", OP, ROLE);

        assertEquals(StatusSolicitacaoFolga.CANCELADO, s.getStatus());
        assertEquals("CANCELADO", out.get("status"));
        assertEquals(960, out.get("saldo_min"));
        verify(solicitacaoRepo).save(s);
        verify(saldoAberturaService).reancorar(OP, "OPERADOR");
    }

    @Test
    @DisplayName("cancelar: solicitação de outro dono → 403 (gotcha 5)")
    void cancelarOutroDono() {
        PontoSolicitacaoFolga s = solicitacao(HOJE.plusDays(3), StatusSolicitacaoFolga.PENDENTE);
        s.setPessoaId("outro");
        when(solicitacaoRepo.findById("sol-1")).thenReturn(Optional.of(s));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.cancelar("sol-1", OP, ROLE));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(solicitacaoRepo, never()).save(any());
    }

    @Test
    @DisplayName("cancelar: já deliberada (APROVADO) → 400")
    void cancelarNaoPendente() {
        PontoSolicitacaoFolga s = solicitacao(HOJE.plusDays(3), StatusSolicitacaoFolga.APROVADO);
        when(solicitacaoRepo.findById("sol-1")).thenReturn(Optional.of(s));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.cancelar("sol-1", OP, ROLE));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(StatusSolicitacaoFolga.APROVADO, s.getStatus());
        verify(solicitacaoRepo, never()).save(any());
    }

    @Test
    @DisplayName("cancelar: id inexistente → 404")
    void cancelarInexistente() {
        when(solicitacaoRepo.findById("nao-existe")).thenReturn(Optional.empty());
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.cancelar("nao-existe", OP, ROLE));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ══ Deliberação do admin (Bloco D / E8) ═══════════════════════

    // ── POST .../solicitacao/{id}/aprovar ──

    @Test
    @DisplayName("aprovar: PENDENTE → APROVADO com deliberador, deliberadoEm do Clock, motivo nulado e aviso PESSOAL")
    void aprovarSucesso() {
        PontoSolicitacaoFolga s = solicitacao(HOJE.plusDays(3), StatusSolicitacaoFolga.PENDENTE);
        s.setMotivoRejeicao("resíduo de uma rejeição anterior");   // aprovar precisa LIMPAR o campo
        when(solicitacaoRepo.findById("sol-1")).thenReturn(Optional.of(s));

        Map<String, Object> out = service.aprovar("sol-1", ADMIN);

        assertEquals(StatusSolicitacaoFolga.APROVADO, s.getStatus());
        assertEquals(ADMIN, s.getDeliberadoPorId());
        assertEquals(AGORA, s.getDeliberadoEm(), "o carimbo vem do Clock injetado, não do relógio da máquina");
        assertNull(s.getMotivoRejeicao());
        assertEquals("APROVADO", out.get("status"));
        assertFalse(out.containsKey("motivo"), "aprovação não tem motivo");

        verify(solicitacaoRepo).save(s);
        verify(saldoAberturaService).reancorar(OP, "OPERADOR");   // reprecifica o cache (Q10)
        verify(avisoService).criarPessoalIndividual(
                argThat((List<AvisoService.DestinatarioAviso> ds) -> ds.size() == 1
                        && OP.equals(ds.get(0).pessoaId()) && ds.get(0).papel() == PapelPessoa.OPERADOR),
                argThat((String msg) -> msg.contains("APROVADA")
                        && msg.contains(ReportConfig.fmtDate(HOJE.plusDays(3)))),
                eq(ADMIN));
    }

    @Test
    @DisplayName("aprovar: pedido de TÉCNICO notifica com o papel TECNICO (o aviso segue o PESSOA_TIPO)")
    void aprovarPedidoDeTecnico() {
        PontoSolicitacaoFolga s = solicitacao("tec-9", "TECNICO", HOJE.plusDays(2), StatusSolicitacaoFolga.PENDENTE);
        when(solicitacaoRepo.findById("sol-9")).thenReturn(Optional.of(s));

        service.aprovar("sol-9", ADMIN);

        verify(saldoAberturaService).reancorar("tec-9", "TECNICO");
        verify(avisoService).criarPessoalIndividual(
                argThat((List<AvisoService.DestinatarioAviso> ds) -> ds.size() == 1
                        && "tec-9".equals(ds.get(0).pessoaId()) && ds.get(0).papel() == PapelPessoa.TECNICO),
                anyString(), eq(ADMIN));
    }

    // ── POST .../solicitacao/{id}/rejeitar ──

    @Test
    @DisplayName("rejeitar: PENDENTE → REJEITADO com motivo stripado, na resposta e na mensagem do aviso")
    void rejeitarSucesso() {
        PontoSolicitacaoFolga s = solicitacao(HOJE.plusDays(4), StatusSolicitacaoFolga.PENDENTE);
        when(solicitacaoRepo.findById("sol-2")).thenReturn(Optional.of(s));

        Map<String, Object> out = service.rejeitar("sol-2", ADMIN, "   Sem cobertura na sala   ");

        assertEquals(StatusSolicitacaoFolga.REJEITADO, s.getStatus());
        assertEquals(ADMIN, s.getDeliberadoPorId());
        assertEquals(AGORA, s.getDeliberadoEm());
        assertEquals("Sem cobertura na sala", s.getMotivoRejeicao(), "o motivo é stripado antes de persistir");
        assertEquals("REJEITADO", out.get("status"));
        assertEquals("Sem cobertura na sala", out.get("motivo"));

        verify(solicitacaoRepo).save(s);
        verify(saldoAberturaService).reancorar(OP, "OPERADOR");   // estorno implícito (C-5.6)
        verify(avisoService).criarPessoalIndividual(anyList(),
                argThat((String msg) -> msg.contains("REJEITADA")
                        && msg.contains("Motivo: Sem cobertura na sala.")),
                eq(ADMIN));
    }

    @ParameterizedTest(name = "[{index}] motivo = \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("rejeitar: motivo ausente/em branco → 400 ANTES de carregar a solicitação (D-3.2)")
    void rejeitarSemMotivo(String motivo) {
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.rejeitar("sol-2", ADMIN, motivo));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("motivo"));
        verifyNoInteractions(solicitacaoRepo, saldoAberturaService, avisoService, administradorRepo);
    }

    @Test
    @DisplayName("corrige F47 — motivo de 301 caracteres → 400 nomeando o CAMPO, antes de tocar o banco (era ORA-12899 → 500)")
    void rejeitarMotivoAcimaDoLimite() {
        // MOTIVO_REJEICAO é VARCHAR2(1000) em BYTES: um motivo colado de um e-mail/norma estourava a
        // coluna, o ORA-12899 caía no handler genérico e o admin recebia um 500 com um toast sem
        // nenhuma pista da causa — a rejeição ficava impossível. Mesma correção do F33 (retificação).
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.rejeitar("sol-2", ADMIN, "ç".repeat(301)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("motivo da rejeição"), ex.getMessage());
        assertTrue(ex.getMessage().contains("300 caracteres"), ex.getMessage());
        verifyNoInteractions(solicitacaoRepo, saldoAberturaService, avisoService, administradorRepo);
    }

    @Test
    @DisplayName("corrige F47 — motivo de 300 caracteres passa (limite inclusivo) e é persistido inteiro")
    void rejeitarMotivoNoLimite() {
        String motivo = "ç".repeat(300);   // 600 bytes em UTF-8 — dentro do budget de 1000 da coluna
        PontoSolicitacaoFolga s = solicitacao(HOJE.plusDays(4), StatusSolicitacaoFolga.PENDENTE);
        when(solicitacaoRepo.findById("sol-2")).thenReturn(Optional.of(s));

        service.rejeitar("sol-2", ADMIN, motivo);

        assertEquals(StatusSolicitacaoFolga.REJEITADO, s.getStatus());
        assertEquals(motivo, s.getMotivoRejeicao());
        verify(solicitacaoRepo).save(s);
    }

    // ── ordem da validação: 404 → 403 (T-1.2/T-1.3) → só PENDENTE ──

    @Test
    @DisplayName("deliberar: solicitação inexistente → 404 (aprovar e rejeitar)")
    void deliberarInexistente() {
        when(solicitacaoRepo.findById("nao-existe")).thenReturn(Optional.empty());

        ServiceValidationException aoAprovar = assertThrows(ServiceValidationException.class,
                () -> service.aprovar("nao-existe", ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, aoAprovar.getStatus());

        ServiceValidationException aoRejeitar = assertThrows(ServiceValidationException.class,
                () -> service.rejeitar("nao-existe", ADMIN, "motivo válido"));
        assertEquals(HttpStatus.NOT_FOUND, aoRejeitar.getStatus());

        verify(solicitacaoRepo, never()).save(any());
        verifyNoInteractions(avisoService, saldoAberturaService);
    }

    @Test
    @DisplayName("deliberar: T-1.2 — o admin não delibera o PRÓPRIO pedido → 403 antes de olhar o servidorPublico")
    void deliberarProprioPedido() {
        PontoSolicitacaoFolga s = solicitacao(ADMIN, "ADMINISTRADOR", HOJE.plusDays(3),
                StatusSolicitacaoFolga.PENDENTE);
        when(solicitacaoRepo.findById("sol-3")).thenReturn(Optional.of(s));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aprovar("sol-3", ADMIN));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("próprio pedido"));
        assertEquals(StatusSolicitacaoFolga.PENDENTE, s.getStatus());
        // T-1.2 vem ANTES de T-1.3: nem chega a consultar o caller no repositório
        verifyNoInteractions(administradorRepo);
        verify(solicitacaoRepo, never()).save(any());
    }

    /** T-1.3: só admin SERVIDOR PÚBLICO delibera pedido de admin. Os 3 casos que NÃO passam. */
    static Stream<Arguments> callersSemPoderSobreAdmin() {
        return Stream.of(
                Arguments.of("servidorPublico = false", Optional.of(admin(false))),
                Arguments.of("servidorPublico = null", Optional.of(admin(null))),
                Arguments.of("caller não encontrado", Optional.<Administrador>empty()));
    }

    @ParameterizedTest(name = "[{index}] {0} → 403")
    @MethodSource("callersSemPoderSobreAdmin")
    @DisplayName("deliberar: T-1.3 — pedido de ADMINISTRADOR só é deliberado por admin servidor público")
    void deliberarPedidoDeAdminSemServidorPublico(String cenario, Optional<Administrador> caller) {
        PontoSolicitacaoFolga s = solicitacao("adm-alvo", "ADMINISTRADOR", HOJE.plusDays(3),
                StatusSolicitacaoFolga.PENDENTE);
        when(solicitacaoRepo.findById("sol-4")).thenReturn(Optional.of(s));
        when(administradorRepo.findById(ADMIN)).thenReturn(caller);

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aprovar("sol-4", ADMIN));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("servidores públicos"));
        assertEquals(StatusSolicitacaoFolga.PENDENTE, s.getStatus());
        verify(solicitacaoRepo, never()).save(any());
        verifyNoInteractions(avisoService);
    }

    @Test
    @DisplayName("deliberar: T-1.3 — admin servidor público APROVA o pedido de outro admin (contraprova)")
    void deliberarPedidoDeAdminComServidorPublico() {
        PontoSolicitacaoFolga s = solicitacao("adm-alvo", "ADMINISTRADOR", HOJE.plusDays(3),
                StatusSolicitacaoFolga.PENDENTE);
        when(solicitacaoRepo.findById("sol-4")).thenReturn(Optional.of(s));
        when(administradorRepo.findById(ADMIN)).thenReturn(Optional.of(admin(true)));

        service.aprovar("sol-4", ADMIN);

        assertEquals(StatusSolicitacaoFolga.APROVADO, s.getStatus());
        verify(solicitacaoRepo).save(s);
        verify(saldoAberturaService).reancorar("adm-alvo", "ADMINISTRADOR");
        verify(avisoService).criarPessoalIndividual(
                argThat((List<AvisoService.DestinatarioAviso> ds) -> ds.size() == 1
                        && "adm-alvo".equals(ds.get(0).pessoaId()) && ds.get(0).papel() == PapelPessoa.ADMIN),
                anyString(), eq(ADMIN));
    }

    @ParameterizedTest(name = "[{index}] status {0} → 400")
    @EnumSource(value = StatusSolicitacaoFolga.class, names = {"APROVADO", "REJEITADO", "CANCELADO"})
    @DisplayName("deliberar: solicitação já deliberada (ou cancelada) → 400, sem regravar")
    void deliberarNaoPendente(StatusSolicitacaoFolga status) {
        PontoSolicitacaoFolga s = solicitacao(HOJE.plusDays(3), status);
        when(solicitacaoRepo.findById("sol-5")).thenReturn(Optional.of(s));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aprovar("sol-5", ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("pendentes"));
        assertEquals(status, s.getStatus(), "o status anterior é preservado");
        verify(solicitacaoRepo, never()).save(any());
        verifyNoInteractions(avisoService, saldoAberturaService);
    }

    @Test
    @DisplayName("deliberar: o 403 vem ANTES do 400 — próprio pedido JÁ deliberado responde 403, não 'não pendente'")
    void deliberarProprioPedidoJaDeliberado() {
        PontoSolicitacaoFolga s = solicitacao(ADMIN, "ADMINISTRADOR", HOJE.plusDays(3),
                StatusSolicitacaoFolga.APROVADO);
        when(solicitacaoRepo.findById("sol-6")).thenReturn(Optional.of(s));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aprovar("sol-6", ADMIN));

        // Invertida a ordem em deliberavel(), este caso viraria 400 — e o estado do pedido vazaria
        // para quem não tem poder sobre ele.
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("próprio pedido"));
    }

    @Test
    @DisplayName("deliberar: o 403 vem ANTES do 400 — pedido de admin JÁ deliberado, caller sem SP, responde 403")
    void deliberarPedidoDeAdminJaDeliberadoSemServidorPublico() {
        PontoSolicitacaoFolga s = solicitacao("adm-alvo", "ADMINISTRADOR", HOJE.plusDays(3),
                StatusSolicitacaoFolga.REJEITADO);
        when(solicitacaoRepo.findById("sol-7")).thenReturn(Optional.of(s));
        when(administradorRepo.findById(ADMIN)).thenReturn(Optional.of(admin(false)));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aprovar("sol-7", ADMIN));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("servidores públicos"));
    }

    // ── Relatório da fila do admin (Q27) ──

    @Test
    @DisplayName("enriquecerRows…: saldo_min numérico vira ±HH:MM e as demais chaves da linha são preservadas")
    void enriquecerRowsComSaldoNumerico() {
        Map<String, Object> linha = new LinkedHashMap<>();
        linha.put("nome", "Fulano de Tal");
        linha.put("saldo_min", 1570L);            // o motor devolve Number (convertValue → Long)
        linha.put("data_folga", "2026-07-20");
        Map<String, Object> negativa = new LinkedHashMap<>(linha);
        negativa.put("saldo_min", -123L);

        List<Map<String, Object>> rows = service.enriquecerRowsParaRelatorioSolicitacoesAdmin(
                List.of(linha, negativa));

        assertEquals("+26:10", rows.get(0).get("saldo"));
        assertEquals("-02:03", rows.get(1).get("saldo"));
        assertEquals("Fulano de Tal", rows.get(0).get("nome"));
        assertEquals("2026-07-20", rows.get(0).get("data_folga"));
        assertEquals(1570L, rows.get(0).get("saldo_min"), "a coluna crua continua na linha");
        assertFalse(linha.containsKey("saldo"), "a linha original não é mutada (o service copia)");
    }

    @Test
    @DisplayName("enriquecerRows…: saldo ausente/nulo/não-numérico → \"--\" (pessoa sem linha em PNT_BANCO_SALDO)")
    void enriquecerRowsSemSaldo() {
        Map<String, Object> semChave = new LinkedHashMap<>(Map.of("nome", "Sem Saldo"));
        Map<String, Object> nula = new LinkedHashMap<>();
        nula.put("nome", "Saldo Nulo");
        nula.put("saldo_min", null);              // LEFT JOIN sem linha de saldo
        Map<String, Object> texto = new LinkedHashMap<>();
        texto.put("nome", "Saldo Texto");
        texto.put("saldo_min", "n/d");

        List<Map<String, Object>> rows = service.enriquecerRowsParaRelatorioSolicitacoesAdmin(
                List.of(semChave, nula, texto));

        assertEquals("--", rows.get(0).get("saldo"));
        assertEquals("--", rows.get(1).get("saldo"));
        assertEquals("--", rows.get(2).get("saldo"));
        assertEquals("Sem Saldo", rows.get(0).get("nome"));
    }
}
