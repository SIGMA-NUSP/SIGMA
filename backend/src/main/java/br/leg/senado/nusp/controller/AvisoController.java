package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.security.AdminOnly;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AvisoService;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.controller.ControllerUtils.pagedResponse;
import static br.leg.senado.nusp.controller.ControllerUtils.parseJson;

/**
 * Endpoints do sistema de avisos.
 * Admin:    CRUD em /api/admin/avisos/**
 * Operador: consulta/ciência em /api/forms/checklist/aviso/**
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Avisos",
     description = "Avisos cadastrados pelo admin e exibidos aos destinatários (operadores/técnicos)")
public class AvisoController {

    private final AvisoService avisoService;
    private final ObjectMapper objectMapper;

    // ══ Admin ═══════════════════════════════════════════════════

    @AdminOnly
    @GetMapping("/api/admin/avisos/list")
    public ResponseEntity<?> listar(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters) {
        PagedResult r = avisoService.listarTodosPaginado(page, limit, search, sort, direction, parseJson(objectMapper, filters));
        return pagedResponse(r, page, limit);
    }

    @AdminOnly
    @PostMapping("/api/admin/avisos")
    public ResponseEntity<?> criar(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {
        var req = new AvisoService.CriarAvisoRequest(
                asStr(payload.get("tipo")),
                asBool(payload.get("permanente")),
                asInt(payload.get("duracao_dias")),
                asBool(payload.get("manter_apos_ciencia")),
                strList(payload.get("mensagens")),
                asStr(payload.get("alvo_tipo")),
                intList(payload.get("sala_ids")),
                strList(payload.get("operador_ids")),
                strList(payload.get("tecnico_ids")),
                strList(payload.get("admin_ids")),
                asLong(payload.get("escala_id")));
        var data = avisoService.criar(req, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", data));
    }

    /** Escalas atual+futuras (período, ocupação "Cadastro nº X" e plenários) para o painel do aviso de Escala. */
    @AdminOnly
    @GetMapping("/api/admin/avisos/escalas-disponiveis")
    public ResponseEntity<?> escalasDisponiveis() {
        return okData(avisoService.escalasDisponiveis());
    }

    /** Operadores + técnicos + administradores ({id, nome, tipo}, ordem pt-BR) para o card Pessoal. */
    @AdminOnly
    @GetMapping("/api/admin/avisos/pessoas")
    public ResponseEntity<?> pessoas() {
        return okData(avisoService.listarPessoas());
    }

    @AdminOnly
    @GetMapping("/api/admin/avisos/{id}/detalhe")
    public ResponseEntity<?> detalhe(@PathVariable String id) {
        return okData(avisoService.obterDetalhe(id));
    }

    @AdminOnly
    @PatchMapping("/api/admin/avisos/{id}/desativar")
    public ResponseEntity<?> desativar(@PathVariable String id) {
        avisoService.desativar(id);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Aviso desativado."));
    }

    /** Salas com aviso ativo (para desabilitar no form do admin). */
    @AdminOnly
    @GetMapping("/api/admin/avisos/salas-ocupadas")
    public ResponseEntity<?> salasOcupadas(@RequestParam(defaultValue = "VERIFICACAO") String tipo) {
        return okData(avisoService.salasOcupadas(tipo));
    }

    // ══ Operador (verificação) ══════════════════════════════════

    /**
     * Retorna o aviso de verificação pendente de ciência para o operador logado
     * na sala informada. Se não houver, retorna data=null.
     */
    @GetMapping("/api/forms/checklist/aviso-pendente")
    public ResponseEntity<?> avisoPendente(
            @RequestParam("sala_id") Integer salaId,
            @AuthenticationPrincipal UserPrincipal principal) {
        var aviso = avisoService.buscarPendenteVerificacao(salaId, principal.getId(), PapelPessoa.OPERADOR);
        return okData(aviso.orElse(null));
    }

    @PostMapping("/api/forms/checklist/aviso/{cadastroId}/ciencia")
    public ResponseEntity<?> registrarCiencia(
            @PathVariable("cadastroId") String cadastroId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Integer salaId = body != null ? asInt(body.get("sala_id")) : null;
        avisoService.registrarCiencia(cadastroId, salaId, principal.getId(), PapelPessoa.OPERADOR);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ══ Pós-login (avisos pessoais) ═════════════════════════════

    /**
     * Avisos pendentes do usuário logado (qualquer papel), para exibir em qualquer página.
     * contexto=geral → ESCALA/PESSOAL/GERAL; contexto=agenda → idem + AGENDA (telas de agenda).
     */
    @GetMapping("/api/avisos/pendentes")
    public ResponseEntity<?> avisosPendentes(
            @RequestParam(defaultValue = "geral") String contexto,
            @AuthenticationPrincipal UserPrincipal principal) {
        PapelPessoa papel = PapelPessoa.fromRole(principal.getRole());
        List<Map<String, Object>> data = papel == null ? List.of()
                : avisoService.buscarPendentes(principal.getId(), papel, contexto);
        return okData(data);
    }

    /** Ciência de um aviso pessoal (sem sala) pelo usuário logado. */
    @PostMapping("/api/avisos/{cadastroId}/ciencia")
    public ResponseEntity<?> registrarCienciaPessoal(
            @PathVariable("cadastroId") String cadastroId,
            @AuthenticationPrincipal UserPrincipal principal) {
        PapelPessoa papel = PapelPessoa.fromRole(principal.getRole());
        if (papel != null)
            avisoService.registrarCiencia(cadastroId, null, principal.getId(), papel);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** "Visto" de um aviso de AGENDA (sem sala) pelo usuário logado — registrado na exibição (§6.2). */
    @PostMapping("/api/avisos/{cadastroId}/visto")
    public ResponseEntity<?> registrarVisto(
            @PathVariable("cadastroId") String cadastroId,
            @AuthenticationPrincipal UserPrincipal principal) {
        PapelPessoa papel = PapelPessoa.fromRole(principal.getRole());
        if (papel != null)
            avisoService.registrarVisto(cadastroId, principal.getId(), papel);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ══ Helpers de parsing ══════════════════════════════════════

    private ResponseEntity<?> okData(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    private Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        try { return Integer.valueOf(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> r = new ArrayList<>();
        for (Object x : l) if (x != null) r.add(x.toString());
        return r;
    }

    private List<Integer> intList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Integer> r = new ArrayList<>();
        for (Object x : l) {
            if (x == null) continue;
            try { r.add(Integer.valueOf(x.toString().trim())); }
            catch (NumberFormatException ignored) { /* ignora item inválido */ }
        }
        return r;
    }
}
