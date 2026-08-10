package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import jakarta.persistence.EntityManager;

/**
 * IT da listagem de anormalidades do admin ({@code listAnormalidades}) contra Oracle real. O ponto
 * central é o recorte por sala: o {@code sala_id} entra como WHERE da query EXTERNA e tem de
 * eliminar as anormalidades das demais salas — pendurado no ON do LEFT JOIN de operador, ele não
 * eliminaria linha nenhuma e a listagem devolveria todas as salas com {@code registrado_por} nulo
 * nas alheias.
 */
@OracleIT
class AnormalidadeListagemIT {

    @Autowired private TestEntityManager em;

    private AdminDashboardService service;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @BeforeEach
    void setUp() {
        service = new AdminDashboardService(emReal(), new ObjectMapper());
    }

    @Test
    @DisplayName("listagem: o filtro de sala devolve só as anormalidades daquela sala, com o operador resolvido")
    void filtroDeSalaRecortaAListagem() {
        Sala salaUm = CenarioFactory.novaSala(emReal());
        Sala salaDois = CenarioFactory.novaSala(emReal());
        Operador operador = CenarioFactory.novoOperador(emReal());
        RegistroOperacaoAudio registroUm = CenarioFactory.novoRegistroAudio(emReal(), salaUm);
        RegistroOperacaoAudio registroDois = CenarioFactory.novoRegistroAudio(emReal(), salaDois);
        RegistroOperacaoOperador entradaUm = CenarioFactory.novaEntrada(emReal(), registroUm, operador, 1);
        RegistroOperacaoOperador entradaDois = CenarioFactory.novaEntrada(emReal(), registroDois, operador, 1);
        RegistroAnormalidade daSalaUm = CenarioFactory.novaAnormalidade(emReal(), registroUm, salaUm,
                entradaUm.getId(), operador.getId());
        RegistroAnormalidade daSalaDois = CenarioFactory.novaAnormalidade(emReal(), registroDois, salaDois,
                entradaDois.getId(), operador.getId());

        var semFiltro = service.listAnormalidades(1, 100, "", "data", "desc", null, null, null);
        List<Object> idsSemFiltro = semFiltro.data().stream().map(m -> m.get("id")).toList();
        assertTrue(idsSemFiltro.contains(daSalaUm.getId()) && idsSemFiltro.contains(daSalaDois.getId()),
                "sem filtro, as anormalidades das duas salas aparecem");

        var daUm = service.listAnormalidades(1, 100, "", "data", "desc", null, null, salaUm.getId());
        List<Object> ids = daUm.data().stream().map(m -> m.get("id")).toList();
        assertTrue(ids.contains(daSalaUm.getId()));
        assertFalse(ids.contains(daSalaDois.getId()), "anormalidade de outra sala não pode aparecer no recorte");
        assertTrue(daUm.data().stream().allMatch(m -> salaUm.getNome().equals(m.get("sala_nome"))),
                "nenhuma linha de outra sala");
        assertEquals(daUm.data().size(), daUm.total(), "o COUNT usa o mesmo recorte da query de dados");

        Map<String, Object> linha = daUm.data().stream()
                .filter(m -> daSalaUm.getId().equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(operador.getNomeCompleto(), linha.get("registrado_por"),
                "o LEFT JOIN de operador segue resolvendo o nome dentro do recorte");
    }
}
