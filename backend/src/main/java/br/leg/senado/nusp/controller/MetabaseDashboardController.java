package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.entity.MetabaseDashboard;
import br.leg.senado.nusp.security.AdminOnly;
import br.leg.senado.nusp.service.MetabaseEmbedService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de dashboards Metabase e geração de URL de embed assinado.
 *
 * Tudo aqui sob /api/admin/** — SecurityConfig já restringe a ROLE_ADMINISTRADOR.
 */
@RestController
@RequestMapping("/api/admin/metabase")
@AdminOnly
@RequiredArgsConstructor
@Tag(name = "Metabase — Embed", description = "Catálogo de dashboards e geração de URLs de embed assinado (requer ROLE_ADMINISTRADOR)")
public class MetabaseDashboardController {

    private final MetabaseEmbedService embedService;

    @GetMapping("/dashboards")
    public ResponseEntity<List<Map<String, Object>>> listar() {
        List<Map<String, Object>> body = embedService.listarAtivos().stream()
                .map(MetabaseDashboardController::toCard)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/dashboards/{id}/embed-url")
    public ResponseEntity<Map<String, String>> embedUrl(@PathVariable("id") String id) {
        String url = embedService.gerarEmbedUrl(id);
        return ResponseEntity.ok(Map.of("url", url));
    }

    private static Map<String, Object> toCard(MetabaseDashboard d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("titulo", d.getTitulo());
        m.put("descricao", d.getDescricao());
        m.put("icone", d.getIcone());
        m.put("ordem", d.getOrdem());
        return m;
    }
}
