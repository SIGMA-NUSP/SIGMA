package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static br.leg.senado.nusp.service.NativeQueryUtils.clean;

/**
 * Retificação de folha de ponto publicada — a planilha que o funcionário preenchia à mão, agora
 * dentro do sistema.
 *
 * <p><b>A célula é a unidade.</b> Cada gravação mexe em UM campo de UM dia: o horário digitado
 * entra sozinho e os outros três continuam valendo o que a folha oficial trouxe. Não há envio em
 * lote, não há par obrigatório e não há confirmação — quem quiser trocar de novo, troca; quem
 * quiser desistir, apaga.
 *
 * <p><b>Ou horários, ou uma ocorrência.</b> No lugar dos horários, o dia pode receber um tipo do
 * catálogo que o funcionário declara ("Banco de horas"): declarar limpa os horários, digitar um
 * horário desfaz a declaração. As duas coisas na mesma célula seriam duas verdades para o mesmo
 * dia.
 *
 * <p><b>Quando o dia aceita.</b> Não há prazo em dias corridos: o que abre e fecha cada dia é a
 * publicação das folhas ({@link JanelaRetificacao}). Fora dela, ficam de fora sábado e domingo e o
 * dia que o administrador marcou para todos — a marcação individual, não: sobre ela o funcionário
 * ainda escreve.
 *
 * <p>1 retificação por (pessoa, dia) — a UK do banco é a autoridade final —, e o dono é sempre
 * quem retifica: administrador não retifica folha alheia.
 */
@Service
@RequiredArgsConstructor
public class RetificacaoService {

    private static final String STATUS_PUBLICADO = "PUBLICADO";
    /** UK (PESSOA_ID, PESSOA_TIPO, DATA) — a ÚNICA violação que é corrida de gravação. */
    private static final String UK_PESSOA_DIA = "UK_PNT_RETIF_PESSOA_DIA";
    /** As células que o funcionário edita, na ordem impressa na folha. */
    private static final List<String> CAMPOS = List.of("ent1", "sai1", "ent2", "sai2");

    private final PontoLotePaginaRepository paginaRepo;
    private final PontoLoteRepository loteRepo;
    private final PontoRetificacaoRepository retificacaoRepo;
    private final PontoDiaMarcacaoRepository diaMarcacaoRepo;
    private final PontoTipoMarcacaoRepository tipoRepo;
    private final PontoFolhaLinhaRepository folhaLinhaRepo;

    // ══════════════════════════════════════════════════════════════
    // Leitura
    // ══════════════════════════════════════════════════════════════

