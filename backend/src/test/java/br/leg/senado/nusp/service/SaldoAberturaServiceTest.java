package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unitários de {@link SaldoAberturaService#reancorar}.
 *
 * <p>Aqui se prova apenas que a re-âncora lê a linha de saldo pelo caminho que SEGURA a pessoa
 * ({@code lockPorPessoa} → {@code SELECT ... FOR UPDATE}), e que a trava vem ANTES das leituras que
 * alimentam o número gravado — sem a ordem, a soma dos débitos vivos sairia de um instante e a
 * gravação de outro, com um commit alheio no meio. Que o lock de fato serializa duas transações só
 * o Oracle real pode dizer: é o {@code PontoReancoraConcorrenteIT}.
 */
@ExtendWith(MockitoExtension.class)
class SaldoAberturaServiceTest {

    @Mock private PontoLotePaginaRepository paginaRepo;
    @Mock private PontoBancoSaldoRepository saldoRepo;
    @Mock private PontoSolicitacaoFolgaRepository solicitacaoRepo;

    @InjectMocks
    private SaldoAberturaService service;

    private static final String OP = "op-1";
    private static final String TIPO = "OPERADOR";
    private static final LocalDate FIM_DA_FOLHA = LocalDate.of(2026, 6, 30);

    /** A folha oficial da pessoa (âncora): página de lote PUBLICADO com BANCO extraído. */
    private void folhaOficialDe(int bancoFinalMin) {
        PontoLotePagina pagina = new PontoLotePagina();
        pagina.setId("pag-1");
        pagina.setBancoFinalMin(bancoFinalMin);
        PontoLote lote = new PontoLote();
        lote.setId("lote-1");
        lote.setDataFim(FIM_DA_FOLHA);
        when(paginaRepo.findCandidatasAncora(eq(OP), eq(TIPO), any(Limit.class)))
                .thenReturn(List.<Object[]>of(new Object[] { pagina, lote }));
    }

    private PontoBancoSaldo linhaExistente() {
        PontoBancoSaldo saldo = new PontoBancoSaldo();
        saldo.setId("saldo-1");
        saldo.setPessoaId(OP);
        saldo.setPessoaTipo(TIPO);
        saldo.setSaldoAberturaMin(0);
        saldo.setSaldoBancoMin(0);
        return saldo;
    }

    private PontoBancoSaldo gravado() {
        ArgumentCaptor<PontoBancoSaldo> captor = ArgumentCaptor.forClass(PontoBancoSaldo.class);
        verify(saldoRepo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a re-âncora TRAVA a pessoa antes de ler os débitos e de gravar o saldo")
    void reancoraTravaAPessoaAntesDeLerEGravar() {
        when(saldoRepo.lockPorPessoa(OP, TIPO)).thenReturn(Optional.of(linhaExistente()));
        folhaOficialDe(1000);
        when(solicitacaoRepo.somaDebitosVivosApos(eq(OP), eq(TIPO), anyCollection(), eq(FIM_DA_FOLHA)))
                .thenReturn(480L);

        service.reancorar(OP, TIPO);

        // A ordem importa: travar DEPOIS de somar os débitos deixaria a janela de corrida aberta —
        // a soma sairia de um instante e a gravação de outro, com o commit alheio no meio.
        InOrder ordem = inOrder(saldoRepo, solicitacaoRepo);
        ordem.verify(saldoRepo).lockPorPessoa(OP, TIPO);
        ordem.verify(solicitacaoRepo).somaDebitosVivosApos(eq(OP), eq(TIPO), anyCollection(), eq(FIM_DA_FOLHA));
        ordem.verify(saldoRepo).save(any(PontoBancoSaldo.class));
        // A leitura SEM lock não pode mais ser o caminho da re-âncora (ela é a leitura do saldo na tela).
        verify(saldoRepo, never()).findByPessoaIdAndPessoaTipo(any(), any());

        PontoBancoSaldo saldo = gravado();
        assertEquals(1000, saldo.getSaldoAberturaMin());
        assertEquals(FIM_DA_FOLHA, saldo.getAncoraData());
        assertEquals("pag-1", saldo.getAncoraPaginaId());
        assertEquals(520, saldo.getSaldoBancoMin(), "abertura 1000 − 480 de débito vivo pós-âncora");
    }

    @Test
    @DisplayName("o SELECT do saldo na re-âncora é FOR UPDATE: lockPorPessoa carrega @Lock(PESSIMISTIC_WRITE)")
    void lockPorPessoaCarregaLockPessimista() throws NoSuchMethodException {
        // Sem esta trava, apagar a anotação deixaria o método com o mesmo nome e a mesma assinatura: os
        // unitários e a suíte seguiriam verdes, e o lock teria evaporado em silêncio.
        Lock lock = PontoBancoSaldoRepository.class
                .getMethod("lockPorPessoa", String.class, String.class).getAnnotation(Lock.class);

        assertNotNull(lock, "lockPorPessoa sem @Lock não segura a linha: o SELECT vira uma leitura comum");
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    @DisplayName("pessoa sem folha oficial: abertura 0, âncora NULL e TODO débito vivo desconta")
    void semFolhaOficialTodoDebitoDesconta() {
        when(saldoRepo.lockPorPessoa(OP, TIPO)).thenReturn(Optional.of(linhaExistente()));
        when(paginaRepo.findCandidatasAncora(eq(OP), eq(TIPO), any(Limit.class))).thenReturn(List.of());
        when(solicitacaoRepo.somaDebitosVivos(eq(OP), eq(TIPO), anyCollection())).thenReturn(360L);

        service.reancorar(OP, TIPO);

        PontoBancoSaldo saldo = gravado();
        assertEquals(0, saldo.getSaldoAberturaMin());
        assertNull(saldo.getAncoraData());
        assertNull(saldo.getAncoraPaginaId());
        assertEquals(-360, saldo.getSaldoBancoMin());
        verify(solicitacaoRepo, never()).somaDebitosVivosApos(any(), any(), anyCollection(), any());
    }

    @Test
    @DisplayName("pessoa que nunca teve saldo não tem linha para travar: a linha nasce aqui, na transação")
    void pessoaSemLinhaDeSaldoNasceNaTransacao() {
        // O lock não bloqueia ninguém quando a linha não existe (é o mesmo idioma — e o mesmo residual —
        // do `solicitar`): duas criações concorrentes colidem na UK UQ_PNT_SALDO_PESSOA, não na espera.
        when(saldoRepo.lockPorPessoa(OP, TIPO)).thenReturn(Optional.empty());
        folhaOficialDe(120);
        when(solicitacaoRepo.somaDebitosVivosApos(eq(OP), eq(TIPO), anyCollection(), eq(FIM_DA_FOLHA)))
                .thenReturn(0L);

        service.reancorar(OP, TIPO);

        PontoBancoSaldo novo = gravado();
        assertNull(novo.getId(), "linha nova — o INSERT sai no flush desta transação");
        assertEquals(OP, novo.getPessoaId());
        assertEquals(TIPO, novo.getPessoaTipo());
        assertEquals(120, novo.getSaldoBancoMin());
    }
}
