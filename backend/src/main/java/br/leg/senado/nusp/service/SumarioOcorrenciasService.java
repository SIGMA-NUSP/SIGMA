package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sumário de ocorrências das folhas — "Ocorrências Secullum": a matriz funcionários × status
 * impressos no cartão-ponto (FERNC, Atecc, BancN, Falta…), contando DIAS num intervalo de
 * competências.
 *
 * <p>A fonte é a tabela da folha gravada na publicação, e cada mês-calendário de cada pessoa é
 * representado por UMA folha só — a que vale como versão daquele mês:
 * <ol>
 *   <li>a mensal DEFINITIVA, que fecha o mês;</li>
 *   <li>na falta dela, a mensal PRÉVIA;</li>
 *   <li>na falta das duas, a SEMANAL publicada que mais cobre o mês — as semanais são cumulativas
 *       (01–05, 01–12, 01–19…), então a de maior alcance contém as anteriores. O mês fica coberto
 *       só até onde ela vai, e isso é esperado.</li>
 * </ol>
 * Sem essa escolha, a pessoa com semanais e mensal do mesmo mês teria os dias contados duas vezes.
 *
 * <p>A disputa é feita MÊS A MÊS, e a cobertura considerada é a recortada no mês: a semanal que
 * cruza a virada (29/06 a 05/07) participa dos dois lados dela, mas com dois dias em junho ela não
 * tira do mês a semanal que foi até 28/06 — só vence julho, onde de fato alcança mais.
 *
 * <p>A seleção é feita em memória, sobre as candidatas do período; ao banco vão duas consultas —
 * as páginas publicadas que tocam a janela e os dias com ocorrência das folhas escolhidas.
 * Somente leitura.
 */
@Service
@RequiredArgsConstructor
public class SumarioOcorrenciasService {

    /**
     * Ocorrências coletivas — feriado, ponto facultativo e dispensa de ponto: valem para a equipe
     * inteira nos mesmos dias, então enchem a tabela sem distinguir ninguém — ficam fora do
     * sumário. A comparação é pela forma normalizada.
     */
    private static final Set<String> OCORRENCIAS_COLETIVAS = Set.of("feriado", "p.facul", "disposi");

    private static final String TIPO_MENSAL = "MENSAL";

    private final PontoLotePaginaRepository paginaRepo;
    private final PontoFolhaLinhaRepository folhaLinhaRepo;
    /** Nome de exibição do par polimórfico (id, tipo) — o mesmo lookup do resto do Ponto. */
    private final PessoaCadastroLookup pessoaCadastro;

    /** Uma pessoa num mês-calendário: a chave do que o sumário resolve. */
    private record ChavePessoaMes(String pessoaId, String pessoaTipo, YearMonth mes) {}

    /** A pessoa dona de uma folha. */
    private record ChavePessoa(String pessoaId, String pessoaTipo) {}

    /** Uma folha publicada candidata a representar um mês daquela pessoa. */
    private record Candidata(String loteId, String pessoaId, String pessoaTipo, String tipo, String categoria,
                             LocalDate dataInicio, LocalDate dataFim,
                             LocalDateTime publicadoEm, LocalDateTime criadoEm) {}

    /** A página vinculada, com a pessoa e o lote de onde ela veio. */
    private record Folha(ChavePessoa pessoa, String loteId) {}

    /**
     * Ordem da disputa por UM mês — vence a MAIOR. A natureza da folha decide primeiro (a definitiva
     * fecha o mês, a prévia o abre, a semanal é parcial); entre folhas de mesma natureza vence a que
     * cobre mais dias DAQUELE mês.
     *
     * <p>Empatado tudo isso, o mês é representado pela folha <b>publicada mais recentemente</b> — o
     * caso das duas definitivas do mesmo mês, que passaram a conviver quando publicar virou
     * substituir: o sumário tem de contar as ocorrências da versão que vale, não da corrigida. A
     * data do upload e o id do lote continuam depois, só para que a resposta nunca dependa da ordem
     * de chegada das linhas.
     */
    private static Comparator<Candidata> precedenciaNo(YearMonth mes) {
        return Comparator.comparingInt((Candidata c) -> forca(c, mes))
                .thenComparingLong(c -> diasCobertosNo(c, mes))
                .thenComparing(Candidata::dataFim)
                .thenComparing(Candidata::publicadoEm, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Candidata::criadoEm)
                .thenComparing(Candidata::loteId);
    }

