package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static br.leg.senado.nusp.service.NativeQueryUtils.asItem;
import static br.leg.senado.nusp.service.NativeQueryUtils.asList;
import static br.leg.senado.nusp.service.NativeQueryUtils.blankToNull;
import static br.leg.senado.nusp.service.NativeQueryUtils.clean;
import static br.leg.senado.nusp.service.NativeQueryUtils.textoLimitado;

/**
 * Retificação de folha de ponto publicada (Bloco B-1). O dono da folha registra,
 * por dia dentro do prazo, os horários corretos (pares Ent./Saí. — ao menos um par
 * completo, 2 ou 4 horários; Q32 + F31).
 * Sem edição nem exclusão na v1 (Q1); 1 retificação por (pessoa, dia) — UK.
 * A gravação é SEMPRE em lote e transacional (F39) — ver {@link #criarRetificacoes}.
 */
@Service
@RequiredArgsConstructor
public class RetificacaoService {

    /** Prazo de retificação em dias corridos a partir da publicação (Q37). */
    private static final int PRAZO_DIAS = 5;
    private static final String STATUS_PUBLICADO = "PUBLICADO";
    /** Hora HH:MM 00:00–23:59 (regex canônico do projeto, cf. AdminCrudService). */
    private static final Pattern HORA = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    /** Teto da observação livre do dia (F33) — o mesmo do motivo de rejeição (BancoHorasService). */
    private static final int MAX_OBSERVACOES = 300;
    /** UK (PESSOA_ID, PESSOA_TIPO, DATA) do changelog 035 — a ÚNICA violação que vira "já retificado". */
    private static final String UK_PESSOA_DIA = "UK_PNT_RETIF_PESSOA_DIA";

    private final PontoLotePaginaRepository paginaRepo;
    private final PontoLoteRepository loteRepo;
    private final PontoRetificacaoRepository retificacaoRepo;

    /**
     * Último dia (INCLUSIVE) em que a folha do lote pode ser retificada (Q37):
     * data da publicação (TRUNC de PUBLICADO_EM) + 5 dias corridos. Lote ainda
     * não publicado (PUBLICADO_EM null) → {@code null}.
     */
    public LocalDate limiteRetificacao(PontoLote lote) {
        if (lote == null || lote.getPublicadoEm() == null) return null;
        return lote.getPublicadoEm().toLocalDate().plusDays(PRAZO_DIAS);
    }

    /**
     * Cria TODAS as retificações da folha numa ÚNICA transação (F39): ou o lote inteiro
     * grava, ou nada grava. Corpo: {@code {"dias": [{data, ent1, sai1, ent2, sai2, observacoes}, …]}}.
     *
     * <p>Antes eram N requisições (1 por dia), cada uma em transação própria: um dia recusado
     * no meio deixava os anteriores gravados — e, como a v1 não tem edição nem exclusão (Q1),
     * o estado parcial era DEFINITIVO, enquanto a tela dizia que nada fora salvo. Aqui a recusa
     * de um dia qualquer derruba o lote inteiro, e a mensagem NOMEIA o dia e o motivo (é o que
     * o usuário precisa para consertar e reenviar).
     *
     * <p>Validações: acesso do dono (a), uma vez para o lote; depois, por dia, em
     * {@link #criarDia} — período (b) → prazo (c) → formato/pares (d) → dia inédito (e) →
     * tamanho da observação (f).
     */
    @Transactional
    public Map<String, Object> criarRetificacoes(String paginaId, String solicitanteId, Map<String, Object> body) {
        FolhaAlvo fa = folhaDoDono(paginaId, solicitanteId);      // (a) — vale para o lote todo
        List<Object> itens = asList(body == null ? null : body.get("dias"), "dias");
        if (itens.isEmpty()) {
            throw new ServiceValidationException("Informe ao menos um dia em 'dias'.");
        }

        Set<LocalDate> vistos = new HashSet<>();
        List<Map<String, Object>> criadas = new ArrayList<>(itens.size());
        for (Object item : itens) {
            criadas.add(criarDia(paginaId, fa, solicitanteId, asItem(item, "dias"), vistos));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", criadas.size());
        out.put("retificacoes", criadas);
        return out;
    }

    /**
     * Valida e grava UM dia do lote — o ponto único por onde todo dia retificado passa, incluindo
     * as validações de CONTEÚDO (par mínimo, F31; tamanho da observação, F33). Qualquer recusa
     * lança e, como o chamador é {@code @Transactional}, leva junto os dias já gravados no mesmo
     * lote.
     *
     * <p>Toda mensagem de recusa nomeia o DIA: numa submissão de vários dias, "o motivo" sem o
     * dia não diz o que consertar.
     */
    private Map<String, Object> criarDia(String paginaId, FolhaAlvo fa, String solicitanteId,
                                         Map<String, Object> item, Set<LocalDate> vistos) {
        PontoLote lote = fa.lote();
        PontoLotePagina pg = fa.pagina();

        // (b) data dentro do período da folha (Q2) — ranges sem TRUNC (gotcha 4)
        LocalDate data = parseData(clean(item, "data"));
        if (data.isBefore(lote.getDataInicio()) || data.isAfter(lote.getDataFim())) {
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " está fora do período da folha ("
                    + ReportConfig.fmtDate(lote.getDataInicio()) + " a "
                    + ReportConfig.fmtDate(lote.getDataFim()) + ").");
        }

        // (c) prazo: hoje <= limiteRetificacao (inclusive) — Q37
        LocalDate limite = limiteRetificacao(lote);
        if (limite == null || LocalDate.now().isAfter(limite)) {
            throw new ServiceValidationException("PRAZO_EXPIRADO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Prazo de retificação encerrado"
                            + (limite != null ? " em " + ReportConfig.fmtDate(limite) : "") + "."));
        }

