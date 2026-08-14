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
import org.junit.jupiter.params.provider.ValueSource;
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
import br.leg.senado.nusp.service.SumarioOcorrenciasService;
import br.leg.senado.nusp.service.TipoMarcacaoService;

import static org.hamcrest.Matchers.containsString;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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
 * Contrato HTTP do {@link PontoController} — controller MISTO (sem {@code @RequestMapping} de
 * classe): rotas admin ({@code /api/admin/ponto/**}) e do funcionário ({@code /api/ponto/**}) no
 * mesmo arquivo; a cadeia completa (filtro JWT + matcher {@code /api/admin/**} +
 * {@code @AdminOnly}) sobre estas rotas é medida aqui. Com os services mockados, endpoint
 * homogêneo é plumbing de delegação — cobre-se cada FAMÍLIA de contrato em endpoints
 * representativos, mais os singulares (upload multipart, publicação, grade/XLSX, streaming de
 * PDF, retificação em lote, folgas, deliberação, relatório, paginação e binding), sem exaurir
 * os 35 mappings.
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

    /** O username que o token carrega ({@code teste.<perfil>}) — é ele que o controller repassa. */
    private static final String USERNAME_DO_TOKEN = "teste." + TokenFactory.ADMIN;

    /** O username do token de OPERADOR — o download da própria folha viaja com ele. */
    private static final String USERNAME_OPERADOR = "teste." + TokenFactory.OPERADOR;

    /** Mensagem única do handler de requisição malformada. */
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
    @MockitoBean private TipoMarcacaoService tipoMarcacaoService;
    @MockitoBean private GradeRetificacaoService gradeRetificacaoService;
    @MockitoBean private SumarioOcorrenciasService sumarioOcorrenciasService;
    @MockitoBean private PontoXlsxService pontoXlsxService;
    @MockitoBean private BancoHorasService bancoHorasService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReportPdfService pdfService;
    @MockitoBean private ReportDocxService docxService;
    // ObjectMapper (a última dependência do construtor) vem do slice — NÃO mockar.

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

        // Stubs do braço 2xx da matriz (valores diferentes do default do Mockito).
        when(pontoService.listarLotes(USERNAME_DO_TOKEN)).thenReturn(List.of(Map.of("id", LOTE_ID, "paginas", 42)));
        when(pontoService.minhasFolhas(TokenFactory.USER_ID))
                .thenReturn(List.of(Map.of("id", PAGINA_ID, "mes_ref", "2026-07")));
        // O papel entra no service (role do token) → matcher no role, valor exato no id do dono.
        when(bancoHorasService.listMinhasSolicitacoes(eq(TokenFactory.USER_ID), anyString(),
                eq(1), eq(25), eq("data_solicitacao"), eq("desc"), isNull()))
                .thenReturn(new PagedResult(List.of(Map.of("id", SOLICITACAO_ID)), 1, Map.of()));
        when(bancoHorasService.listSolicitacoesAdmin(TokenFactory.USER_ID, 1, 25, "data_solicitacao", "desc", null, null))
                .thenReturn(new PagedResult(List.of(Map.of("id", SOLICITACAO_ID)), 1, Map.of()));
    }

    // ══ 1) Matriz RBAC do controller misto (cadeia completa) ═══════════════

    /**
     * Rotas GET representativas dos dois prefixos, incluindo um PAR do mesmo domínio (as
     * solicitações do banco de horas): a fila do admin exige ADMINISTRADOR e a lista do próprio
     * funcionário aceita os 3 papéis — no MESMO arquivo.
     *
     * <p>O dado é tabular por natureza (rota × papel × status), a situação que justifica
     * {@code @ParameterizedTest}; é o mesmo idiom do {@code RbacMatrixMistoTest}.
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

        verify(pontoService).listarLotes(USERNAME_DO_TOKEN);
    }

    // ══ 3) Exclusão de lote/página ═════════════════════════════════════════

    /**
     * A permissão de excluir viaja como FLAG de CADA LOTE da listagem, computada pelo backend a
     * partir do status do lote e do {@code username} do principal (o front nunca compara nome de
     * usuário). É ela que faz o X aparecer naquela linha — e é só um controle de UI: quem chamar o
     * DELETE mesmo assim leva o 403 do service.
     */
    @Nested
    @DisplayName("exclusão de lote/página (em revisão, qualquer admin; publicado, o master)")
    class ExclusaoDePublicacoes {

        private static final String PREVIEW_LOTE = "/api/admin/ponto/lote/" + LOTE_ID + "/exclusao/preview";
        private static final String PREVIEW_PAGINA =
                "/api/admin/ponto/lote/" + LOTE_ID + "/pagina/" + PAGINA_ID + "/exclusao/preview";
        private static final String DELETE_LOTE = "/api/admin/ponto/lote/" + LOTE_ID;
        private static final String DELETE_PAGINA = "/api/admin/ponto/lote/" + LOTE_ID + "/pagina/" + PAGINA_ID;

        /**
         * A flag é decidida LOTE A LOTE, pelo status daquele lote e pelo username do principal — e o
         * lote do service chega intacto: a permissão é acrescentada numa cópia, nunca escrita por
         * cima do payload que ele montou.
         */
        @Test
        @DisplayName("GET /lotes: o pode_excluir vem em CADA lote, decidido pelo status e pelo principal")
        void listagemCarregaAFlagPorLote() throws Exception {
            when(pontoService.listarLotes(USERNAME_DO_TOKEN)).thenReturn(List.of(
                    Map.of("id", LOTE_ID, "status", "REVISAO"),
                    Map.of("id", PAGINA_ID, "status", "PUBLICADO")));
            when(pontoExclusaoService.podeExcluir("REVISAO", USERNAME_DO_TOKEN)).thenReturn(true);
            when(pontoExclusaoService.podeExcluir("PUBLICADO", USERNAME_DO_TOKEN)).thenReturn(false);

            mockMvc.perform(Requests.get("/api/admin/ponto/lotes").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(LOTE_ID))
                    .andExpect(jsonPath("$.data[0].pode_excluir").value(true))
                    .andExpect(jsonPath("$.data[1].pode_excluir").value(false))
                    // A flag saiu da raiz: quem lê o envelope não decide mais nada por ela.
                    .andExpect(jsonPath("$.pode_excluir").doesNotExist());
        }

        /**
         * A permissão acompanha o lote em toda resposta que o descreve — e a publicação é o momento
         * exato em que ela muda: o lote que qualquer admin podia descartar vira publicado, e o X tem
         * de sumir ali, sem esperar uma nova carga da listagem.
         */
        @Test
        @DisplayName("a publicação e o detalhe devolvem o lote com o pode_excluir do status ATUAL")
        void permissaoAcompanhaOLotePublicado() throws Exception {
            when(pontoService.publicar(LOTE_ID, true, false, USERNAME_DO_TOKEN))
                    .thenReturn(Map.of("id", LOTE_ID, "status", "PUBLICADO"));
            when(pontoService.obterLote(LOTE_ID, USERNAME_DO_TOKEN)).thenReturn(Map.of("id", LOTE_ID, "status", "PUBLICADO"));
            when(pontoExclusaoService.podeExcluir("PUBLICADO", USERNAME_DO_TOKEN)).thenReturn(false);

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pode_excluir").value(false));

            mockMvc.perform(Requests.get("/api/admin/ponto/lote/" + LOTE_ID).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pode_excluir").value(false));
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
        @DisplayName("DELETE do lote e da página: o controller passa username (permissão) e id (autoria da trilha)")
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
        @DisplayName("admin comum autenticado: o 403 do service chega ao cliente nas quatro rotas")
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

    /**
     * Catálogo de tipos de ocorrência: ler é de qualquer admin, escrever é do master. Aqui se mede
     * a cadeia HTTP — quem é barrado antes do dispatch, o que o controller repassa ao service
     * (username decide a PERMISSÃO, id assina a AUTORIA da trilha) e o formato das respostas.
     */
    @Nested
    @DisplayName("Catálogo de tipos de ocorrência")
    class TiposDeMarcacao {

        private static final String TIPO_ID = "b3d2e1f0-1111-4a2b-8c3d-4e5f60718293";
        private static final String TIPOS = "/api/admin/ponto/tipos-marcacao";
        private static final String PREVIEW_TIPO = TIPOS + "/" + TIPO_ID + "/exclusao/preview";
        private static final String DELETE_TIPO = TIPOS + "/" + TIPO_ID;

        /** O username que o token carrega ({@code teste.<perfil>}) — é ele que o controller repassa. */
        private static final String USERNAME_DO_TOKEN = "teste." + TokenFactory.ADMIN;

        @Test
        @DisplayName("GET .../tipos-marcacao — devolve o catálogo; sem escopo na query, o service recebe null")
        void listaOCatalogo() throws Exception {
            when(tipoMarcacaoService.listar(null)).thenReturn(
                    Map.of("tipos", List.of(Map.of("id", TIPO_ID, "nome", "Feriado",
                            "badge", "Fer", "escopo", "GLOBAL"))));

            mockMvc.perform(Requests.get(TIPOS).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.tipos[0].nome").value("Feriado"))
                    .andExpect(jsonPath("$.data.tipos[0].badge").value("Fer"));

            verify(tipoMarcacaoService).listar(null);
        }

        @Test
        @DisplayName("GET .../tipos-marcacao?escopo=INDIVIDUAL — o escopo da query chega ao service")
        void listaPorEscopo() throws Exception {
            when(tipoMarcacaoService.listar("INDIVIDUAL")).thenReturn(Map.of("tipos", List.of()));

            mockMvc.perform(Requests.get(TIPOS).param("escopo", "INDIVIDUAL")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.tipos").isEmpty());

            verify(tipoMarcacaoService).listar("INDIVIDUAL");
        }

        @Test
        @DisplayName("POST .../tipos-marcacao — 201 com o corpo do lote; username decide a permissão, id assina a autoria")
        void cadastraLote() throws Exception {
            when(tipoMarcacaoService.criar(any(), eq(USERNAME_DO_TOKEN), eq(TokenFactory.USER_ID)))
                    .thenReturn(Map.of("criados", List.of(Map.of("nome", "Luto"))));

            mockMvc.perform(Requests.post(TIPOS)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tipos\":[{\"nome\":\"Luto\",\"badge\":\"Lut\",\"escopo\":\"GLOBAL\"}]}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.criados[0].nome").value("Luto"));

            verify(tipoMarcacaoService).criar(any(), eq(USERNAME_DO_TOKEN), eq(TokenFactory.USER_ID));
        }

        @Test
        @DisplayName("GET .../{id}/exclusao/preview — as contagens do que morre chegam ao modal")
        void previewDaExclusao() throws Exception {
            when(tipoMarcacaoService.previewExclusao(TIPO_ID, USERNAME_DO_TOKEN)).thenReturn(
                    Map.of("marcacoes", 4, "pessoas_afetadas", 2, "pessoas", List.of("Ana", "Bruno")));

            mockMvc.perform(Requests.get(PREVIEW_TIPO).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.marcacoes").value(4))
                    .andExpect(jsonPath("$.data.pessoas[1]").value("Bruno"));
        }

        @Test
        @DisplayName("DELETE .../{id} — o resumo do que foi apagado volta ao cliente")
        void excluiTipo() throws Exception {
            when(tipoMarcacaoService.excluir(TIPO_ID, USERNAME_DO_TOKEN, TokenFactory.USER_ID))
                    .thenReturn(Map.of("marcacoes", 4));

            mockMvc.perform(Requests.delete(DELETE_TIPO).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.marcacoes").value(4));

            verify(tipoMarcacaoService).excluir(TIPO_ID, USERNAME_DO_TOKEN, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("admin comum: o 403 do service chega ao cliente nas três rotas de escrita")
        void adminComumRecebe403NasEscritas() throws Exception {
            ServiceValidationException forbidden =
                    new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
            doThrow(forbidden).when(tipoMarcacaoService).criar(any(), anyString(), anyString());
            doThrow(forbidden).when(tipoMarcacaoService).previewExclusao(anyString(), anyString());
            doThrow(forbidden).when(tipoMarcacaoService).excluir(anyString(), anyString(), anyString());

            for (MockHttpServletRequestBuilder req : List.of(
                    Requests.post(TIPOS).contentType(MediaType.APPLICATION_JSON).content("{\"tipos\":[]}"),
                    Requests.get(PREVIEW_TIPO),
                    Requests.delete(DELETE_TIPO))) {
                mockMvc.perform(req.header("Authorization", admin))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value("forbidden"));
            }
        }

        @Test
        @DisplayName("operador: barrado pelo matcher /api/admin/** antes do dispatch — o service nem é tocado")
        void naoAdminNaoChegaAoService() throws Exception {
            mockMvc.perform(Requests.get(TIPOS).header("Authorization", operador))
                    .andExpect(status().isForbidden());
            mockMvc.perform(Requests.delete(DELETE_TIPO).header("Authorization", operador))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(tipoMarcacaoService);
        }

        @Test
        @DisplayName("tipo inexistente: o 404 do service chega como 404")
        void tipoInexistente404() throws Exception {
            doThrow(new ServiceValidationException("Tipo de ocorrência não encontrado.", HttpStatus.NOT_FOUND))
                    .when(tipoMarcacaoService).excluir(anyString(), anyString(), anyString());

            mockMvc.perform(Requests.delete(DELETE_TIPO).header("Authorization", admin))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Tipo de ocorrência não encontrado."));
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
                        .content("{\"ano\":2026,\"mes\":7,\"globais\":{\"aplicar\":"
                                + "[{\"data\":\"2026-07-09\",\"tipo_id\":\"t-feriado\"}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(marcacaoService).aplicarLote(
                argThat(body -> Integer.valueOf(2026).equals(body.get("ano"))
                        && body.get("globais") instanceof Map<?, ?> globais
                        && globais.get("aplicar") instanceof List<?> aplicar
                        && aplicar.get(0) instanceof Map<?, ?> item
                        && "2026-07-09".equals(item.get("data"))
                        && "t-feriado".equals(item.get("tipo_id"))),
                eq(TokenFactory.USER_ID));
    }

    // ══ 2) Upload multipart ════════════════════════════════════════════════

    @Nested
    @DisplayName("upload multipart — POST /api/admin/ponto/upload")
    class Upload {

        @Test
        @DisplayName("POST /api/admin/ponto/upload — 201 com o arquivo, os params e o id do admin do token")
        void upload_multipart_201() throws Exception {
            when(pontoService.upload(any(), eq("MENSAL"), eq("PREVIA"), eq("2026-07-01"), eq("2026-07-31"),
                    eq(TokenFactory.USER_ID), eq(false), eq(USERNAME_DO_TOKEN)))
                    .thenReturn(Map.of("lote_id", LOTE_ID, "paginas", 3));

            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .file(new MockMultipartFile("arquivo", "cartao-ponto.pdf",
                                    MediaType.APPLICATION_PDF_VALUE, PDF))
                            .param("tipo", "MENSAL")
                            .param("categoria", "PREVIA")
                            .param("data_inicio", "2026-07-01")
                            .param("data_fim", "2026-07-31")
                            .header("Authorization", admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.lote_id").value(LOTE_ID))
                    .andExpect(jsonPath("$.data.paginas").value(3));

            // O UUID do dono NÃO vem do corpo: é o principal.getId() do token.
            verify(pontoService).upload(
                    argThat(f -> "cartao-ponto.pdf".equals(f.getOriginalFilename())
                            && "arquivo".equals(f.getName())
                            && f.getSize() == PDF.length),
                    eq("MENSAL"), eq("PREVIA"), eq("2026-07-01"), eq("2026-07-31"),
                    eq(TokenFactory.USER_ID), eq(false), eq(USERNAME_DO_TOKEN));
        }

        /**
         * O envio oculto não tem checkbox na tela: é o campo {@code oculto} do multipart, feito
         * direto pela API. O que este teste trava é a travessia — o campo chega ao service como
         * booleano verdadeiro; sem ele, um typo no nome do param deixaria o upload oculto
         * silenciosamente inerte (o default {@code false} mascara tudo).
         */
        @Test
        @DisplayName("upload com oculto=true — o campo do multipart chega ao service como booleano")
        void upload_ocultoChegaAoService() throws Exception {
            when(pontoService.upload(any(), eq("MENSAL"), eq("DEFINITIVA"), eq("2025-05-01"),
                    eq("2025-05-31"), eq(TokenFactory.USER_ID), eq(true), eq(USERNAME_DO_TOKEN)))
                    .thenReturn(Map.of("lote_id", LOTE_ID, "paginas", 1));

            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .file(new MockMultipartFile("arquivo", "acervo.pdf",
                                    MediaType.APPLICATION_PDF_VALUE, PDF))
                            .param("tipo", "MENSAL")
                            .param("categoria", "DEFINITIVA")
                            .param("data_inicio", "2025-05-01")
                            .param("data_fim", "2025-05-31")
                            .param("oculto", "true")
                            .header("Authorization", admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.lote_id").value(LOTE_ID));

            verify(pontoService).upload(any(), eq("MENSAL"), eq("DEFINITIVA"), eq("2025-05-01"),
                    eq("2025-05-31"), eq(TokenFactory.USER_ID), eq(true), eq(USERNAME_DO_TOKEN));
        }

        /**
         * A folha mensal viaja com a NATUREZA escolhida no envio (prévia abre o mês, definitiva o
         * fecha), num campo próprio do multipart posicionado entre o tipo e as datas. É a posição
         * que este teste trava: categoria e data_inicio são ambos texto, então trocá-las de lugar
         * compila — e o lote nasceria com a data no lugar da natureza, sem erro nenhum.
         */
        @ParameterizedTest(name = "[{index}] folha mensal {0}")
        @ValueSource(strings = {"PREVIA", "DEFINITIVA"})
        @DisplayName("upload mensal — a natureza escolhida chega ao service entre o tipo e as datas")
        void upload_mensal_naturezaChegaAoService(String categoria) throws Exception {
            when(pontoService.upload(any(), eq("MENSAL"), eq(categoria), eq("2026-07-01"),
                    eq("2026-07-31"), eq(TokenFactory.USER_ID), eq(false), eq(USERNAME_DO_TOKEN)))
                    .thenReturn(Map.of("lote_id", LOTE_ID, "paginas", 3));

            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .file(new MockMultipartFile("arquivo", "cartao-ponto.pdf",
                                    MediaType.APPLICATION_PDF_VALUE, PDF))
                            .param("tipo", "MENSAL")
                            .param("categoria", categoria)
                            .param("data_inicio", "2026-07-01")
                            .param("data_fim", "2026-07-31")
                            .header("Authorization", admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.lote_id").value(LOTE_ID));

            verify(pontoService).upload(any(), eq("MENSAL"), eq(categoria), eq("2026-07-01"),
                    eq("2026-07-31"), eq(TokenFactory.USER_ID), eq(false), eq(USERNAME_DO_TOKEN));
        }

        /**
         * A folha semanal não é prévia nem definitiva e por isso o front nem envia o campo — o
         * multipart chega SEM a parte {@code categoria}. Ela é opcional no controller de propósito:
         * exigi-la mataria o envio semanal com um 400 de parâmetro ausente, antes de o service
         * poder decidir qualquer coisa. O que chega lá é {@code null}, e o 201 sai igual.
         */
        @Test
        @DisplayName("upload semanal sem o campo categoria — o service recebe null e o envio continua 201")
        void upload_semanalSemCategoria_recebeNull() throws Exception {
            when(pontoService.upload(any(), eq("SEMANAL"), isNull(), eq("2026-07-06"), eq("2026-07-12"),
                    eq(TokenFactory.USER_ID), eq(false), eq(USERNAME_DO_TOKEN)))
                    .thenReturn(Map.of("lote_id", LOTE_ID, "paginas", 5));

            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .file(new MockMultipartFile("arquivo", "cartao-ponto.pdf",
                                    MediaType.APPLICATION_PDF_VALUE, PDF))
                            .param("tipo", "SEMANAL")
                            .param("data_inicio", "2026-07-06")
                            .param("data_fim", "2026-07-12")
                            .header("Authorization", admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.lote_id").value(LOTE_ID))
                    .andExpect(jsonPath("$.data.paginas").value(5));

            verify(pontoService).upload(any(), eq("SEMANAL"), isNull(), eq("2026-07-06"),
                    eq("2026-07-12"), eq(TokenFactory.USER_ID), eq(false), eq(USERNAME_DO_TOKEN));
        }

        /**
         * Requisição NÃO-multipart no endpoint de upload (o cliente errou o Content-Type) faz o
         * resolver do {@code @RequestParam MultipartFile} lançar
         * {@code MultipartException("Current request is not a multipart request")} — um erro do
         * CLIENTE. A {@code MultipartException} está na lista do {@code handleBadRequest} → 400 no
         * shape padrão, log WARN.
         *
         * <p>A resposta depende de o {@code MultipartFile} ser o PRIMEIRO argumento resolvido: reordenar
         * a assinatura faria um {@code @RequestParam} String faltar antes, e o 400 viria por outro
         * caminho — com a MESMA mensagem, que é genérica. Se este teste mudar de comportamento
         * depois de um refactor da assinatura, é isto.
         */
        @Test
        @DisplayName("upload com Content-Type JSON (não multipart) responde 400, não 500")
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
            when(pontoService.upload(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                    .thenThrow(new ServiceValidationException("O arquivo enviado não é um PDF válido."));

            mockMvc.perform(Requests.multipart("/api/admin/ponto/upload")
                            .file(new MockMultipartFile("arquivo", "nao-e-pdf.txt",
                                    MediaType.TEXT_PLAIN_VALUE, "isto não é um PDF".getBytes(StandardCharsets.UTF_8)))
                            .param("tipo", "MENSAL")
                            .param("categoria", "PREVIA")
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
     * O corpo do vínculo é opcional em dois sentidos diferentes, e é essa distinção que a leitura tem de
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
            when(pontoService.atualizarVinculo(LOTE_ID, PAGINA_ID, "op-1", "OPERADOR", USERNAME_DO_TOKEN))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "MANUAL"));

            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pessoa_id\":\"op-1\",\"pessoa_tipo\":\"OPERADOR\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.status_match").value("MANUAL"));

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, "op-1", "OPERADOR", USERNAME_DO_TOKEN);
        }

        /**
         * O teste-guardião: sem corpo, os DOIS campos chegam ao service como {@code null} —
         * é assim que o admin desfaz um vínculo errado. Se um dia alguém trocar a leitura por um helper
         * de campo obrigatório, este teste cai antes de a função sumir da tela.
         */
        @Test
        @DisplayName("PATCH sem corpo continua DESVINCULANDO: o service recebe null/null")
        void vincular_semCorpo_desvincula() throws Exception {
            when(pontoService.atualizarVinculo(eq(LOTE_ID), eq(PAGINA_ID), isNull(), isNull(), eq(USERNAME_DO_TOKEN)))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "PENDENTE"));

            mockMvc.perform(Requests.patch(ROTA).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status_match").value("PENDENTE"));

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, null, null, USERNAME_DO_TOKEN);
        }

        /**
         * O corpo que a UI realmente manda ao desvincular (`admin-ponto.component.ts#onAssign`): os campos
         * vêm com {@code null} EXPLÍCITO, não ausentes. É o caminho de produção do desvincular, e é o que
         * um helper que confundisse "null" com "tipo inválido" teria quebrado — na tela, não no teste.
         */
        @Test
        @DisplayName("PATCH {\"pessoa_id\":null,\"pessoa_tipo\":null} (o corpo REAL do front) desvincula")
        void vincular_nullsExplicitos_desvincula() throws Exception {
            when(pontoService.atualizarVinculo(eq(LOTE_ID), eq(PAGINA_ID), isNull(), isNull(), eq(USERNAME_DO_TOKEN)))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "PENDENTE"));

            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pessoa_id\":null,\"pessoa_tipo\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status_match").value("PENDENTE"));

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, null, null, USERNAME_DO_TOKEN);
        }

        /** Idem com corpo vazio: {@code {}} não é "tipo errado", é ausência — desvincula igual. */
        @Test
        @DisplayName("PATCH com corpo {} também desvincula (ausência ≠ tipo errado)")
        void vincular_corpoVazio_desvincula() throws Exception {
            when(pontoService.atualizarVinculo(eq(LOTE_ID), eq(PAGINA_ID), isNull(), isNull(), eq(USERNAME_DO_TOKEN)))
                    .thenReturn(Map.of("id", LOTE_ID, "status_match", "PENDENTE"));

            mockMvc.perform(Requests.patch(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            verify(pontoService).atualizarVinculo(LOTE_ID, PAGINA_ID, null, null, USERNAME_DO_TOKEN);
        }

        /**
         * Antes: {@code body.get("pessoa_tipo").toString()} transformava a lista {@code ["OPERADOR"]} no
         * texto {@code "[OPERADOR]"}, e o service recusava com uma mensagem sobre pessoa inválida — a
         * mensagem errada para o defeito certo. Agora o tipo é conferido no controller, e a publicação
         * do erro nomeia o campo.
         */
        @Test
        @DisplayName("lista em pessoa_tipo → 400 nomeando o campo, service nunca chamado")
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
        @DisplayName("objeto em pessoa_id → 400 nomeando o campo, service nunca chamado")
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
            when(pontoService.publicar(LOTE_ID, true, false, USERNAME_DO_TOKEN)).thenReturn(Map.of("publicado", true, "avisos", 12));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.avisos").value(12));

            verify(pontoService).publicar(LOTE_ID, true, false, USERNAME_DO_TOKEN);
        }

        @Test
        @DisplayName("POST /api/admin/ponto/lote/{id}/publicar — corpo {emitir_aviso:false} publica calado")
        void publicar_emitirAvisoFalse_naoEmite() throws Exception {
            when(pontoService.publicar(LOTE_ID, false, false, USERNAME_DO_TOKEN)).thenReturn(Map.of("publicado", true, "avisos", 0));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emitir_aviso\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.avisos").value(0));

            verify(pontoService).publicar(LOTE_ID, false, false, USERNAME_DO_TOKEN);
        }

        /**
         * O desligamento do aviso era
         * {@code !Boolean.FALSE.equals(valor)}, então a STRING "false" — tipo errado, não booleano — não
         * desligava nada: o lote era publicado COM aviso, e a publicação dispara um aviso PESSOAL para
         * CADA pessoa da folha. O cliente pedia silêncio e recebia notificação em massa, sem erro.
         *
         * <p>Agora o campo exige booleano genuíno: outro tipo → 400 nomeando {@code emitir_aviso}, e
         * <b>nada é publicado</b> (é o que o {@code verifyNoInteractions} trava — a recusa tem de vir
         * ANTES da publicação, não depois).
         */
        @Test
        @DisplayName("corpo {emitir_aviso:\"false\"} (string) → 400 nomeando o campo, sem publicar nada")
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
        @DisplayName("{emitir_aviso:0} (número) também é 400 nomeando o campo, sem publicar")
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
        @DisplayName("corpo sem o campo emitir_aviso mantém o default (publica COM aviso)")
        void publicar_corpoSemOCampo_emiteAviso() throws Exception {
            when(pontoService.publicar(LOTE_ID, true, false, USERNAME_DO_TOKEN)).thenReturn(Map.of("publicado", true, "avisos", 12));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"outro_campo\":123}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.avisos").value(12));

            verify(pontoService).publicar(LOTE_ID, true, false, USERNAME_DO_TOKEN);
        }

        /**
         * Os dois passos da substituição, do lado do contrato: o 1º POST não manda o campo e recebe o
         * pedido de confirmação — nada publicado; o 2º manda {@code confirmar_substituicao} e publica.
         * Substituir folha de outras pessoas nunca acontece por omissão do cliente.
         */
        @Test
        @DisplayName("sem confirmar_substituicao o service recebe false, e a resposta de confirmação passa inteira")
        void publicar_semConfirmacao_repassaFalseEDevolveOPedido() throws Exception {
            when(pontoService.publicar(LOTE_ID, true, false, USERNAME_DO_TOKEN))
                    .thenReturn(Map.of("requer_confirmacao", true, "substituicoes", 7));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.requer_confirmacao").value(true))
                    .andExpect(jsonPath("$.data.substituicoes").value(7))
                    // Não é um lote: não há status, e nenhuma permissão de exclusão a carregar.
                    .andExpect(jsonPath("$.data.pode_excluir").doesNotExist());
        }

        @Test
        @DisplayName("corpo {confirmar_substituicao:true} autoriza a substituição no service")
        void publicar_comConfirmacao_repassaTrue() throws Exception {
            when(pontoService.publicar(LOTE_ID, true, true, USERNAME_DO_TOKEN))
                    .thenReturn(Map.of("id", LOTE_ID, "status", "PUBLICADO"));

            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmar_substituicao\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLICADO"));

            verify(pontoService).publicar(LOTE_ID, true, true, USERNAME_DO_TOKEN);
        }

        @Test
        @DisplayName("{confirmar_substituicao:\"true\"} (string) → 400 nomeando o campo, sem publicar")
        void publicar_confirmarSubstituicaoString_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/ponto/lote/" + LOTE_ID + "/publicar")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmar_substituicao\":\"true\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("confirmar_substituicao")));

            verifyNoInteractions(pontoService);
        }

        @Test
        @DisplayName("POST /api/admin/ponto/lote/{id}/publicar — lote já publicado vira 400 com a mensagem do service")
        void publicar_loteJaPublicado_400() throws Exception {
            when(pontoService.publicar(eq(LOTE_ID), anyBoolean(), anyBoolean(), anyString()))
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

    // ══ 4b) Sumário de ocorrências das folhas ══════════════════════════════

    @Nested
    @DisplayName("sumário de ocorrências — repasse do intervalo de competências")
    class SumarioDeOcorrencias {

        @Test
        @DisplayName("GET /api/admin/ponto/ocorrencias/sumario — repassa de/ate e envelopa o retorno em {ok,data}")
        void sumario_repassaIntervalo() throws Exception {
            when(sumarioOcorrenciasService.sumario("2026-01", "2026-12"))
                    .thenReturn(Map.of("de", "2026-01", "ocorrencias", List.of(Map.of("codigo", "FERNC"))));

            mockMvc.perform(Requests.get("/api/admin/ponto/ocorrencias/sumario")
                            .param("de", "2026-01").param("ate", "2026-12")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.ocorrencias[0].codigo").value("FERNC"));

            verify(sumarioOcorrenciasService).sumario("2026-01", "2026-12");
        }

        /**
         * Os parâmetros são opcionais no controller de propósito: a ausência tem de chegar ao service,
         * que responde com a frase em pt-BR que nomeia o campo. Exigi-los aqui devolveria o 400 genérico
         * do Spring, sem dizer qual campo falta nem em que formato.
         */
        @Test
        @DisplayName("sem os parâmetros — a validação é do service, que recebe nulo")
        void sumario_semParametros_chegaNuloAoService() throws Exception {
            when(sumarioOcorrenciasService.sumario(null, null))
                    .thenThrow(new ServiceValidationException("Competência inválida em de (use AAAA-MM)."));

            mockMvc.perform(Requests.get("/api/admin/ponto/ocorrencias/sumario").header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Competência inválida em de (use AAAA-MM)."));

            verify(sumarioOcorrenciasService).sumario(null, null);
        }

        @Test
        @DisplayName("rota admin: operador leva 403 e o service nem é tocado")
        void sumario_operadorBarrado() throws Exception {
            mockMvc.perform(Requests.get("/api/admin/ponto/ocorrencias/sumario")
                            .param("de", "2026-01").param("ate", "2026-12")
                            .header("Authorization", operador))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(sumarioOcorrenciasService);
        }
    }

    // ══ 5) Streaming de PDF (headers montados NO controller) ═══════════════

    @Nested
    @DisplayName("streamPdf — download do funcionário (attachment) e preview do admin (inline)")
    class StreamPdf {

        @Test
        @DisplayName("GET /api/ponto/folha/{id}/download — application/pdf, attachment com o nome do record e os bytes")
        void download_attachmentComNomeEBytes() throws Exception {
            when(pontoService.baixarFolha(PAGINA_ID, TokenFactory.USER_ID, TokenFactory.OPERADOR, USERNAME_OPERADOR))
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
            verify(pontoService).baixarFolha(PAGINA_ID, TokenFactory.USER_ID, TokenFactory.OPERADOR, USERNAME_OPERADOR);
        }

        @Test
        @DisplayName("GET /api/ponto/folha/{id}/download?inline=true — mesmo PDF, exibido em vez de baixado")
        void download_inline() throws Exception {
            when(pontoService.baixarFolha(PAGINA_ID, TokenFactory.USER_ID, TokenFactory.OPERADOR, USERNAME_OPERADOR))
                    .thenReturn(new ArquivoPonto(PDF, "ponto_julho_2026.pdf"));

            mockMvc.perform(Requests.get("/api/ponto/folha/" + PAGINA_ID + "/download")
                            .param("inline", "true")
                            .header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition", containsString("inline")))
                    .andExpect(content().bytes(PDF));
        }

        @Test
        @DisplayName("GET /api/admin/ponto/pagina/{id}/preview — mesmo PDF, mas inline (a única diferença é o disposition)")
        void preview_inline() throws Exception {
            when(pontoService.previewPagina(PAGINA_ID, USERNAME_DO_TOKEN)).thenReturn(new ArquivoPonto(PDF, "pagina-9.pdf"));

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
            when(pontoService.baixarFolha(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new ServiceValidationException("Acesso negado a esta folha.", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.get("/api/ponto/folha/" + PAGINA_ID + "/download")
                            .header("Authorization", operador))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    // O 403 do RBAC diria "Acesso negado." (handler) ou viria sem corpo (matcher).
                    .andExpect(jsonPath("$.error").value("Acesso negado a esta folha."));
        }
    }

    // ══ 5b) Retificação por célula (as gravações do funcionário na própria folha) ══

    /**
     * A retificação grava de célula em célula: um PUT por horário, um PUT para a ocorrência do dia e
     * um DELETE para apagar. Aqui trava-se o que é do CONTROLLER — o corpo cru chega inteiro ao
     * service, o dono vem do token, a data do DELETE vem do caminho e a recusa do service vira 400
     * com a frase intacta.
     */
    @Nested
    @DisplayName("retificação por célula — PUT/DELETE /api/ponto/folha/{id}/retificacoes/…")
    class RetificacaoPorCelula {

        private static final String CELULA = "{\"data\":\"2026-07-06\",\"campo\":\"ent1\",\"valor\":\"08:00\"}";
        private static final String TIPO = "{\"data\":\"2026-07-06\",\"tipo_id\":\"tipo-1\"}";

        @Test
        @DisplayName("PUT célula — 200 com o corpo cru chegando ao service e o dono do token")
        void celula_200_corpoChegaInteiro() throws Exception {
            when(retificacaoService.salvarCelula(eq(PAGINA_ID), eq(TokenFactory.USER_ID), any()))
                    .thenReturn(Map.of("data", "2026-07-06", "ent1", "08:00"));

            mockMvc.perform(Requests.put("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/celula")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CELULA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.ent1").value("08:00"));

            verify(retificacaoService).salvarCelula(eq(PAGINA_ID), eq(TokenFactory.USER_ID),
                    argThat(body -> "2026-07-06".equals(body.get("data"))
                            && "ent1".equals(body.get("campo"))
                            && "08:00".equals(body.get("valor"))));
        }

        @Test
        @DisplayName("PUT célula — a recusa do service vira 400 com a frase INTACTA (é ela que nomeia o dia)")
        void celula_recusada_400() throws Exception {
            when(retificacaoService.salvarCelula(anyString(), anyString(), any()))
                    .thenThrow(new ServiceValidationException("O dia 06/07/2026 não pode mais ser retificado."));

            mockMvc.perform(Requests.put("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/celula")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CELULA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("O dia 06/07/2026 não pode mais ser retificado."));
        }

        /** Corpo ausente é problema do CLIENTE: chega ao service como null e volta 400, não 500. */
        @Test
        @DisplayName("PUT célula sem corpo — chega ao service como null e vira 400")
        void celula_semCorpo_400() throws Exception {
            when(retificacaoService.salvarCelula(eq(PAGINA_ID), eq(TokenFactory.USER_ID), isNull()))
                    .thenThrow(new ServiceValidationException("Data obrigatória."));

            mockMvc.perform(Requests.put("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/celula")
                            .header("Authorization", operador))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Data obrigatória."));
        }

        @Test
        @DisplayName("PUT tipo — o corpo da ocorrência chega ao service do jeito que veio")
        void tipo_200_corpoChegaInteiro() throws Exception {
            when(retificacaoService.salvarTipo(eq(PAGINA_ID), eq(TokenFactory.USER_ID), any()))
                    .thenReturn(Map.of("data", "2026-07-06", "tipo_nome", "Banco de horas"));

            mockMvc.perform(Requests.put("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/tipo")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TIPO))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.tipo_nome").value("Banco de horas"));

            verify(retificacaoService).salvarTipo(eq(PAGINA_ID), eq(TokenFactory.USER_ID),
                    argThat(body -> "tipo-1".equals(body.get("tipo_id"))));
        }

        @Test
        @DisplayName("DELETE — a data vem do caminho e o dono do token")
        void limpar_200_dataDoCaminho() throws Exception {
            when(retificacaoService.limpar(PAGINA_ID, TokenFactory.USER_ID, "2026-07-06"))
                    .thenReturn(Map.of("data", "2026-07-06"));

            mockMvc.perform(Requests.delete("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/2026-07-06")
                            .header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.data").value("2026-07-06"));

            verify(retificacaoService).limpar(PAGINA_ID, TokenFactory.USER_ID, "2026-07-06");
        }

        @Test
        @DisplayName("DELETE de dia sem retificação — 404 com a frase do service")
        void limpar_semRetificacao_404() throws Exception {
            when(retificacaoService.limpar(anyString(), anyString(), anyString()))
                    .thenThrow(new ServiceValidationException("Retificação não encontrada.",
                            HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.delete("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/2026-07-06")
                            .header("Authorization", operador))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Retificação não encontrada."));
        }

        @Test
        @DisplayName("sem token — 401 e o service não é tocado")
        void semToken_401() throws Exception {
            mockMvc.perform(Requests.put("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/celula")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CELULA))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(Requests.delete("/api/ponto/folha/" + PAGINA_ID + "/retificacoes/2026-07-06"))
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

    // ══ 7) Deliberação do admin (a solicitação inteira, de uma vez) ═══════

    @Nested
    @DisplayName("deliberação do admin — os dias aprovados e os rejeitados no corpo, motivo opcional")
    class Deliberacao {

        private static final String ROTA = "/api/admin/ponto/banco/solicitacao/" + SOLICITACAO_ID + "/deliberar";

        @Test
        @DisplayName("POST .../deliberar — as duas listas e o motivo chegam ao service, com o admin do token")
        void deliberar_200() throws Exception {
            when(bancoHorasService.deliberar(SOLICITACAO_ID, TokenFactory.USER_ID,
                    List.of("dia-1"), List.of("dia-2"), "Sem cobertura na escala"))
                    .thenReturn(Map.of("aprovados", 1, "rejeitados", 1));

            mockMvc.perform(Requests.post(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"aprovados\":[\"dia-1\"],\"rejeitados\":[\"dia-2\"],"
                                    + "\"motivo\":\"Sem cobertura na escala\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.aprovados").value(1))
                    .andExpect(jsonPath("$.data.rejeitados").value(1));

            verify(bancoHorasService).deliberar(SOLICITACAO_ID, TokenFactory.USER_ID,
                    List.of("dia-1"), List.of("dia-2"), "Sem cobertura na escala");
        }

        @Test
        @DisplayName("POST .../deliberar — sem corpo, listas vazias e motivo null chegam ao service, que recusa (a exigência é dele)")
        void deliberar_semCorpo_400() throws Exception {
            when(bancoHorasService.deliberar(eq(SOLICITACAO_ID), eq(TokenFactory.USER_ID),
                    eq(List.of()), eq(List.of()), isNull()))
                    .thenThrow(new ServiceValidationException("Delibere todos os dias pendentes da solicitação."));

            mockMvc.perform(Requests.post(ROTA).header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Delibere todos os dias pendentes da solicitação."));

            verify(bancoHorasService).deliberar(SOLICITACAO_ID, TokenFactory.USER_ID, List.of(), List.of(), null);
        }

        /**
         * O caso mais feio: {@code body.get("motivo").toString()} aceitava um OBJETO e gravava o
         * literal {@code "{a=1}"} como motivo da rejeição — passava na obrigatoriedade e no teto de 300
         * do service, e o funcionário lia aquilo como justificativa. Agora o controller exige texto
         * quando o campo vem; a OBRIGATORIEDADE continua sendo do service (não é duplicada aqui — o
         * teste do "sem corpo" acima prova que o {@code null} segue chegando lá).
         */
        @Test
        @DisplayName("objeto em motivo → 400 nomeando o campo, service nunca chamado (nada é gravado)")
        void deliberar_motivoNaoTextual_400() throws Exception {
            mockMvc.perform(Requests.post(ROTA)
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"motivo\":{\"a\":1}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(containsString("motivo")));

            verifyNoInteractions(bancoHorasService);
        }

        @Test
        @DisplayName("POST .../deliberar — solicitação inexistente vira 404 (a ordem 404→403→400 é do service; aqui prova-se o mapeamento)")
        void deliberar_inexistente_404() throws Exception {
            when(bancoHorasService.deliberar(anyString(), anyString(), anyList(), anyList(), any()))
                    .thenThrow(new ServiceValidationException("Solicitação não encontrada.", HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.post(ROTA).header("Authorization", admin))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Solicitação não encontrada."));
        }

        @Test
        @DisplayName("POST .../deliberar — admin deliberando o próprio pedido vira 403 do service (T-1.2)")
        void deliberar_proprioPedido_403() throws Exception {
            when(bancoHorasService.deliberar(anyString(), anyString(), anyList(), anyList(), any()))
                    .thenThrow(new ServiceValidationException("Você não pode deliberar o próprio pedido.",
                            HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.post(ROTA).header("Authorization", admin))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Você não pode deliberar o próprio pedido."));
        }

        @Test
        @DisplayName("POST .../deliberar — solicitação já deliberada vira 400")
        void deliberar_jaDeliberada_400() throws Exception {
            when(bancoHorasService.deliberar(anyString(), anyString(), anyList(), anyList(), any()))
                    .thenThrow(new ServiceValidationException("Apenas solicitações pendentes podem ser deliberadas."));

            mockMvc.perform(Requests.post(ROTA).header("Authorization", admin))
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
         * Contraste deliberado com o handler de binding inválido: aqui page/limit são {@code @RequestParam String}
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

            verify(bancoHorasService).listSolicitacoesAdmin(TokenFactory.USER_ID, 1, 25, "data_solicitacao", "desc",
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
                    "data_solicitacao", "desc", null, null, true))
                    .thenReturn(new PagedResult(cruas, 1, Map.of()));
            when(bancoHorasService.enriquecerRowsParaRelatorioSolicitacoesAdmin(cruas)).thenReturn(enriquecidas);
            when(pdfService.gerarRelatorioSolicitacoesAdmin(enriquecidas)).thenReturn(pdf);
            when(docxService.gerarRelatorioSolicitacoesAdmin(enriquecidas)).thenReturn(docx);
            // respond devolve ResponseEntity<?> (wildcard) → doReturn evita o problema de captura de tipo.
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

    // ══ 9) Binding inválido → 400 ══════════════════════════════════════════

    @Nested
    @DisplayName("requisição malformada responde 400 no shape padrão, não 500")
    class BindingInvalido {

        @Test
        @DisplayName("GET /api/admin/ponto/marcacoes — param tipado com lixo (?ano=abc) dá 400, e o service não é chamado")
        void paramTipadoInvalido_400() throws Exception {
            // ano/mes são @RequestParam int: valor não-numérico → MethodArgumentTypeMismatchException,
            // tratada pelo handler de requisição malformada → 400.
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