    /**
     * Peso da natureza da folha naquele mês. A mensal só pesa como mensal no mês que ela cobre por
     * inteiro: um período atípico, que apenas encosta no mês, disputa como qualquer folha parcial —
     * não faz sentido uma "mensal" de cinco dias fechar um mês que outra folha cobre inteiro.
     */
    private static int forca(Candidata c, YearMonth mes) {
        if (!TIPO_MENSAL.equals(c.tipo()) || !cobreMesInteiro(c, mes)) return 1;
        return PontoLote.CATEGORIA_DEFINITIVA.equals(c.categoria()) ? 3 : 2;
    }

    private static boolean cobreMesInteiro(Candidata c, YearMonth mes) {
        return !c.dataInicio().isAfter(mes.atDay(1)) && !c.dataFim().isBefore(mes.atEndOfMonth());
    }

    /** Dias do mês que o período da folha alcança (a cobertura recortada no mês). */
    private static long diasCobertosNo(Candidata c, YearMonth mes) {
        LocalDate ini = c.dataInicio().isBefore(mes.atDay(1)) ? mes.atDay(1) : c.dataInicio();
        LocalDate fim = c.dataFim().isAfter(mes.atEndOfMonth()) ? mes.atEndOfMonth() : c.dataFim();
        return ini.isAfter(fim) ? 0 : ChronoUnit.DAYS.between(ini, fim) + 1;
    }

    /**
     * Sumário do intervalo de competências {@code de}–{@code ate} (ambos {@code AAAA-MM}, inclusive).
     *
     * <p>Colunas: as ocorrências realmente encontradas no período, da mais frequente para a menos
     * (o catálogo é o que as folhas trouxeram, não uma lista fixa). Linhas: os funcionários com
     * folha publicada no período — operadores, técnicos e administradores com folha —, em ordem
     * alfabética pt-BR. Célula: dias daquela ocorrência.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> sumario(String deRaw, String ateRaw) {
        YearMonth de = parseCompetencia(deRaw, "de");
        YearMonth ate = parseCompetencia(ateRaw, "ate");
        if (ate.isBefore(de)) {
            throw new ServiceValidationException("O mês final não pode ser anterior ao inicial.");
        }

        Map<String, Folha> folhaDaPagina = new LinkedHashMap<>();
        Map<ChavePessoaMes, Candidata> folhaDoMes = escolherFolhas(de, ate, folhaDaPagina);

        Map<ChavePessoa, Map<String, Contagem>> porPessoa = contarDias(de, ate, folhaDoMes, folhaDaPagina);

        return montarResposta(de, ate, pessoasComFolha(folhaDoMes), porPessoa);
    }

    // ══════════════════════════════════════════════════════════════
    // Qual folha representa cada mês de cada pessoa
    // ══════════════════════════════════════════════════════════════

    /**
     * Percorre as folhas publicadas que tocam o intervalo e guarda, para cada (pessoa, mês), a que
     * vence a precedência daquele mês. Uma folha disputa TODOS os meses que o período dela toca — a
     * semanal que cruza a virada concorre nos dois lados dela.
     *
     * <p>A disputa é entre LOTES, não entre páginas: uma pessoa pode ter mais de uma página no mesmo
     * lote (folha que ocupa duas páginas do PDF), e as duas são a mesma folha daquele mês.
     */
    private Map<ChavePessoaMes, Candidata> escolherFolhas(YearMonth de, YearMonth ate,
                                                          Map<String, Folha> folhaDaPagina) {
        Map<ChavePessoaMes, Candidata> folhaDoMes = new HashMap<>();
        for (Object[] r : paginaRepo.findPublicadasNoPeriodo(de.atDay(1), ate.atEndOfMonth())) {
            PontoLotePagina p = (PontoLotePagina) r[0];
            PontoLote l = (PontoLote) r[1];
            ChavePessoa pessoa = new ChavePessoa(p.getPessoaId(), p.getPessoaTipo());
            folhaDaPagina.put(p.getId(), new Folha(pessoa, l.getId()));
            Candidata c = new Candidata(l.getId(), p.getPessoaId(), p.getPessoaTipo(),
                    l.getTipo(), l.getCategoria(), l.getDataInicio(), l.getDataFim(),
                    l.getPublicadoEm(), l.getCriadoEm());
            for (YearMonth mes : mesesTocados(l, de, ate)) {
                Comparator<Candidata> precedencia = precedenciaNo(mes);
                folhaDoMes.merge(new ChavePessoaMes(p.getPessoaId(), p.getPessoaTipo(), mes), c,
                        (atual, nova) -> precedencia.compare(nova, atual) > 0 ? nova : atual);
            }
        }
        return folhaDoMes;
    }

