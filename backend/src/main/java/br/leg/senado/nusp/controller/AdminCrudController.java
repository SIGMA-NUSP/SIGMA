package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.AdminOnly;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AdminCrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints de CRUD admin — equivale à parte de criação de usuários
 * e form-edit de api/views/admin.py do Python.
 */
@RestController
@RequestMapping("/api/admin")
@AdminOnly
@RequiredArgsConstructor
@Tag(name = "Admin — Cadastros", description = "CRUD de operadores, administradores e configurações de formulário (requer ROLE_ADMINISTRADOR)")
public class AdminCrudController {

    private final AdminCrudService crudService;

    // ══ Criação de Operador ═════════════════════════════════════

    @PostMapping("/operadores/novo")
    public ResponseEntity<?> operadorNovo(
            @RequestParam(value = "nome_completo", required = false) String nomeCompleto,
            @RequestParam(value = "nome_exibicao", required = false) String nomeExibicao,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "senha", required = false) String senha,
            @RequestParam(value = "foto", required = false) MultipartFile foto,
            @RequestParam(value = "plenario_principal", required = false, defaultValue = "false") boolean plenarioPrincipal,
            @RequestParam(value = "plenario_principal_fixo", required = false, defaultValue = "false") boolean plenarioPrincipalFixo) {

        Map<String, Object> operador = crudService.criarOperador(
                nomeCompleto, nomeExibicao, email, username, senha, foto,
                plenarioPrincipal, plenarioPrincipalFixo);

        return criado("operador", operador);
    }

    // ══ Criação de Técnico ══════════════════════════════════════

    @PostMapping("/tecnicos/novo")
    public ResponseEntity<?> tecnicoNovo(
            @RequestParam(value = "nome_completo", required = false) String nomeCompleto,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "senha", required = false) String senha,
            @RequestParam(value = "foto", required = false) MultipartFile foto) {

        Map<String, Object> tecnico = crudService.criarTecnico(
                nomeCompleto, email, username, senha, foto);

        return criado("tecnico", tecnico);
    }

    // ══ Criação de Administrador ════════════════════════════════

    @PostMapping("/admins/novo")
    public ResponseEntity<?> adminNovo(
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        Map<String, Object> admin = crudService.criarAdministrador(
                payload.get("nome_completo"),
                payload.get("email"),
                payload.get("username"),
                payload.get("senha"),
                principal.getUsername());

        return criado("administrador", admin);
    }

    // ══ Alterar Senha de Operador ═══════════════════════════════

    @PostMapping("/operador/{id}/alterar-senha")
    public ResponseEntity<?> alterarSenhaOperador(
            @PathVariable String id,
            @RequestBody Map<String, Object> payload) {
        String novaSenha = payload.get("nova_senha") != null ? payload.get("nova_senha").toString() : "";
        if (novaSenha.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "message", "A senha deve ter pelo menos 4 caracteres."));
        }
        crudService.changeOperadorPassword(id, novaSenha);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Senha alterada com sucesso."));
    }

    // ══ Toggle Plenário Principal ══════════════════════════════

    @PatchMapping("/operador/{id}/toggle-plenario")
    public ResponseEntity<?> togglePlenario(@PathVariable String id) {
        boolean novoValor = crudService.togglePlenarioPrincipal(id);
        return ResponseEntity.ok(Map.of("ok", true, "plenario_principal", novoValor));
    }

    @PatchMapping("/operador/{id}/toggle-plenario-fixo")
    public ResponseEntity<?> togglePlenarioFixo(@PathVariable String id) {
        boolean novoValor = crudService.togglePlenarioPrincipalFixo(id);
        return ResponseEntity.ok(Map.of("ok", true, "plenario_principal_fixo", novoValor));
    }

    // ══ Atualizar Turno ════════════════════════════════════════

    @PatchMapping("/operador/{id}/turno")
    public ResponseEntity<?> setTurno(@PathVariable String id, @RequestBody Map<String, String> body) {
        String novoValor = crudService.setTurnoOperador(id, body.get("turno"));
        return ResponseEntity.ok(Map.of("ok", true, "turno", novoValor));
    }

    // ══ Toggle Participa Escala ═══════════════════════════════

    @PatchMapping("/operador/{id}/toggle-escala")
    public ResponseEntity<?> toggleEscala(@PathVariable String id) {
        boolean novoValor = crudService.toggleParticipaEscala(id);
        return ResponseEntity.ok(Map.of("ok", true, "participa_escala", novoValor));
    }

    // ══ Perfil de Operador — Buscar ════════════════════════════

    @GetMapping("/operador/{id}")
    public ResponseEntity<?> operadorPerfil(@PathVariable String id) {
        Map<String, Object> operador = crudService.getOperadorPerfil(id);
        return ResponseEntity.ok(Map.of("ok", true, "operador", operador));
    }

    // ══ Perfil de Operador — Atualizar (multipart: foto opcional) ═

    @PostMapping("/operador/{id}/atualizar")
    public ResponseEntity<?> operadorAtualizar(
            @PathVariable String id,
            @RequestParam(value = "nome_completo", required = false) String nomeCompleto,
            @RequestParam(value = "nome_exibicao", required = false) String nomeExibicao,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "turno", required = false, defaultValue = "M") String turno,
            @RequestParam(value = "carga_horaria", required = false) String cargaHoraria,
            @RequestParam(value = "horario_trabalho_inicio", required = false) String horarioInicio,
            @RequestParam(value = "horario_trabalho_fim", required = false) String horarioFim,
            @RequestParam(value = "plenario_principal", required = false, defaultValue = "false") boolean plenarioPrincipal,
            @RequestParam(value = "plenario_principal_fixo", required = false, defaultValue = "false") boolean plenarioPrincipalFixo,
            @RequestParam(value = "participa_escala", required = false, defaultValue = "false") boolean participaEscala,
            @RequestParam(value = "foto", required = false) MultipartFile foto) {

        Map<String, Object> operador = crudService.atualizarOperador(
                id, nomeCompleto, nomeExibicao, email, turno, cargaHoraria,
                horarioInicio, horarioFim, plenarioPrincipal, plenarioPrincipalFixo,
                participaEscala, foto);

        return ResponseEntity.ok(Map.of("ok", true, "operador", operador));
    }

    // ══ Perfil de Técnico — Buscar ═════════════════════════════

    @GetMapping("/tecnico/{id}")
    public ResponseEntity<?> tecnicoPerfil(@PathVariable String id) {
        Map<String, Object> tecnico = crudService.getTecnicoPerfil(id);
        return ResponseEntity.ok(Map.of("ok", true, "tecnico", tecnico));
    }

    // ══ Perfil de Técnico — Atualizar (multipart: foto opcional) ═

    @PostMapping("/tecnico/{id}/atualizar")
    public ResponseEntity<?> tecnicoAtualizar(
            @PathVariable String id,
            @RequestParam(value = "nome_completo", required = false) String nomeCompleto,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "turno", required = false) String turno,
            @RequestParam(value = "carga_horaria", required = false) String cargaHoraria,
            @RequestParam(value = "horario_trabalho_inicio", required = false) String horarioInicio,
            @RequestParam(value = "horario_trabalho_fim", required = false) String horarioFim,
            @RequestParam(value = "foto", required = false) MultipartFile foto) {

        Map<String, Object> tecnico = crudService.atualizarTecnico(
                id, nomeCompleto, email, turno, cargaHoraria,
                horarioInicio, horarioFim, foto);

        return ResponseEntity.ok(Map.of("ok", true, "tecnico", tecnico));
    }

    // ══ Perfil de Administrador — Buscar (somente master) ══════

    @GetMapping("/administrador/{id}")
    public ResponseEntity<?> administradorPerfil(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> administrador = crudService.getAdministradorPerfil(id, principal.getUsername());
        return ResponseEntity.ok(Map.of("ok", true, "administrador", administrador));
    }

    // ══ Perfil de Administrador — Atualizar (multipart; só master) ═

    @PostMapping("/administrador/{id}/atualizar")
    public ResponseEntity<?> administradorAtualizar(
            @PathVariable String id,
            @RequestParam(value = "nome_completo", required = false) String nomeCompleto,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "servidor_publico", required = false, defaultValue = "false") boolean servidorPublico,
            @RequestParam(value = "turno", required = false) String turno,
            @RequestParam(value = "carga_horaria", required = false) String cargaHoraria,
            @RequestParam(value = "horario_trabalho_inicio", required = false) String horarioInicio,
            @RequestParam(value = "horario_trabalho_fim", required = false) String horarioFim,
            @RequestParam(value = "foto", required = false) MultipartFile foto,
            @AuthenticationPrincipal UserPrincipal principal) {

        Map<String, Object> administrador = crudService.atualizarAdministrador(
                id, nomeCompleto, email, servidorPublico, turno, cargaHoraria,
                horarioInicio, horarioFim, foto, principal.getUsername());

        return ResponseEntity.ok(Map.of("ok", true, "administrador", administrador));
    }

    // ══ Form Edit — Listar ══════════════════════════════════════

    @GetMapping("/form-edit/{entidade}/list")
    public ResponseEntity<?> formEditList(@PathVariable String entidade) {
        if ("checklist-itens".equals(entidade)) return checklistItensGone("list");

        return okComDados(crudService.listFormEditItems(entidade));
    }

    // ══ Form Edit — Salvar ══════════════════════════════════════

    @PostMapping("/form-edit/{entidade}/save")
    public ResponseEntity<?> formEditSave(
            @PathVariable String entidade,
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        if ("checklist-itens".equals(entidade)) return checklistItensGone("save");

        List<Map<String, Object>> items = extrairItems(payload);
        if (items == null) return payloadInvalido("Campo 'items' é obrigatório e deve ser uma lista.");

        return okComDados(crudService.saveFormEditItems(entidade, items, principal.getId()));
    }

    // ══ Sala Config — Listar ════════════════════════════════════

    @GetMapping("/form-edit/sala-config/{salaId}/list")
    public ResponseEntity<?> salaConfigList(@PathVariable String salaId) {
        int id = parseSalaId(salaId);
        return okComDados(crudService.listSalaConfigItems(id));
    }

    // ══ Sala Config — Salvar ════════════════════════════════════

    @PostMapping("/form-edit/sala-config/{salaId}/save")
    public ResponseEntity<?> salaConfigSave(
            @PathVariable String salaId,
            @RequestBody Map<String, Object> payload) {

        int id = parseSalaId(salaId);

        List<Map<String, Object>> items = extrairItems(payload);
        if (items == null) return payloadInvalido("Campo 'items' é obrigatório e deve ser uma lista.");

        return okComDados(crudService.saveSalaConfigItems(id, items));
    }

    // ══ Sala Config — Aplicar a Todas ═══════════════════════════

    @PostMapping("/form-edit/sala-config/aplicar-todas")
    public ResponseEntity<?> salaConfigAplicarTodas(@RequestBody Map<String, Object> payload) {
        Object sourceSalaIdRaw = payload.get("source_sala_id");
        if (sourceSalaIdRaw == null) return payloadInvalido("Campo 'source_sala_id' é obrigatório.");

        int sourceSalaId = parseSalaId(sourceSalaIdRaw.toString());

        List<Map<String, Object>> items = extrairItems(payload);
        if (items == null) return payloadInvalido("Campo 'items' é obrigatório e deve ser uma lista.");

        return okComDados(crudService.applySalaConfigToAll(sourceSalaId, items));
    }

    // ══ Helper ══════════════════════════════════════════════════

    private ResponseEntity<?> criado(String chave, Object entidade) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put(chave, entidade);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private ResponseEntity<?> okComDados(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.putAll(data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> payloadInvalido(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "PAYLOAD_INVALIDO",
                "message", message));
    }

    /**
     * Itens do payload — {@code null} quando o formato não serve, e o chamador responde 400
     * PAYLOAD_INVALIDO.
     *
     * <p>O {@code instanceof List} guardava só o CONTAINER (F29): o cast dos elementos some no
     * erasure, então {@code {"items":["x"]}} passava aqui e estourava {@code ClassCastException}
     * lá dentro do service — 500 por um corpo torto do cliente. Agora cada elemento é conferido.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extrairItems(Map<String, Object> payload) {
        if (!(payload.get("items") instanceof List<?> lista)) return null;
        for (Object item : lista) {
            if (!(item instanceof Map)) return null;
        }
        return (List<Map<String, Object>>) lista;
    }

    private ResponseEntity<?> checklistItensGone(String rota) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "ok", false,
                "error", "DEPRECATED",
                "message", "Use /sala-config/<sala_id>/" + rota + " ao invés."));
    }

    private int parseSalaId(String raw) {
        try {
            int id = Integer.parseInt(raw);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            return -1; // O service vai validar e lançar erro
        }
    }
}
