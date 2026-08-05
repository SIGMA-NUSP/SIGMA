package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.MetabaseDashboard;
import br.leg.senado.nusp.repository.MetabaseDashboardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recorte do catálogo de dashboards por página.
 *
 * O catálogo é semeado à mão em cada ambiente e lido por páginas diferentes:
 * o que se prova aqui é que um pedido malformado (ausente, em branco, com
 * caixa trocada) não devolve silenciosamente a página errada nem lista vazia.
 */
@ExtendWith(MockitoExtension.class)
class MetabaseEmbedServiceTest {

    @Mock private MetabaseDashboardRepository repository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private MetabaseEmbedService service;

    @Test
    @DisplayName("contexto ausente responde pelo Painel Administrativo, que já lia este catálogo")
    void contextoNulo_respondePeloPainel() {
        when(repository.findByAtivoTrueAndContextoOrderByOrdemAscTituloAsc(MetabaseDashboard.CONTEXTO_PAINEL))
                .thenReturn(List.of());

        assertEquals(List.of(), service.listarAtivos(null));

        verify(repository).findByAtivoTrueAndContextoOrderByOrdemAscTituloAsc(MetabaseDashboard.CONTEXTO_PAINEL);
    }

    @Test
    @DisplayName("contexto em branco cai no Painel — chave sem valor na URL não vira filtro vazio")
    void contextoEmBranco_respondePeloPainel() {
        when(repository.findByAtivoTrueAndContextoOrderByOrdemAscTituloAsc(MetabaseDashboard.CONTEXTO_PAINEL))
                .thenReturn(List.of());

        assertEquals(List.of(), service.listarAtivos("   "));

        verify(repository).findByAtivoTrueAndContextoOrderByOrdemAscTituloAsc(MetabaseDashboard.CONTEXTO_PAINEL);
    }

    @Test
    @DisplayName("contexto é normalizado: espaços em volta e caixa não mudam a página consultada")
    void contexto_normalizadoParaMaiusculas() {
        MetabaseDashboard dashboard = new MetabaseDashboard();
        when(repository.findByAtivoTrueAndContextoOrderByOrdemAscTituloAsc(
                MetabaseDashboard.CONTEXTO_GESTAO_PESSOAS)).thenReturn(List.of(dashboard));

        assertEquals(List.of(dashboard), service.listarAtivos("  gestao_pessoas  "));

        verify(repository).findByAtivoTrueAndContextoOrderByOrdemAscTituloAsc(
                MetabaseDashboard.CONTEXTO_GESTAO_PESSOAS);
    }

    @Test
    @DisplayName("pedido de todas as páginas devolve o catálogo ativo inteiro, sem recorte")
    void contextoTodos_devolveCatalogoInteiro() {
        MetabaseDashboard dashboard = new MetabaseDashboard();
        when(repository.findByAtivoTrueOrderByOrdemAscTituloAsc()).thenReturn(List.of(dashboard));

        assertEquals(List.of(dashboard), service.listarAtivos(MetabaseEmbedService.CONTEXTO_TODOS));

        verify(repository).findByAtivoTrueOrderByOrdemAscTituloAsc();
    }

    @Test
    @DisplayName("página sem dashboard cadastrado devolve lista vazia, não erro")
    void paginaSemDashboard_listaVazia() {
        when(repository.findByAtivoTrueAndContextoOrderByOrdemAscTituloAsc(
                MetabaseDashboard.CONTEXTO_GESTAO_PESSOAS)).thenReturn(List.of());

        assertEquals(List.of(), service.listarAtivos(MetabaseDashboard.CONTEXTO_GESTAO_PESSOAS));
    }
}