    /**
     * O que a tela precisa para desenhar a folha: quais dias aceitam edição, o que já foi
     * retificado e os tipos que o funcionário pode declarar.
     *
     * <p>Os dias cobrem o período da folha consultada, e o {@code aberto} de cada um responde só
     * pela janela de publicação — sábado, domingo e a marcação global chegam separados, porque a
     * tela os desenha de outro jeito (dia bloqueado que exibe o nome da ocorrência).
     *
     * <p>A chave de LEITURA das retificações é a mesma da gravação — pessoa+tipo+dia —, recortada
     * pelo período da folha consultada e NÃO pela página que ancorou cada uma: duas folhas
     * publicadas da mesma pessoa podem cobrir o mesmo dia (as semanais são cumulativas), e o dia
     * retificado tem de aparecer retificado nas duas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarRetificacoes(String paginaId, String solicitanteId) {
        FolhaAlvo fa = folhaDoDono(paginaId, solicitanteId, false);
        PontoLote lote = fa.lote();
        String pessoaTipo = fa.pagina().getPessoaTipo();
        Map<String, PontoTipoMarcacao> catalogo = catalogoPorId();

        Map<LocalDate, String> globais = marcacoesGlobais(lote.getDataInicio(), lote.getDataFim(), catalogo);
        List<Map<String, Object>> dias = new ArrayList<>();
        for (LocalDate d = lote.getDataInicio(); !d.isAfter(lote.getDataFim()); d = d.plusDays(1)) {
            Map<String, Object> dia = new LinkedHashMap<>();
            dia.put("data", d.toString());
            dia.put("aberto", fa.janela().aberto(d));
            dia.put("marcacao_global", globais.get(d));
            dias.add(dia);
        }

        List<Map<String, Object>> retificacoes = new ArrayList<>();
        for (PontoRetificacao r : retificacaoRepo.findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                solicitanteId, pessoaTipo, lote.getDataInicio(), lote.getDataFim())) {
            retificacoes.add(retificacaoMap(r, catalogo.get(r.getTipoId())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dias", dias);
        out.put("retificacoes", retificacoes);
        out.put("tipos", tiposDoFuncionario(catalogo));
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // Gravação — uma célula, um dia, por vez
    // ══════════════════════════════════════════════════════════════

    /**
     * Grava UM horário de um dia: {@code {"data", "campo", "valor"}}, com {@code campo} em
     * {@code ent1|sai1|ent2|sai2} e {@code valor} em HH:MM.
     *
     * <p>Os demais horários já retificados do dia permanecem; a declaração de ocorrência, se
     * havia, sai — o dia passa a ser de horários.
     *
     * <p>A ordem do dia é conferida sobre o par EFETIVO: o que está sendo gravado, o que a pessoa
     * já havia retificado e, no que sobrar, o que a folha imprimiu.
     */
    @Transactional
    public Map<String, Object> salvarCelula(String paginaId, String solicitanteId, Map<String, Object> body) {
        Map<String, Object> item = body == null ? Map.of() : body;
        FolhaAlvo fa = folhaDoDono(paginaId, solicitanteId, true);
        LocalDate data = parseData(clean(item, "data"));
        String campo = clean(item, "campo").toLowerCase(Locale.ROOT);
        if (!CAMPOS.contains(campo)) {
            throw new ServiceValidationException("Célula inválida.");
        }
        String valor = hora(clean(item, "valor"), data);
        exigirDiaEditavel(fa, data);

        PontoRetificacao r = daFolhaAtual(fa, paginaId, solicitanteId, data);
        r.setTipoId(null);
        aplicar(r, campo, valor);
        exigirOrdem(r, fa, data);

        return gravar(r, null);
    }

    /**
     * Declara uma ocorrência do catálogo para o dia inteiro: {@code {"data", "tipo_id"}}. Os quatro
     * horários do dia são zerados — a célula passa a ser o texto do tipo.
     *
     * <p>Só entra tipo marcado como visível ao funcionário; o resto do catálogo é do
     * administrador, e pedi-lo por id não o torna disponível.
     */
    @Transactional
    public Map<String, Object> salvarTipo(String paginaId, String solicitanteId, Map<String, Object> body) {
        Map<String, Object> item = body == null ? Map.of() : body;
        FolhaAlvo fa = folhaDoDono(paginaId, solicitanteId, true);
        LocalDate data = parseData(clean(item, "data"));
        PontoTipoMarcacao tipo = tipoRepo.findById(clean(item, "tipo_id"))
                .filter(t -> Boolean.TRUE.equals(t.getVisivelFuncionario()))
                .orElseThrow(() -> new ServiceValidationException("Tipo de ocorrência não disponível."));
        exigirDiaEditavel(fa, data);

        PontoRetificacao r = daFolhaAtual(fa, paginaId, solicitanteId, data);
        r.setEnt1(null);
        r.setSai1(null);
        r.setEnt2(null);
        r.setSai2(null);
        r.setTipoId(tipo.getId());

        return gravar(r, tipo);
    }

