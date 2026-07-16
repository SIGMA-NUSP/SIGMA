package br.leg.senado.nusp.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.entity.MetabaseDashboard;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.service.MetabaseEmbedService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do {@link MetabaseDashboardController} (T16).
 *
 * Segurança real (filtro + matcher /api/admin/**); {@link MetabaseEmbedService}
 * mockado — aqui NÃO se testa regra de negócio, só o contrato de resposta.
 * O RBAC papel×rota já está provado na matriz do T15 (este controller é o
 * representativo admin de lá) → não se duplica.
 *
 * Das famílias (a)–(f) do T16, este controller só exercita as aplicáveis: os
 * 2 endpoints são triviais e homogêneos (sem guard manual, sem 404 de detalhe,
 * sem binding tipado obrigatório além do path String, sem paginação). Cobre-se
 * o shape de cada um (200) e o mapeamento service→HTTP via
 * {@code GlobalExceptionHandler} (família c).
 */
@SigmaControllerTest(MetabaseDashboardController.class)
class MetabaseDashboardControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private MetabaseEmbedService embedService;

    private String admin;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        admin = "Bearer " + tokens.valido(TokenFactory.ADMIN);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Test
    @DisplayName("GET /dashboards — mapeia cada MetabaseDashboard para o card {id,titulo,descricao,icone,ordem}")
    void dashboards_mapeiaCards() throws Exception {
        MetabaseDashboard d = mock(MetabaseDashboard.class);
        when(d.getId()).thenReturn("d1");
        when(d.getTitulo()).thenReturn("Painel de Indicadores");
        when(d.getDescricao()).thenReturn("Visão geral");
        when(d.getIcone()).thenReturn("chart");
        when(d.getOrdem()).thenReturn(3);
        when(embedService.listarAtivos()).thenReturn(java.util.List.of(d));

        mockMvc.perform(Requests.get("/api/admin/metabase/dashboards").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("d1"))
                .andExpect(jsonPath("$[0].titulo").value("Painel de Indicadores"))
                .andExpect(jsonPath("$[0].descricao").value("Visão geral"))
                .andExpect(jsonPath("$[0].icone").value("chart"))
                .andExpect(jsonPath("$[0].ordem").value(3));
    }

    @Test
    @DisplayName("GET /dashboards/{id}/embed-url — devolve {url} com a URL assinada pelo service")
    void embedUrl_devolveUrl() throws Exception {
        when(embedService.gerarEmbedUrl("42")).thenReturn("https://bi-homolog/embed/token123");

        mockMvc.perform(Requests.get("/api/admin/metabase/dashboards/42/embed-url").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://bi-homolog/embed/token123"));
    }

    @Test
    @DisplayName("família (c) — ServiceValidationException do service é mapeada pelo GlobalExceptionHandler "
            + "(status próprio + {ok:false, error})")
    void embedUrl_servicoLanca_mapeadoPeloHandler() throws Exception {
        when(embedService.gerarEmbedUrl("999"))
                .thenThrow(new ServiceValidationException("dashboard_nao_encontrado", HttpStatus.NOT_FOUND));

        mockMvc.perform(Requests.get("/api/admin/metabase/dashboards/999/embed-url").header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("dashboard_nao_encontrado"));
    }
}
