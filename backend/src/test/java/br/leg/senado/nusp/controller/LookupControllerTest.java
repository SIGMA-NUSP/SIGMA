package br.leg.senado.nusp.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.leg.senado.nusp.controller.support.Requests;
import br.leg.senado.nusp.controller.support.SigmaControllerTest;
import br.leg.senado.nusp.controller.support.TokenFactory;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.ComissaoRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shape representativo do {@link LookupController} (T17).
 *
 * O javadoc de produção chama os lookups de públicos, mas o
 * {@code SecurityConfig} real exige autenticação para esta rota; esse RBAC já
 * foi caracterizado no T15. Aqui se usa token válido e se testa somente o
 * envelope/mapeamento do handler com repositories mockados.
 */
@SigmaControllerTest(LookupController.class)
class LookupControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthSessionRepository authSessionRepository; // mock da meta-anotação

    @MockitoBean private OperadorRepository operadorRepository;
    @MockitoBean private SalaRepository salaRepository;
    @MockitoBean private ComissaoRepository comissaoRepository;

    private String operador;

    @BeforeEach
    void setUp() {
        TokenFactory tokens = new TokenFactory(jwtTokenProvider);
        operador = "Bearer " + tokens.valido(TokenFactory.OPERADOR);
        // Sessão viva; o default do Mockito (0) significaria sessão inválida → 401.
        when(authSessionRepository.touchSession(anyLong(), anyString(), anyInt())).thenReturn(1);
    }

    @Test
    @DisplayName("operadores — 200 {ok,data} não vazio com os quatro campos do lookup")
    void operadores_shape() throws Exception {
        Operador op = new Operador();
        op.setId("op-1");
        op.setNomeCompleto("Operadora Um");
        op.setParticipaEscala(true);
        op.setTurno("V");
        when(operadorRepository.findAllOrderByNomeCompleto()).thenReturn(List.of(op));

        mockMvc.perform(Requests.get("/api/forms/lookup/operadores")
                        .header("Authorization", operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("op-1"))
                .andExpect(jsonPath("$.data[0].nome_completo").value("Operadora Um"))
                .andExpect(jsonPath("$.data[0].participa_escala").value(true))
                .andExpect(jsonPath("$.data[0].turno").value("V"));

        verify(operadorRepository).findAllOrderByNomeCompleto();
        verifyNoInteractions(salaRepository, comissaoRepository);
    }
}