    /** Meses-calendário que o lote toca, limitados ao intervalo pedido. */
    private static List<YearMonth> mesesTocados(PontoLote l, YearMonth de, YearMonth ate) {
        YearMonth ini = YearMonth.from(l.getDataInicio());
        YearMonth fim = YearMonth.from(l.getDataFim());
        if (ini.isBefore(de)) ini = de;
        if (fim.isAfter(ate)) fim = ate;
        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = ini; !m.isAfter(fim); m = m.plusMonths(1)) meses.add(m);
        return meses;
    }

    // ══════════════════════════════════════════════════════════════
    // Contagem dos dias
    // ══════════════════════════════════════════════════════════════

    /** Os dias de uma ocorrência, com o texto como a folha o imprimiu. */
    private static final class Contagem {
        private final String rotulo;
        private final Set<LocalDate> dias = new HashSet<>();

        Contagem(String rotulo) {
            this.rotulo = rotulo;
        }
    }

    /**
     * Dias por pessoa e ocorrência. Cada dia só conta se vier da folha escolhida para o mês DELE:
     * a folha que perdeu a disputa continua no banco, e contá-la duplicaria os dias que ela repete.
     *
     * <p>Os dias são acumulados em conjunto para que uma folha reprocessada — ou uma linha repetida
     * na página — não infle a contagem: a célula é "em quantos dias", não "quantas linhas".
     *
     * <p>Ocorrências que só diferem na caixa são a mesma coluna: agrupa-se pela forma normalizada e
     * exibe-se o primeiro texto visto, senão um "FERNC" e um "Fernc" partiriam o total em duas.
     */
    private Map<ChavePessoa, Map<String, Contagem>> contarDias(YearMonth de, YearMonth ate,
                                                               Map<ChavePessoaMes, Candidata> folhaDoMes,
                                                               Map<String, Folha> folhaDaPagina) {
        Map<ChavePessoa, Map<String, Contagem>> out = new HashMap<>();
        List<String> escolhidas = paginasDasFolhasEscolhidas(folhaDoMes, folhaDaPagina);
        if (escolhidas.isEmpty()) return out;

        for (Object[] r : ocorrenciasDasPaginas(escolhidas, de.atDay(1), ate.atEndOfMonth())) {
            String paginaId = (String) r[0];
            LocalDate data = (LocalDate) r[1];
            String ocorrencia = ((String) r[2]).trim();
            String chave = normalizar(ocorrencia);
            if (chave.isEmpty() || OCORRENCIAS_COLETIVAS.contains(chave)) continue;

            Folha folha = folhaDaPagina.get(paginaId);
            if (folha == null || !daFolhaDoMes(folha, YearMonth.from(data), folhaDoMes)) continue;

            out.computeIfAbsent(folha.pessoa(), k -> new HashMap<>())
               .computeIfAbsent(chave, k -> new Contagem(ocorrencia))
               .dias.add(data);
        }
        return out;
    }

    /** Todas as páginas dos lotes que venceram algum mês — inclusive as irmãs, do mesmo lote e pessoa. */
    private static List<String> paginasDasFolhasEscolhidas(Map<ChavePessoaMes, Candidata> folhaDoMes,
                                                           Map<String, Folha> folhaDaPagina) {
        Set<Folha> vencedoras = new HashSet<>();
        for (Candidata c : folhaDoMes.values()) {
            vencedoras.add(new Folha(new ChavePessoa(c.pessoaId(), c.pessoaTipo()), c.loteId()));
        }
        List<String> paginas = new ArrayList<>();
        folhaDaPagina.forEach((paginaId, folha) -> {
            if (vencedoras.contains(folha)) paginas.add(paginaId);
        });
        return paginas;
    }

    /** A folha da página é a que representa aquele mês para aquela pessoa? */
    private static boolean daFolhaDoMes(Folha folha, YearMonth mes, Map<ChavePessoaMes, Candidata> folhaDoMes) {
        Candidata doMes = folhaDoMes.get(
                new ChavePessoaMes(folha.pessoa().pessoaId(), folha.pessoa().pessoaTipo(), mes));
        return doMes != null && doMes.loteId().equals(folha.loteId());
    }

    /**
     * Os dias com ocorrência das folhas escolhidas. A lista é quebrada em blocos porque um
     * {@code IN} do Oracle não aceita mais de mil valores — e um ano de folhas da equipe inteira
     * passa disso.
     */
    private List<Object[]> ocorrenciasDasPaginas(List<String> paginaIds, LocalDate inicio, LocalDate fim) {
        List<Object[]> linhas = new ArrayList<>();
        for (List<String> bloco : NativeQueryUtils.partitionForIn(paginaIds)) {
            linhas.addAll(folhaLinhaRepo.findOcorrenciasNoPeriodo(bloco, inicio, fim));
        }
        return linhas;
    }

    /** Forma de comparação de uma ocorrência: sem espaços nas bordas e sem distinção de caixa. */
    private static String normalizar(String ocorrencia) {
        return ocorrencia.toLowerCase(Locale.ROOT);
    }

    // ══════════════════════════════════════════════════════════════
    // Montagem da resposta
    // ══════════════════════════════════════════════════════════════

    /** Pessoas com alguma folha representando um mês do intervalo — as linhas da tabela. */
    private static Set<ChavePessoa> pessoasComFolha(Map<ChavePessoaMes, Candidata> folhaDoMes) {
        Set<ChavePessoa> pessoas = new LinkedHashSet<>();
        for (Candidata c : folhaDoMes.values()) pessoas.add(new ChavePessoa(c.pessoaId(), c.pessoaTipo()));
        return pessoas;
    }

    private Map<String, Object> montarResposta(YearMonth de, YearMonth ate,
                                               Set<ChavePessoa> pessoas,
                                               Map<ChavePessoa, Map<String, Contagem>> porPessoa) {
        // TreeMap na ordem pt-BR: o total define a posição da coluna, e o código desempata para que
        // ocorrências igualmente frequentes não troquem de lugar entre duas consultas iguais.
        Map<String, Integer> totalPorChave = new HashMap<>();
        Map<String, String> rotuloPorChave = new TreeMap<>(NativeQueryUtils.ORDEM_TEXTO_PT_BR);
        for (Map<String, Contagem> ocorrencias : porPessoa.values()) {
            ocorrencias.forEach((chave, c) -> {
                totalPorChave.merge(chave, c.dias.size(), Integer::sum);
                rotuloPorChave.putIfAbsent(chave, c.rotulo);
            });
        }

        List<Map<String, Object>> ocorrencias = new ArrayList<>();
        // A lista nasce em ordem pt-BR (o TreeMap) e a reordenação por total é estável: empate de
        // total mantém a ordem alfabética, e duas consultas iguais devolvem as colunas na mesma ordem.
        List<String> chavesOrdenadas = new ArrayList<>(rotuloPorChave.keySet());
        chavesOrdenadas.sort(Comparator.comparingInt((String chave) -> totalPorChave.get(chave)).reversed());
        for (String chave : chavesOrdenadas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codigo", rotuloPorChave.get(chave));
            m.put("total", totalPorChave.get(chave));
            ocorrencias.add(m);
        }

        List<Map<String, Object>> funcionarios = new ArrayList<>();
        for (ChavePessoa p : pessoas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pessoa_id", p.pessoaId());
            m.put("pessoa_tipo", p.pessoaTipo());
            m.put("nome", pessoaCadastro.nome(p.pessoaId(), p.pessoaTipo()));
            // Célula zerada não vai no payload: a tabela é esparsa (a maioria dos pares não tem nada).
            Map<String, Object> contagens = new LinkedHashMap<>();
            Map<String, Contagem> daPessoa = porPessoa.getOrDefault(p, Map.of());
            for (String chave : chavesOrdenadas) {
                Contagem c = daPessoa.get(chave);
                if (c != null && !c.dias.isEmpty()) contagens.put(rotuloPorChave.get(chave), c.dias.size());
            }
            m.put("contagens", contagens);
            funcionarios.add(m);
        }
        // O id desempata os homônimos: sem ele, duas execuções iguais podiam trocá-los de lugar.
        funcionarios.sort(Comparator
                .comparing((Map<String, Object> m) -> String.valueOf(m.get("nome")), NativeQueryUtils.ORDEM_TEXTO_PT_BR)
                .thenComparing(m -> String.valueOf(m.get("pessoa_id"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("de", de.toString());
        out.put("ate", ate.toString());
        out.put("ocorrencias", ocorrencias);
        out.put("funcionarios", funcionarios);
        return out;
    }

    private static YearMonth parseCompetencia(String raw, String campo) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (Exception e) {
            throw new ServiceValidationException("Competência inválida em " + campo + " (use AAAA-MM).");
        }
    }
}
