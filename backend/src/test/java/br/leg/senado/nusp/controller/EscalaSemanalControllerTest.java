package br.leg.senado.nusp.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.EscalaSemanalService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link EscalaSemanalController} (T16).
 *
 * Controller MISTO (sem @RequestMapping de classe): rotas /api/admin/escala/**
 * e rotas comuns /api/escala/** no mesmo arquivo. RBAC provado no T15 (é o
 * representativo misto de lá). Famílias do T16: paginação admin (e),
 * mapeamento ServiceValidationException→HTTP (c), binding inválido
 * respondendo 400 (d, F6 — corrigido no C4) e contrato singular — o status condicional do
 * save (201 cria / 200 atualiza), DELETE e a mistura admin/comum (f, a rota
 * comum /escala/minha combina service + repositório). Sem guard manual (a)
 * nem 404 de detalhe (b) neste controller.
 */
@SigmaControllerTest(EscalaSemanalController.class)
class EscalaSemanalControllerTest {

    private static final String ADMIN_USERNAME = "teste.administrador"; // TokenFactory: "teste." + perfil

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private EscalaSemanalService escalaService;
    @MockitoBean private OperadorRepository operadorRepo;

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

    // ══ (e) Paginação admin ════════════════════════════════════════════════

    @Test
    @DisplayName("família (e) — /admin/escala/list repassa page/limit ao service e envelopa {ok,data,meta}")
    void list_repassaPaginacao() throws Exception {
        when(escalaService.listarEscalasPaginado(4, 9))
                .thenReturn(Map.of("data", List.of(), "meta", Map.of("total", 0)));

        mockMvc.perform(Requests.get("/api/admin/escala/list?page=4&limit=9").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.meta.total").value(0));

        verify(escalaService).listarEscalasPaginado(4, 9);
    }

    // ══ (f) Contrato singular — status condicional do save, DELETE, mistura ═

    @Nested
    @DisplayName("família (f) — contrato singular (save condicional, delete, rota comum mista)")
    class ContratoSingular {

        @Test
        @DisplayName("POST /admin/escala/save — sem id → 201 (cria) e repassa criadoPor=username do token")
        void save_semId_201() throws Exception {
            when(escalaService.salvarEscala(isNull(), any(LocalDate.class), any(LocalDate.class),
                    any(), any(), any(), eq(ADMIN_USERNAME))).thenReturn(Map.of("id", 100));

            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.id").value(100));

            verify(escalaService).salvarEscala(isNull(), any(LocalDate.class), any(LocalDate.class),
                    any(), any(), any(), eq(ADMIN_USERNAME));
        }

        @Test
        @DisplayName("POST /admin/escala/save — com id → 200 (atualiza): o status muda conforme id presente")
        void save_comId_200() throws Exception {
            when(escalaService.salvarEscala(eq(7L), any(LocalDate.class), any(LocalDate.class),
                    any(), any(), any(), eq(ADMIN_USERNAME))).thenReturn(Map.of("id", 7));

            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":7,\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.id").value(7));
        }

        @Test
        @DisplayName("DELETE /admin/escala/{id} — 200 {ok, message} e delega ao service")
        void excluir_200() throws Exception {
            mockMvc.perform(Requests.delete("/api/admin/escala/5").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.message").value("Escala excluída com sucesso."));

            verify(escalaService).excluirEscala(5L);
        }

        @Test
        @DisplayName("GET /api/escala/minha (rota comum) — operador: combina service + repositório em {ok,data,plenario_principal}")
        void minhaEscala_operador_200() throws Exception {
            Operador op = mock(Operador.class);
            when(op.getPlenarioPrincipal()).thenReturn(true);
            when(operadorRepo.findById(TokenFactory.USER_ID)).thenReturn(Optional.of(op));
            when(escalaService.minhaEscalaHoje(TokenFactory.USER_ID)).thenReturn(List.of(Map.of("sala", "Plenário 1")));

            mockMvc.perform(Requests.get("/api/escala/minha").header("Authorization", operador))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data[0].sala").value("Plenário 1"))
                    .andExpect(jsonPath("$.plenario_principal").value(true));
        }
    }

    // ══ (c) ServiceValidationException → GlobalExceptionHandler ════════════

    @Test
    @DisplayName("família (c) — save com período recusado pelo service → 400 {ok:false, error}")
    void save_periodoInvalido_400() throws Exception {
        when(escalaService.salvarEscala(any(), any(), any(), any(), any(), any(), anyString()))
                .thenThrow(new ServiceValidationException("periodo_invalido"));

        mockMvc.perform(Requests.post("/api/admin/escala/save")
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data_inicio\":\"2026-07-17\",\"data_fim\":\"2026-07-13\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("periodo_invalido"));
    }

    // ══ (d) Binding inválido — corrige F6 (400) ════════════════════════════

    // ══ Payload do corpo (Map cru) — corrige F27 (400, não 500) ═══════════

    /**
     * O corpo destes endpoints chega como {@code Map}, sem binding do Spring: o controller lia
     * {@code payload.get("data_inicio").toString()} e {@code (List<String>) entry.getValue()} direto,
     * então um corpo incompleto ou de tipo errado rebentava em NPE/ClassCastException e o cliente
     * recebia **500** por um erro que era dele. Agora cada campo é exigido e cada lista é conferida
     * (container E elementos — o cast some no erasure) antes do parse, com o nome do campo na mensagem.
     * O handler do F6 não alcança nada disto (não é binding do Spring) — daí o F27.
     */
    @Nested
    @DisplayName("corrige F27 — corpo incompleto/inválido responde 400 com o campo culpado")
    class PayloadInvalido {

        @Test
        @DisplayName("save sem data_inicio → 400 (antes: NPE → 500)")
        void save_semDataInicio_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_fim\":\"2026-07-17\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Campo obrigatório ausente: data_inicio."));

