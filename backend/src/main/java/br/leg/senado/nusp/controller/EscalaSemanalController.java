package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.security.AdminOnly;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.EscalaSemanalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

import static br.leg.senado.nusp.controller.ControllerUtils.chaveNumerica;
import static br.leg.senado.nusp.controller.ControllerUtils.listaDeTextos;
import static br.leg.senado.nusp.controller.ControllerUtils.optLong;
import static br.leg.senado.nusp.controller.ControllerUtils.reqData;

/**
 * Endpoints da Escala Semanal.
 * Admin: CRUD completo em /api/admin/escala/**
 * Operador: consulta da própria escala em /api/escala/minha
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Escala Semanal", description = "Gerenciamento da escala semanal de operadores por plenário")
public class EscalaSemanalController {

    private final EscalaSemanalService escalaService;
    private final OperadorRepository operadorRepo;

    // ══ Admin — Listar ══════════════════════════════════════════

    @AdminOnly
    @GetMapping("/api/admin/escala/list")
    public ResponseEntity<?> listar(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return listarEscalas(page, limit);
    }

    // ══ Admin — Obter ═══════════════════════════════════════════

    @AdminOnly
    @GetMapping("/api/admin/escala/{id}")
    public ResponseEntity<?> obter(@PathVariable Long id) {
        return obterEscalaResponse(id);
    }

    // ══ Admin — Salvar (criar ou atualizar) ═════════════════════

    @AdminOnly
    @PostMapping("/api/admin/escala/save")
    public ResponseEntity<?> salvar(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        Long id = optLong(payload, "id");
        LocalDate dataInicio = reqData(payload, "data_inicio");
        LocalDate dataFim = reqData(payload, "data_fim");

        Map<Integer, List<String>> salasOperadores = parseSalasOperadores(payload.get("salas"));
        Map<String, List<String>> funcoes = parseFuncoes(payload.get("funcoes"));
        Map<Integer, Map<String, String>> turnosPorSala = parseTurnosPorSala(payload.get("turnos"));

        var result = escalaService.salvarEscala(id, dataInicio, dataFim, salasOperadores, turnosPorSala, funcoes, principal.getUsername());
        HttpStatus status = id != null ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(Map.of("ok", true, "data", result));
    }

    // ══ Admin — Excluir ═════════════════════════════════════════

    @AdminOnly
    @DeleteMapping("/api/admin/escala/{id}")
    public ResponseEntity<?> excluir(@PathVariable Long id) {
        escalaService.excluirEscala(id);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Escala excluída com sucesso."));
    }

    // ══ Admin — Gerar escala por rodízio ════════════════════════

    @AdminOnly
    @PostMapping("/api/admin/escala/rodizio/preview")
    public ResponseEntity<?> previewRodizio(@RequestBody Map<String, Object> payload) {
        LocalDate dataInicio = reqData(payload, "data_inicio");
        LocalDate dataFim = reqData(payload, "data_fim");
        var result = escalaService.gerarPreviaEscalaRodizio(dataInicio, dataFim);
        return ResponseEntity.ok(Map.of("ok", true, "data", result));
    }

    @AdminOnly
    @PostMapping("/api/admin/escala/rodizio")
    public ResponseEntity<?> gerarRodizio(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        LocalDate dataInicio = reqData(payload, "data_inicio");
        LocalDate dataFim = reqData(payload, "data_fim");
        var result = escalaService.gerarEscalaRodizio(dataInicio, dataFim, principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", result));
    }

    // ══ Admin — Operadores escalados hoje (por sala) ═════════════

    @AdminOnly
    @GetMapping("/api/admin/escala/operadores-hoje")
    public ResponseEntity<?> operadoresHoje() {
        return ResponseEntity.ok(Map.of("ok", true, "data", escalaService.operadoresEscaladosHoje()));
    }

    // ══ Operador — Listar escalas (somente leitura) ══════════════

    @GetMapping("/api/escala/list")
    public ResponseEntity<?> listarParaOperador(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return listarEscalas(page, limit);
    }

    @GetMapping("/api/escala/{id}")
    public ResponseEntity<?> obterParaOperador(@PathVariable Long id) {
        return obterEscalaResponse(id);
    }

    // ══ Operador — Minha escala de hoje ═════════════════════════

    @GetMapping("/api/escala/minha")
    public ResponseEntity<?> minhaEscala(@AuthenticationPrincipal UserPrincipal principal) {
        var resultado = escalaService.minhaEscalaHoje(principal.getId());
        boolean plenarioPrincipal = operadorRepo.findById(principal.getId())
                .map(op -> Boolean.TRUE.equals(op.getPlenarioPrincipal()))
                .orElse(false);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "data", resultado,
                "plenario_principal", plenarioPrincipal));
    }

    // ══ Helpers ═════════════════════════════════════════════════

    private ResponseEntity<?> listarEscalas(int page, int limit) {
        var r = escalaService.listarEscalasPaginado(page, limit);
        return ResponseEntity.ok(Map.of("ok", true, "data", r.get("data"), "meta", r.get("meta")));
    }

    private ResponseEntity<?> obterEscalaResponse(Long id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", escalaService.obterEscala(id)));
    }

    // Converter salas: { "3": ["uuid1","uuid2"], "4": ["uuid3"] }
    private Map<Integer, List<String>> parseSalasOperadores(Object raw) {
        Map<Integer, List<String>> salasOperadores = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> salasMap) {
            for (var entry : salasMap.entrySet()) {
                int salaId = chaveNumerica(entry.getKey(), "salas");
                salasOperadores.put(salaId, listaDeTextos(entry.getValue(), "salas"));
            }
        }
        return salasOperadores;
    }

    // Converter funções: { "APOIO_COMISSOES": ["uuid1"], "FECHAMENTO": ["uuid2","uuid3"] }
    private Map<String, List<String>> parseFuncoes(Object raw) {
        Map<String, List<String>> funcoes = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> funcoesMap) {
            for (var entry : funcoesMap.entrySet()) {
                String tipo = entry.getKey().toString();
                funcoes.put(tipo, listaDeTextos(entry.getValue(), "funcoes"));
            }
        }
        return funcoes;
    }

    // Converter turnos por sala: { "3": {"uuid1":"M","uuid2":"V"} } — turno definido no editor
    private Map<Integer, Map<String, String>> parseTurnosPorSala(Object raw) {
        Map<Integer, Map<String, String>> turnosPorSala = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> turnosMap) {
            for (var entry : turnosMap.entrySet()) {
                int salaId = chaveNumerica(entry.getKey(), "turnos");
                Map<String, String> inner = new LinkedHashMap<>();
                if (entry.getValue() instanceof Map<?, ?> m) {
                    for (var e2 : m.entrySet()) inner.put(e2.getKey().toString(), String.valueOf(e2.getValue()));
                }
                turnosPorSala.put(salaId, inner);
            }
        }
        return turnosPorSala;
    }
}
