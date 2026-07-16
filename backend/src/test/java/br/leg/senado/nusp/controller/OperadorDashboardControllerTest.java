package br.leg.senado.nusp.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import br.leg.senado.nusp.service.OperadorDashboardService;
import br.leg.senado.nusp.service.ReportPdfService;
import br.leg.senado.nusp.service.ReportService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link OperadorDashboardController} (T17).
 *
 * Segurança real e services 100% mockados. O RBAC papel×rota já foi
 * provado no T15; aqui se cobre o repasse da identidade/paginação e o
 * mapeamento dos gates reais do {@link OperadorDashboardService}. Para cada
 * recurso, o service decide 404 antes de 403 e lança
 * {@link ServiceValidationException}; o retorno {@code null} tolerado pelo
 * controller não é usado para simular ausência, pois não é o contrato real.
 */
@SigmaControllerTest(OperadorDashboardController.class)
class OperadorDashboardControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private OperadorDashboardService dashboardService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReportPdfService pdfService;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Test
    @DisplayName("meus-checklists — repassa principal.id e paginação exatos; devolve envelope {ok,data,meta}")
    void meusChecklists_repassaIdentidadeEPaginacao() throws Exception {
        PagedResult resultado = new PagedResult(List.of(Map.of("id", 9L)), 15, Map.of());
        when(dashboardService.listMeusChecklists(
                TokenFactory.USER_ID, 3, 7, "sala", "asc", null)).thenReturn(resultado);

        mockMvc.perform(Requests.get(
                        "/api/operador/meus-checklists?page=3&limit=7&sort=sala&direction=asc")
                        .header("Authorization", operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.meta.page").value(3))
                .andExpect(jsonPath("$.meta.limit").value(7))
                .andExpect(jsonPath("$.meta.total").value(15))
                .andExpect(jsonPath("$.meta.pages").value(3));

        verify(dashboardService).listMeusChecklists(
                TokenFactory.USER_ID, 3, 7, "sala", "asc", null);
        verifyNoInteractions(reportService, pdfService);
    }

    @Nested
    @DisplayName("detalhes presentes — envelope e somente_leitura do service preservados")
    class DetalhesPresentes {

        @Test
        @DisplayName("checklist adicional via junction — 200 com somente_leitura=false")
        void checklistAdicional_200LeituraEscrita() throws Exception {
            when(dashboardService.getMeuChecklistDetalhe(41L, TokenFactory.USER_ID))
                    .thenReturn(Map.of("id", 41L, "somente_leitura", false));

            mockMvc.perform(Requests.get("/api/operador/checklist/detalhe?checklist_id=41")
                            .header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.id").value(41))
                    .andExpect(jsonPath("$.data.somente_leitura").value(false));

            verify(dashboardService).getMeuChecklistDetalhe(41L, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("operação para fixo do Plenário Principal — 200 com somente_leitura=true")
        void operacaoFixoPlenario_200SomenteLeitura() throws Exception {
            when(dashboardService.getMinhaOperacaoDetalhe(42L, TokenFactory.USER_ID))
                    .thenReturn(Map.of("id", 42L, "somente_leitura", true));

            mockMvc.perform(Requests.get("/api/operador/operacao/detalhe?entrada_id=42")
                            .header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.id").value(42))
                    .andExpect(jsonPath("$.data.somente_leitura").value(true));

            verify(dashboardService).getMinhaOperacaoDetalhe(42L, TokenFactory.USER_ID);
        }
    }

    @Nested
    @DisplayName("gate por recurso — 404 antes de 403, conforme o service real")
    class GatePorRecurso {

        @Test
        @DisplayName("checklist inexistente — SVE 404 com mensagem do recurso")
        void checklistInexistente_404() throws Exception {
            when(dashboardService.getMeuChecklistDetalhe(41L, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException(
                            "Checklist não encontrado.", HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.get("/api/operador/checklist/detalhe?checklist_id=41")
                            .header("Authorization", operador))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Checklist não encontrado."));

            verify(dashboardService).getMeuChecklistDetalhe(41L, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("checklist existente sem acesso — SVE 403 depois do 404")
        void checklistSemAcesso_403() throws Exception {
            when(dashboardService.getMeuChecklistDetalhe(41L, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.get("/api/operador/checklist/detalhe?checklist_id=41")
                            .header("Authorization", operador))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Acesso negado."));

            verify(dashboardService).getMeuChecklistDetalhe(41L, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("operação inexistente — SVE 404 com mensagem do recurso")
        void operacaoInexistente_404() throws Exception {
            when(dashboardService.getMinhaOperacaoDetalhe(42L, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException(
                            "Operação não encontrada.", HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.get("/api/operador/operacao/detalhe?entrada_id=42")
                            .header("Authorization", operador))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Operação não encontrada."));

            verify(dashboardService).getMinhaOperacaoDetalhe(42L, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("operação existente sem acesso — SVE 403 depois do 404")
        void operacaoSemAcesso_403() throws Exception {
            when(dashboardService.getMinhaOperacaoDetalhe(42L, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.get("/api/operador/operacao/detalhe?entrada_id=42")
                            .header("Authorization", operador))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Acesso negado."));

            verify(dashboardService).getMinhaOperacaoDetalhe(42L, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("anormalidade inexistente — SVE 404 com mensagem do recurso")
        void anormalidadeInexistente_404() throws Exception {
            when(dashboardService.getMinhaAnormalidadeDetalhe(43L, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException(
                            "Anormalidade não encontrada.", HttpStatus.NOT_FOUND));

            mockMvc.perform(Requests.get("/api/operador/anormalidade/detalhe?id=43")
                            .header("Authorization", operador))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Anormalidade não encontrada."));

            verify(dashboardService).getMinhaAnormalidadeDetalhe(43L, TokenFactory.USER_ID);
        }

        @Test
        @DisplayName("anormalidade de operação em que o operador é adicional — sem junction própria, SVE 403")
        void anormalidadeAdicionalSemJunction_403() throws Exception {
            when(dashboardService.getMinhaAnormalidadeDetalhe(43L, TokenFactory.USER_ID))
                    .thenThrow(new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.get("/api/operador/anormalidade/detalhe?id=43")
                            .header("Authorization", operador))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Acesso negado."));

            verify(dashboardService).getMinhaAnormalidadeDetalhe(43L, TokenFactory.USER_ID);
        }
    }

    @Test
    @DisplayName("corrige F6 — binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_corrigeF6_400() throws Exception {
        // checklist_id é @RequestParam obrigatório: ausente gera MissingServletRequestParameterException,
        // agora tratada pelo handler de requisição malformada do GlobalExceptionHandler → 400.
        // Achado F6 da §5 do plano (test-implementation-plan-2026-07.md) — corrigido no C4.
        mockMvc.perform(Requests.get("/api/operador/checklist/detalhe")
                        .header("Authorization", operador))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));

        verifyNoInteractions(dashboardService, reportService, pdfService);
    }
}
