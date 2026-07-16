package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.AdminOnly;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.controller.ControllerUtils.REPORT_LIMIT;
import static br.leg.senado.nusp.controller.ControllerUtils.getInt;
import static br.leg.senado.nusp.controller.ControllerUtils.pagedResponse;
import static br.leg.senado.nusp.controller.ControllerUtils.parseJson;

/**
 * Endpoints de dashboard admin — equivale a api/views/admin.py do Python.
 * Listagens paginadas, detalhes e observações de anormalidades.
 */
@RestController
@RequestMapping("/api/admin")
@AdminOnly
@RequiredArgsConstructor
@Tag(name = "Admin — Dashboard", description = "Consultas, listagens e relatórios administrativos (requer ROLE_ADMINISTRADOR)")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;
    private final ReportService reportService;
    private final ReportPdfService pdfService;
    private final ReportDocxService docxService;
    private final RdsXlsxService rdsService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    // ══ Helpers comuns ════════════════════════════════════════

    private ResponseEntity<?> okData(Object data) {
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    private ResponseEntity<?> detalheOr404(Map<String, Object> data) {
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return okData(data);
    }

    // ══ Operadores ════════════════════════════════════════════

    @GetMapping("/dashboard/operadores")
    public ResponseEntity<?> operadores(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "nome") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listOperadores(p, l, search, sort, direction, parseJson(objectMapper, filters)), p, l);
    }

    // ══ Técnicos ══════════════════════════════════════════════

    @GetMapping("/dashboard/tecnicos")
    public ResponseEntity<?> tecnicos(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "nome") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listTecnicos(p, l, search, sort, direction, parseJson(objectMapper, filters)), p, l);
    }

    // ══ Administradores (somente o master) ════════════════════

    @GetMapping("/dashboard/administradores")
    public ResponseEntity<?> administradores(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "nome") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!authService.isMaster(principal.getRole(), principal.getUsername())) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));
        }
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listAdministradores(p, l, search, sort, direction, parseJson(objectMapper, filters)), p, l);
    }

    // ══ Checklists ════════════════════════════════════════════

    @GetMapping("/dashboard/checklists")
    public ResponseEntity<?> checklists(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listChecklists(p, l, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters)), p, l);
    }

    @GetMapping("/checklist/detalhe")
    public ResponseEntity<?> checklistDetalhe(@RequestParam("checklist_id") long checklistId) {
        return detalheOr404(dashboardService.getChecklistDetalhe(checklistId));
    }

    // ══ Operações (sessões) ═══════════════════════════════════

    @GetMapping("/dashboard/operacoes")
    public ResponseEntity<?> operacoes(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listOperacoes(p, l, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters)), p, l);
    }

    @GetMapping("/dashboard/operacoes/entradas")
    public ResponseEntity<?> operacoesEntradas(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listOperacoesEntradas(p, l, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters)), p, l);
    }

    @GetMapping("/dashboard/operacoes/entradas-sessao")
    public ResponseEntity<?> entradasDeSessao(@RequestParam("registro_id") long registroId) {
        Map<String, Object> result = dashboardService.listEntradasDeSessao(registroId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("data", result.get("entradas"));
        response.put("is_plenario_principal", result.get("is_plenario_principal"));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/operacao/detalhe")
    public ResponseEntity<?> operacaoDetalhe(@RequestParam("entrada_id") long entradaId) {
        return detalheOr404(dashboardService.getEntradaDetalhe(entradaId));
    }

    // ══ Histórico de edições ══════════════════════════════════

    @GetMapping("/checklist/historico")
    public ResponseEntity<?> checklistHistorico(@RequestParam("checklist_id") long checklistId) {
        return okData(dashboardService.listChecklistHistorico(checklistId));
    }

    @GetMapping("/checklist/historico/versao")
    public ResponseEntity<?> checklistHistoricoVersao(@RequestParam("historico_id") long historicoId) {
        return detalheOr404(dashboardService.getChecklistVersao(historicoId));
    }

    @GetMapping("/operacao/historico")
    public ResponseEntity<?> operacaoHistorico(@RequestParam("entrada_id") long entradaId) {
        return okData(dashboardService.listEntradaHistorico(entradaId));
    }

    @GetMapping("/operacao/historico/versao")
    public ResponseEntity<?> operacaoHistoricoVersao(@RequestParam("historico_id") long historicoId) {
        return detalheOr404(dashboardService.getEntradaVersao(historicoId));
    }

    // ══ Anormalidades ═════════════════════════════════════════

    @GetMapping("/dashboard/anormalidades/salas")
    public ResponseEntity<?> anormalidadesSalas(@RequestParam(defaultValue = "") String search) {
        return okData(dashboardService.listSalasComAnormalidades(search));
    }

    @GetMapping("/dashboard/anormalidades/lista")
    public ResponseEntity<?> anormalidadesLista(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) Integer sala_id) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listAnormalidades(p, l, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters), sala_id), p, l);
    }

    @GetMapping("/anormalidade/detalhe")
    public ResponseEntity<?> anormalidadeDetalhe(@RequestParam("id") long anomId) {
        return detalheOr404(dashboardService.getAnormalidadeDetalhe(anomId));
    }

    // ══ Relatórios (PDF/DOCX) ════════════════════════════════

    @GetMapping("/dashboard/operadores/relatorio")
    public ResponseEntity<?> operadoresRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "nome") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rows = dashboardService.listOperadores(1, REPORT_LIMIT, search, sort, direction, parseJson(objectMapper, filters), true).data();
        return reportService.respond(format, "relatorio_operadores_audio",
                () -> pdfService.gerarRelatorioOperadores(rows),
                () -> docxService.gerarRelatorioOperadores(rows));
    }

    @GetMapping("/dashboard/checklists/relatorio")
    public ResponseEntity<?> checklistsRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rawRows = dashboardService.listChecklists(1, REPORT_LIMIT, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters), true).data();
        List<Map<String, Object>> rows = dashboardService.enriquecerRowsParaRelatorioChecklists(rawRows);
        return reportService.respond(format, "relatorio_checklists",
                () -> pdfService.gerarRelatorioChecklists(rows),
                () -> docxService.gerarRelatorioChecklists(rows));
    }

    @GetMapping("/dashboard/operacoes/relatorio")
    public ResponseEntity<?> operacoesRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rawRows = dashboardService.listOperacoes(1, REPORT_LIMIT, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters), true).data();
        List<Map<String, Object>> rows = dashboardService.enriquecerRowsParaRelatorioOperacoes(rawRows);
        return reportService.respond(format, "relatorio_operacoes_sessoes",
                () -> pdfService.gerarRelatorioOperacoesSessoes(rows),
                () -> docxService.gerarRelatorioOperacoesSessoes(rows));
    }

    @GetMapping("/dashboard/operacoes/entradas/relatorio")
    public ResponseEntity<?> operacoesEntradasRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rawRows = dashboardService.listOperacoesEntradas(1, REPORT_LIMIT, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters), true).data();
        List<Map<String, Object>> rows = dashboardService.enriquecerRowsParaRelatorioEntradas(rawRows);
        return reportService.respond(format, "relatorio_operacoes_entradas",
                () -> pdfService.gerarRelatorioOperacoesEntradas(rows),
                () -> docxService.gerarRelatorioOperacoesEntradas(rows));
    }

    @GetMapping("/dashboard/anormalidades/lista/relatorio")
    public ResponseEntity<?> anormalidadesRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) Integer sala_id) {
        List<Map<String, Object>> rawRows = dashboardService.listAnormalidades(1, REPORT_LIMIT, search, sort, direction, parseJson(objectMapper, periodo), parseJson(objectMapper, filters), sala_id, true).data();
        List<Map<String, Object>> rows = dashboardService.enriquecerRowsParaRelatorioAnormalidades(rawRows);
        return reportService.respond(format, "relatorio_anormalidades",
                () -> pdfService.gerarRelatorioAnormalidades(rows),
                () -> docxService.gerarRelatorioAnormalidades(rows));
    }

    // ══ RDS (XLSX) ════════════════════════════════════════════

    @GetMapping("/operacoes/rds/anos")
    public ResponseEntity<?> rdsAnos() {
        return okData(dashboardService.listRdsAnos());
    }

    @GetMapping("/operacoes/rds/meses")
    public ResponseEntity<?> rdsMeses(@RequestParam("ano") int ano) {
        return okData(dashboardService.listRdsMeses(ano));
    }

    @GetMapping("/operacoes/rds/gerar")
    public ResponseEntity<?> rdsGerar(@RequestParam("ano") int ano, @RequestParam("mes") int mes) {
        if (ano < 1900 || mes < 1 || mes > 12) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Parâmetros 'ano'/'mes' inválidos"));
        }
        List<Map<String, Object>> rows = dashboardService.fetchRdsRows(ano, mes);
        byte[] xlsx = rdsService.gerarRdsXlsx(ano, mes, rows);
        String filename = String.format("RDS %d-%02d", ano, mes);
        return reportService.respondXlsx(xlsx, filename);
    }

    // ══ Observações de anormalidade ═══════════════════════════

    @PostMapping("/anormalidade/observacao-supervisor")
    public ResponseEntity<?> observacaoSupervisor(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!authService.canEditObsSupervisor(principal.getRole(), principal.getUsername()))
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));

        return salvarObservacao(body, principal, true);
    }

    @PostMapping("/anormalidade/observacao-chefe")
    public ResponseEntity<?> observacaoChefe(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!authService.canEditObsChefe(principal.getRole(), principal.getUsername()))
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));

        return salvarObservacao(body, principal, false);
    }

    private ResponseEntity<?> salvarObservacao(Map<String, Object> body, UserPrincipal principal, boolean isSupervisor) {
        Object anomIdRaw = body.get("id");
        String observacao = body.get("observacao") != null ? body.get("observacao").toString().strip() : "";
        if (anomIdRaw == null) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "id_obrigatorio"));
        if (observacao.isEmpty()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "observacao_obrigatoria"));

        long anomId;
        try { anomId = Long.parseLong(anomIdRaw.toString()); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "id_invalido")); }

        if (isSupervisor) dashboardService.salvarObservacaoSupervisor(anomId, observacao, principal.getId());
        else dashboardService.salvarObservacaoChefe(anomId, observacao, principal.getId());

        return ResponseEntity.ok(Map.of("ok", true));
    }
}
