package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.ChecklistService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints de Checklist — equivale a api/views/checklist.py do Python.
 * 3 endpoints: registro, itens-tipo, editar.
 */
@RestController
@RequestMapping("/api/forms/checklist")
@RequiredArgsConstructor
@Tag(name = "Checklist", description = "Registro e edição de checklists de sala")
public class ChecklistController {

    private final ChecklistService checklistService;

    /**
     * POST /api/forms/checklist/registro
     * Equivale a checklist_registro_view() do Python.
     */
    @PostMapping("/registro")
    public ResponseEntity<?> registro(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> result = checklistService.registrar(body, principal.getId());
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("ok", true);
        return ResponseEntity.status(201).body(response);
    }

    /**
     * GET /api/forms/checklist/itens-tipo?sala_id=...
     * Equivale a checklist_itens_tipo_view() do Python.
     * Endpoint PÚBLICO (sem autenticação).
     */
    @GetMapping("/itens-tipo")
    public ResponseEntity<?> itensTipo(@RequestParam("sala_id") int salaId) {
        List<Map<String, Object>> data = checklistService.itensTipoPorSala(salaId);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * PUT /api/forms/checklist/editar
     * Equivale a checklist_editar_view() do Python.
     * Inclui verificação de ownership (criado_por == userId).
     */
    @PutMapping("/editar")
    public ResponseEntity<?> editar(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {

        Object checklistIdRaw = body.get("checklist_id");
        if (checklistIdRaw == null)
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "checklist_id obrigatório"));

        long checklistId;
        try { checklistId = Long.parseLong(checklistIdRaw.toString()); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "checklist_id inválido")); }

        Map<String, Object> result = checklistService.editar(checklistId, body, principal.getId());
        result.put("ok", true);
        return ResponseEntity.ok(result);
    }
}
