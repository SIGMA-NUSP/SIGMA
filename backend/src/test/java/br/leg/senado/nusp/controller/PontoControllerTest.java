package br.leg.senado.nusp.controller;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.BancoHorasService;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import br.leg.senado.nusp.service.GradeRetificacaoService;
import br.leg.senado.nusp.service.MarcacaoService;
import br.leg.senado.nusp.service.PontoExclusaoService;
import br.leg.senado.nusp.service.PontoService;
import br.leg.senado.nusp.service.PontoService.ArquivoPonto;
import br.leg.senado.nusp.service.PontoXlsxService;
import br.leg.senado.nusp.service.ReportDocxService;
import br.leg.senado.nusp.service.ReportPdfService;
import br.leg.senado.nusp.service.ReportService;
import br.leg.senado.nusp.service.RetificacaoService;

import static org.hamcrest.Matchers.containsString;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link PontoController} (T27b) — o maior controller do módulo Ponto e o último
 * do sistema sem teste (26 mappings: 16 admin em {@code /api/admin/ponto/**} e 10 comuns em
 * {@code /api/ponto/**}; empata com o {@code AdminDashboardController} em número de rotas).
 *
 * <p><b>Controller MISTO</b> (sem {@code @RequestMapping} de classe — é o F2): as rotas admin e as
 * do funcionário convivem no mesmo arquivo. A matriz do T15 ({@code RbacMatrixMistoTest}) deixou o
 * Ponto de fora por decisão do plano, então a <b>cadeia completa</b> (filtro JWT + matcher
 * {@code /api/admin/**} + {@code @AdminOnly}) sobre estas rotas é medida aqui. As outras duas
 * coberturas do F2 já existem e NÃO são duplicadas: a varredura estática da anotação
 * ({@code AdminOnlyCoberturaTest}) e a prova da camada de method security isolada
 * ({@code AdminOnlyMethodSecurityTest}, com os filtros desligados). Os 4 modos de token inválido
 * também são do T15 — aqui só o "sem token".
 *
 * <p><b>Regra de famílias (T16):</b> com os 9 services mockados, endpoint homogêneo é plumbing de
 * delegação — cobre-se cada FAMÍLIA de contrato em endpoints representativos, mais os singulares
 * (upload multipart, publicação, grade/XLSX, streaming de PDF, retificação em lote, solicitação de
 * folgas, deliberação, relatório, paginação e binding). Exaurir os 26 mappings seria testar o
 * {@code @RequestMapping} do Spring. Ficam deliberadamente FORA, por serem repetição de família já
 * coberta: a LEITURA das retificações da folha, o relatório PESSOAL ({@code respondPdf} — mesma
 * família do relatório do admin), o saldo ({@code GET /api/ponto/banco}) e as leituras admin de
 * lote/página/pessoas.
 *
 * <p>O POST de retificação entrou na seleção no C10: deixou de ser "mais um POST por dia" e virou o
 * LOTE transacional (F39) — contrato novo, corpo estruturado e recusa que precisa chegar ao usuário
 * nomeando o dia.
 *
 * <p><b>Onde a resposta é do controller e onde é do stub:</b> {@code download}/{@code preview}
 * montam os headers no próprio controller ({@code streamPdf}) — ali os headers SÃO asserção. Já o
 * XLSX e os relatórios saem do {@link ReportService}, que aqui é <b>mock</b>: assertar
 * Content-Type/Disposition deles seria assertar o próprio stub, então o que se trava é a
 * <b>interação</b> (bytes e nome calculados no controller) mais o pass-through da resposta.
 */
@SigmaControllerTest(PontoController.class)
class PontoControllerTest {

    private static final String SEM_TOKEN = "sem-token";
    private static final String LOTE_ID = "8f14e45f-ceea-467a-9575-28db1b39ad63";
    private static final String PAGINA_ID = "c9f0f895-fb98-4b1e-a5d2-1b0ab1e2c3d4";
    private static final String SOLICITACAO_ID = "45c48cce-2e2d-4d1f-b8f9-fbcd8b1d5a11";

    /** Mensagem única do handler de requisição malformada (F6, corrigido no C4). */
    private static final String MSG_BINDING_INVALIDO = "Requisição inválida. Verifique os dados enviados.";

    /** Conteúdo binário fixo — nada de PDF real: o parser vive no service, aqui só trafega bytes. */
    private static final byte[] PDF = "%PDF-1.4 folha".getBytes(StandardCharsets.UTF_8);

    /** Corpo que o ReportService MOCKADO devolve — provar que ele chega intacto ao cliente é o pass-through. */
    private static final byte[] RESPOSTA_REPORT_SERVICE = "resposta-do-report-service".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private PontoService pontoService;
    @MockitoBean private PontoExclusaoService pontoExclusaoService;
    @MockitoBean private RetificacaoService retificacaoService;
    @MockitoBean private MarcacaoService marcacaoService;
    @MockitoBean private GradeRetificacaoService gradeRetificacaoService;
    @MockitoBean private PontoXlsxService pontoXlsxService;
    @MockitoBean private BancoHorasService bancoHorasService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReportPdfService pdfService;
    @MockitoBean private ReportDocxService docxService;
    // ObjectMapper (10ª dependência do construtor) vem do slice — NÃO mockar.

    private TokenFactory tokens;
    private String admin;
    private String operador;

    @BeforeEach
    void setUp() {
        tokens = new TokenFactory(jwtTokenProvider);
        admin = "Bearer " + tokens.valido(TokenFactory.ADMIN);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401 em tudo.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);

        // Stubs do braço 2xx da matriz (§0.5: valores diferentes do default do Mockito).
        when(pontoService.listarLotes()).thenReturn(List.of(Map.of("id", LOTE_ID, "paginas", 42)));
        when(pontoService.minhasFolhas(TokenFactory.USER_ID))
                .thenReturn(List.of(Map.of("id", PAGINA_ID, "mes_ref", "2026-07")));
        // O papel entra no service (role do token) → matcher no role, valor exato no id do dono.
        when(bancoHorasService.listMinhasSolicitacoes(eq(TokenFactory.USER_ID), anyString(),
                eq(1), eq(25), eq("data_folga"), eq("desc"), isNull()))
                .thenReturn(new PagedResult(List.of(Map.of("id", SOLICITACAO_ID)), 1, Map.of()));
        when(bancoHorasService.listSolicitacoesAdmin(TokenFactory.USER_ID, 1, 25, "padrao", "asc", null, null))
                .thenReturn(new PagedResult(List.of(Map.of("id", SOLICITACAO_ID)), 1, Map.of()));
    }

    // ══ 1) Matriz RBAC do controller misto (cadeia completa) ═══════════════

    /**
     * Rotas GET representativas dos dois prefixos, incluindo um PAR do mesmo domínio (as
     * solicitações do banco de horas): a fila do admin exige ADMINISTRADOR e a lista do próprio
     * funcionário aceita os 3 papéis — no MESMO arquivo, que é o risco que o F2 descreve.
     *
     * <p>O dado é tabular por natureza (rota × papel × status), única situação em que a §0.5
     * autoriza {@code @ParameterizedTest}; é o mesmo idiom do {@code RbacMatrixMistoTest}.
     */
    static Stream<Arguments> matriz() {
        return Stream.of(
                // Rota admin do Ponto: só ADMINISTRADOR passa
                Arguments.of("/api/admin/ponto/lotes", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/ponto/lotes", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/ponto/lotes", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/ponto/lotes", SEM_TOKEN, 401),
                // Rota comum do MESMO controller: qualquer papel autenticado passa
                Arguments.of("/api/ponto/minhas-folhas", TokenFactory.ADMIN, 200),
                Arguments.of("/api/ponto/minhas-folhas", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/ponto/minhas-folhas", TokenFactory.TECNICO, 200),
                Arguments.of("/api/ponto/minhas-folhas", SEM_TOKEN, 401),
                // Par admin×comum do MESMO domínio (banco de horas), no MESMO arquivo:
                Arguments.of("/api/admin/ponto/banco/solicitacoes", TokenFactory.ADMIN, 200),
                Arguments.of("/api/admin/ponto/banco/solicitacoes", TokenFactory.OPERADOR, 403),
                Arguments.of("/api/admin/ponto/banco/solicitacoes", TokenFactory.TECNICO, 403),
                Arguments.of("/api/admin/ponto/banco/solicitacoes", SEM_TOKEN, 401),
                Arguments.of("/api/ponto/banco/solicitacoes", TokenFactory.ADMIN, 200),
                Arguments.of("/api/ponto/banco/solicitacoes", TokenFactory.OPERADOR, 200),
                Arguments.of("/api/ponto/banco/solicitacoes", TokenFactory.TECNICO, 200),
                Arguments.of("/api/ponto/banco/solicitacoes", SEM_TOKEN, 401)
        );
    }

    @ParameterizedTest(name = "[{index}] {1} em {0} → {2}")
    @MethodSource("matriz")
    void matrizRbac(String rota, String papel, int esperado) throws Exception {
        executar(rota, papel).andExpect(status().is(esperado));
    }

    /**
     * O delta que a matriz não mede: a requisição barrada morre ANTES do dispatch — nem o filtro (401)
     * nem o matcher (403) deixam o service do módulo ser tocado. O corpo do 401 é do
     * {@code JwtAuthenticationFilter} (shape {@code {error,message}}, sem {@code ok} — é a única resposta
     * de erro fora do shape da API); o 403 do matcher vem do Spring Security, sem corpo.
     */
    @Test
    @DisplayName("requisição barrada não chega ao service — sem token 401 (JSON do filtro) e operador 403")
    void rotaAdminBarrada_naoTocaOService() throws Exception {
        mockMvc.perform(Requests.get("/api/admin/ponto/lotes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Missing Authorization header"));

        mockMvc.perform(Requests.get("/api/admin/ponto/lotes").header("Authorization", operador))
                .andExpect(status().isForbidden());

        verifyNoInteractions(pontoService);
    }

    /** O braço 200 da matriz mede status; o envelope {ok,data} da rota admin representativa fica aqui. */
    @Test
    @DisplayName("GET /api/admin/ponto/lotes — admin recebe {ok,data} com o payload do service")
    void lotes_admin_payloadDoService() throws Exception {
        mockMvc.perform(Requests.get("/api/admin/ponto/lotes").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].id").value(LOTE_ID))
                .andExpect(jsonPath("$.data[0].paginas").value(42));

        verify(pontoService).listarLotes();
    }

    // ══ 3) Exclusão de publicações (F59) — master-only ══════════════════════

    /**
     * A permissão de excluir viaja como FLAG na listagem, computada pelo backend a partir do
     * {@code username} do principal (o front nunca compara nome de usuário). É ela que faz o X
     * aparecer — e é só um controle de UI: quem chamar o DELETE mesmo assim leva o 403 do service.
     */
    @Nested
    @DisplayName("F59 — exclusão de lote/página pelo admin master")
    class ExclusaoDePublicacoes {

        private static final String PREVIEW_LOTE = "/api/admin/ponto/lote/" + LOTE_ID + "/exclusao/preview";
        private static final String PREVIEW_PAGINA =
                "/api/admin/ponto/lote/" + LOTE_ID + "/pagina/" + PAGINA_ID + "/exclusao/preview";
        private static final String DELETE_LOTE = "/api/admin/ponto/lote/" + LOTE_ID;
        private static final String DELETE_PAGINA = "/api/admin/ponto/lote/" + LOTE_ID + "/pagina/" + PAGINA_ID;

        /** O username que o token carrega ({@code teste.<perfil>}) — é ele que o controller repassa. */
        private static final String USERNAME_DO_TOKEN = "teste." + TokenFactory.ADMIN;

        @Test
        @DisplayName("corrige F59 — GET /lotes carrega pode_excluir, computado pelo backend a partir do username do principal")
        void listagemCarregaAFlagDoMaster() throws Exception {
            when(pontoExclusaoService.podeExcluir(USERNAME_DO_TOKEN)).thenReturn(true);

            mockMvc.perform(Requests.get("/api/admin/ponto/lotes").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pode_excluir").value(true));

            verify(pontoExclusaoService).podeExcluir(USERNAME_DO_TOKEN);
        }

        @Test
        @DisplayName("admin não-master: a flag vem FALSE (e o X não é renderizado)")
        void listagemSemAFlagParaAdminComum() throws Exception {
            mockMvc.perform(Requests.get("/api/admin/ponto/lotes").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pode_excluir").value(false));
        }

        @Test
        @DisplayName("GET .../exclusao/preview (lote e página) — o payload do preview chega ao modal, com o username do principal")
        void previewDaExclusao() throws Exception {
            when(pontoExclusaoService.previewLote(LOTE_ID, USERNAME_DO_TOKEN))
                    .thenReturn(Map.of("escopo", "LOTE", "retificacoes_excluidas", 3));
            when(pontoExclusaoService.previewPagina(LOTE_ID, PAGINA_ID, USERNAME_DO_TOKEN))
                    .thenReturn(Map.of("escopo", "PAGINA", "retificacoes_excluidas", 1));

            mockMvc.perform(Requests.get(PREVIEW_LOTE).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.escopo").value("LOTE"))
                    .andExpect(jsonPath("$.data.retificacoes_excluidas").value(3));

            mockMvc.perform(Requests.get(PREVIEW_PAGINA).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.escopo").value("PAGINA"))
                    .andExpect(jsonPath("$.data.retificacoes_excluidas").value(1));
        }

        /**
         * O DELETE leva DUAS identidades do principal, e as duas importam: o {@code username} decide a
         * PERMISSÃO (é o master?) e o {@code id} é quem ASSINA a trilha de auditoria (FK
         * EXCLUIDO_POR_ID). Trocar um pelo outro daria 403 em todo mundo ou uma FK violada.
         */
        @Test
        @DisplayName("corrige F59 — DELETE do lote e da página: o controller passa username (permissão) e id (autoria da trilha)")
        void deleteRepassaUsernameEId() throws Exception {
            when(pontoExclusaoService.excluirLote(LOTE_ID, USERNAME_DO_TOKEN, TokenFactory.USER_ID))
                    .thenReturn(Map.of("escopo", "LOTE", "paginas_excluidas", 2));
            when(pontoExclusaoService.excluirPagina(LOTE_ID, PAGINA_ID, USERNAME_DO_TOKEN, TokenFactory.USER_ID))
                    .thenReturn(Map.of("escopo", "PAGINA", "paginas_excluidas", 1));

            mockMvc.perform(Requests.delete(DELETE_LOTE).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.paginas_excluidas").value(2));

            mockMvc.perform(Requests.delete(DELETE_PAGINA).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.escopo").value("PAGINA"));

            verify(pontoExclusaoService).excluirLote(LOTE_ID, USERNAME_DO_TOKEN, TokenFactory.USER_ID);
            verify(pontoExclusaoService).excluirPagina(LOTE_ID, PAGINA_ID, USERNAME_DO_TOKEN, TokenFactory.USER_ID);
        }

        /**
         * O 403 do service (admin autenticado, mas não master) atravessa a cadeia real e chega ao
         * cliente como 403 no shape da API — é o que o modal do front lê e mostra. Esconder o botão
         * nunca foi a segurança; esta rota é.
         */
        @Test
        @DisplayName("corrige F59 — admin comum autenticado: o 403 do service chega ao cliente nas quatro rotas")
        void adminComumRecebe403DoService() throws Exception {
            ServiceValidationException forbidden =
                    new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
            doThrow(forbidden).when(pontoExclusaoService).previewLote(anyString(), anyString());
            doThrow(forbidden).when(pontoExclusaoService).previewPagina(anyString(), anyString(), anyString());
            doThrow(forbidden).when(pontoExclusaoService).excluirLote(anyString(), anyString(), anyString());
            doThrow(forbidden).when(pontoExclusaoService)
                    .excluirPagina(anyString(), anyString(), anyString(), anyString());

            for (MockHttpServletRequestBuilder req : List.of(
                    Requests.get(PREVIEW_LOTE), Requests.get(PREVIEW_PAGINA),
                    Requests.delete(DELETE_LOTE), Requests.delete(DELETE_PAGINA))) {
                mockMvc.perform(req.header("Authorization", admin))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value("forbidden"));
            }
        }

        @Test
        @DisplayName("operador/técnico: barrado pelo matcher /api/admin/** antes do dispatch — o service nem é tocado")
        void naoAdminNaoChegaAoService() throws Exception {
            mockMvc.perform(Requests.delete(DELETE_LOTE).header("Authorization", operador))
                    .andExpect(status().isForbidden());
            mockMvc.perform(Requests.get(PREVIEW_LOTE).header("Authorization", operador))
                    .andExpect(status().isForbidden());
            mockMvc.perform(Requests.delete(DELETE_LOTE))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(pontoExclusaoService);
        }

        @Test
        @DisplayName("lote inexistente: o 404 do service chega como 404 (o modal do front o mostra e a lista é recarregada)")
        void loteInexistente404() throws Exception {
            doThrow(new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND))
                    .when(pontoExclusaoService).excluirLote(anyString(), anyString(), anyString());

            mockMvc.perform(Requests.delete(DELETE_LOTE).header("Authorization", admin))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Lote não encontrado."));
        }
    }

    @Test
    @DisplayName("GET /api/ponto/minhas-folhas — devolve o payload do service para o dono do token")
    void minhasFolhas_payloadDoService() throws Exception {
        mockMvc.perform(Requests.get("/api/ponto/minhas-folhas").header("Authorization", operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].id").value(PAGINA_ID))
                .andExpect(jsonPath("$.data[0].mes_ref").value("2026-07"));

        verify(pontoService).minhasFolhas(TokenFactory.USER_ID);
    }

    /** O único verbo não-GET das rotas comuns: a cadeia RBAC vale nele igual (o matcher não olha método). */
    @Test
    @DisplayName("PATCH /api/ponto/banco/solicitacao/{id}/cancelar — operador cancela o próprio pedido (dono e papel do token)")
    void cancelarSolicitacao_operador_200() throws Exception {
        when(bancoHorasService.cancelar(SOLICITACAO_ID, TokenFactory.USER_ID, TokenFactory.OPERADOR))
                .thenReturn(Map.of("status", "CANCELADA"));

        mockMvc.perform(Requests.patch("/api/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/cancelar")
                        .header("Authorization", operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELADA"));

        verify(bancoHorasService).cancelar(SOLICITACAO_ID, TokenFactory.USER_ID, TokenFactory.OPERADOR);
    }

    /**
     * Única rota do controller que responde {@code {ok:true}} SEM {@code data} (o modal Configurar aplica
     * um lote de marcações e não devolve payload) — e o segundo ponto, fora do upload, em que o corpo cru
     * e o {@code principal.getId()} entram juntos numa escrita do admin.
     */
    @Test
    @DisplayName("PUT /api/admin/ponto/marcacoes — 200 {ok:true} sem data, com o corpo cru e o admin do token")
    void aplicarMarcacoes_200_semData() throws Exception {
        mockMvc.perform(Requests.put("/api/admin/ponto/marcacoes")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ano\":2026,\"mes\":7,\"globais\":{\"2026-07-09\":\"FERIADO\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(marcacaoService).aplicarLote(
                argThat(body -> Integer.valueOf(2026).equals(body.get("ano"))
                        && body.get("globais") instanceof Map<?, ?> globais
                        && "FERIADO".equals(globais.get("2026-07-09"))),
                eq(TokenFactory.USER_ID));
    }

    // ══ 2) Upload multipart ════════════════════════════════════════════════

    @Nested
    @DisplayName("upload multipart — POST /api/admin/ponto/upload")
    class Upload {

        @Test
        @DisplayName("POST /api/admin/ponto/upload — 201 com o arquivo, os params e o id do admin do token")
        void upload_multipart_201() throws Exception {
            when(pontoService.upload(any(), eq("MENSAL"), eq("2026-07-01"), eq("2026-07-31"),
                    eq(TokenFactory.USER_ID)))
                    .thenReturn(Map.of("lote_id", LOTE_ID, "paginas", 3));

            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .file(new MockMultipartFile("arquivo", "cartao-ponto.pdf",
                                    MediaType.APPLICATION_PDF_VALUE, PDF))
                            .param("tipo", "MENSAL")
                            .param("data_inicio", "2026-07-01")
                            .param("data_fim", "2026-07-31")
                            .header("Authorization", admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.lote_id").value(LOTE_ID))
                    .andExpect(jsonPath("$.data.paginas").value(3));

            // O UUID do dono NÃO vem do corpo: é o principal.getId() do token (mesmo padrão do F2).
            verify(pontoService).upload(
                    argThat(f -> "cartao-ponto.pdf".equals(f.getOriginalFilename())
                            && "arquivo".equals(f.getName())
                            && f.getSize() == PDF.length),
                    eq("MENSAL"), eq("2026-07-01"), eq("2026-07-31"), eq(TokenFactory.USER_ID));
        }

        /**
         * Inversão do {@code caracteriza F36} (C13): requisição NÃO-multipart no endpoint de upload (o
         * cliente errou o Content-Type) faz o resolver do {@code @RequestParam MultipartFile} lançar
         * {@code MultipartException("Current request is not a multipart request")}. Ela não tinha handler
         * — só a subclasse do 413 tinha — e caía no {@code @ExceptionHandler(Exception.class)}: <b>500 +
         * log ERROR com stacktrace</b> por um erro que é do CLIENTE. Agora a {@code MultipartException}
         * está na lista do {@code handleBadRequest} → 400 no shape padrão, log WARN.
         *
         * <p>A resposta depende de o {@code MultipartFile} ser o PRIMEIRO argumento resolvido: reordenar
         * a assinatura faria um {@code @RequestParam} String faltar antes, e o 400 viria por outro
         * caminho (o do F6) — com a MESMA mensagem, que é genérica. Se este teste mudar de comportamento
         * depois de um refactor da assinatura, é isto.
         */
        @Test
        @DisplayName("corrige F36 — upload com Content-Type JSON (não multipart) responde 400, não 500")
        void upload_requisicaoNaoMultipart_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/ponto/upload")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tipo\":\"MENSAL\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(MSG_BINDING_INVALIDO));

            verifyNoInteractions(pontoService);
        }

        @Test
        @DisplayName("POST /api/admin/ponto/upload — PDF recusado pelo service vira 400 {ok:false, error}")
        void upload_arquivoInvalido_400() throws Exception {
            when(pontoService.upload(any(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new ServiceValidationException("O arquivo enviado não é um PDF válido."));

            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .file(new MockMultipartFile("arquivo", "nao-e-pdf.txt",
                                    MediaType.TEXT_PLAIN_VALUE, "isto não é um PDF".getBytes(StandardCharsets.UTF_8)))
                            .param("tipo", "MENSAL")
                            .param("data_inicio", "2026-07-01")
                            .param("data_fim", "2026-07-31")
                            .header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("O arquivo enviado não é um PDF válido."));
        }
    }

    // ══ 2b) Vínculo da página (leitura tipada que PRESERVA o desvincular) ══

    /**
     * O corpo do vínculo é opcional em dois sentidos diferentes, e é essa distinção que o F35 tinha de
     * respeitar: {@code pessoa_id} AUSENTE é a ordem de <b>desvincular</b> (o service devolve a página a
     * PENDENTE quando recebe {@code null}); {@code pessoa_id} presente com o tipo ERRADO é erro do
     * cliente. Um helper "campo obrigatório" aqui teria matado o desvincular — daí o teste-guardião.
     */
    @Nested
    @DisplayName("vincular — PATCH /api/admin/ponto/lote/{loteId}/pagina/{paginaId}")
    class Vincular {

        private static final String ROTA = "/api/admin/ponto/lote/" + LOTE_ID + "/pagina/" + PAGINA_ID;

        @Test
        @DisplayName("PATCH com pessoa_id/pessoa_tipo — os dois textos chegam ao service e o vínculo é devolvido em {ok,data}")
        void vincular_repassaOParAoService() throws Exception {
            when(pontoService.atualizarVinculo(LOTE_ID, PAGINA_ID, "op-1", "OPERADOR"))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "MANUAL"));

            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pessoa_id\":\"op-1\",\"pessoa_tipo\":\"OPERADOR\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.status_match").value("MANUAL"));

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, "op-1", "OPERADOR");
        }

        /**
         * O guardião do trap do estágio: sem corpo, os DOIS campos chegam ao service como {@code null} —
         * é assim que o admin desfaz um vínculo errado. Se um dia alguém trocar a leitura por um helper
         * de campo obrigatório, este teste cai antes de a função sumir da tela.
         */
        @Test
        @DisplayName("corrige F35 — PATCH sem corpo continua DESVINCULANDO: o service recebe null/null")
        void vincular_semCorpo_desvincula() throws Exception {
            when(pontoService.atualizarVinculo(eq(LOTE_ID), eq(PAGINA_ID), isNull(), isNull()))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "PENDENTE"));

            mockMvc.perform(Requests.patch(ROTA).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status_match").value("PENDENTE"));

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, null, null);
        }

        /**
         * O corpo que a UI realmente manda ao desvincular (`admin-ponto.component.ts#onAssign`): os campos
         * vêm com {@code null} EXPLÍCITO, não ausentes. É o caminho de produção do desvincular, e é o que
         * um helper que confundisse "null" com "tipo inválido" teria quebrado — na tela, não no teste.
         */
        @Test
        @DisplayName("corrige F35 — PATCH {\"pessoa_id\":null,\"pessoa_tipo\":null} (o corpo REAL do front) desvincula")
        void vincular_nullsExplicitos_desvincula() throws Exception {
            when(pontoService.atualizarVinculo(eq(LOTE_ID), eq(PAGINA_ID), isNull(), isNull()))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "PENDENTE"));

            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pessoa_id\":null,\"pessoa_tipo\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status_match").value("PENDENTE"));

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, null, null);
        }

        /** Idem com corpo vazio: {@code {}} não é "tipo errado", é ausência — desvincula igual. */
        @Test
        @DisplayName("corrige F35 — PATCH com corpo {} também desvincula (ausência ≠ tipo errado)")
        void vincular_corpoVazio_desvincula() throws Exception {
            when(pontoService.atualizarVinculo(eq(LOTE_ID), eq(PAGINA_ID), isNull(), isNull()))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "PENDENTE"));

            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, null, null);
        }

        /**
         * Antes: {@code body.get("pessoa_tipo").toString()} transformava a lista {@code ["OPERADOR"]} no
         * texto {@code "[OPERADOR]"}, e o service recusava com uma mensagem sobre pessoa inválida — a
         * mensagem errada para o defeito certo. Agora o tipo é conferido no controller, e a publicação
         * do erro nomeia o campo.
         */
        @Test
        @DisplayName("corrige F35 — lista em pessoa_tipo → 400 nomeando o campo, service nunca chamado")
        void vincular_tipoNaoTextual_400() throws Exception {
            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pessoa_id\":\"op-1\",\"pessoa_tipo\":[\"OPERADOR\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(containsString("pessoa_tipo")));

            verifyNoInteractions(pontoService);
        }

        @Test
        @DisplayName("corrige F35 — objeto em pessoa_id → 400 nomeando o campo, service nunca chamado")
        void vincular_idNaoTextual_400() throws Exception {
            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pessoa_id\":{\"a\":1},\"pessoa_tipo\":\"OPERADOR\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("pessoa_id")));

            verifyNoInteractions(pontoService);
        }
    }

    // ══ 3) Publicação do lote (semântica do emitir_aviso) ══════════════════

    @Nested
    @DisplayName("publicar — POST /api/admin/ponto/lote/{id}/publicar (corpo OPCIONAL)")
    class Publicar {

        @Test
        @DisplayName("POST /api/admin/ponto/lote/{id}/publicar — sem corpo, emite aviso (é o default: só um false explícito o desliga)")
        void publicar_semCorpo_emiteAviso() throws Exception {
            when(pontoService.publicar(LOTE_ID, true)).thenReturn(Map.of("publicado", true, "avisos", 12));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.avisos").value(12));

            verify(pontoService).publicar(LOTE_ID, true);
        }

        @Test
        @DisplayName("POST /api/admin/ponto/lote/{id}/publicar — corpo {emitir_aviso:false} publica calado")
        void publicar_emitirAvisoFalse_naoEmite() throws Exception {
            when(pontoService.publicar(LOTE_ID, false)).thenReturn(Map.of("publicado", true, "avisos", 0));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emitir_aviso\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.avisos").value(0));

            verify(pontoService).publicar(LOTE_ID, false);
        }

        /**
         * Inversão do {@code caracteriza F35} (C13): o desligamento do aviso era
         * {@code !Boolean.FALSE.equals(valor)}, então a STRING "false" — tipo errado, não booleano — não
         * desligava nada: o lote era publicado COM aviso, e a publicação dispara um aviso PESSOAL para
         * CADA pessoa da folha. O cliente pedia silêncio e recebia notificação em massa, sem erro.
         *
         * <p>Agora o campo exige booleano genuíno: outro tipo → 400 nomeando {@code emitir_aviso}, e
         * <b>nada é publicado</b> (é o que o {@code verifyNoInteractions} trava — a recusa tem de vir
         * ANTES da publicação, não depois).
         */
        @Test
        @DisplayName("corrige F35 — corpo {emitir_aviso:\"false\"} (string) → 400 nomeando o campo, sem publicar nada")
        void publicar_emitirAvisoStringFalse_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emitir_aviso\":\"false\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(containsString("emitir_aviso")));

            verifyNoInteractions(pontoService);
        }

        /** Qualquer outro tipo no campo tem o mesmo destino — o número 0 não é "false" (nem 1 é "true"). */
        @Test
        @DisplayName("corrige F35 — {emitir_aviso:0} (número) também é 400 nomeando o campo, sem publicar")
        void publicar_emitirAvisoNumero_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emitir_aviso\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("emitir_aviso")));

            verifyNoInteractions(pontoService);
        }

        /** Corpo presente, campo ausente: o default continua sendo emitir (o {@code null} não é "false"). */
        @Test
        @DisplayName("corrige F35 — corpo sem o campo emitir_aviso mantém o default (publica COM aviso)")
        void publicar_corpoSemOCampo_emiteAviso() throws Exception {
            when(pontoService.publicar(LOTE_ID, true)).thenReturn(Map.of("publicado", true, "avisos", 12));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"outro_campo\":123}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.avisos").value(12));

            verify(pontoService).publicar(LOTE_ID, true);
        }

        @Test
        @DisplayName("POST /api/admin/ponto/lote/{id}/publicar — lote já publicado vira 400 com a mensagem do service")
        void publicar_loteJaPublicado_400() throws Exception {
            when(pontoService.publicar(eq(LOTE_ID), anyBoolean()))
                    .thenThrow(new ServiceValidationException("Lote já está publicado."));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Lote já está publicado."));
        }
    }

    // ══ 4) Grade de retificações + exportação XLSX ═════════════════════════

    @Nested
    @DisplayName("grade de retificações — JSON do service e XLSX pelo ReportService")
    class GradeEXlsx {

        @Test
        @DisplayName("GET /api/admin/ponto/retificacoes/grade — repassa categoria/ano/mes e envelopa o retorno em {ok,data}")
        void grade_repassaParametros() throws Exception {
            when(gradeRetificacaoService.montar("operadores", 2026, 7))
                    .thenReturn(Map.of("categoria", "operadores", "dias_no_mes", 31));

            mockMvc.perform(Requests.get("/api/admin/ponto/retificacoes/grade")
                            .param("categoria", "operadores").param("ano", "2026").param("mes", "7")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.dias_no_mes").value(31));

            verify(gradeRetificacaoService).montar("operadores", 2026, 7);
        }

        /**
         * O nome do arquivo é a única lógica do controller aqui: {@code ponto_{categoria minúscula}_{AAMM}}
         * (Q31). Os headers do XLSX saem do {@code reportService.respondXlsx} — MOCKADO —, então assertá-los
         * seria assertar o stub: o que se trava é a interação (bytes do PontoXlsxService + nome calculado) e
         * o pass-through da resposta que o ReportService devolveu.
         */
        @Test
        @DisplayName("GET /api/admin/ponto/retificacoes/grade/xlsx — bytes do PontoXlsxService e nome ponto_{categoria}_{AAMM} ao ReportService")
        void gradeXlsx_repassaBytesENomeCalculado() throws Exception {
            byte[] xlsx = "PK planilha".getBytes(StandardCharsets.UTF_8);
            // Categoria com espaços: o controller repassa a string CRUA ao gerador e só limpa o NOME do
            // arquivo (strip + toLowerCase) — as duas metades ficam travadas de uma vez.
            when(pontoXlsxService.gerar(" Operadores ", 2026, 7)).thenReturn(xlsx);
            when(reportService.respondXlsx(any(), anyString()))
                    .thenReturn(ResponseEntity.ok(RESPOSTA_REPORT_SERVICE));

            mockMvc.perform(Requests.get("/api/admin/ponto/retificacoes/grade/xlsx")
                            .param("categoria", " Operadores ").param("ano", "2026").param("mes", "7")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(RESPOSTA_REPORT_SERVICE));

            // " Operadores " + 2026/07 → ponto_operadores_2607 (strip + toLowerCase + AAMM no controller).
            verify(reportService).respondXlsx(same(xlsx), eq("ponto_operadores_2607"));
        }
    }

    // ══ 5) Streaming de PDF (headers montados NO controller) ═══════════════

    @Nested
    @DisplayName("streamPdf — download do funcionário (attachment) e preview do admin (inline)")
    class StreamPdf {

        @Test
        @DisplayName("GET /api/ponto/folha/{id}/download — application/pdf, attachment com o nome do record e os bytes")
        void download_attachmentComNomeEBytes() throws Exception {
            when(pontoService.baixarFolha(PAGINA_ID, TokenFactory.USER_ID, TokenFactory.OPERADOR))
                    .thenReturn(new ArquivoPonto(PDF, "ponto_julho_2026.pdf"));

            mockMvc.perform(Requests.get("/api/ponto/folha/" + PAGINA_ID + "/download")
                            .header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition", containsString("attachment")))
                    .andExpect(header().string("Content-Disposition",
                            containsString("filename=\"ponto_julho_2026.pdf\"")))
                    .andExpect(content().bytes(PDF));

            // O dono e o papel vêm do token — o path só traz a página (ownership é do service).
            verify(pontoService).baixarFolha(PAGINA_ID, TokenFactory.USER_ID, TokenFactory.OPERADOR);
        }

        @Test
        @DisplayName("GET /api/admin/ponto/pagina/{id}/preview — mesmo PDF, mas inline (a única diferença é o disposition)")
        void preview_inline() throws Exception {
            when(pontoService.previewPagina(PAGINA_ID)).thenReturn(new ArquivoPonto(PDF, "pagina-9.pdf"));

            mockMvc.perform(Requests.get("/api/admin/ponto/pagina/" + PAGINA_ID + "/preview")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition", containsString("inline")))
                    .andExpect(header().string("Content-Disposition",
                            containsString("filename=\"pagina-9.pdf\"")));
        }

        @Test
        @DisplayName("GET /api/ponto/folha/{id}/download — folha de outra pessoa dá 403 do SERVICE (≠ o 403 do RBAC: o papel passa, o dono não)")
        void download_folhaDeOutraPessoa_403() throws Exception {
            when(pontoService.baixarFolha(anyString(), anyString(), anyString()))
                    .thenThrow(new ServiceValidationException("Acesso negado a esta folha.", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.get("/api/ponto/folha/" + PAGINA_ID + "/download")
                            .header("Authorization", operador))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    // O 403 do RBAC diria "Acesso negado." (handler) ou viria sem corpo (matcher).
                    .andExpect(jsonPath("$.error").value("Acesso negado a esta folha."));
        }
    }

    // ══ 5b) Retificação em LOTE (o único POST do funcionário com corpo estruturado) ══

    /**
     * A rota de retificação estava fora da seleção enquanto era "mais um POST por dia". Desde o C10
     * ela é o LOTE transacional (F39) — contrato NOVO, e o único caminho de gravação da retificação:
     * o corpo carrega todos os dias, e uma recusa por dia tem de chegar ao usuário nomeando o dia.
     * Aqui trava-se o que é do CONTROLLER (o corpo cru chega inteiro ao service, o dono vem do token,
     * o 201 envelopa o resumo do lote e a recusa do service vira 400 com a frase intacta).
     */
    @Nested
    @DisplayName("retificação em lote — POST /api/ponto/folha/{id}/retificacoes")
    class RetificacaoEmLote {

        private static final String CORPO = "{\"dias\":["
                + "{\"data\":\"2026-07-06\",\"ent1\":\"08:00\",\"sai1\":\"12:00\",\"ent2\":null,\"sai2\":null,\"observacoes\":\"esqueci de bater\"},"
                + "{\"data\":\"2026-07-08\",\"ent1\":\"09:00\",\"sai1\":\"15:00\",\"ent2\":null,\"sai2\":null,\"observacoes\":\"\"}]}";

        @Test
        @DisplayName("POST — 201 com os DOIS dias num corpo só (uma chamada ao service) e o dono do token")
        void retificacoes_201_umaChamadaComTodosOsDias() throws Exception {
            when(retificacaoService.criarRetificacoes(eq(PAGINA_ID), eq(TokenFactory.USER_ID), any()))
                    .thenReturn(Map.of("total", 2));

            mockMvc.perform(Requests.post("/api/ponto/folha/" + PAGINA_ID + "/retificacoes")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.total").value(2));

            // Um POST, N dias: o corpo cru chega inteiro (a validação por dia é toda do service).
            verify(retificacaoService).criarRetificacoes(eq(PAGINA_ID), eq(TokenFactory.USER_ID),
                    argThat(body -> body.get("dias") instanceof List<?> dias
                            && dias.size() == 2
                            && dias.get(0) instanceof Map<?, ?> primeiro
                            && "2026-07-06".equals(primeiro.get("data"))
                            && "08:00".equals(primeiro.get("ent1"))));
        }

        @Test
        @DisplayName("POST — dia recusado no lote vira 400 com a frase do service INTACTA (é ela que nomeia o dia)")
        void retificacoes_diaRecusado_400() throws Exception {
            when(retificacaoService.criarRetificacoes(anyString(), anyString(), any()))
                    .thenThrow(new ServiceValidationException("O dia 08/07/2026 já foi retificado."));

            mockMvc.perform(Requests.post("/api/ponto/folha/" + PAGINA_ID + "/retificacoes")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("O dia 08/07/2026 já foi retificado."));
        }

        /** Corpo ausente é problema do CLIENTE: 400 do módulo (nomeando o campo), não 500 nem 400 genérico. */
        @Test
        @DisplayName("POST sem corpo — chega ao service como null e vira 400 nomeando o campo 'dias'")
        void retificacoes_semCorpo_400() throws Exception {
            when(retificacaoService.criarRetificacoes(eq(PAGINA_ID), eq(TokenFactory.USER_ID), isNull()))
                    .thenThrow(new ServiceValidationException("Informe ao menos um dia em 'dias'."));

            mockMvc.perform(Requests.post("/api/ponto/folha/" + PAGINA_ID + "/retificacoes")
                            .header("Authorization", operador))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Informe ao menos um dia em 'dias'."));
        }

        @Test
        @DisplayName("POST sem token — 401 e o service não é tocado")
        void retificacoes_semToken_401() throws Exception {
            mockMvc.perform(Requests.post("/api/ponto/folha/" + PAGINA_ID + "/retificacoes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(retificacaoService);
        }
    }

    // ══ 6) Solicitação de folgas (banco de horas do funcionário) ═══════════

    @Nested
    @DisplayName("solicitar folgas — POST /api/ponto/banco/solicitar")
    class SolicitarFolgas {

        private static final String CORPO = "{\"dias\":[\"2026-07-20\",\"2026-07-21\"]}";

        @Test
        @DisplayName("POST /api/ponto/banco/solicitar — 201 com o corpo cru repassado ao service, junto do id e do papel do token")
        void solicitar_201() throws Exception {
            when(bancoHorasService.solicitar(eq(TokenFactory.USER_ID), eq(TokenFactory.OPERADOR), any()))
                    .thenReturn(Map.of("criadas", 2, "saldo_min", 480));

            mockMvc.perform(Requests.post("/api/ponto/banco/solicitar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.criadas").value(2));

            // O corpo chega como Map cru (sem binding): a validação dos dias é toda do service.
            verify(bancoHorasService).solicitar(eq(TokenFactory.USER_ID), eq(TokenFactory.OPERADOR),
                    argThat(body -> body.get("dias") instanceof List<?> dias
                            && dias.equals(List.of("2026-07-20", "2026-07-21"))));
        }

        @Test
        @DisplayName("POST /api/ponto/banco/solicitar — saldo insuficiente vira 400 com a mensagem do service")
        void solicitar_saldoInsuficiente_400() throws Exception {
            when(bancoHorasService.solicitar(anyString(), anyString(), any()))
                    .thenThrow(new ServiceValidationException(
                            "Saldo insuficiente: a solicitação debita 12h00 e o saldo é 04h00."));

            mockMvc.perform(Requests.post("/api/ponto/banco/solicitar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error")
                            .value("Saldo insuficiente: a solicitação debita 12h00 e o saldo é 04h00."));
        }

        @Test
        @DisplayName("POST /api/ponto/banco/solicitar — carga horária não cadastrada (gate Q17) vira 409, não um 400 genérico")
        void solicitar_cargaHorariaInvalida_409() throws Exception {
            when(bancoHorasService.solicitar(anyString(), anyString(), any()))
                    .thenThrow(new ServiceValidationException(
                            "Sua carga horária não está cadastrada corretamente. Procure a Gestão de Pessoas.",
                            HttpStatus.CONFLICT));

            mockMvc.perform(Requests.post("/api/ponto/banco/solicitar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(
                            "Sua carga horária não está cadastrada corretamente. Procure a Gestão de Pessoas."));
        }
    }

    // ══ 7) Deliberação do admin (aprovar / rejeitar) ═══════════════════════

    @Nested
    @DisplayName("deliberação do admin — aprovar (sem corpo) e rejeitar (motivo no corpo opcional)")
    class Deliberacao {

        @Test
        @DisplayName("POST .../banco/solicitacao/{id}/aprovar — 200 com o id do path e o do admin do token")
        void aprovar_200() throws Exception {
            when(bancoHorasService.aprovar(SOLICITACAO_ID, TokenFactory.USER_ID))
                    .thenReturn(Map.of("status", "APROVADA"));

            mockMvc.perform(Requests.post("/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/aprovar")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.status").value("APROVADA"));

            verify(bancoHorasService).aprovar(SOLICITACAO_ID, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("POST .../banco/solicitacao/{id}/rejeitar — o motivo é extraído do corpo opcional e repassado ao service")
        void rejeitar_comMotivo_200() throws Exception {
            when(bancoHorasService.rejeitar(SOLICITACAO_ID, TokenFactory.USER_ID, "Sem cobertura na escala"))
                    .thenReturn(Map.of("status", "REJEITADA"));

            mockMvc.perform(Requests.post("/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/rejeitar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"motivo\":\"Sem cobertura na escala\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJEITADA"));

            verify(bancoHorasService).rejeitar(SOLICITACAO_ID, TokenFactory.USER_ID, "Sem cobertura na escala");
        }

        @Test
        @DisplayName("POST .../banco/solicitacao/{id}/rejeitar — sem corpo, motivo null chega ao service, que responde 400 (a exigência é dele)")
        void rejeitar_semCorpo_motivoNull_400() throws Exception {
            when(bancoHorasService.rejeitar(eq(SOLICITACAO_ID), eq(TokenFactory.USER_ID), isNull()))
                    .thenThrow(new ServiceValidationException("Informe o motivo da rejeição."));

            mockMvc.perform(Requests.post("/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/rejeitar")
                            .header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Informe o motivo da rejeição."));

            verify(bancoHorasService).rejeitar(SOLICITACAO_ID, TokenFactory.USER_ID, null);
        }

        /**
         * O caso mais feio do F35: {@code body.get("motivo").toString()} aceitava um OBJETO e gravava o
         * literal {@code "{a=1}"} como motivo da rejeição — passava na obrigatoriedade e no teto de 300
         * do service, e o funcionário lia aquilo como justificativa. Agora o controller exige texto
         * quando o campo vem; a OBRIGATORIEDADE continua sendo do service (não é duplicada aqui — o
         * teste do "sem corpo" acima prova que o {@code null} segue chegando lá).
         */
        @Test
        @DisplayName("corrige F35 — objeto em motivo → 400 nomeando o campo, service nunca chamado (nada é gravado)")
        void rejeitar_motivoNaoTextual_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/rejeitar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"motivo\":{\"a\":1}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(containsString("motivo")));

            verifyNoInteractions(bancoHorasService);
        }

        @Test
        @DisplayName("POST .../banco/solicitacao/{id}/aprovar — solicitação inexistente vira 404 (a ordem 404→403→400 é do service; aqui prova-se o mapeamento)")
        void aprovar_inexistente_404() throws Exception {
            when(bancoHorasService.aprovar(anyString(), anyString()))
                    .thenThrow(new ServiceValidationException("Solicitação não encontrada.", HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.post("/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/aprovar")
                            .header("Authorization", admin))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Solicitação não encontrada."));
        }

        @Test
        @DisplayName("POST .../banco/solicitacao/{id}/aprovar — admin deliberando o próprio pedido vira 403 do service (T-1.2)")
        void aprovar_proprioPedido_403() throws Exception {
            when(bancoHorasService.aprovar(anyString(), anyString()))
                    .thenThrow(new ServiceValidationException("Você não pode deliberar o próprio pedido.",
                            HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.post("/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/aprovar")
                            .header("Authorization", admin))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Você não pode deliberar o próprio pedido."));
        }

        @Test
        @DisplayName("POST .../banco/solicitacao/{id}/aprovar — solicitação já deliberada vira 400")
        void aprovar_jaDeliberada_400() throws Exception {
            when(bancoHorasService.aprovar(anyString(), anyString()))
                    .thenThrow(new ServiceValidationException("Apenas solicitações pendentes podem ser deliberadas."));

            mockMvc.perform(Requests.post("/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/aprovar")
                            .header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Apenas solicitações pendentes podem ser deliberadas."));
        }
    }

    // ══ 8) Listagem paginada e relatório ═══════════════════════════════════

    @Nested
    @DisplayName("fila do admin — paginação/filtros e o relatório PDF/DOCX")
    class ListagemERelatorio {

        @Test
        @DisplayName("GET /api/admin/ponto/banco/solicitacoes — page/limit/sort/direction/search/filters repassados; meta e facetas no envelope")
        void solicitacoesAdmin_repassaPaginacaoEFiltros() throws Exception {
            when(bancoHorasService.listSolicitacoesAdmin(TokenFactory.USER_ID, 2, 5, "nome", "desc",
                    "fulano", Map.of("status", List.of("PENDENTE"))))
                    .thenReturn(new PagedResult(List.of(Map.of("id", SOLICITACAO_ID)), 7,
                            Map.of("status", List.of(Map.of("value", "PENDENTE")))));

            mockMvc.perform(Requests.get("/api/admin/ponto/banco/solicitacoes")
                            .param("page", "2").param("limit", "5")
                            .param("sort", "nome").param("direction", "desc")
                            .param("search", "fulano")
                            .param("filters", "{\"status\":[\"PENDENTE\"]}")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(SOLICITACAO_ID))
                    .andExpect(jsonPath("$.meta.page").value(2))
                    .andExpect(jsonPath("$.meta.limit").value(5))
                    .andExpect(jsonPath("$.meta.total").value(7))
                    .andExpect(jsonPath("$.meta.pages").value(2)) // ceil(7/5) — cálculo do pagedResponse
                    // As facetas do PagedResult entram no meta.distinct: é delas que vivem os filtros de coluna.
                    .andExpect(jsonPath("$.meta.distinct.status[0].value").value("PENDENTE"));

            verify(bancoHorasService).listSolicitacoesAdmin(TokenFactory.USER_ID, 2, 5, "nome", "desc",
                    "fulano", Map.of("status", List.of("PENDENTE")));
        }

        /**
         * Contraste deliberado com o binding do F6: aqui page/limit são {@code @RequestParam String}
         * passados pelo {@code getInt}, que ENGOLE o lixo e cai no default (1/25) — não é 400. E um
         * {@code filters} que não é JSON vira {@code null} no {@code parseJson}, também sem erro. É o
         * contrato atual das listagens; só os params tipados ({@code int}) devolvem 400.
         */
        @Test
        @DisplayName("GET /api/admin/ponto/banco/solicitacoes — page/limit não numéricos caem no default (1/25) e filters malformado vira null, sem 400")
        void solicitacoesAdmin_paramsLixo_caemNoDefault() throws Exception {
            mockMvc.perform(Requests.get("/api/admin/ponto/banco/solicitacoes")
                            .param("page", "abc").param("limit", "-").param("filters", "{isto-nao-e-json")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.page").value(1))
                    .andExpect(jsonPath("$.meta.limit").value(25));

            verify(bancoHorasService).listSolicitacoesAdmin(TokenFactory.USER_ID, 1, 25, "padrao", "asc",
                    null, null);
        }

        /**
         * O relatório dispara o {@code respond(format, nome, pdfSupplier, docxSupplier)} do ReportService
         * — MOCKADO —, então os headers seriam do stub. O que importa travar: o format default (pdf), o
         * nome do arquivo, e que os suppliers geram sobre as rows ENRIQUECIDAS
         * ({@code enriquecerRowsParaRelatorioSolicitacoesAdmin}), não sobre as cruas. Os matchers
         * invocam cada supplier — é assim que se prova a fiação sem o dispatcher real.
         */
        @Test
        @DisplayName("GET /api/admin/ponto/banco/solicitacoes/relatorio — format default pdf, nome fixo e suppliers sobre as rows enriquecidas")
        void relatorioAdmin_dispatchComRowsEnriquecidas() throws Exception {
            List<Map<String, Object>> cruas = List.of(Map.of("id", SOLICITACAO_ID));
            List<Map<String, Object>> enriquecidas = List.of(Map.of("id", SOLICITACAO_ID, "nome", "Fulano"));
            byte[] pdf = "PDF-DO-RELATORIO".getBytes(StandardCharsets.UTF_8);
            byte[] docx = "DOCX-DO-RELATORIO".getBytes(StandardCharsets.UTF_8);

            when(bancoHorasService.listSolicitacoesAdmin(TokenFactory.USER_ID, 1, ControllerUtils.REPORT_LIMIT,
                    "padrao", "asc", null, null, true))
                    .thenReturn(new PagedResult(cruas, 1, Map.of()));
            when(bancoHorasService.enriquecerRowsParaRelatorioSolicitacoesAdmin(cruas)).thenReturn(enriquecidas);
            when(pdfService.gerarRelatorioSolicitacoesAdmin(enriquecidas)).thenReturn(pdf);
            when(docxService.gerarRelatorioSolicitacoesAdmin(enriquecidas)).thenReturn(docx);
            // respond devolve ResponseEntity<?> (wildcard) → doReturn evita o problema de captura de tipo (T16).
            doReturn(ResponseEntity.ok(RESPOSTA_REPORT_SERVICE))
                    .when(reportService).respond(anyString(), anyString(), any(), any());

            mockMvc.perform(Requests.get("/api/admin/ponto/banco/solicitacoes/relatorio")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(RESPOSTA_REPORT_SERVICE));

            verify(reportService).respond(eq("pdf"), eq("relatorio_solicitacoes_banco"),
                    argThat(supplier -> Arrays.equals(pdf, supplier.get())),
                    argThat(supplier -> Arrays.equals(docx, supplier.get())));
        }
    }

    // ══ 9) Binding inválido → 400 (F6, corrigido no C4) ════════════════════

    @Nested
    @DisplayName("corrige F6 — requisição malformada responde 400 no shape padrão, não 500")
    class BindingInvalido {

        @Test
        @DisplayName("GET /api/admin/ponto/marcacoes — param tipado com lixo (?ano=abc) dá 400, e o service não é chamado")
        void paramTipadoInvalido_400() throws Exception {
            // ano/mes são @RequestParam int: valor não-numérico → MethodArgumentTypeMismatchException,
            // hoje tratada pelo handler de requisição malformada (F6, corrigido no C4) → 400.
            mockMvc.perform(Requests.get("/api/admin/ponto/marcacoes")
                            .param("ano", "abc").param("mes", "7")
                            .header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(MSG_BINDING_INVALIDO));

            verifyNoInteractions(marcacaoService);
        }

        @Test
        @DisplayName("POST /api/ponto/banco/solicitar — corpo JSON ilegível dá 400")
        void corpoJsonIlegivel_400() throws Exception {
            mockMvc.perform(Requests.post("/api/ponto/banco/solicitar")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dias\":["))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(MSG_BINDING_INVALIDO));

            verifyNoInteractions(bancoHorasService);
        }

        @Test
        @DisplayName("POST /api/admin/ponto/upload — sem a parte 'arquivo' dá 400 (parte multipart ausente)")
        void parteMultipartAusente_400() throws Exception {
            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .param("tipo", "MENSAL")
                            .param("data_inicio", "2026-07-01")
                            .param("data_fim", "2026-07-31")
                            .header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(MSG_BINDING_INVALIDO));

            verifyNoInteractions(pontoService);
        }
    }

    // ══ Helpers ════════════════════════════════════════════════════════════

    private ResultActions executar(String rota, String papel) throws Exception {
        MockHttpServletRequestBuilder req = Requests.get(rota);
        if (!SEM_TOKEN.equals(papel)) {
            req.header("Authorization", "Bearer " + tokens.valido(papel));
        }
        return mockMvc.perform(req);
    }
}
