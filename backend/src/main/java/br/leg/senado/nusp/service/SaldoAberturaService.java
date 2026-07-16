package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Âncora oficial do banco de horas (handoff §4.2 / plano E2): a folha
 * publicada mais recente com BANCO extraído define o saldo de abertura de
 * cada pessoa; o cache SALDO_BANCO_MIN desconta apenas os débitos vivos
 * (PENDENTE/APROVADO) com DATA_FOLGA posterior à âncora — o BANCO do PDF já
 * embute as folgas do período que ele cobre (Q4). Ao publicar um lote novo a
 * âncora avança e o período recém-coberto passa a valer pelo oficial.
 */
@Service
@RequiredArgsConstructor
public class SaldoAberturaService {

    private final PontoLotePaginaRepository paginaRepo;
    private final PontoBancoSaldoRepository saldoRepo;
    private final PontoSolicitacaoFolgaRepository solicitacaoRepo;

    private static final List<StatusSolicitacaoFolga> STATUS_VIVOS =
            List.of(StatusSolicitacaoFolga.PENDENTE, StatusSolicitacaoFolga.APROVADO);

    /** Pessoa polimórfica (PESSOA_ID + PESSOA_TIPO) — a chave da linha única de PNT_BANCO_SALDO. */
    public record ChavePessoa(String pessoaId, String pessoaTipo) {}

    /**
     * Ordem em que os locks de saldo são adquiridos quando se re-ancora um CONJUNTO de pessoas
     * (F60): tipo, depois id. O critério é arbitrário — o que importa é ser o MESMO em toda
     * re-âncora em lote (publicação, backfill e, desde o F59, exclusão), para que dois laços
     * concorrentes nunca peguem as linhas de duas pessoas em ordem inversa (o que dá ORA-00060,
     * deadlock, servido ao admin como 500 cru).
     *
     * <p>Mora aqui, e não em quem chama, porque a invariante é do LOCK — e o lock é desta classe.
     * O laço em si fica com o chamador de propósito: ele precisa invocar {@link #reancorar} no BEAN
     * (proxy transacional), e um laço interno faria self-invocation.
     */
    public static final Comparator<ChavePessoa> ORDEM_DO_LOCK =
            Comparator.comparing(ChavePessoa::pessoaTipo).thenComparing(ChavePessoa::pessoaId);

    /**
     * Recalcula âncora + abertura + cache da pessoa (upsert na linha única de
     * PNT_BANCO_SALDO). Sem folha oficial utilizável: abertura 0 e âncora NULL
     * ("sem folha oficial", handoff §6.3) — aí todo débito vivo desconta.
     * Sempre dentro de transação (gotcha 3); chamado na publicação de lote
     * (pessoas do lote) e, a partir do E7/E8, nos eventos de solicitação.
     *
     * <p><b>A pessoa é TRAVADA antes de qualquer leitura</b> ({@code lockPorPessoa} →
     * SELECT ... FOR UPDATE, o mesmo idioma de {@code BancoHorasService.solicitar}) — F60.
     * O lock é o que faz o par ler-e-gravar ser atômico entre transações: a publicação
     * segurava o LOTE, não a PESSOA, e por isso somava os débitos vivos ANTES do commit de
     * uma solicitação simultânea e gravava, depois dele, um SALDO_BANCO_MIN sem aquele
     * débito — saldo inflado na tela até o próximo evento da pessoa. Travando, uma das duas
     * transações espera a outra e recalcula sobre o estado já commitado, em qualquer ordem.
     *
     * <p><b>Residual (mesmo do {@code solicitar}):</b> pessoa que nunca teve saldo não tem
     * linha para travar — o lock não bloqueia ninguém e a linha nasce aqui, na transação.
     * Duas criações concorrentes colidem na UK UQ_PNT_SALDO_PESSOA (uma delas falha e sofre
     * rollback), e não na espera do lock. É inócuo na prática: sem linha, o saldo é 0 e
     * {@code solicitar} recusa por saldo insuficiente antes de criar débito algum.
     */
    @Transactional
    public PontoBancoSaldo reancorar(String pessoaId, String pessoaTipo) {
        PontoBancoSaldo saldo = saldoRepo.lockPorPessoa(pessoaId, pessoaTipo)
                .orElseGet(() -> {
                    PontoBancoSaldo novo = new PontoBancoSaldo();
                    novo.setPessoaId(pessoaId);
                    novo.setPessoaTipo(pessoaTipo);
                    return novo;
                });

        List<Object[]> candidatas = paginaRepo.findCandidatasAncora(pessoaId, pessoaTipo, Limit.of(1));

        int abertura = 0;
        LocalDate ancoraData = null;
        String ancoraPaginaId = null;
        if (!candidatas.isEmpty()) {
            PontoLotePagina pagina = (PontoLotePagina) candidatas.get(0)[0];
            PontoLote lote = (PontoLote) candidatas.get(0)[1];
            abertura = pagina.getBancoFinalMin();
            ancoraData = lote.getDataFim();
            ancoraPaginaId = pagina.getId();
        }

        long debitos = ancoraData == null
                ? solicitacaoRepo.somaDebitosVivos(pessoaId, pessoaTipo, STATUS_VIVOS)
                : solicitacaoRepo.somaDebitosVivosApos(pessoaId, pessoaTipo, STATUS_VIVOS, ancoraData);

        saldo.setSaldoAberturaMin(abertura);
        saldo.setAncoraData(ancoraData);
        saldo.setAncoraPaginaId(ancoraPaginaId);
        saldo.setSaldoBancoMin(abertura - (int) debitos);
        return saldoRepo.save(saldo);
    }
}