        // (d) formato HH:MM + ao menos um par completo, pares fechados — 2 ou 4 horários (Q32 + F31)
        String ent1 = horaOuNull(clean(item, "ent1"), data);
        String sai1 = horaOuNull(clean(item, "sai1"), data);
        String ent2 = horaOuNull(clean(item, "ent2"), data);
        String sai2 = horaOuNull(clean(item, "sai2"), data);
        validarPares(ent1, sai1, ent2, sai2, data);

        // (e) dia inédito: primeiro no próprio lote (o corpo pode repetir o dia), depois no
        // banco (UK PESSOA_ID+PESSOA_TIPO+DATA — Q1, sem edição na v1)
        if (!vistos.add(data)) {
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " aparece mais de uma vez na retificação.");
        }
        String pessoaTipo = pg.getPessoaTipo();
        if (retificacaoRepo.existsByPessoaIdAndPessoaTipoAndData(solicitanteId, pessoaTipo, data)) {
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " já foi retificado.");
        }

        PontoRetificacao r = new PontoRetificacao();
        r.setPaginaId(paginaId);
        r.setPessoaId(solicitanteId);
        r.setPessoaTipo(pessoaTipo);
        r.setData(data);
        r.setEnt1(ent1);
        r.setSai1(sai1);
        r.setEnt2(ent2);
        r.setSai2(sai2);
        r.setObservacoes(textoLimitado(blankToNull(clean(item, "observacoes")), MAX_OBSERVACOES,
                "A observação do dia " + ReportConfig.fmtDate(data)));   // (f) F33
        try {
            retificacaoRepo.saveAndFlush(r);   // flush força a UK a decidir agora
        } catch (DataIntegrityViolationException e) {
            // corrida (2 submissões do mesmo dia entre o exists e o insert): a UK é a
            // autoridade final — mapeia para o mesmo 400 amigável do check (gotcha 3).
            // SÓ a UK: qualquer outra violação de integridade sobe (F33). Dizer "já foi
            // retificado" para um erro que não é esse manda o usuário embora convencido de que
            // gravou — enquanto o prazo de 5 dias corre. Um 500 honesto é menos danoso.
            if (!violouUk(e)) throw e;
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " já foi retificado.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("data", data.toString());
        return out;
    }

    /**
     * Dias já retificados que caem no período da folha (para a UI marcar/desabilitar) + o dia-limite
     * e se o prazo já venceu. Só o dono acessa (mesma regra do criar).
     *
     * <p>A chave de LEITURA é a MESMA da gravação — pessoa+tipo+dia (a UK) —, recortada pelo período
     * do lote da folha consultada; NÃO é a página que ancorou a retificação (F32). Duas folhas
     * publicadas da mesma pessoa podem cobrir o mesmo dia (semanais 01–05, 01–12…: cumulativas por
     * decisão). Filtrando por PAGINA_ID, o dia retificado pela folha A aparecia livre na folha B; ao
     * enviar, o usuário levava 400 "O dia … já foi retificado" sem enxergar retificação alguma na
     * tela — e, sem edição nem exclusão na v1 (Q1), o dia ficava congelado sem explicação. Agora ele
     * aparece retificado nas DUAS folhas, e o front nem chega a mandá-lo.
     *
     * <p>{@code limite}/{@code prazo_expirado} continuam sendo os do lote da folha CONSULTADA: a
     * janela de 5 dias é DA FOLHA (a publicação de uma folha nova reabre a janela só através dela e
     * só para os dias ainda não retificados). PAGINA_ID segue GRAVADO na criação — é a proveniência
     * da retificação, insumo da exclusão de publicações; só a leitura desta tela deixou de usá-la.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarRetificacoes(String paginaId, String solicitanteId) {
        FolhaAlvo fa = folhaDoDono(paginaId, solicitanteId);
        PontoLote lote = fa.lote();
        LocalDate limite = limiteRetificacao(lote);

        List<Map<String, Object>> retificacoes = new ArrayList<>();
        for (PontoRetificacao r : retificacaoRepo.findByPessoaIdAndPessoaTipoAndDataBetweenOrderByData(
                solicitanteId, fa.pagina().getPessoaTipo(), lote.getDataInicio(), lote.getDataFim())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("data", r.getData().toString());   // YYYY-MM-DD
            m.put("ent1", r.getEnt1());
            m.put("sai1", r.getSai1());
            m.put("ent2", r.getEnt2());
            m.put("sai2", r.getSai2());
            m.put("observacoes", r.getObservacoes());
            retificacoes.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limite", limite != null ? limite.toString() : null);
        out.put("limite_fmt", limite != null ? ReportConfig.fmtDate(limite) : null);
        out.put("prazo_expirado", limite == null || LocalDate.now().isAfter(limite));
        out.put("retificacoes", retificacoes);
        return out;
    }

    // ══════════════════════════════════════════════════════════════

    /**
     * Localiza a página + lote garantindo que o solicitante é o DONO da folha e
     * que ela está publicada. Difere de {@code PontoService.checarAcessoFolha}
     * (que libera admin p/ preview/download): a retificação é SEMPRE do próprio
     * dono — admin não retifica folha alheia (gotcha 5). Leaf (só repos) → sem ciclo.
     */
    private FolhaAlvo folhaDoDono(String paginaId, String solicitanteId) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Folha não encontrada.", HttpStatus.NOT_FOUND));
        if (pg.getPessoaId() == null || !pg.getPessoaId().equals(solicitanteId)) {
            throw new ServiceValidationException("Acesso negado a esta folha.", HttpStatus.FORBIDDEN);
        }
        PontoLote lote = loteRepo.findById(pg.getLoteId())
                .orElseThrow(() -> new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND));
        if (!STATUS_PUBLICADO.equals(lote.getStatus())) {
            throw new ServiceValidationException("Folha indisponível.", HttpStatus.NOT_FOUND);
        }
        return new FolhaAlvo(pg, lote);
    }

    private LocalDate parseData(String s) {
        if (s.isBlank()) throw new ServiceValidationException("Data obrigatória.");
        try {
            return LocalDate.parse(s);   // ISO YYYY-MM-DD (gotcha 4)
        } catch (Exception e) {
            throw new ServiceValidationException("Data inválida (use AAAA-MM-DD).");
        }
    }

    /** Valida o formato HH:MM; string vazia → {@code null} (par ausente). */
    private String horaOuNull(String s, LocalDate dia) {
        if (s.isBlank()) return null;
        if (!HORA.matcher(s).matches()) {
            throw new ServiceValidationException("Horário inválido no dia " + ReportConfig.fmtDate(dia)
                    + ": '" + s + "'. Use o formato HH:MM.");
        }
        return s;
    }

    /**
     * Pares Ent./Saí.: ao menos UM par completo (F31) e pares fechados — 2 ou 4 horários, o par 2
     * só com o par 1 (Q32; as colunas são sequenciais, não "manhã/tarde": um dia de duas marcações
     * é sempre o par 1).
     *
     * <p><b>Zero horários deixou de ser válido (F31).</b> Antes, os 4 nulos passavam (par de nulos
     * é "completo" por vacuidade) e nasciam retificações VAZIAS: a jusante, a grade e a planilha da
     * chefia tratam "existe retificação" como "tem horários", então o dia que exibia "Banco de
     * horas"/"Férias"/"Feriado" virava célula vazia e a contagem de folgas caía 1 — sem edição nem
     * exclusão na v1 (Q1) e com a UK barrando o refazer, só o DBA desfazia. A rota escolhida foi
     * proibir o estado na entrada, e não remendar a precedência a jusante.
     *
     * <p>{@code par1Completo} exige os DOIS horários do par 1, o que já implica a antiga cláusula
     * {@code par2SemPar1} (par 2 sozinho ⇒ par 1 incompleto ⇒ recusa).
     */
    private void validarPares(String ent1, String sai1, String ent2, String sai2, LocalDate dia) {
        if (ent1 == null && sai1 == null && ent2 == null && sai2 == null) {
            throw new ServiceValidationException("Informe ao menos o par Ent. 1 / Saí. 1 no dia "
                    + ReportConfig.fmtDate(dia) + ": uma retificação sem horários não é permitida.");
        }
        boolean par1Completo = ent1 != null && sai1 != null;
        boolean par2Completo = (ent2 != null) == (sai2 != null);
        if (!par1Completo || !par2Completo) {
            throw new ServiceValidationException("Preencha os pares Ent./Saí. completos "
                    + "(2 ou 4 horários) no dia " + ReportConfig.fmtDate(dia) + ".");
        }
    }

    /**
     * A violação é a da UK (PESSOA_ID, PESSOA_TIPO, DATA)? O nome da constraint viaja na cadeia de
     * causas — o {@code ORA-00001} do ojdbc o traz na mensagem ("unique constraint
     * (NUSP.UK_PNT_RETIF_PESSOA_DIA) violated") e o Hibernate o repete na
     * {@code ConstraintViolationException} que embrulha. Varrer a cadeia é o que distingue "esse dia
     * já foi retificado" de qualquer outra integridade violada (que não deve mentir ao usuário).
     */
    private static boolean violouUk(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.toUpperCase(Locale.ROOT).contains(UK_PESSOA_DIA)) return true;
        }
        return false;
    }

    private record FolhaAlvo(PontoLotePagina pagina, PontoLote lote) {}
}