    /**
     * Apaga a retificação do dia — o equivalente a esvaziar a célula da planilha: o dia volta a
     * valer o que a folha oficial trouxe, sem deixar registro de que houve correção.
     *
     * <p>Apagar cobra menos que escrever: basta a janela estar aberta. O que impede de ESCREVER
     * num dia — o fim de semana, a ocorrência que o administrador marcou para todos — pode ter
     * chegado depois da correção, e desfazer o que se escreveu antes não pode ficar refém disso.
     */
    @Transactional
    public Map<String, Object> limpar(String paginaId, String solicitanteId, String dataTexto) {
        FolhaAlvo fa = folhaDoDono(paginaId, solicitanteId, true);
        LocalDate data = parseData(dataTexto == null ? "" : dataTexto.strip());
        exigirJanelaAberta(fa, data);

        PontoRetificacao r = retificacaoDoDia(solicitanteId, fa.pagina().getPessoaTipo(), data)
                .orElseThrow(() -> new ServiceValidationException("Retificação não encontrada.",
                        HttpStatus.NOT_FOUND));
        retificacaoRepo.delete(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data.toString());
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // Validação do dia
    // ══════════════════════════════════════════════════════════════

    /**
     * O dia aceita receber conteúdo? Além da janela: o dia é útil e não tem ocorrência marcada para
     * todos.
     *
     * <p>A marcação INDIVIDUAL não entra: ela é do administrador sobre aquela pessoa, e a
     * retificação vence a marcação na precedência da grade.
     */
    private void exigirDiaEditavel(FolhaAlvo fa, LocalDate data) {
        exigirJanelaAberta(fa, data);
        DayOfWeek diaSemana = data.getDayOfWeek();
        if (diaSemana == DayOfWeek.SATURDAY || diaSemana == DayOfWeek.SUNDAY) {
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " não é dia útil.");
        }
        String marcacao = marcacaoGlobal(data);
        if (marcacao != null) {
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " está marcado como " + marcacao + ".");
        }
    }

    /**
     * O dia ainda está na janela de retificação, e pertence à folha aberta. É o mínimo exigido de
     * qualquer mexida — criar, alterar ou apagar.
     */
    private void exigirJanelaAberta(FolhaAlvo fa, LocalDate data) {
        PontoLote lote = fa.lote();
        if (data.isBefore(lote.getDataInicio()) || data.isAfter(lote.getDataFim())) {
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " está fora do período da folha.");
        }
        JanelaRetificacao.Motivo motivo = fa.janela().motivo(data);
        if (motivo == null) return;
        if (motivo == JanelaRetificacao.Motivo.DEFINITIVA_PUBLICADA) {
            throw new ServiceValidationException("Não é possível retificar. Folha definitiva já publicada.");
        }
        throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                + " não pode mais ser retificado.");
    }

    /**
     * Os horários do dia, do jeito que ficam depois desta gravação, precisam avançar no relógio.
     * O que a pessoa não retificou entra na conferência com o valor da folha que representa o dia
     * — a última publicada que o cobre, que nem sempre é a folha aberta na tela.
     */
    private void exigirOrdem(PontoRetificacao r, FolhaAlvo fa, LocalDate data) {
        HorariosDoDia efetivo = HorariosDoDia.daRetificacao(r).sobre(horariosDaFolha(fa, data));
        if (!efetivo.emOrdem()) {
            throw new ServiceValidationException("Os horários do dia " + ReportConfig.fmtDate(data)
                    + " estão fora de ordem.");
        }
    }

    /** As células de horário que a folha imprimiu naquele dia; vazio se ela não trouxe o dia. */
    private HorariosDoDia horariosDaFolha(FolhaAlvo fa, LocalDate data) {
        List<String> paginas = fa.janela().paginasDoDia(data);
        if (paginas.isEmpty()) return HorariosDoDia.VAZIO;
        for (Object[] linha : folhaLinhaRepo.findCelulasDasFolhas(paginas)) {
            if (data.equals(linha[2])) {
                return HorariosDoDia.daFolha((String) linha[4], (String) linha[5],
                        (String) linha[6], (String) linha[7]);
            }
        }
        return HorariosDoDia.VAZIO;
    }

    /** O nome da ocorrência que vale para todos naquele dia, ou {@code null} se o dia é livre. */
    private String marcacaoGlobal(LocalDate data) {
        Optional<PontoDiaMarcacao> marcacao = diaMarcacaoRepo.findByData(data);
        if (marcacao.isEmpty()) return null;
        return tipoRepo.findById(marcacao.get().getTipoId())
                .map(PontoTipoMarcacao::getNome)
                .orElse(null);
    }

    private Map<LocalDate, String> marcacoesGlobais(LocalDate inicio, LocalDate fim,
                                                    Map<String, PontoTipoMarcacao> catalogo) {
        Map<LocalDate, String> out = new HashMap<>();
        for (PontoDiaMarcacao m : diaMarcacaoRepo
                .findByDataGreaterThanEqualAndDataLessThanOrderByData(inicio, fim.plusDays(1))) {
            PontoTipoMarcacao tipo = catalogo.get(m.getTipoId());
            if (tipo != null) out.put(m.getData(), tipo.getNome());
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════

    private Optional<PontoRetificacao> retificacaoDoDia(String pessoaId, String pessoaTipo, LocalDate data) {
        return retificacaoRepo.findByPessoaIdAndPessoaTipoAndData(pessoaId, pessoaTipo, data);
    }

    /**
     * A retificação do dia pronta para receber o que se está gravando — a que já existia ou uma
     * nova.
     *
     * <p>A página é a PROVENIÊNCIA e não muda: fica a da folha que viu a correção nascer, ainda que
     * ela seja refeita por outra folha que cubra o mesmo dia (as semanais são cumulativas). É por
     * essa origem que a exclusão de uma publicação sabe o que apagar junto — e é ela que o preview
     * da exclusão conta antes de perguntar.
     */
    private PontoRetificacao daFolhaAtual(FolhaAlvo fa, String paginaId, String pessoaId, LocalDate data) {
        String pessoaTipo = fa.pagina().getPessoaTipo();
        return retificacaoDoDia(pessoaId, pessoaTipo, data).orElseGet(() -> {
            PontoRetificacao nova = new PontoRetificacao();
            nova.setPaginaId(paginaId);
            nova.setPessoaId(pessoaId);
            nova.setPessoaTipo(pessoaTipo);
            nova.setData(data);
            return nova;
        });
    }

    private static void aplicar(PontoRetificacao r, String campo, String valor) {
        switch (campo) {
            case "ent1" -> r.setEnt1(valor);
            case "sai1" -> r.setSai1(valor);
            case "ent2" -> r.setEnt2(valor);
            default -> r.setSai2(valor);
        }
    }

    /**
     * Grava e devolve o dia como ele ficou. O flush força a UK a decidir agora: duas gravações
     * simultâneas do mesmo dia (dois dispositivos, o toque repetido) chegariam ao insert juntas, e
     * é a constraint que desempata.
     */
    private Map<String, Object> gravar(PontoRetificacao r, PontoTipoMarcacao tipo) {
        try {
            retificacaoRepo.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            if (!violouUk(e)) throw e;
            throw new ServiceValidationException("Não foi possível concluir a operação.");
        }
        return retificacaoMap(r, tipo);
    }

    /** Forma pública de uma retificação — a mesma na listagem e na resposta de cada gravação. */
    private static Map<String, Object> retificacaoMap(PontoRetificacao r, PontoTipoMarcacao tipo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("data", r.getData().toString());   // YYYY-MM-DD
        m.put("ent1", r.getEnt1());
        m.put("sai1", r.getSai1());
        m.put("ent2", r.getEnt2());
        m.put("sai2", r.getSai2());
        m.put("tipo_id", r.getTipoId());
        m.put("tipo_nome", tipo != null ? tipo.getNome() : null);
        m.put("conta_folga", tipo != null && Boolean.TRUE.equals(tipo.getContaFolga()));
        return m;
    }

    /** O catálogo inteiro por id — pequeno, e a tela precisa dele por dois motivos numa consulta só. */
    private Map<String, PontoTipoMarcacao> catalogoPorId() {
        Map<String, PontoTipoMarcacao> out = new HashMap<>();
        for (PontoTipoMarcacao t : tipoRepo.findAll()) out.put(t.getId(), t);
        return out;
    }

    /** Os tipos que o funcionário pode declarar, na ordem em que o catálogo os exibe. */
    private static List<Map<String, Object>> tiposDoFuncionario(Map<String, PontoTipoMarcacao> catalogo) {
        List<Map<String, Object>> out = new ArrayList<>();
        catalogo.values().stream()
                .filter(t -> Boolean.TRUE.equals(t.getVisivelFuncionario()))
                .sorted((a, b) -> NativeQueryUtils.ORDEM_TEXTO_PT_BR.compare(a.getNome(), b.getNome()))
                .forEach(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("nome", t.getNome());
                    out.add(m);
                });
        return out;
    }

    /**
     * Localiza a página + lote garantindo que o solicitante é o DONO da folha e que ela está
     * publicada. Difere de {@code PontoService.checarAcessoFolha} (que libera admin p/
     * preview/download): a retificação é SEMPRE do próprio dono — admin não retifica folha alheia.
     *
     * <p>As folhas publicadas da pessoa são lidas UMA vez: elas respondem tanto se esta folha ainda
     * é a folha dela quanto quais dias a janela deixa em aberto.
     *
     * <p>Quem vai GRAVAR trava a publicação antes de ler o dia: o salvamento é automático, célula a
     * célula, e duas edições do mesmo dia saem da tela quase juntas — sem a fila, as duas leriam o
     * dia ainda vazio e uma morreria na unicidade, levando junto o que o funcionário digitou. A
     * trava é a do LOTE, a mesma que publicar e excluir tomam primeiro: quem espera por quem fica
     * sempre na mesma ordem, e duas operações não se prendem uma à outra.
     */
    private FolhaAlvo folhaDoDono(String paginaId, String solicitanteId, boolean paraGravar) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Folha não encontrada.", HttpStatus.NOT_FOUND));
        if (pg.getPessoaId() == null || !pg.getPessoaId().equals(solicitanteId)) {
            throw new ServiceValidationException("Acesso negado a esta folha.", HttpStatus.FORBIDDEN);
        }
        PontoLote lote = (paraGravar ? loteRepo.lockPorId(pg.getLoteId()) : loteRepo.findById(pg.getLoteId()))
                .orElseThrow(() -> new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND));
        // A folha substituída por outra publicada depois deixou de ser a folha do dono: some da lista
        // dele e não abre mais para download — a retificação responde a mesma coisa. A regra mora no
        // FolhaSubstituida, e é uma só para os três caminhos. A folha de lote OCULTO tampouco existe
        // para o dono, e recebe a mesma resposta.
        List<Object[]> publicadas = paginaRepo.findFolhasPublicadasByPessoa(pg.getPessoaId());
        if (!STATUS_PUBLICADO.equals(lote.getStatus())
                || Boolean.TRUE.equals(lote.getOculto())
                || FolhaSubstituida.substituida(pg.getId(), publicadas)) {
            throw new ServiceValidationException("Folha indisponível.", HttpStatus.NOT_FOUND);
        }
        return new FolhaAlvo(pg, lote,
                JanelaRetificacao.daPessoa(publicadas, pg.getPessoaId(), pg.getPessoaTipo()));
    }

    private LocalDate parseData(String s) {
        if (s.isBlank()) throw new ServiceValidationException("Data obrigatória.");
        try {
            return LocalDate.parse(s);   // ISO YYYY-MM-DD
        } catch (Exception e) {
            throw new ServiceValidationException("Data inválida (use AAAA-MM-DD).");
        }
    }

    /** O horário digitado, obrigatório e em HH:MM. */
    private String hora(String s, LocalDate dia) {
        if (!HorariosDoDia.HORA.matcher(s).matches()) {
            throw new ServiceValidationException("Horário inválido no dia " + ReportConfig.fmtDate(dia) + ".");
        }
        return s;
    }

    /**
     * A violação é a da UK (PESSOA_ID, PESSOA_TIPO, DATA)? O nome da constraint viaja na cadeia de
     * causas — o {@code ORA-00001} do ojdbc o traz na mensagem ("unique constraint
     * (NUSP.UK_PNT_RETIF_PESSOA_DIA) violated") e o Hibernate o repete na
     * {@code ConstraintViolationException} que embrulha. Varrer a cadeia é o que distingue a corrida
     * de gravação de qualquer outra integridade violada (que não deve virar mensagem amigável).
     */
    private static boolean violouUk(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.toUpperCase(Locale.ROOT).contains(UK_PESSOA_DIA)) return true;
        }
        return false;
    }

    /** A folha aberta na tela e a janela de retificação da pessoa, lidas de uma vez só. */
    private record FolhaAlvo(PontoLotePagina pagina, PontoLote lote, JanelaRetificacao janela) {}
}
