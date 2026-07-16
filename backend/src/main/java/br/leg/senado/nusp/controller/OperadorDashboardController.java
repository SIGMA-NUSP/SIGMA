package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.controller.ControllerUtils.REPORT_LIMIT;
import static br.leg.senado.nusp.controller.ControllerUtils.getInt;
import static br.leg.senado.nusp.controller.ControllerUtils.pagedResponse;
import static br.leg.senado.nusp.controller.ControllerUtils.parseJson;

/**
 * Endpoints do dashboard do operador — equivale a operador_dashboard.py do Python.
 * 7 endpoints: meus-checklists, minhas-operacoes, detalhes, relatórios.
 */
@RestController
@RequestMapping("/api/operador")
@RequiredArgsConstructor
@Tag(name = "Painel do Operador", description = "Dashboard e relatórios do operador autenticado")
public class OperadorDashboardController {

    private final OperadorDashboardService dashboardService;
    private final ReportService reportService;
    private final ReportPdfService pdfService;
    private final ObjectMapper objectMapper;

    private ResponseEntity<?> detalheOr404(Map<String, Object> data) {
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Meus Checklists ═══════════════════════════════════════

    @GetMapping("/meus-checklists")
    public ResponseEntity<?> meusChecklists(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listMeusChecklists(principal.getId(), p, l, sort, direction, parseJson(objectMapper, filters)), p, l);
    }

    @GetMapping("/meus-checklists/relatorio")
    public ResponseEntity<?> meusChecklistsRelatorio(
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<Map<String, Object>> rows = dashboardService.listMeusChecklists(
                principal.getId(), 1, REPORT_LIMIT, sort, direction, parseJson(objectMapper, filters), true).data();
        return reportService.respondPdf(
                pdfService.gerarRelatorioMeusChecklists(rows), "relatorio_verificacao_salas");
    }

    @GetMapping("/checklist/detalhe")
    public ResponseEntity<?> meuChecklistDetalhe(
            @RequestParam("checklist_id") long checklistId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return detalheOr404(dashboardService.getMeuChecklistDetalhe(checklistId, principal.getId()));
    }

    // ══ Minhas Operações ══════════════════════════════════════

    @GetMapping("/minhas-operacoes")
    public ResponseEntity<?> minhasOperacoes(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listMinhasOperacoes(principal.getId(), p, l, sort, direction, parseJson(objectMapper, filters)), p, l);
    }

    @GetMapping("/minhas-operacoes/relatorio")
    public ResponseEntity<?> minhasOperacoesRelatorio(
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<Map<String, Object>> rows = dashboardService.listMinhasOperacoes(
                principal.getId(), 1, REPORT_LIMIT, sort, direction, parseJson(objectMapper, filters), true).data();
        return reportService.respondPdf(
                pdfService.gerarRelatorioMinhasOperacoes(rows), "relatorio_operacoes_audio");
    }

    @GetMapping("/operacao/detalhe")
    public ResponseEntity<?> minhaOperacaoDetalhe(
            @RequestParam("entrada_id") long entradaId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return detalheOr404(dashboardService.getMinhaOperacaoDetalhe(entradaId, principal.getId()));
    }

    // ══ Minha Anormalidade ════════════════════════════════════

    @GetMapping("/anormalidade/detalhe")
    public ResponseEntity<?> minhaAnormalidadeDetalhe(
            @RequestParam("id") long anomId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return detalheOr404(dashboardService.getMinhaAnormalidadeDetalhe(anomId, principal.getId()));
    }
}