            verifyNoInteractions(escalaService);
        }

        @Test
        @DisplayName("save com valor de sala que não é lista → 400 (antes: ClassCastException → 500)")
        void save_valorDeSalaNaoEhLista_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\","
                                    + "\"salas\":{\"3\":\"op-1\"}}")) // deveria ser ["op-1"]
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Valor inválido em salas (esperado uma lista)."));

            verifyNoInteractions(escalaService);
        }

        @Test
        @DisplayName("save com valor de função que não é lista → 400 (o mesmo cast cru vivia nos dois parsers)")
        void save_valorDeFuncaoNaoEhLista_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\","
                                    + "\"funcoes\":{\"FECHAMENTO\":\"op-1\"}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Valor inválido em funcoes (esperado uma lista)."));

            verifyNoInteractions(escalaService);
        }

        @Test
        @DisplayName("save com item vazio na lista de operadores → 400 (antes: NPE no service → 500)")
        void save_itemVazioNaLista_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\","
                                    + "\"salas\":{\"3\":[\"op-1\", null]}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Item vazio na lista de salas."));

            verifyNoInteractions(escalaService);
        }

        @Test
        @DisplayName("save com id vazio → 400 (e NÃO um registro novo criado em silêncio)")
        void save_idVazio_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"\",\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Valor inválido em id (esperado um número)."));

            verifyNoInteractions(escalaService); // nada de criar uma escala duplicada achando que o id "não veio"
        }

        @Test
        @DisplayName("save com operadores numéricos na lista → 201 (vira texto; antes: ClassCastException no service → 500)")
        void save_operadoresNaoTextuais_viramTexto() throws Exception {
            when(escalaService.salvarEscala(isNull(), any(LocalDate.class), any(LocalDate.class),
                    eq(Map.of(3, List.of("1", "2"))), any(), any(), eq(ADMIN_USERNAME)))
                    .thenReturn(Map.of("id", 100));

            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\","
                                    + "\"salas\":{\"3\":[1, 2]}}"))
                    .andExpect(status().isCreated());

            verify(escalaService).salvarEscala(isNull(), any(LocalDate.class), any(LocalDate.class),
                    eq(Map.of(3, List.of("1", "2"))), any(), any(), eq(ADMIN_USERNAME));
        }

        @Test
        @DisplayName("save com data fora do ISO → 400 nomeando o campo (o F6 já dava 400; o que muda é a mensagem)")
        void save_dataForaDoIso_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"13/07/2026\",\"data_fim\":\"2026-07-17\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Data inválida em data_inicio (use AAAA-MM-DD)."));

            verifyNoInteractions(escalaService);
        }

        @Test
        @DisplayName("save com id não numérico → 400 (antes: NumberFormatException → 500)")
        void save_idNaoNumerico_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"abc\",\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Valor inválido em id (esperado um número)."));

            verifyNoInteractions(escalaService);
        }

        @Test
        @DisplayName("save com chave de sala não numérica → 400 (antes: NumberFormatException → 500)")
        void save_chaveDeSalaNaoNumerica_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/save")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"2026-07-13\",\"data_fim\":\"2026-07-17\","
                                    + "\"salas\":{\"abc\":[\"op-1\"]}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Chave inválida em salas: abc (esperado um número)."));

            verifyNoInteractions(escalaService);
        }

        @Test
        @DisplayName("rodízio sem data_fim → 400 (o mesmo parse cru vivia nos 3 endpoints)")
        void rodizio_semDataFim_400() throws Exception {
            mockMvc.perform(Requests.post("/api/admin/escala/rodizio")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"data_inicio\":\"2026-07-13\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Campo obrigatório ausente: data_fim."));

            verifyNoInteractions(escalaService);
        }
    }

    @Test
    @DisplayName("corrige F6 — binding inválido responde 400 no shape padrão de erro")
    void bindingInvalido_corrigeF6_400() throws Exception {
        // page é @RequestParam int: valor não-numérico → MethodArgumentTypeMismatchException,
        // agora tratada pelo handler de requisição malformada do GlobalExceptionHandler → 400.
        // Achado F6 da §5 do plano — corrigido no C4.
        mockMvc.perform(Requests.get("/api/admin/escala/list?page=abc").header("Authorization", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Requisição inválida. Verifique os dados enviados."));
    }
}
