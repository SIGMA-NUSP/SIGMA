package br.leg.senado.nusp.controller;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AdminCrudService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link AdminCrudController}. Segurança real; {@link AdminCrudService}
 * mockado (o RBAC papel×rota tem suíte própria). Cobre: o contrato MULTIPART de foto
 * (singular deste controller); o mapeamento ServiceValidationException→HTTP — inclusive o
 * guard "somente master", que aqui vive no SERVICE (via callerUsername) e chega ao cliente
 * como 403 pelo handler, não como guard manual do controller; o binding inválido
 * respondendo 400; e as validações inline do controller (senha curta, payload sem items,
 * rota deprecada).
 */
@SigmaControllerTest(AdminCrudController.class)
class AdminCrudControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AdminCrudService crudService;

    private String admin;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        admin = "Bearer " + tokens.valido(TokenFactory.ADMIN);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    // ══ (f) Multipart de foto — o contrato singular deste controller ═══════

    @Nested
    @DisplayName("família (f) — upload multipart de foto")
    class MultipartFoto {

        @Test
        @DisplayName("POST /operadores/novo — repassa campos + arquivo de foto ao service; 201 {ok, operador}")
        void criarOperador_multipart_201() throws Exception {
            when(crudService.criarOperador(eq("Fulano de Tal"), eq("Fulano"), eq("fulano@senado.leg.br"),
                    eq("fulano.tal"), eq("abcd1234"), any(MultipartFile.class), eq(true), eq(false)))
                    .thenReturn(Map.of("id", "novo-1", "nome", "Fulano de Tal"));

            mockMvc.perform(Requests.multipart("/api/admin/operadores/novo")
                            .file(new MockMultipartFile("foto", "avatar.png", "image/png", new byte[]{1, 2, 3}))
                            .param("nome_completo", "Fulano de Tal")
                            .param("nome_exibicao", "Fulano")
                            .param("email", "fulano@senado.leg.br")
                            .param("username", "fulano.tal")
                            .param("senha", "abcd1234")
                            .param("plenario_principal", "true")
                            .header("Authorization", admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.operador.id").value("novo-1"));

            // A foto multipart chega ao service com o filename original preservado.
            verify(crudService).criarOperador(anyString(), anyString(), anyString(), anyString(), anyString(),
                    argThat(f -> f != null && "avatar.png".equals(f.getOriginalFilename())), eq(true), eq(false));
        }

        /**
         * Aqui a requisição não-multipart <b>não</b> produz {@code MultipartException}. A diferença
         * é o {@code required}: no upload do Ponto o {@code MultipartFile} é obrigatório, e o
         * resolver, ao não achar a parte numa requisição que nem é multipart, lança a exceção
         * (mapeada para 400). Aqui todas as partes são {@code required = false} — o resolver
         * devolve {@code null}, o método é invocado com os campos nulos e quem recusa é o SERVICE
         * (no slice ele é mock; em produção, a validação de campo obrigatório).
         */
        @Test
        @DisplayName("requisição não-multipart com partes OPCIONAIS não estoura: foto chega null ao service")
        void naoMultipart_partesOpcionais_chegamNulasAoService() throws Exception {
            // Stub explícito: sem ele o 201 dependeria de o `criado()` tolerar um payload null — detalhe de
            // implementação alheio ao que se mede aqui (o destino da requisição não-multipart).
            when(crudService.criarOperador(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                    .thenReturn(Map.of("id", "novo-1"));

            mockMvc.perform(Requests.post("/api/admin/operadores/novo")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nome_completo\":\"Fulano\"}"))
                    .andExpect(status().isCreated());   // ≠ 500: nenhuma exceção foi resolvida

            // O corpo JSON não alimenta @RequestParam: tudo chega nulo — inclusive a foto, que é a parte
            // multipart. É este null (em vez da MultipartException) que separa este controller do upload.
            verify(crudService).criarOperador(isNull(), isNull(), isNull(), isNull(), isNull(),
                    isNull(), eq(false), eq(false));
        }

        @Test
        @DisplayName("POST /operador/{id}/atualizar — multipart com foto opcional; 200 {ok, operador}")
        void atualizarOperador_multipart_200() throws Exception {
            when(crudService.atualizarOperador(eq("op-1"), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), anyBoolean(), any(MultipartFile.class)))
                    .thenReturn(Map.of("id", "op-1"));

            mockMvc.perform(Requests.multipart("/api/admin/operador/op-1/atualizar")
                            .file(new MockMultipartFile("foto", "nova.jpg", "image/jpeg", new byte[]{9}))
                            .param("nome_completo", "Op Um")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.operador.id").value("op-1"));

            verify(crudService).atualizarOperador(eq("op-1"), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), anyBoolean(),
                    argThat(f -> f != null && "nova.jpg".equals(f.getOriginalFilename())));
        }
    }

    // ══ Toggle pontual (PATCH) ═════════════════════════════════════════════

    @Test
    @DisplayName("PATCH /operador/{id}/toggle-plenario — devolve o novo valor booleano do service")
    void togglePlenario_200() throws Exception {
        when(crudService.togglePlenarioPrincipal("op-1")).thenReturn(true);

        mockMvc.perform(Requests.patch("/api/admin/operador/op-1/toggle-plenario").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.plenario_principal").value(true));
    }

    // ══ (c) ServiceValidationException → GlobalExceptionHandler ════════════

    @Nested
    @DisplayName("família (c) — ServiceValidationException do service mapeada pelo handler")
    class MapeamentoServiceHttp {

        @Test
        @DisplayName("criarOperador — conflito do service → 409 {ok:false, error}")
        void criarOperador_conflito_409() throws Exception {
            when(crudService.criarOperador(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                    .thenThrow(new ServiceValidationException("username_ja_existe", HttpStatus.CONFLICT));

            mockMvc.perform(Requests.multipart("/api/admin/operadores/novo")
                            .param("nome_completo", "X")
                            .param("username", "duplicado")
                            .header("Authorization", admin))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("username_ja_existe"));
        }

        @Test
        @DisplayName("GET /administrador/{id} — guard 'somente master' vive no SERVICE; 403 chega via handler")
        void administradorPerfil_guardNoService_403() throws Exception {
            // O callerUsername (principal.getUsername()) é repassado ao service; quando o requireMaster
            // do AdminCrudService rejeita, lança ServiceValidationException(403) → GlobalExceptionHandler.
            when(crudService.getAdministradorPerfil("adm-1", "teste.administrador"))
                    .thenThrow(new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN));

            mockMvc.perform(Requests.get("/api/admin/administrador/adm-1").header("Authorization", admin))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }
    }

    // ══ Validações inline do controller ════════════════════════════════════

    @Nested
    @DisplayName("validações inline do controller (antes de tocar o service)")
    class ValidacoesInline {

        @Test
        @DisplayName("alterar-senha — senha < 4 chars → 400 e o service não é chamado")
        void alterarSenha_curta_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/operador/op-1/alterar-senha")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nova_senha\":\"ab\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value("A senha deve ter pelo menos 4 caracteres."));

            verify(crudService, never()).changeOperadorPassword(anyString(), anyString());
        }

        @Test
        @DisplayName("form-edit save — payload sem 'items' → 400 PAYLOAD_INVALIDO")
        void formEditSave_semItems_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/form-edit/salas/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("PAYLOAD_INVALIDO"));
        }

        @Test
        @DisplayName("form-edit save com item que não é objeto → 400 PAYLOAD_INVALIDO (antes: ClassCastException → 500)")
        void formEditSave_itemNaoEhObjeto_400() throws Exception {
            // O instanceof List guardava só o container: a lista de textos passava e estourava no service.
            mockMvc.perform(Requests.post("/api/admin/form-edit/salas/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[\"nao-sou-um-objeto\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("PAYLOAD_INVALIDO"));

            verifyNoInteractions(crudService);
        }

        @Test
        @DisplayName("form-edit list checklist-itens — rota deprecada → 410 GONE")
        void formEditList_checklistItens_410() throws Exception {
            mockMvc.perform(Requests.get("/api/admin/form-edit/checklist-itens/list").header("Authorization", admin))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("DEPRECATED"));
        }
    }

    // ══ (d) Binding inválido → 400 ═════════════════════════════════════════

    @Test
    @DisplayName("binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_400() throws Exception {
        // @RequestBody malformado → HttpMessageNotReadableException, tratada pelo handler
        // de requisição malformada do GlobalExceptionHandler → 400.
        mockMvc.perform(Requests.post("/api/admin/operador/op-1/alterar-senha")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));
    }
}
