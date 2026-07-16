package br.leg.senado.nusp.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AdminDashboardService;
import br.leg.senado.nusp.service.AuthService;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import br.leg.senado.nusp.service.RdsXlsxService;
import br.leg.senado.nusp.service.ReportDocxService;
import br.leg.senado.nusp.service.ReportPdfService;
import br.leg.senado.nusp.service.ReportService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link AdminDashboardController} (T16).
 *
 * Segurança real; services 100% mockados. O RBAC papel×rota já está provado
 * na matriz do T15 — aqui exercita-se, com token de admin válido, o que o
 * filtro NÃO cobre: os 3 guards MANUAIS por username (isMaster/canEditObs*,
 * família a), o contrato 404 de detalhe (b), o mapeamento
 * ServiceValidationException→HTTP do {@code GlobalExceptionHandler} (c), o
 * binding inválido respondendo 400 (d, achado F6 — corrigido no C4), o repasse de
 * paginação ao service (e) e os endpoints de contrato singular — relatório
 * com {@code format} e RDS/XLSX (f).
 *
 * Regra de dimensionamento do T16: 1–2 endpoints representativos por família,
 * nunca endpoint-a-endpoint (o controller tem ~30 endpoints homogêneos de
 * delegação — exauri-los seria testar o @RequestMapping do Spring).
 */
@SigmaControllerTest(AdminDashboardController.class)
class AdminDashboardControllerTest {

    private static final String ADMIN_USERNAME = "teste.administrador"; // TokenFactory: "teste." + perfil
    private static final String ADMIN_ROLE = "administrador";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AdminDashboardService dashboardService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReportPdfService pdfService;
    @MockitoBean private ReportDocxService docxService;
    @MockitoBean private RdsXlsxService rdsService;
    @MockitoBean private AuthService authService;

    private String admin;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        admin = "Bearer " + tokens.valido(TokenFactory.ADMIN);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    private static PagedResult vazio() {
        return new PagedResult(List.of(), 0, Map.of());
    }

    // ══ (a) Guards manuais por username — os dois ramos de cada ════════════

    @Nested
    @DisplayName("família (a) — guards manuais por username (isMaster / canEditObs*)")
    class GuardsManuais {

        @Test
        @DisplayName("administradores — isMaster=false → 403 {ok:false, error:forbidden} (o service nem é chamado)")
        void administradores_naoMaster_403() throws Exception {
            when(authService.isMaster(ADMIN_ROLE, ADMIN_USERNAME)).thenReturn(false);

            mockMvc.perform(Requests.get("/api/admin/dashboard/administradores").header("Authorization", admin))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("forbidden"));

