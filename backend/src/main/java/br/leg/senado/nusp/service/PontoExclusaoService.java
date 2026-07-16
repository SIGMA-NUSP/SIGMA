package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoExclusaoLog;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AvisoAlvoRepository;
import br.leg.senado.nusp.repository.AvisoCadastroRepository;
import br.leg.senado.nusp.repository.AvisoCienciaRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import br.leg.senado.nusp.repository.PontoExclusaoLogRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.service.SaldoAberturaService.ChavePessoa;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Exclusão de publicações do Ponto (F59) — o desfazer que não existia.
 *
 * <p><b>O problema.</b> A publicação de um lote era irreversível, e a guarda da folha mensal (C6/F32)
 * transformava um engano do admin em prejuízo permanente: publicada por engano uma MENSAL (PDF
 * errado, período digitado errado), a competência daquelas pessoas FECHAVA — a mensal correta passava
 * a ser recusada, e as semanais atrasadas do mês também. A recusa mandava "remover a página e publicar
 * novamente", o que é impossível num lote publicado; só um DELETE manual no Oracle o desfazia.
 *
 * <p><b>O que este service faz.</b> Dá ao <b>admin master</b> (e só a ele — {@code app.admin.master-username},
 * o mesmo mecanismo do {@code AdminCrudService}) o poder de excluir um lote inteiro ou uma folha
 * individual dele, com um PREVIEW honesto das consequências antes do gesto. A exclusão é PROFUNDA e
 * cirúrgica ao mesmo tempo — leva tudo que aquela publicação criou, e <b>nada</b> que ela não tenha
 * criado:
 *
 * <ul>
 *   <li><b>retificações</b> ancoradas nas páginas excluídas (chave = PAGINA_ID). As da mesma pessoa
 *       ancoradas em OUTRA folha publicada sobrevivem — folhas semanais são cumulativas e cobrem os
 *       mesmos dias;</li>
 *   <li><b>avisos pessoais</b> criados por AQUELA publicação (chave = {@code ORIGEM_LOTE_ID}, gravado
 *       na publicação). Avisos de outra origem — o desfecho de folga do banco de horas — jamais são
 *       tocados;</li>
 *   <li><b>arquivos</b> (PDF do lote e das páginas), best-effort DEPOIS do commit;</li>
 *   <li><b>âncora do banco de horas</b>: quem perde a folha mais recente volta a ancorar na
 *       imediatamente anterior; sem nenhuma, abertura 0 e âncora NULL.</li>
 * </ul>
 *
 * <p><b>Reabrir o mês não custa código.</b> A guarda da mensal (C6) pergunta ao banco quais mensais
 * estão publicadas; morta a mensal, o mês volta a aceitar publicação — sozinho.
 *
 * <p><b>Concorrência.</b> Excluir toma o MESMO lock de linha do lote que publicar e vincular
 * ({@code lockPorId} — F49/F58), inclusive quando o alvo é uma única página: os três caminhos
 * serializam, e nenhum roda sobre um lote que o outro está mudando.
 *
 * <p><b>A ordem das deleções não é estética</b> — cada passo existe por uma razão dura, e trocá-los
 * quebra o estágio: ver {@link #executar}.
 */
@Service
@RequiredArgsConstructor
public class PontoExclusaoService {

    private static final Logger log = LoggerFactory.getLogger(PontoExclusaoService.class);

    private static final String ESCOPO_LOTE = "LOTE";
    private static final String ESCOPO_PAGINA = "PAGINA";
    private static final String STATUS_PUBLICADO = "PUBLICADO";
    private static final String TIPO_MENSAL = "MENSAL";

    /** Competência (mês) que a exclusão de uma mensal publicada REABRE — o mesmo rótulo das recusas do C6. */
    private static final DateTimeFormatter COMPETENCIA = DateTimeFormatter.ofPattern("MM/yyyy");

    /** Textos da re-âncora prevista (preview) — o que o admin lê antes de confirmar. */
    private static final String REANCORA_INALTERADA = "não muda";
    private static final String REANCORA_SEM_FOLHA = "fica sem folha oficial — abertura 0";

    private final PontoLoteRepository loteRepo;
    private final PontoLotePaginaRepository paginaRepo;
    private final PontoRetificacaoRepository retificacaoRepo;
    private final PontoBancoSaldoRepository saldoRepo;
    private final PontoExclusaoLogRepository exclusaoLogRepo;
    private final AvisoCadastroRepository cadastroRepo;
    private final AvisoAlvoRepository alvoRepo;
    private final AvisoCienciaRepository cienciaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;
    private final SaldoAberturaService saldoAberturaService;
    private final ObjectMapper objectMapper;

    /** O ÚNICO admin que exclui — mesma propriedade que autoriza criar/editar administradores. */
    @Value("${app.admin.master-username}")
    private String masterUsername;

    @Value("${app.files.dir}")
    private String filesDir;

    // ══════════════════════════════════════════════════════════════
    // Permissão
    // ══════════════════════════════════════════════════════════════

    /**
     * O usuário é o master? Alimenta a flag {@code pode_excluir} da listagem de lotes — o botão X só
     * aparece para quem pode. <b>Esconder o botão não é segurança</b>: quem chamar o endpoint mesmo
     * assim leva 403 do {@link #requireMaster}.
     */
    public boolean podeExcluir(String username) {
        return masterUsername != null && masterUsername.equalsIgnoreCase(username);
    }

    /** 403 "forbidden" para todo não-master — idioma do {@code AdminCrudService}. Nada é lido nem escrito antes. */
    private void requireMaster(String callerUsername) {
        if (!podeExcluir(callerUsername)) {
            throw new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Preview — as consequências REAIS daquele item, antes do gesto
    // ══════════════════════════════════════════════════════════════

    /** Preview da exclusão do LOTE inteiro (todas as páginas). Master-only, como a exclusão. */
    @Transactional(readOnly = true)
    public Map<String, Object> previewLote(String loteId, String callerUsername) {
        requireMaster(callerUsername);
        return preview(alvoLote(loteId, this::buscarLote));
    }

    /** Preview da exclusão de UMA folha do lote. */
    @Transactional(readOnly = true)
    public Map<String, Object> previewPagina(String loteId, String paginaId, String callerUsername) {
        requireMaster(callerUsername);
        return preview(alvoPagina(loteId, paginaId, this::buscarLote));
    }

    /**
     * O que morre se isto for excluído — contado no banco, nunca em texto fixo. É CONSULTIVO: entre
     * o preview e o DELETE o mundo pode mudar (uma retificação nova, um lote publicado); a verdade é
     * a da transação de exclusão, que refaz o levantamento sob o lock.
     */
    private Map<String, Object> preview(Alvo alvo) {
        Fatos fatos = levantar(alvo);

        List<Map<String, Object>> pessoas = new ArrayList<>();
        for (ChavePessoa p : fatos.pessoas()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pessoa_id", p.pessoaId());
            m.put("nome", nomeDaPessoa(p));
            m.put("tipo", p.pessoaTipo());
            m.put("retificacoes_excluidas", fatos.retificacoesDe(p).size());
            m.put("reancora", reancoraPrevista(p, fatos.paginaIds()));
            pessoas.add(m);
        }

        Map<String, Object> out = cabecalho(alvo);
        out.put("pessoas", pessoas);
        out.put("paginas_excluidas", alvo.paginas().size());
        out.put("retificacoes_excluidas", fatos.retificacoes().size());
        out.put("avisos_destinatarios", fatos.destinatariosDosAvisos(this::nomeDoAlvo));
        out.put("avisos_removidos", fatos.alvosDeAviso());
        out.put("reabre_competencia", reabreCompetencia(alvo));
        out.put("arquivos", fatos.arquivos().size());
        return out;
    }

    /**
     * Para onde a âncora do banco da pessoa vai depois da exclusão (texto do modal). Se a âncora atual
     * NÃO é uma das páginas que morrem, nada muda — o caso mais comum quando se exclui uma folha
     * antiga. Se é, a próxima candidata (ignorando as páginas alvo) assume; não havendo nenhuma, a
     * pessoa fica sem folha oficial.
     */
    private String reancoraPrevista(ChavePessoa pessoa, List<String> paginasAlvo) {
        String ancoraAtual = saldoRepo.findByPessoaIdAndPessoaTipo(pessoa.pessoaId(), pessoa.pessoaTipo())
                .map(PontoBancoSaldo::getAncoraPaginaId).orElse(null);
        if (ancoraAtual == null || !paginasAlvo.contains(ancoraAtual)) return REANCORA_INALTERADA;

        List<Object[]> candidatas = paginaRepo.findCandidatasAncoraExcluindo(
                pessoa.pessoaId(), pessoa.pessoaTipo(), paginasAlvo, Limit.of(1));
        if (candidatas.isEmpty()) return REANCORA_SEM_FOLHA;

        PontoLote lote = (PontoLote) candidatas.get(0)[1];
        return "volta para a folha " + ReportConfig.fmtDate(lote.getDataInicio())
                + " a " + ReportConfig.fmtDate(lote.getDataFim());
    }

    /**
     * A competência que esta exclusão REABRE, ou {@code null}. Só uma MENSAL PUBLICADA fecha o mês
     * (C6/F32) — e só enquanto tem gente vinculada. Nenhum mecanismo novo: a guarda consulta as
     * mensais publicadas, e morta a mensal o mês volta a aceitar publicação sozinho.
     */
    private String reabreCompetencia(Alvo alvo) {
        PontoLote lote = alvo.lote();
        if (!TIPO_MENSAL.equals(lote.getTipo()) || !STATUS_PUBLICADO.equals(lote.getStatus())) return null;
        boolean alguemVinculado = alvo.paginas().stream().anyMatch(p -> p.getPessoaId() != null);
        return alguemVinculado ? YearMonth.from(lote.getDataInicio()).format(COMPETENCIA) : null;
    }

    // ══════════════════════════════════════════════════════════════
    // Exclusão
    // ══════════════════════════════════════════════════════════════

    /** Exclui o LOTE inteiro: páginas, retificações, avisos daquela publicação, arquivos e a âncora. */
    @Transactional
    public Map<String, Object> excluirLote(String loteId, String callerUsername, String callerId) {
        requireMaster(callerUsername);
        return executar(alvoLote(loteId, this::buscarLoteComLock), callerId);
    }

    /**
     * Exclui UMA folha do lote. O lote SOBREVIVE, mesmo ficando sem nenhuma página — quem o apaga é o
     * master, com o X dele. {@code TOTAL_PAGINAS} é metadado do upload (quantas páginas o PDF tinha) e
     * não é reescrito.
     */
    @Transactional
    public Map<String, Object> excluirPagina(String loteId, String paginaId, String callerUsername, String callerId) {
        requireMaster(callerUsername);
        return executar(alvoPagina(loteId, paginaId, this::buscarLoteComLock), callerId);
    }

    /**
     * A exclusão, numa transação só. <b>A ordem é o estágio</b>:
     *
     * <ol>
     *   <li>o lote já veio TRAVADO ({@code lockPorId}, no {@code alvo*}) — publicar e vincular esperam;</li>
     *   <li>levantar os fatos (páginas alvo, pessoas, retificações por página, avisos pela ORIGEM);</li>
     *   <li>retificações PRIMEIRO, e com flush: a {@code FK_PNT_RETIF_PAGINA} não tem cascade, então
     *       apagar a página antes morre em ORA-02292;</li>
     *   <li>avisos, pela proveniência — nunca por autor/tipo/texto;</li>
     *   <li>páginas (e o lote, se o escopo for o lote);</li>
     *   <li><b>flush</b> e SÓ ENTÃO a re-âncora — o recompute NÃO pode enxergar a página que acabou de
     *       morrer, ou o saldo volta a ancorar exatamente nela;</li>
     *   <li>a trilha de auditoria, com as contagens REAIS do que morreu (não as do preview);</li>
     *   <li>os arquivos, DEPOIS do commit (nunca dentro da transação: um rollback não os traz de volta).</li>
     * </ol>
     */
    private Map<String, Object> executar(Alvo alvo, String callerId) {
        Fatos fatos = levantar(alvo);                                          // 2

        retificacaoRepo.deleteAll(fatos.retificacoes());                       // 3
        retificacaoRepo.flush();                                               //   ⚠️ antes das páginas

        AvisosRemovidos avisos = removerAvisos(alvo, fatos);                   // 4

        paginaRepo.deleteAll(alvo.paginas());                                  // 5
        if (alvo.escopoLote()) loteRepo.delete(alvo.lote());

        // 6 — as deleções TÊM de estar no banco antes da re-âncora: o recompute procura a folha
        // publicada mais recente da pessoa, e uma página morta ainda visível seria escolhida de novo.
        //
        // ⚠️ Honestidade sobre este flush (medido por mutação): removê-lo NÃO reproduz o defeito hoje —
        // `findCandidatasAncora` é JPQL, o Hibernate está em FlushMode.AUTO e faz autoflush das deleções
        // pendentes antes do SELECT. Ele fica porque (a) a proteção passa a ser explícita em vez de
        // acidental e (b) no dia em que essa query virar SQL NATIVO o autoflush não acontece — e o
        // defeito voltaria calado, com o saldo ancorado numa folha que já não existe.
        paginaRepo.flush();
        reancorarEmOrdem(fatos.pessoas());

        Map<String, Object> resumo = resumo(alvo, fatos, avisos);              // 7
        gravarTrilha(alvo, callerId, resumo);

        apagarArquivosAposCommit(fatos.arquivos());                            // 8
        log.info("Exclusão de ponto [{}] lote={} por {}: {} página(s), {} retificação(ões), {} aviso(s)",
                alvo.escopo(), alvo.lote().getId(), callerId, alvo.paginas().size(),
                fatos.retificacoes().size(), avisos.alvos());
        return resumo;
    }

    /**
     * Re-ancora as pessoas afetadas na {@link SaldoAberturaService#ORDEM_DO_LOCK} (F60) — a MESMA
     * ordem da publicação e do backfill. Sem uma ordem comum, uma exclusão e uma publicação com as
     * mesmas pessoas travariam as linhas de saldo em sentidos opostos e morreriam em ORA-00060.
     *
     * <p>Uma chamada por pessoa é tudo o que a re-âncora exige: o {@code reancorar} é o recompute
     * genérico (acha sozinho a folha publicada mais recente que ainda existe) — e as páginas mortas
     * já saíram do banco, no flush acima.
     */
    private void reancorarEmOrdem(Collection<ChavePessoa> pessoas) {
        pessoas.stream().sorted(SaldoAberturaService.ORDEM_DO_LOCK)
                .forEach(p -> saldoAberturaService.reancorar(p.pessoaId(), p.pessoaTipo()));
    }

    /**
     * Apaga os avisos que ESTA publicação criou (e só eles).
     *
     * <p>Escopo LOTE: os cadastros com a ORIGEM morrem, e o {@code ON DELETE CASCADE} de
     * FRM_AVISO_MENSAGEM/ALVO/CIENCIA limpa os filhos.
     *
     * <p>Escopo PÁGINA: só o ALVO daquela pessoa (e a CIÊNCIA dela) sai — o aviso continua vivo para
     * os demais destinatários do mesmo cadastro. Um cadastro que fique com ZERO alvos morre junto:
     * seria um aviso ativo sem ninguém para vê-lo.
     */
    private AvisosRemovidos removerAvisos(Alvo alvo, Fatos fatos) {
        int cadastros = 0;
        int alvos = 0;
        for (AvisoAfetado afetado : fatos.avisos()) {
            if (!alvo.escopoLote()) {
                alvoRepo.deleteAll(afetado.mortos());
                cienciaDaPessoa(afetado.cadastro().getId(), alvo.pessoaDaPagina()).ifPresent(cienciaRepo::delete);
            }
            alvos += afetado.mortos().size();
            if (afetado.cadastroFicaSemAlvos()) {
                cadastroRepo.delete(afetado.cadastro());   // cascade leva mensagem/alvo/ciência
                cadastros++;
            }
        }
        return new AvisosRemovidos(cadastros, alvos);
    }

    /** A ciência daquela pessoa naquele cadastro (avisos PESSOAL não têm sala — a chave é cadastro+pessoa). */
    private Optional<AvisoCiencia> cienciaDaPessoa(String cadastroId, ChavePessoa pessoa) {
        PapelPessoa papel = pessoa == null ? null : PapelPessoa.dePessoaTipo(pessoa.pessoaTipo());
        if (papel == null) return Optional.empty();
        return switch (papel) {
            case OPERADOR -> cienciaRepo.findByCadastroIdAndOperadorId(cadastroId, pessoa.pessoaId());
            case TECNICO -> cienciaRepo.findByCadastroIdAndTecnicoId(cadastroId, pessoa.pessoaId());
            case ADMIN -> cienciaRepo.findByCadastroIdAndAdminId(cadastroId, pessoa.pessoaId());
        };
    }

    // ══════════════════════════════════════════════════════════════
    // Trilha de auditoria (PNT_EXCLUSAO_LOG)
    // ══════════════════════════════════════════════════════════════

    /**
     * O snapshot do que morreu — e é snapshot porque as linhas referenciadas já não existem para
     * quem for ler a trilha depois. Traz as contagens REAIS da transação (não as do preview) e a
     * âncora RESULTANTE de cada pessoa, já recalculada.
     */
    private Map<String, Object> resumo(Alvo alvo, Fatos fatos, AvisosRemovidos avisos) {
        List<Map<String, Object>> pessoas = new ArrayList<>();
        for (ChavePessoa p : fatos.pessoas()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pessoa_id", p.pessoaId());
            m.put("nome", nomeDaPessoa(p));
            m.put("tipo", p.pessoaTipo());
            m.put("retificacoes_excluidas", fatos.retificacoesDe(p).size());
            m.put("reancora", ancoraResultante(p));
            pessoas.add(m);
        }

        Map<String, Object> out = cabecalho(alvo);
        out.put("pessoas", pessoas);
        out.put("paginas_excluidas", alvo.paginas().size());
        out.put("retificacoes_excluidas", fatos.retificacoes().size());
        out.put("avisos_destinatarios", fatos.destinatariosDosAvisos(this::nomeDoAlvo));
        out.put("avisos_removidos", avisos.alvos());
        out.put("avisos_cadastros_removidos", avisos.cadastros());
        out.put("reabre_competencia", reabreCompetencia(alvo));
        out.put("arquivos", fatos.arquivos().size());
        return out;
    }

    /** Onde o saldo da pessoa ficou ancorado DEPOIS da re-âncora — a folha real, ou "sem folha oficial". */
    private String ancoraResultante(ChavePessoa pessoa) {
        LocalDate ancora = saldoRepo.findByPessoaIdAndPessoaTipo(pessoa.pessoaId(), pessoa.pessoaTipo())
                .map(PontoBancoSaldo::getAncoraData).orElse(null);
        return ancora != null ? "ancorado em " + ReportConfig.fmtDate(ancora) : REANCORA_SEM_FOLHA;
    }

    private void gravarTrilha(Alvo alvo, String callerId, Map<String, Object> resumo) {
        PontoExclusaoLog trilha = new PontoExclusaoLog();
        trilha.setEscopo(alvo.escopo());
        trilha.setLoteId(alvo.lote().getId());
        trilha.setPaginaId(alvo.escopoLote() ? null : alvo.paginas().get(0).getId());
        trilha.setExcluidoPorId(callerId);
        trilha.setExcluidoEm(LocalDateTime.now());   // idioma da aplicação (nunca SYSTIMESTAMP — C17)
        trilha.setResumo(json(resumo));
        exclusaoLogRepo.save(trilha);
    }

    /**
     * Falhar aqui derruba a transação inteira, de propósito: uma exclusão sem trilha é exatamente o
     * estado que este estágio existe para nunca mais produzir. (Na prática o Map só tem tipos simples
     * e o Jackson não tem como falhar.)
     */
    private String json(Map<String, Object> resumo) {
        try {
            return objectMapper.writeValueAsString(resumo);
        } catch (JsonProcessingException e) {
            throw new ServiceValidationException("Não foi possível registrar a trilha da exclusão.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Arquivos — best-effort, DEPOIS do commit
    // ══════════════════════════════════════════════════════════════

    /**
     * Um arquivo apagado não volta com o rollback: se a deleção fosse dentro da transação e o commit
     * falhasse, o banco continuaria apontando para PDFs que já não existem. Depois do commit, o pior
     * caso é o inverso — um arquivo órfão em disco, que não quebra nada e sai num WARN.
     */
    private void apagarArquivosAposCommit(List<String> relPaths) {
        if (relPaths.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            relPaths.forEach(this::apagarArquivo);   // sem transação (teste unitário): apaga agora
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relPaths.forEach(PontoExclusaoService.this::apagarArquivo);
            }
        });
    }

    private void apagarArquivo(String relPath) {
        try {
            Files.deleteIfExists(Paths.get(filesDir).resolve(relPath));
        } catch (IOException | RuntimeException e) {
            log.warn("Falha ao apagar o arquivo de ponto {} depois da exclusão: {} — o banco já commitou; "
                    + "o arquivo fica órfão em disco.", relPath, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Levantamento dos fatos (o mesmo para o preview e para a exclusão)
    // ══════════════════════════════════════════════════════════════

    /**
     * Tudo que morre com este alvo. É o passo 2 da exclusão e o corpo do preview — um só levantamento,
     * para que o modal não possa prometer uma coisa e a transação fazer outra.
     */
    private Fatos levantar(Alvo alvo) {
        List<String> paginaIds = alvo.paginas().stream().map(PontoLotePagina::getId).toList();
        List<PontoRetificacao> retificacoes = paginaIds.isEmpty()
                ? List.of()
                : retificacaoRepo.findByPaginaIdIn(paginaIds);

        Set<ChavePessoa> pessoas = new LinkedHashSet<>();
        for (PontoLotePagina p : alvo.paginas()) {
            if (p.getPessoaId() != null) pessoas.add(new ChavePessoa(p.getPessoaId(), p.getPessoaTipo()));
        }

        List<String> arquivos = new ArrayList<>();
        for (PontoLotePagina p : alvo.paginas()) {
            if (p.getArquivoPagina() != null) arquivos.add(p.getArquivoPagina());
        }
        // O PDF original é do LOTE: só sai quando o lote inteiro sai.
        if (alvo.escopoLote() && alvo.lote().getArquivoOriginal() != null) {
            arquivos.add(alvo.lote().getArquivoOriginal());
        }

        return new Fatos(paginaIds, retificacoes, List.copyOf(pessoas), avisosAfetados(alvo), arquivos);
    }

    /**
     * Os avisos daquela publicação e QUAIS alvos deles morrem — a ORIGEM é a única chave.
     * Escopo LOTE: todos os alvos. Escopo PÁGINA: só os da pessoa daquela folha (o cadastro segue
     * vivo para os demais).
     *
     * <p>Página PENDENTE (sem pessoa) não avisou ninguém: nenhum alvo morre. É por isso que "sem
     * pessoa" e "escopo lote" não podem colapsar no mesmo {@code null} — seriam os dois extremos.
     */
    private List<AvisoAfetado> avisosAfetados(Alvo alvo) {
        ChavePessoa pessoa = alvo.pessoaDaPagina();
        if (!alvo.escopoLote() && pessoa == null) return List.of();

        List<AvisoAfetado> out = new ArrayList<>();
        for (AvisoCadastro cad : cadastroRepo.findByOrigemLoteId(alvo.lote().getId())) {
            List<AvisoAlvo> todos = alvoRepo.findByCadastroId(cad.getId());
            List<AvisoAlvo> mortos = pessoa == null
                    ? todos
                    : todos.stream().filter(a -> alvoEhDa(a, pessoa)).toList();
            if (!mortos.isEmpty()) out.add(new AvisoAfetado(cad, todos.size(), mortos));
        }
        return out;
    }

    /** O alvo do aviso endereça esta pessoa? (ALVO_TIPO + a coluna do papel — ADMINISTRADOR ↔ ADMIN.) */
    private static boolean alvoEhDa(AvisoAlvo alvo, ChavePessoa pessoa) {
        PapelPessoa papel = PapelPessoa.dePessoaTipo(pessoa.pessoaTipo());
        if (papel == null) return false;
        return switch (papel) {
            case OPERADOR -> alvo.getAlvoTipo() == AlvoTipoAviso.OPERADOR
                    && pessoa.pessoaId().equals(alvo.getOperadorId());
            case TECNICO -> alvo.getAlvoTipo() == AlvoTipoAviso.TECNICO
                    && pessoa.pessoaId().equals(alvo.getTecnicoId());
            case ADMIN -> alvo.getAlvoTipo() == AlvoTipoAviso.ADMIN
                    && pessoa.pessoaId().equals(alvo.getAdminId());
        };
    }

    // ══════════════════════════════════════════════════════════════
    // Alvo, fatos e helpers
    // ══════════════════════════════════════════════════════════════

    /** O que a exclusão mira: o lote (com todas as páginas) ou UMA página dele. */
    private record Alvo(PontoLote lote, List<PontoLotePagina> paginas, boolean escopoLote) {

        String escopo() {
            return escopoLote ? ESCOPO_LOTE : ESCOPO_PAGINA;
        }

        /** A pessoa da folha alvo — só faz sentido no escopo PÁGINA; null também se ela estava PENDENTE. */
        ChavePessoa pessoaDaPagina() {
            if (escopoLote) return null;
            PontoLotePagina pg = paginas.get(0);
            return pg.getPessoaId() == null ? null : new ChavePessoa(pg.getPessoaId(), pg.getPessoaTipo());
        }
    }

    /** O que morre com o alvo, levantado no banco uma única vez. */
    private record Fatos(List<String> paginaIds,
                         List<PontoRetificacao> retificacoes,
                         List<ChavePessoa> pessoas,
                         List<AvisoAfetado> avisos,
                         List<String> arquivos) {

        List<PontoRetificacao> retificacoesDe(ChavePessoa pessoa) {
            return retificacoes.stream()
                    .filter(r -> pessoa.pessoaId().equals(r.getPessoaId())
                            && pessoa.pessoaTipo().equals(r.getPessoaTipo()))
                    .toList();
        }

        int alvosDeAviso() {
            return avisos.stream().mapToInt(a -> a.mortos().size()).sum();
        }

        List<String> destinatariosDosAvisos(Function<AvisoAlvo, String> nome) {
            // F30: ordenação pt-BR (o .sorted() cru punha maiúsculas antes de minúsculas e os
            // acentuados no fim).
            return avisos.stream().flatMap(a -> a.mortos().stream()).map(nome)
                    .sorted(NativeQueryUtils.ORDEM_TEXTO_PT_BR).toList();
        }
    }

    /** Um cadastro de aviso daquela publicação, com os alvos que morrem nesta exclusão. */
    private record AvisoAfetado(AvisoCadastro cadastro, int totalDeAlvos, List<AvisoAlvo> mortos) {

        /** Cadastro sem nenhum destinatário restante é aviso invisível — morre junto. */
        boolean cadastroFicaSemAlvos() {
            return mortos.size() >= totalDeAlvos;
        }
    }

    private record AvisosRemovidos(int cadastros, int alvos) {}

    private Alvo alvoLote(String loteId, Function<String, PontoLote> carregarLote) {
        PontoLote lote = carregarLote.apply(loteId);
        return new Alvo(lote, paginaRepo.findByLoteIdOrderByNumeroPagina(loteId), true);
    }

    private Alvo alvoPagina(String loteId, String paginaId, Function<String, PontoLote> carregarLote) {
        PontoLote lote = carregarLote.apply(loteId);   // o lock é do LOTE mesmo quando o alvo é a página
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Página não encontrada.", HttpStatus.NOT_FOUND));
        if (!pg.getLoteId().equals(loteId)) {
            throw new ServiceValidationException("Página não pertence ao lote informado.");
        }
        return new Alvo(lote, List.of(pg), false);
    }

    private PontoLote buscarLote(String loteId) {
        return loteRepo.findById(loteId)
                .orElseThrow(() -> new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND));
    }

    /**
     * O MESMO {@code SELECT ... FOR UPDATE} da publicação e do vínculo (F49/F58): excluir serializa
     * com os dois. Publicar durante a espera não confunde a exclusão — ela relê o lote já publicado e
     * segue, agora profunda sobre o que a publicação criou.
     */
    private PontoLote buscarLoteComLock(String loteId) {
        return loteRepo.lockPorId(loteId)
                .orElseThrow(() -> new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND));
    }

    private Map<String, Object> cabecalho(Alvo alvo) {
        PontoLote l = alvo.lote();
        Map<String, Object> lote = new LinkedHashMap<>();
        lote.put("id", l.getId());
        lote.put("tipo", l.getTipo());
        lote.put("data_inicio", l.getDataInicio().toString());
        lote.put("data_fim", l.getDataFim().toString());
        lote.put("status", l.getStatus());
        lote.put("publicado", STATUS_PUBLICADO.equals(l.getStatus()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("escopo", alvo.escopo());
        out.put("lote", lote);
        out.put("pagina", alvo.escopoLote() ? null : paginaResumo(alvo.paginas().get(0)));
        return out;
    }

    private Map<String, Object> paginaResumo(PontoLotePagina pg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pg.getId());
        m.put("numero_pagina", pg.getNumeroPagina());
        m.put("pessoa_nome", pg.getPessoaId() == null
                ? null
                : nomeDaPessoa(new ChavePessoa(pg.getPessoaId(), pg.getPessoaTipo())));
        return m;
    }

    /** Nome de exibição da pessoa polimórfica; o id cru como último recurso (nunca deixa o modal mudo). */
    private String nomeDaPessoa(ChavePessoa pessoa) {
        PapelPessoa papel = PapelPessoa.dePessoaTipo(pessoa.pessoaTipo());
        if (papel == null) return pessoa.pessoaId();
        return switch (papel) {
            case OPERADOR -> operadorRepo.findById(pessoa.pessoaId())
                    .map(o -> o.getNomeCompleto()).orElse(pessoa.pessoaId());
            case TECNICO -> tecnicoRepo.findById(pessoa.pessoaId())
                    .map(t -> t.getNomeCompleto()).orElse(pessoa.pessoaId());
            case ADMIN -> administradorRepo.findById(pessoa.pessoaId())
                    .map(a -> a.getNomeCompleto()).orElse(pessoa.pessoaId());
        };
    }

    /** Nome do destinatário de um alvo de aviso (a coluna preenchida diz o papel). */
    private String nomeDoAlvo(AvisoAlvo alvo) {
        if (alvo.getOperadorId() != null) return nomeDaPessoa(new ChavePessoa(alvo.getOperadorId(), "OPERADOR"));
        if (alvo.getTecnicoId() != null) return nomeDaPessoa(new ChavePessoa(alvo.getTecnicoId(), "TECNICO"));
        if (alvo.getAdminId() != null) return nomeDaPessoa(new ChavePessoa(alvo.getAdminId(), "ADMINISTRADOR"));
        return String.valueOf(alvo.getAlvoTipo());   // coletivo (TODOS_*): a publicação não os cria
    }
}
