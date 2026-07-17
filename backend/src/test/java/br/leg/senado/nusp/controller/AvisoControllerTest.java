package br.leg.senado.nusp.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.AvisoService;
import br.leg.senado.nusp.service.AvisoService.CriarAvisoRequest;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link AvisoController}. Controller MISTO (sem @RequestMapping de
 * classe): rotas /api/admin/avisos/** e rotas comuns /api/forms/checklist/** e
 * /api/avisos/** no mesmo arquivo — o RBAC dessa mistura tem suíte própria (matriz RBAC);
 * aqui é só o contrato de resposta, com o token do papel certo. Cobre: paginação admin,
 * criar 201 + validação mapeada pelo handler, binding inválido respondendo 400 e a mistura
 * admin/comum de contrato singular. Sem guard manual por username nem 404 de detalhe
 * neste controller.
 */
@SigmaControllerTest(AvisoController.class)
class AvisoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private AvisoService avisoService;

    private String admin;
    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        admin = "Bearer " + tokens.valido(TokenFactory.ADMIN);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    // ══ Rotas admin ════════════════════════════════════════════════════════

    @Nested
    @DisplayName("rotas admin (/api/admin/avisos/**)")
    class Admin {

        @Test
        @DisplayName("família (e) — list repassa page/limit ao service e envelopa {ok,data,meta}")
        void list_repassaPaginacao() throws Exception {
            when(avisoService.listarTodosPaginado(2, 5, "", "data", "desc", null))
                    .thenReturn(new PagedResult(List.of(), 0, Map.of()));

            mockMvc.perform(Requests.get("/api/admin/avisos/list?page=2&limit=5").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.meta.page").value(2))
                    .andExpect(jsonPath("$.meta.limit").value(5));

            verify(avisoService).listarTodosPaginado(eq(2), eq(5), eq(""), eq("data"), eq("desc"), any());
        }

        @Test
        @DisplayName("POST /api/admin/avisos — cria e repassa principal.id ao service; 201 {ok, data}")
        void criar_201() throws Exception {
            when(avisoService.criar(any(CriarAvisoRequest.class), eq(TokenFactory.USER_ID)))
                    .thenReturn(Map.of("id", "aviso-1"));

            mockMvc.perform(Requests.post("/api/admin/avisos")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            // Público SALA: o service é @MockitoBean (o payload não chega em validarAlvo),
                            // mas o caminho feliz do slice deve espelhar um corpo que o service REAL aceita
                            // — "ADMIN" hoje é 400, e usá-lo aqui daria a impressão contrária.
                            .content("{\"tipo\":\"VERIFICACAO\",\"permanente\":true,\"mensagens\":[\"oi\"],\"alvo_tipo\":\"SALA\",\"sala_ids\":[3]}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.id").value("aviso-1"));

            verify(avisoService).criar(any(CriarAvisoRequest.class), eq(TokenFactory.USER_ID));
        }

        @Test
        @DisplayName("família (c) — criar com validação recusada pelo service → 400 {ok:false, error}")
        void criar_validacaoRecusada_400() throws Exception {
            when(avisoService.criar(any(CriarAvisoRequest.class), anyString()))
                    .thenThrow(new ServiceValidationException("nenhuma_mensagem_valida"));

            mockMvc.perform(Requests.post("/api/admin/avisos")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tipo\":\"PESSOAL\",\"mensagens\":[],\"alvo_tipo\":\"ADMIN\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("nenhuma_mensagem_valida"));
        }

        @Test
        @DisplayName("PATCH /api/admin/avisos/{id}/desativar — 200 {ok, message}")
        void desativar_200() throws Exception {
            mockMvc.perform(Requests.patch("/api/admin/avisos/av-1/desativar").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.message").value("Aviso desativado."));

            verify(avisoService).desativar("av-1");
        }
    }

    // ══ Rotas comuns (mesmo arquivo, sem restrição de papel) ═══════════════

    @Nested
    @DisplayName("rotas comuns (/api/forms/checklist/**, /api/avisos/**) — mistura no mesmo controller")
    class Comum {

        @Test
        @DisplayName("GET /api/avisos/pendentes — operador: resolve papel do token, repassa contexto default e envelopa data")
        void pendentes_operador_200() throws Exception {
            when(avisoService.buscarPendentes(TokenFactory.USER_ID, PapelPessoa.OPERADOR, "geral"))
                    .thenReturn(List.of(Map.of("id", "p1")));

            mockMvc.perform(Requests.get("/api/avisos/pendentes").header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data[0].id").value("p1"));

            verify(avisoService).buscarPendentes(TokenFactory.USER_ID, PapelPessoa.OPERADOR, "geral");
        }

        @Test
        @DisplayName("POST /api/forms/checklist/aviso/{id}/ciencia — repassa (cadastroId, salaId, principal.id, papel) ao service")
        void registrarCiencia_200() throws Exception {
            mockMvc.perform(Requests.post("/api/forms/checklist/aviso/cad-1/ciencia")
                            .header("Authorization", operador)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sala_id\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));

            verify(avisoService).registrarCiencia("cad-1", 3, TokenFactory.USER_ID, PapelPessoa.OPERADOR);
        }
    }

    // ══ Binding inválido (400) ════════════════════════════════════════════

    @Test
    @DisplayName("binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_400() throws Exception {
        // sala_id é @RequestParam obrigatório em /aviso-pendente: ausente →
        // MissingServletRequestParameterException, agora tratada pelo handler de requisição
        // malformada do GlobalExceptionHandler → 400.
        mockMvc.perform(Requests.get("/api/forms/checklist/aviso-pendente").header("Authorization", operador))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));
    }
}
