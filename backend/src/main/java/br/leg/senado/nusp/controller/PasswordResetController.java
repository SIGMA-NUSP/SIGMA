package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.service.PasswordResetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static br.leg.senado.nusp.controller.ControllerUtils.senhaCurtaBadRequest;

@RestController
@RequestMapping("/api/password")
@RequiredArgsConstructor
@Tag(name = "Recuperação de Senha", description = "Solicitação e redefinição de senha via token por e-mail")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /**
     * POST /api/password/forgot
     * Body: { "username": "..." }
     * Sempre retorna 200 com ok:true (segurança — não revelar se username existe).
     * Se o username existir, retorna também email_masked.
     */
    @PostMapping("/forgot")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            return ResponseEntity.ok(Map.of("ok", true));
        }

        Map<String, String> result = passwordResetService.requestReset(username.trim());
        if (result == null) {
            // Username não existe — retorna ok sem email_masked
            return ResponseEntity.ok(Map.of("ok", true));
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "email_masked", result.get("email_masked")
        ));
    }

    /**
     * GET /api/password/validate-token?token=xxx
     * Verifica se o token é válido (para o frontend saber antes de mostrar o form).
     */
    @GetMapping("/validate-token")
    public ResponseEntity<Map<String, Object>> validateToken(@RequestParam String token) {
        boolean valid = passwordResetService.validateToken(token);
        return ResponseEntity.ok(Map.of("ok", true, "valid", valid));
    }

    /**
     * POST /api/password/reset
     * Body: { "token": "...", "novaSenha": "..." }
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String novaSenha = body.get("novaSenha");

        if (token == null || token.isBlank() || novaSenha == null || novaSenha.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Dados incompletos."));
        }

        ResponseEntity<Map<String, Object>> senhaCurta = senhaCurtaBadRequest(novaSenha);
        if (senhaCurta != null) return senhaCurta;

        boolean success = passwordResetService.resetPassword(token, novaSenha);
        if (!success) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Token inválido ou expirado."));
        }

        return ResponseEntity.ok(Map.of("ok", true, "message", "Senha redefinida com sucesso."));
    }
}
