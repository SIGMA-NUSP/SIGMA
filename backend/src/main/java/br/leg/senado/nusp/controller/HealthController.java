package br.leg.senado.nusp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Health", description = "Verificação de saúde da aplicação")
public class HealthController {

    @Value("${app.env.label:}")
    private String envLabel;

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("envLabel", envLabel != null ? envLabel : "");
        return ResponseEntity.ok(body);
    }
}
