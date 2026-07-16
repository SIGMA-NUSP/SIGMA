package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.entity.Comissao;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.repository.ComissaoRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Endpoints de lookup (dados de referência) — todos públicos (sem autenticação).
 * Equivalem às views lookup_operadores, lookup_salas, comissoes_lookup_view do Python.
 * Retornam {"ok": true, "data": [...]}
 */
@RestController
@RequestMapping("/api/forms/lookup")
@RequiredArgsConstructor
@Tag(name = "Lookup", description = "Dados de referência públicos (operadores, salas, comissões)")
public class LookupController {

    private final OperadorRepository operadorRepository;
    private final SalaRepository salaRepository;
    private final ComissaoRepository comissaoRepository;

    /**
     * GET /api/forms/lookup/operadores
     * Equivale ao lookup_operadores() do Python.
     */
    @GetMapping("/operadores")
    public ResponseEntity<?> operadores() {
        List<Map<String, Object>> data = operadorRepository.findAllOrderByNomeCompleto()
                .stream()
                .map(o -> Map.<String, Object>of(
                        "id", o.getId(),
                        "nome_completo", o.getNomeCompleto(),
                        "participa_escala", Boolean.TRUE.equals(o.getParticipaEscala()),
                        "turno", o.getTurno() != null ? o.getTurno() : "M"))
                .toList();
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * GET /api/forms/lookup/salas
     * Equivale ao lookup_salas() do Python.
     * Ordenado por COALESCE(ordem, 9999), nome.
     */
    @GetMapping("/salas")
    public ResponseEntity<?> salas() {
        List<Map<String, String>> data = salaRepository.findAtivasOrdenadas()
                .stream()
                .map(s -> Map.of("id", String.valueOf(s.getId()), "nome", s.getNome()))
                .toList();
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * GET /api/forms/lookup/comissoes
     * Equivale ao comissoes_lookup_view() do Python.
     */
    @GetMapping("/comissoes")
    public ResponseEntity<?> comissoes() {
        List<Map<String, String>> data = comissaoRepository.findAtivasOrdenadas()
                .stream()
                .map(c -> Map.of("id", String.valueOf(c.getId()), "nome", c.getNome()))
                .toList();
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // GET /api/forms/lookup/registro-operacao → implementado em OperacaoController
}
