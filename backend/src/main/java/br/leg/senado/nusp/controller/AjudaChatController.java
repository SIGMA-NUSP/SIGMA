package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AjudaChatService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static br.leg.senado.nusp.controller.ControllerUtils.reqTexto;

/**
 * Chat de ajuda ao usuário final (IA sobre o manual da página).
 * Qualquer papel autenticado — a rota cai no anyRequest().authenticated() do SecurityConfig.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Ajuda — Chat IA",
     description = "Dúvidas de uso da tela respondidas por IA com base no manual da página")
public class AjudaChatController {

    private final AjudaChatService ajudaChatService;

    @PostMapping("/api/ajuda/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        String pergunta = reqTexto(body, "pergunta");
        String pagina = reqTexto(body, "pagina");
        String resposta = ajudaChatService.responder(
                principal.getId(), pagina, pergunta, body.get("historico"));
        return ResponseEntity.ok(Map.of("ok", true, "resposta", resposta));
    }
}
