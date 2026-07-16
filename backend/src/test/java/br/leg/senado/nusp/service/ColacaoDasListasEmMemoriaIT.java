package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import jakarta.persistence.EntityManager;

/**
 * As listas de pessoas que o Ponto ordena <b>em memória</b> — e não pelo banco — obedecem à mesma
 * colação pt-BR das listagens (F30/C15).
 *
 * <p><b>Por que este IT existe.</b> O C15 fixou {@code NLS_SORT=BINARY_AI} na sessão: tudo que vem
 * ORDENADO DO BANCO passou a ignorar acento e caixa. Mas estas duas listas são montadas e ordenadas
 * em Java, e ordenavam por {@code toUpperCase()} — ordem binária, onde "Á" (0xC1) vem depois de "Z".
 * Enquanto o banco ordenava igual (produção rodava em BINARY), ninguém percebia; ao curar o banco, as
 * duas pontas passaram a discordar, e a MESMA equipe aparecia em ordens diferentes conforme a tela:
 * a grade de retificações (e o XLSX exportado dela) dizia "Katiane, Kátia", a listagem de pessoas
 * dizia "Kátia, Katiane". A correção foi apontar as duas para {@link NativeQueryUtils#ORDEM_TEXTO_PT_BR}.
 *
 * <p>O cenário usa nomes reais do espelho de produção — {@code Kátia Mayara} e {@code Katiane} são o
 * par que de fato inverte: sob a ordem binária, {@code Katiane} vem antes ({@code 'a'} = 0x61 &lt;
 * {@code 'á'}); sob a colação pt-BR, {@code Kátia} vem antes ({@code katia} &lt; {@code katiane}).
 */
@OracleIT
class ColacaoDasListasEmMemoriaIT {

    @Autowired
    private TestEntityManager em;
    @Autowired
    private OperadorRepository operadorRepo;
    @Autowired
    private TecnicoRepository tecnicoRepo;
    @Autowired
    private AdministradorRepository administradorRepo;
    @Autowired
    private PontoRetificacaoRepository retificacaoRepo;
    @Autowired
    private PontoSolicitacaoFolgaRepository folgaRepo;
    @Autowired
    private PontoDiaMarcacaoRepository diaRepo;
    @Autowired
    private PontoPessoaMarcacaoRepository pessoaRepo;
    @Autowired
    private PontoLoteRepository loteRepo;
    @Autowired
    private PontoLotePaginaRepository paginaRepo;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    /** Operador com nome EXATO — o factory sufixa o nome, e aqui o nome é a matéria do teste. */
    private Operador comNomeExato(String nome, String login) {
        Operador op = CenarioFactory.novoOperador(emReal(), "Semente", login);
        op.setNomeCompleto(nome);
        emReal().flush();
        return op;
    }

    /** O par que inverte (Kátia/Katiane) + um acentuado inicial e um ordinal, que só o NFKD acerta. */
    private void semearNomesDoEspelhoDeProducao() {
        comNomeExato("Katiane dos Santos", "c1");
        comNomeExato("Kátia Mayara", "c2");
        comNomeExato("Ângela Costa", "c3");
        comNomeExato("Ana Beatriz", "c4");
    }

    private static final List<String> ORDEM_PT_BR = List.of(
            "Ana Beatriz", "Ângela Costa", "Kátia Mayara", "Katiane dos Santos");

    @Test
    @DisplayName("corrige F30 — a grade de retificações (e o XLSX que sai dela) ordena em pt-BR, como as listagens")
    void gradeDeRetificacoes_ordenaEmPtBr() {
        semearNomesDoEspelhoDeProducao();
        GradeRetificacaoService grade = new GradeRetificacaoService(retificacaoRepo, folgaRepo,
                diaRepo, pessoaRepo, operadorRepo, tecnicoRepo, administradorRepo);

        List<String> nomes = grade.montarGrade("operadores", 2026, 7).funcionarios().stream()
                .map(GradeRetificacaoService.Funcionario::nome).toList();

        assertEquals(ORDEM_PT_BR, nomes,
                "as linhas da grade seguem a colação pt-BR — com um toUpperCase() binário, 'Katiane'"
                        + " viria antes de 'Kátia' e 'Ângela' cairia depois de tudo");
    }

    @Test
    @DisplayName("corrige F30 — o seletor de pessoas do Ponto ordena em pt-BR, como as listagens")
    void seletorDePessoas_ordenaEmPtBr() {
        semearNomesDoEspelhoDeProducao();
        PontoService ponto = new PontoService(loteRepo, paginaRepo, operadorRepo, tecnicoRepo,
                administradorRepo, mock(AvisoService.class), mock(SaldoAberturaService.class),
                mock(RetificacaoService.class),
                new PessoaCadastroLookup(operadorRepo, tecnicoRepo, administradorRepo));
        ReflectionTestUtils.setField(ponto, "filesDir", "/tmp/nusp-test-files-inexistente");

        List<String> nomes = ponto.listarPessoas().stream()
                .map(p -> String.valueOf(p.get("nome")))
                .filter(ORDEM_PT_BR::contains)      // técnicos do cenário base não interessam aqui
                .toList();

        assertEquals(ORDEM_PT_BR, nomes, "o dropdown de vínculo do ponto segue a mesma ordem das listagens");
    }
}