            verify(dashboardService, never()).listAdministradores(anyInt(), anyInt(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("administradores — isMaster=true → 200 delega ao service")
        void administradores_master_200() throws Exception {
            when(authService.isMaster(ADMIN_ROLE, ADMIN_USERNAME)).thenReturn(true);
            when(dashboardService.listAdministradores(1, 25, "", "nome", "asc", null)).thenReturn(vazio());

            mockMvc.perform(Requests.get("/api/admin/dashboard/administradores").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.meta.page").value(1));
        }

        @Test
        @DisplayName("observacao-supervisor — canEditObsSupervisor=false → 403 forbidden")
        void obsSupervisor_semPermissao_403() throws Exception {
            when(authService.canEditObsSupervisor(ADMIN_ROLE, ADMIN_USERNAME)).thenReturn(false);

            mockMvc.perform(Requests.post("/api/admin/anormalidade/observacao-supervisor")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":1,\"observacao\":\"texto\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));

            verify(dashboardService, never()).salvarObservacaoSupervisor(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("observacao-supervisor — canEditObsSupervisor=true → 200 e repassa (id, obs, principal.id) ao service")
        void obsSupervisor_comPermissao_200() throws Exception {
            when(authService.canEditObsSupervisor(ADMIN_ROLE, ADMIN_USERNAME)).thenReturn(true);

            mockMvc.perform(Requests.post("/api/admin/anormalidade/observacao-supervisor")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":42,\"observacao\":\"  registrada  \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));

            // O controller faz strip() na observação e usa principal.getId() (USER_ID do TokenFactory).
            verify(dashboardService).salvarObservacaoSupervisor(42L, "registrada", TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("observacao-chefe — canEditObsChefe=false → 403 forbidden")
        void obsChefe_semPermissao_403() throws Exception {
            when(authService.canEditObsChefe(ADMIN_ROLE, ADMIN_USERNAME)).thenReturn(false);

            mockMvc.perform(Requests.post("/api/admin/anormalidade/observacao-chefe")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":1,\"observacao\":\"texto\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));

            verify(dashboardService, never()).salvarObservacaoChefe(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("observacao-chefe — canEditObsChefe=true → 200 e delega ao ramo de chefe")
        void obsChefe_comPermissao_200() throws Exception {
            when(authService.canEditObsChefe(ADMIN_ROLE, ADMIN_USERNAME)).thenReturn(true);

            mockMvc.perform(Requests.post("/api/admin/anormalidade/observacao-chefe")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":7,\"observacao\":\"ok\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));

            verify(dashboardService).salvarObservacaoChefe(7L, "ok", TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("observacao-supervisor — body sem id → 400 id_obrigatorio (validação inline do controller, antes do service)")
        void obsSupervisor_semId_400() throws Exception {
            when(authService.canEditObsSupervisor(ADMIN_ROLE, ADMIN_USERNAME)).thenReturn(true);

            mockMvc.perform(Requests.post("/api/admin/anormalidade/observacao-supervisor")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"observacao\":\"texto\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("id_obrigatorio"));
        }
    }

    // ══ (b) Contrato 404 de detalhe ════════════════════════════════════════

    @Nested
    @DisplayName("família (b) — detalhe: service null → 404 not_found; presente → 200 {ok,data}")
    class Contrato404 {

        @Test
        @DisplayName("checklist/detalhe — service devolve null → 404 {ok:false, error:not_found}")
        void checklistDetalhe_null_404() throws Exception {
            when(dashboardService.getChecklistDetalhe(5L)).thenReturn(null);

            mockMvc.perform(Requests.get("/api/admin/checklist/detalhe?checklist_id=5").header("Authorization", admin))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("not_found"));
        }

        @Test
        @DisplayName("checklist/detalhe — service devolve dados → 200 {ok:true, data}")
        void checklistDetalhe_presente_200() throws Exception {
            when(dashboardService.getChecklistDetalhe(5L)).thenReturn(Map.of("id", 5, "sala", "Plenário 1"));

            mockMvc.perform(Requests.get("/api/admin/checklist/detalhe?checklist_id=5").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.sala").value("Plenário 1"));
        }
    }

    // ══ (c) ServiceValidationException → GlobalExceptionHandler ════════════

    @Nested
    @DisplayName("família (c) — ServiceValidationException mapeada pelo GlobalExceptionHandler")
    class MapeamentoServiceHttp {

        @Test
        @DisplayName("status próprio da exceção + {ok:false, error=<message>}")
        void statusCustomizado() throws Exception {
            when(dashboardService.getChecklistVersao(9L))
                    .thenThrow(new ServiceValidationException("Snapshot do histórico inválido."));

            mockMvc.perform(Requests.get("/api/admin/checklist/historico/versao?historico_id=9").header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Snapshot do histórico inválido."));
        }

        @Test
        @DisplayName("extraFields da exceção são fundidos no corpo (putAll) — 409 {ok:false, error, ...extras}")
        void extraFieldsFundidos() throws Exception {
            when(dashboardService.getEntradaDetalhe(1L))
                    .thenThrow(new ServiceValidationException("invalid_payload", HttpStatus.CONFLICT,
                            Map.of("missing", "campo_x")));

            mockMvc.perform(Requests.get("/api/admin/operacao/detalhe?entrada_id=1").header("Authorization", admin))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("invalid_payload"))
                    .andExpect(jsonPath("$.missing").value("campo_x"));
        }
    }

    // ══ (d) Binding inválido — corrige F6 (400) ════════════════════════════

    @Test
    @DisplayName("corrige F6 — binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_corrigeF6_400() throws Exception {
        // checklist_id é @RequestParam obrigatório: ausente → MissingServletRequestParameterException,
        // agora tratada pelo handler de requisição malformada do GlobalExceptionHandler → 400.
        // Achado F6 da §5 do plano (test-implementation-plan-2026-07.md) — corrigido no C4.
        mockMvc.perform(Requests.get("/api/admin/checklist/detalhe").header("Authorization", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));
    }

    // ══ (e) Repasse de paginação ao service ════════════════════════════════

    @Test
    @DisplayName("família (e) — dashboard/operadores repassa page/limit parseados ao service")
    void paginacao_repassaPageLimit() throws Exception {
        when(dashboardService.listOperadores(3, 7, "", "nome", "asc", null)).thenReturn(vazio());

        mockMvc.perform(Requests.get("/api/admin/dashboard/operadores?page=3&limit=7").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(3))
                .andExpect(jsonPath("$.meta.limit").value(7));

        verify(dashboardService).listOperadores(eq(3), eq(7), eq(""), eq("nome"), eq("asc"), isNull());
    }

    // ══ (f) Contrato singular — relatório com format + RDS/XLSX ════════════

    @Nested
    @DisplayName("família (f) — endpoints de contrato singular")
    class ContratoSingular {

        @Test
        @DisplayName("relatório de operadores — delega ao ReportService com o format e o filenameBase")
        void relatorioOperadores_delegaAoReportService() throws Exception {
            when(dashboardService.listOperadores(1, ControllerUtils.REPORT_LIMIT, "", "nome", "asc", null, true))
                    .thenReturn(vazio());
            // respond devolve ResponseEntity<?> (wildcard) → doReturn evita o problema de captura de tipo.
            doReturn(ResponseEntity.ok(Map.of("ok", true, "fake_report", true)))
                    .when(reportService).respond(eq("docx"), eq("relatorio_operadores_audio"), any(), any());

            mockMvc.perform(Requests.get("/api/admin/dashboard/operadores/relatorio?format=docx").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fake_report").value(true));

            verify(reportService).respond(eq("docx"), eq("relatorio_operadores_audio"), any(), any());
        }

        @Test
        @DisplayName("RDS gerar — ano/mes válidos → delega ao RdsXlsxService e responde o XLSX")
        void rdsGerar_valido_200() throws Exception {
            when(dashboardService.fetchRdsRows(2026, 7)).thenReturn(List.of());
            when(rdsService.gerarRdsXlsx(2026, 7, List.of())).thenReturn(new byte[]{1, 2, 3});
            // respondXlsx devolve ResponseEntity<byte[]> — corpo binário, asserção só de status.
            when(reportService.respondXlsx(any(byte[].class), eq("RDS 2026-07")))
                    .thenReturn(ResponseEntity.ok(new byte[]{1, 2, 3}));

            mockMvc.perform(Requests.get("/api/admin/operacoes/rds/gerar?ano=2026&mes=7").header("Authorization", admin))
                    .andExpect(status().isOk());

            verify(reportService).respondXlsx(any(byte[].class), eq("RDS 2026-07"));
        }

        @Test
        @DisplayName("RDS gerar — ano/mes fora de faixa → 400 (validação inline do controller, antes do service)")
        void rdsGerar_invalido_400() throws Exception {
            mockMvc.perform(Requests.get("/api/admin/operacoes/rds/gerar?ano=1800&mes=7").header("Authorization", admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false));

            verify(rdsService, never()).gerarRdsXlsx(anyInt(), anyInt(), any());
        }
    }
}
