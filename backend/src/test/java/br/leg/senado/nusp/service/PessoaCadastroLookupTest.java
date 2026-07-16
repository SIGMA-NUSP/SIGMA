package br.leg.senado.nusp.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * O {@code switch} de existência do F34 — a ÚNICA implementação real da pergunta "esta pessoa existe?"
 * do par polimórfico do Ponto, e por isso o único lugar onde ela pode ser provada de verdade.
 *
 * <p>Os dois consumidores ({@code MarcacaoService.aplicarLote} e {@code PontoService.atualizarVinculo})
 * mockam este componente — lá se prova o que eles FAZEM com a resposta (400, nada gravado). Se o switch
 * não tivesse teste próprio, trocar {@code case "TECNICO"} pelo repositório errado, ou devolver
 * {@code true} no {@code default}, deixaria a suíte inteira verde e o F34 voltaria calado. É este
 * arquivo que fecha essa porta.
 *
 * <p>O <b>par trocado</b> (id real de OPERADOR declarado como TECNICO) só é demonstrável aqui: exige um
 * cadastro que responde {@code true} e outro que responde {@code false} para o MESMO id.
 */
@ExtendWith(MockitoExtension.class)
class PessoaCadastroLookupTest {

    @Mock private OperadorRepository operadorRepo;
    @Mock private TecnicoRepository tecnicoRepo;
    @Mock private AdministradorRepository administradorRepo;

    @InjectMocks
    private PessoaCadastroLookup lookup;

    @Test
    @DisplayName("corrige F34 — cada tipo consulta o SEU cadastro (e só ele)")
    void cadaTipoConsultaOSeuCadastro() {
        when(operadorRepo.existsById("op-1")).thenReturn(true);
        assertTrue(lookup.existe("op-1", "OPERADOR"));
        verify(tecnicoRepo, never()).existsById("op-1");
        verify(administradorRepo, never()).existsById("op-1");

        when(tecnicoRepo.existsById("tec-7")).thenReturn(true);
        assertTrue(lookup.existe("tec-7", "TECNICO"));

        when(administradorRepo.existsById("adm-9")).thenReturn(true);
        assertTrue(lookup.existe("adm-9", "ADMINISTRADOR"));
    }

    /**
     * O coração do F34: o id EXISTE — como OPERADOR. Declarado TECNICO, a resposta tem de ser não, porque
     * a marcação gravada com o par (op-1, TECNICO) é a linha órfã que nenhuma leitura do módulo procura.
     * Um {@code existsById} genérico (sem o switch por tipo) devolveria true aqui.
     */
    @Test
    @DisplayName("corrige F34 — par trocado: id real de OPERADOR declarado como TECNICO → false (o cadastro consultado é o do TIPO)")
    void parTrocado() {
        when(tecnicoRepo.existsById("op-1")).thenReturn(false);   // no cadastro de técnicos, esse id não existe

        assertFalse(lookup.existe("op-1", "TECNICO"));

        // A pergunta foi feita ao cadastro de TÉCNICOS — o de operadores (onde o id existe) nem é tocado.
        verify(tecnicoRepo).existsById("op-1");
        verifyNoInteractions(operadorRepo, administradorRepo);
    }

    @Test
    @DisplayName("corrige F34 — pessoa inexistente no cadastro do próprio tipo → false")
    void inexistente() {
        when(operadorRepo.existsById("fantasma")).thenReturn(false);

        assertFalse(lookup.existe("fantasma", "OPERADOR"));
    }

    @Test
    @DisplayName("corrige F34 — tipo desconhecido → false, sem consultar cadastro nenhum")
    void tipoDesconhecido() {
        assertFalse(lookup.existe("op-1", "XPTO"));
        assertFalse(lookup.existe("op-1", null));

        verifyNoInteractions(operadorRepo, tecnicoRepo, administradorRepo);
    }

    /** Id vazio nem chega ao banco: {@code existsById(null)} estouraria, e {@code ""} seria uma ida inútil. */
    @Test
    @DisplayName("corrige F34 — id nulo ou em branco → false, sem tocar o banco")
    void idVazio() {
        assertFalse(lookup.existe(null, "OPERADOR"));
        assertFalse(lookup.existe("   ", "OPERADOR"));

        verifyNoInteractions(operadorRepo, tecnicoRepo, administradorRepo);
    }

    /** O tipo chega da UI e do JSON como vier; é o lookup que o põe na forma em que as tabelas o gravam. */
    @Test
    @DisplayName("corrige F34 — o tipo é normalizado (caixa e espaços) antes do switch")
    void tipoNormalizado() {
        when(tecnicoRepo.existsById("tec-7")).thenReturn(true);

        assertTrue(lookup.existe("tec-7", "  tecnico  "));
        assertTrue(lookup.existe("tec-7", "Tecnico"));

        // E o normalizador é o mesmo que os chamadores usam para GRAVAR o PESSOA_TIPO.
        assertTrue("ADMINISTRADOR".equals(PessoaCadastroLookup.normalizarTipo(" administrador ")));
        assertTrue("".equals(PessoaCadastroLookup.normalizarTipo(null)));
    }
}
