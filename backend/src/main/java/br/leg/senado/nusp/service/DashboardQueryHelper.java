package br.leg.senado.nusp.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;

/**
 * Equivale ao query_helpers.py do Python.
 * Constrói queries dinâmicas para dashboards com paginação, busca, filtros e faceted search.
 * Adaptado de PostgreSQL (ILIKE, LIMIT/OFFSET) para Oracle (COLLATE BINARY_AI LIKE, FETCH/OFFSET).
 *
 * <p><b>Colação (F30/C15).</b> Texto neste motor obedece a uma regra só — <i>acento e caixa não
 * contam</i> — aplicada em três lugares que precisam concordar entre si:
 * <ul>
 *   <li><b>ordenação</b> (ORDER BY das listagens e das facetas): vem da sessão,
 *       {@code NLS_SORT=BINARY_AI} fixado no {@code connection-init-sql} do Hikari
 *       (application.yml) — não mais do locale da JVM;</li>
 *   <li><b>busca</b> ({@link #buscaSemAcentoNemCaixa}): colação POR EXPRESSÃO no LIKE, para não
 *       tocar a igualdade textual do resto do sistema (login, unicidade, DISTINCT, GROUP BY);</li>
 *   <li><b>o comparador Java das facetas</b> ({@link #TEXTO_PT_BR}), que ordena em memória o
 *       que o caminho consolidado não pede ao banco — e tem de dar a MESMA lista que o fallback
 *       por-coluna, que ordena no banco.</li>
 * </ul>
 * Mudar um dos três sem os outros faz a listagem e a sua própria faceta discordarem.
 */
public class DashboardQueryHelper {

    private static final Logger log = LoggerFactory.getLogger(DashboardQueryHelper.class);

    /** Resultado de uma listagem paginada. */
    public record PagedResult(List<Map<String, Object>> data, int total, Map<String, List<Map<String, String>>> distinct) {}

    /**
     * Facetas de TEXTO: ordena o {@code value} pela ordenação pt-BR do sistema
     * ({@link NativeQueryUtils#ORDEM_TEXTO_PT_BR}), que reproduz o {@code ORDER BY V} do Oracle sob
     * {@code NLS_SORT=BINARY_AI} — é o par {@code NLSSORT(V)}, {@code NLSSORT(V,'NLS_SORT=BINARY')}
     * de {@link #fetchDistinctPorColuna}, em Java. A regra mora lá (uma só, compartilhada com as
     * listas do Ponto que também ordenam em memória); aqui só se diz sobre QUAL campo ela incide.
     */
    private static final Comparator<Map<String, String>> TEXTO_PT_BR =
            Comparator.comparing(m -> m.get("value"), NativeQueryUtils.ORDEM_TEXTO_PT_BR);

    /** Ordem natural do {@code value} — para date (invertida: ISO ordena cronologicamente) e bool ("false" &lt; "true"). */
    private static final Comparator<Map<String, String>> VALOR_NATURAL =
            Comparator.comparing(m -> m.get("value"));

    /**
     * Executa uma listagem paginada com busca, filtros, faceted search e ordenação.
     * Equivale a _admin_list_view() + fetch_fn() do Python.
     */
    public static PagedResult executePagedQuery(
            EntityManager em,
            String selectCols,
            String fromJoins,
            String dateCol,
            Map<String, String> validSortCols,
            List<String> searchCols,
            Map<String, String> colMap,
            Map<String, String> colTypes,
            int page, int limit, String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters) {
        return executePagedQuery(em, selectCols, fromJoins, dateCol, validSortCols,
                searchCols, colMap, colTypes, page, limit, search, sort, direction, periodo, filters, null);
    }

    public static PagedResult executePagedQuery(
            EntityManager em,
            String selectCols,
            String fromJoins,
            String dateCol,            // coluna de data para filtro de período (ex: "c.DATA_OPERACAO")
            Map<String, String> validSortCols,   // key=param → value=SQL coluna
            List<String> searchCols,   // colunas para busca textual (UPPER LIKE)
            Map<String, String> colMap,           // key=nome front → value=SQL expr (para filtros/distinct)
            Map<String, String> colTypes,         // key=nome front → value=tipo (text/date/bool/number)
            int page, int limit, String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters,
            String tiebreaker) {       // coluna secundária de desempate (ex: "c.ID DESC")
        return executePagedQuery(em, selectCols, fromJoins, dateCol, validSortCols, searchCols,
                colMap, colTypes, page, limit, search, sort, direction, periodo, filters, tiebreaker, false);
    }

    /**
     * Forma canônica. Quando {@code somenteDados} é true (relatórios — Q5), pula o COUNT e as
     * facetas DISTINCT e executa APENAS a query de dados (WHERE/ORDER BY/binds idênticos aos da
     * listagem paginada). O guard {@code total > 0} deixa de existir: com 0 linhas roda 1 query
     * de dados vazia, resultado idêntico ao curto-circuito anterior. Os relatórios só consomem
     * {@code .data()}, então COUNT e facetas eram descartados.
     *
     * <p>Esta sobrecarga (sem {@code baseParams}) é para fromJoins SEM placeholders posicionais —
     * delega com lista de binds-base vazia.
     */
    public static PagedResult executePagedQuery(
            EntityManager em,
            String selectCols,
            String fromJoins,
            String dateCol,
            Map<String, String> validSortCols,
            List<String> searchCols,
            Map<String, String> colMap,
            Map<String, String> colTypes,
            int page, int limit, String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters,
            String tiebreaker, boolean somenteDados) {
        return executePagedQuery(em, selectCols, fromJoins, dateCol, validSortCols, searchCols,
                colMap, colTypes, page, limit, search, sort, direction, periodo, filters,
                tiebreaker, somenteDados, List.of());
    }

    /**
     * Forma canônica com binds-base (Q13). {@code baseParams} são os placeholders posicionais
     * ({@code ?}) que o {@code fromJoins} carrega — ex.: o userId do WHERE de ownership — e que,
     * por aparecerem no SQL ANTES das condições de busca/período/filtros, precisam ser
     * PREPENDADOS à lista de binds. Ordem posicional final: fromJoins → search → período →
     * filtros → OFFSET/FETCH. Trocar o userId interpolado por bind compartilha o cursor no shared
     * pool (1 cursor por operador → 1 só). Vazio para fromJoins sem placeholders (comportamento
     * e SQL idênticos ao anterior).
     */
    public static PagedResult executePagedQuery(
            EntityManager em,
            String selectCols,
            String fromJoins,
            String dateCol,
            Map<String, String> validSortCols,
            List<String> searchCols,
            Map<String, String> colMap,
            Map<String, String> colTypes,
            int page, int limit, String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters,
            String tiebreaker, boolean somenteDados, List<Object> baseParams) {

        int offset = (page - 1) * limit;
        WhereClause wc = buildWhereClause(fromJoins, dateCol, validSortCols, searchCols,
                colMap, colTypes, search, sort, direction, periodo, filters, tiebreaker);

        // Q13: binds do fromJoins (ex.: userId) vêm ANTES dos do WHERE (search→período→filtros).
        List<Object> params = new ArrayList<>(baseParams);
        params.addAll(wc.params());

        // ── Relatórios (Q5): só a query de dados, sem COUNT nem facetas ──
        if (somenteDados) {
            List<Map<String, Object>> data = runDataQuery(em, selectCols, fromJoins,
                    wc.where(), wc.orderBy(), params, offset, limit);
            return new PagedResult(data, data.size(), Map.of());
        }

        // ── COUNT ──
        String countSql = "SELECT COUNT(*) " + fromJoins + " " + wc.where();
        Query countQ = em.createNativeQuery(countSql);
        setParams(countQ, params);
        int total = ((Number) countQ.getSingleResult()).intValue();

        // ── DATA ──
        List<Map<String, Object>> data = new ArrayList<>();
        if (total > 0) {
            data = runDataQuery(em, selectCols, fromJoins, wc.where(), wc.orderBy(), params, offset, limit);
        }

        // ── DISTINCT MAP (faceted search) ──
        // Simplificado: retorna distinct para cada coluna sem filtro cruzado
        Map<String, List<Map<String, String>>> distinct =
                fetchDistinctMap(em, fromJoins, wc.where(), params, colMap, colTypes);

        return new PagedResult(data, total, distinct);
    }

    /**
     * Monta o item de resposta de checklist dos payloads de detalhe/versão (admin e operador)
     * com as 8 chaves em ordem fixa e os defaults comuns de descricao_falha/valor_texto.
     * id/status/descricaoFalha/valorTexto são Object para aceitar tanto valores de query nativa
     * quanto os crus do snapshot JSON do histórico — cada call site mantém sua própria resolução
     * (ex.: default "radio" de tipoWidget onde ele existe hoje).
     */
    public static Map<String, Object> checklistItemMap(Object id, Long itemTipoId, String itemNome,
                                                       Object status, Object descricaoFalha, Object valorTexto,
                                                       boolean editado, String tipoWidget) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("item_tipo_id", itemTipoId);
        m.put("item_nome", itemNome);
        m.put("status", status);
        m.put("descricao_falha", descricaoFalha != null ? descricaoFalha : "");
        m.put("valor_texto", valorTexto != null ? valorTexto : "");
        m.put("editado", editado);
        m.put("tipo_widget", tipoWidget);
        return m;
    }

    // ── Helpers privados ──

    /**
     * Expressão de BUSCA insensível a acento e caixa (F30) — o lado esquerdo dos LIKE do campo de
     * pesquisa e do filtro textual por coluna. {@code COLLATE BINARY_AI} dá peso zero a acento e
     * caixa: "Jose", "jose", "José" e "josé" acham "José" (e "Josa" continua não achando).
     *
     * <p>É colação <b>por expressão</b>, e isso é o ponto: ela vale só neste LIKE. O
     * {@code NLS_COMP} da sessão continua BINARY, então nenhuma outra comparação de texto do
     * sistema muda de semântica — login ({@code usuario = ?}), unicidade, matches exatos do Ponto,
     * o IN das facetas e o DISTINCT/GROUP BY que as alimenta seguem sensíveis a acento e caixa. Um
     * {@code NLS_COMP=LINGUISTIC} global teria dado a mesma busca de graça, e junto: 'douglas'
     * casando 'DOUGLAS' no login e o DISTINCT colapsando 'Jose' com 'José' (medido, C15).
     *
     * <p>O {@code CAST} preserva o que o {@code UPPER(...)} anterior dava de graça: searchCol
     * não-char (ex.: {@code TO_CHAR(c.NUMERO)} em Avisos, o CASE de NOME_SOLICITANTE no Banco de
     * Horas) segue comparável — sem ele, {@code COLLATE} sobre não-char é erro de SQL.
     */
    private static String buscaSemAcentoNemCaixa(String colSql) {
        return "(CAST(" + colSql + " AS VARCHAR2(4000)) COLLATE BINARY_AI)";
    }

    /** Cláusulas WHERE + ORDER BY já montadas e os binds na ordem posicional — compartilhadas por COUNT, DATA e facetas. */
    private record WhereClause(String where, String orderBy, List<Object> params) {}

    /** Monta WHERE (busca textual + período + filtros de coluna) e ORDER BY, acumulando os binds na ordem. */
    private static WhereClause buildWhereClause(
            String fromJoins, String dateCol, Map<String, String> validSortCols,
            List<String> searchCols, Map<String, String> colMap, Map<String, String> colTypes,
            String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters, String tiebreaker) {

        // ── WHERE (busca textual) ──
        boolean fromHasWhere = fromJoins.toUpperCase().contains(" WHERE ");
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            // F30: termo CRU — quem ignora a caixa agora é a colação, não um toUpperCase() que ainda
            // por cima dependia do locale da JVM (o mesmo locale que já bagunçava a ordenação).
            String term = "%" + search.strip() + "%";
            List<String> ors = new ArrayList<>();
            for (String col : searchCols) {
                ors.add(buscaSemAcentoNemCaixa(col) + " LIKE ?");
                params.add(term);
            }
            if (!ors.isEmpty()) conditions.add(String.join(" OR ", ors));
        }

        // ── WHERE (período) ──
        appendDateFilter(conditions, params, dateCol, periodo);

        // ── WHERE (filtros de coluna) ──
        appendColumnFilters(conditions, params, filters, colMap, colTypes, null);

        // Monta o WHERE final. Quando fromJoins já tem WHERE, o prefixo "  AND (" tem espaço
        // duplo de propósito: preserva a SQL byte-idêntica ao formato anterior (sentinela " ").
        String where = conditions.isEmpty()
                ? (fromHasWhere ? " " : "")
                : (fromHasWhere ? "  AND (" : "WHERE (")
                        + String.join(") AND (", conditions) + ")";

        // ── ORDER BY ──
        String orderBy = buildOrderBy(validSortCols, colTypes, sort, direction, tiebreaker);
        return new WhereClause(where, orderBy, params);
    }

    /** Executa a query de dados paginada (OFFSET/FETCH) e mapeia as linhas. Binds de dados vêm após os do WHERE. */
    private static List<Map<String, Object>> runDataQuery(
            EntityManager em, String selectCols, String fromJoins,
            String where, String orderBy, List<Object> params, int offset, int limit) {
        String dataSql = "SELECT " + selectCols + " " + fromJoins + " " + where + " " + orderBy
                + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(offset);
        dataParams.add(limit);
        Query dataQ = em.createNativeQuery(dataSql);
        setParams(dataQ, dataParams);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQ.getResultList();
        return mapRows(rows, parseColumnNames(selectCols));
    }

    /**
     * ORDER BY da listagem. Sort inválido/ausente → PRIMEIRA entrada do {@code validSortCols}
     * (LinkedHashMap: determinístico — F16).
     *
     * <p><b>Desempate das colunas de texto (F30).</b> Sob {@code NLS_SORT=BINARY_AI}, dois cadastros
     * que só diferem por acento ou caixa ("José Silva" e "JOSE SILVA") passam a ter a MESMA chave de
     * ordenação — antes, sob BINARY, a chave era total. Chave não-total + {@code OFFSET/FETCH} é
     * paginação instável: o Oracle pode devolver uma dessas linhas em duas páginas e a outra em
     * nenhuma. O {@code NLSSORT(col,'NLS_SORT=BINARY')} devolve a ordem total, reproduzindo o
     * desempate do comparador Java das facetas. Só para {@code text} — {@code NLSSORT} sobre DATE ou
     * NUMBER converteria implicitamente para string e mudaria a ordem.
     */
    private static String buildOrderBy(Map<String, String> validSortCols, Map<String, String> colTypes,
                                       String sort, String direction, String tiebreaker) {
        // sort == null é caso REAL (relatórios chamam sem sort) e os validSortCols são Map.of()
        // imutáveis — containsKey(null) neles lança NPE.
        String sortKey = (sort != null && validSortCols.containsKey(sort))
                ? sort : validSortCols.keySet().iterator().next();
        String orderCol = validSortCols.get(sortKey);
        String dir = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";
        String orderBy = "ORDER BY " + orderCol + " " + dir;
        if ("text".equals(colTypes.get(sortKey))) {
            orderBy += ", NLSSORT(" + orderCol + ", 'NLS_SORT=BINARY') " + dir;
        }
        if (tiebreaker != null && !tiebreaker.isBlank()) {
            orderBy += ", " + tiebreaker;
        }
        return orderBy;
    }

    private static String[] parseColumnNames(String selectCols) {
        String[] cols = selectCols.split(",");
        String[] names = new String[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String name = cols[i].trim();
            // Extrai alias se existir (ex: "s.NOME AS sala_nome" → "sala_nome")
            int asIdx = name.toUpperCase().lastIndexOf(" AS ");
            if (asIdx >= 0) name = name.substring(asIdx + 4).trim();
            // Remove tabela (ex: "o.NOME_COMPLETO" → "NOME_COMPLETO")
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx >= 0) name = name.substring(dotIdx + 1);
            names[i] = name.toLowerCase();
        }
        return names;
    }

    private static List<Map<String, Object>> mapRows(List<Object[]> rows, String[] names) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < row.length && i < names.length; i++) {
                m.put(names[i], convertValue(row[i]));
            }
            data.add(m);
        }
        return data;
    }

    /**
     * Facetas (faceted search) do {@code meta.distinct}. Q4/C10: consolida as N queries
     * {@code SELECT DISTINCT expr} (1 por coluna do colMap) numa ÚNICA passada
     * {@code GROUP BY GROUPING SETS}. Reduz as listagens de {@code 2+|colMap|} para 3 queries.
     *
     * <p><b>Fallback anti-silencioso (gotcha 16):</b> se a consolidação lançar (ex.: expressão
     * incompatível, CLOB em DISTINCT), cai no caminho por coluna PRESERVANDO o try/catch por faceta
     * de antes — mas emite um WARN identificando listagem e colunas. Sem esse WARN, a listagem
     * regrediria ao custo de N queries sem nenhum sinal. A campanha de validação do C10 exige ZERO
     * ocorrências dele.
     */
    private static Map<String, List<Map<String, String>>> fetchDistinctMap(
            EntityManager em, String fromJoins, String where, List<Object> params,
            Map<String, String> colMap, Map<String, String> colTypes) {
        if (colMap.isEmpty()) return new LinkedHashMap<>();
        // Materializa a ordem de iteração 1× (Map.of/LinkedHashMap: estável p/ a instância). Define
        // os aliases G0..Gn e a ordem das chaves do resultado (idêntica ao caminho por coluna).
        List<Map.Entry<String, String>> facetas = new ArrayList<>(colMap.entrySet());
        try {
            return fetchDistinctConsolidado(em, fromJoins, where, params, facetas, colTypes);
        } catch (Exception e) {
            log.warn("[facetas] consolidacao GROUPING SETS falhou; fallback por coluna. listagem={} colunas={} causa={}",
                    tabelaPrincipal(fromJoins), colMap.keySet(), e.toString());
            return fetchDistinctPorColuna(em, fromJoins, where, params, facetas, colTypes);
        }
    }

    /**
     * Caminho consolidado (Q4/C10, ressalva (v) do §3 / gotcha 16). Monta uma <b>inline view com
     * ALIASES</b> — SELECT interno projeta cada expressão do colMap com alias {@code Gi} (aplicando
     * {@code TRUNC()} nas colunas {@code date}, como o caminho por coluna) e carrega o WHERE
     * completo — e agrupa por {@code GROUP BY GROUPING SETS ((G0),(G1),…)} sobre os ALIASES, nunca
     * sobre a expressão crua (agrupar por subquery escalar dispara ORA-22818). O {@code GROUPING_ID}
     * identifica a faceta de cada linha. NULLs (grupo NULL de cada faceta) são pulados em memória e
     * a reordenação em Java reproduz o {@code ORDER BY V} do Oracle sob {@code NLS_SORT=BINARY_AI}
     * (F30): text ASC sem acento nem caixa ({@link #TEXTO_PT_BR}), date DESC, bool ASC.
     */
    private static Map<String, List<Map<String, String>>> fetchDistinctConsolidado(
            EntityManager em, String fromJoins, String where, List<Object> params,
            List<Map.Entry<String, String>> facetas, Map<String, String> colTypes) {
        int n = facetas.size();
        StringBuilder inner = new StringBuilder();       // "TRUNC(c.DATA) AS G0, (s.NOME) AS G1, …"
        StringBuilder groupingArgs = new StringBuilder(); // "G0, G1, …"
        StringBuilder groupingSets = new StringBuilder(); // "(G0), (G1), …"
        StringBuilder outerCols = new StringBuilder();    // "G0, G1, …"
        for (int i = 0; i < n; i++) {
            String type = colTypes.getOrDefault(facetas.get(i).getKey(), "text");
            String expr = facetas.get(i).getValue();
            String proj = "date".equals(type) ? "TRUNC(" + expr + ")" : "(" + expr + ")";
            if (i > 0) { inner.append(", "); groupingArgs.append(", "); groupingSets.append(", "); outerCols.append(", "); }
            inner.append(proj).append(" AS G").append(i);
            groupingArgs.append("G").append(i);
            groupingSets.append("(G").append(i).append(")");
            outerCols.append("G").append(i);
        }
        // Binds idênticos aos do COUNT (fromJoins→where); as expressões projetadas não têm '?'.
        String sql = "SELECT GROUPING_ID(" + groupingArgs + ") AS GID, " + outerCols
                + " FROM (SELECT " + inner + " " + fromJoins + " " + where + ") iv"
                + " GROUP BY GROUPING SETS (" + groupingSets + ")";
        Query q = em.createNativeQuery(sql);
        setParams(q, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        // GROUPING_ID do grouping set (Gk): todas agregadas exceto Gk → (2^n - 1) - 2^(n-1-k)
        // (primeiro argumento = bit mais significativo).
        int full = (1 << n) - 1;
        Map<Integer, Integer> gidToIdx = new HashMap<>();
        for (int k = 0; k < n; k++) gidToIdx.put(full - (1 << (n - 1 - k)), k);

        List<List<Object>> valsPorFaceta = new ArrayList<>();
        for (int i = 0; i < n; i++) valsPorFaceta.add(new ArrayList<>());
        for (Object[] row : rows) {
            Integer k = gidToIdx.get(((Number) row[0]).intValue());
            if (k == null) continue;              // GID inesperado (defensivo — não deveria ocorrer)
            Object v = row[1 + k];
            if (v == null) continue;              // reproduz o "if (v == null) continue" atual
            valsPorFaceta.get(k).add(v);
        }

        Map<String, List<Map<String, String>>> distinct = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String key = facetas.get(i).getKey();
            String type = colTypes.getOrDefault(key, "text");
            List<Map<String, String>> list = new ArrayList<>();
            for (Object v : valsPorFaceta.get(i)) list.add(facetItem(v, type));
            // Reproduz o ORDER BY V do fallback (que o Oracle faz sob NLS_SORT=BINARY_AI):
            // text ASC sem acento nem caixa (F30), date DESC (ISO ordena cronologicamente) e
            // bool "false"<"true" (=0<1).
            // ⚠️ Vale para os tipos usados hoje (date/text/bool). Uma faceta futura colType "number"
            // (nenhum colMap tem hoje) precisaria de ordenação NUMÉRICA aqui — este sort é lexicográfico
            // pelo "value" (≠ do fallback por coluna, que delega o ORDER BY ao Oracle: number vira numérico).
            list.sort("date".equals(type) ? VALOR_NATURAL.reversed()
                    : "text".equals(type) ? TEXTO_PT_BR
                    : VALOR_NATURAL);
            distinct.put(key, list);
        }
        return distinct;
    }

    /**
     * Fallback: comportamento ANTERIOR ao C10 — 1 query {@code SELECT DISTINCT} por faceta, com o
     * {@code ORDER BY V} feito pelo Oracle e o try/catch POR FACETA (erro numa coluna → lista vazia,
     * sem derrubar as demais). Só é atingido se a consolidação falhar (WARN em fetchDistinctMap).
     *
     * <p>Visível ao pacote (e não privado) porque a igualdade com o consolidado é <b>contratual</b>
     * desde o F30: os dois ordenam texto — um no banco, outro em Java — e só se prova que concordam
     * chamando os dois sobre o mesmo cenário. O {@code DashboardQueryHelperIT} faz isso; pelo
     * caminho normal ele não conseguiria, porque acionar o fallback exige quebrar a consolidação, e
     * aí o WARN derrubaria o próprio teste.
     */
    static Map<String, List<Map<String, String>>> fetchDistinctPorColuna(
            EntityManager em, String fromJoins, String where, List<Object> params,
            List<Map.Entry<String, String>> facetas, Map<String, String> colTypes) {
        Map<String, List<Map<String, String>>> distinct = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : facetas) {
            String key = entry.getKey();
            String type = colTypes.getOrDefault(key, "text");
            String selectExpr = "date".equals(type)
                    ? "DISTINCT TRUNC(" + entry.getValue() + ")"
                    : "DISTINCT (" + entry.getValue() + ")";
            String orderDir = "date".equals(type) ? "DESC" : "ASC";
            // F30: o ORDER BY V já sai sem acento nem caixa (NLS_SORT=BINARY_AI da sessão). O
            // desempate binário fecha o único buraco: 'Jose' e 'José' passam DISTINCT (a igualdade
            // segue BINARY) mas colidem na chave AI, e sem ele o Oracle os devolveria em ordem
            // indefinida — divergindo do consolidado, que desempata pelo valor cru (TEXTO_PT_BR).
            String desempate = "text".equals(type) ? ", NLSSORT(V, 'NLS_SORT=BINARY') " + orderDir : "";
            String distSql = "SELECT " + selectExpr + " AS V " + fromJoins + " " + where
                    + " ORDER BY V " + orderDir + desempate;
            try {
                Query distQ = em.createNativeQuery(distSql);
                setParams(distQ, params);
                @SuppressWarnings("unchecked")
                List<Object> vals = distQ.getResultList();
                List<Map<String, String>> list = new ArrayList<>();
                for (Object v : vals) {
                    if (v == null) continue;
                    list.add(facetItem(v, type));
                }
                distinct.put(key, list);
            } catch (Exception e) {
                log.warn("[facetas] fallback: faceta '{}' de {} falhou — lista vazia. causa={}",
                        key, tabelaPrincipal(fromJoins), e.toString());
                distinct.put(key, List.of());
            }
        }
        return distinct;
    }

    /**
     * value/label de UM item de faceta — formato idêntico ao anterior ao C10 e compartilhado pelos
     * dois caminhos (consolidado e fallback). date → value {@code yyyy-MM-dd} + label
     * {@code dd/MM/yyyy}; bool → value {@code false}/{@code true} + label {@code Não}/{@code Sim};
     * demais → value=label=texto cru.
     */
    private static Map<String, String> facetItem(Object v, String type) {
        String value = v.toString();
        String label = value;
        if ("date".equals(type)) {
            String raw = value.split(" ")[0]; // "2026-03-03 00:00:00.0" → "2026-03-03"
            value = raw;
            String[] parts = raw.split("-");
            label = parts.length == 3 ? parts[2] + "/" + parts[1] + "/" + parts[0] : raw;
        } else if ("bool".equals(type)) {
            boolean b = "1".equals(value) || "true".equalsIgnoreCase(value);
            value = b ? "true" : "false";
            label = b ? "Sim" : "Não";
        }
        return Map.of("value", value, "label", label);
    }

    /** Primeira tabela do fromJoins — identifica a listagem nos WARN de fallback (auditáveis). */
    private static String tabelaPrincipal(String fromJoins) {
        int i = fromJoins.toUpperCase().indexOf("FROM ");
        if (i < 0) return "?";
        String[] toks = fromJoins.substring(i + 5).trim().split("\\s+");
        return toks.length > 0 ? toks[0] : "?";
    }

    private static void appendDateFilter(List<String> conditions, List<Object> params, String dateCol, Map<String, Object> periodo) {
        if (dateCol == null || periodo == null) return;
        @SuppressWarnings("unchecked")
        List<Map<String, String>> ranges = (List<Map<String, String>>) periodo.get("ranges");
        if (ranges == null || ranges.isEmpty()) return;

        List<String> parts = new ArrayList<>();
        for (Map<String, String> r : ranges) {
            String start = r.get("start");
            String end = r.get("end");
            if (start != null && end != null) {
                parts.add(dateCol + " BETWEEN TO_DATE(?, 'YYYY-MM-DD') AND TO_DATE(?, 'YYYY-MM-DD')");
                params.add(start);
                params.add(end);
            }
        }
        if (parts.isEmpty()) return;
        conditions.add(String.join(" OR ", parts));
    }

    private static void appendColumnFilters(List<String> conditions, List<Object> params,
                                             Map<String, Object> filters, Map<String, String> colMap,
                                             Map<String, String> colTypes, String excludeKey) {
        if (filters == null) return;
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            if (key.equals(excludeKey)) continue;
            if (!colMap.containsKey(key)) continue;
            if (!(entry.getValue() instanceof Map)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) entry.getValue();
            String colSql = colMap.get(key);
            String colType = colTypes.getOrDefault(key, "text");

            appendTextFilter(conditions, params, colSql, colType, spec);
            appendRangeFilter(conditions, params, colSql, colType, spec);
            appendValuesFilter(conditions, params, colSql, colType, spec);
        }
    }

    private static void appendTextFilter(List<String> conditions, List<Object> params,
                                         String colSql, String colType, Map<String, Object> spec) {
        String text = spec.get("text") != null ? spec.get("text").toString().strip() : "";
        if (!text.isEmpty()) {
            if ("date".equals(colType)) {
                String cond = "TO_CHAR(" + colSql + ", 'DD/MM/YYYY') LIKE ?";
                conditions.add(cond);
            } else {
                // F30: o filtro textual por coluna é o mesmo gesto do campo de busca (o usuário
                // DIGITA um trecho) → mesma regra, mesma expressão. Já o filtro por VALORES
                // (appendValuesFilter) é igualdade contra um item escolhido na lista de facetas:
                // continua exato, senão marcar "Jose" traria "José" — que a lista mostra à parte.
                conditions.add(buscaSemAcentoNemCaixa(colSql) + " LIKE ?");
            }
            params.add("%" + text + "%");   // termo cru: a colação ignora a caixa (o ramo date é só dígitos e '/')
        }
    }

    private static void appendRangeFilter(List<String> conditions, List<Object> params,
                                          String colSql, String colType, Map<String, Object> spec) {
        if ("date".equals(colType) && spec.get("range") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> range = (Map<String, Object>) spec.get("range");
            String from = range.get("from") != null ? range.get("from").toString().strip() : "";
            String to = range.get("to") != null ? range.get("to").toString().strip() : "";
            if (!from.isEmpty()) {
                // Q6: sargável — TRUNC(col) >= :d ⟺ col >= :d (libera INDEX RANGE SCAN nos *_DATA_SALA).
                conditions.add(colSql + " >= TO_DATE(?, 'YYYY-MM-DD')");
                params.add(from);
            }
            if (!to.isEmpty()) {
                // Q6: sargável — TRUNC(col) <= :d ⟺ col < :d + 1 (meia-noite do dia seguinte, exclusiva).
                conditions.add(colSql + " < TO_DATE(?, 'YYYY-MM-DD') + 1");
                params.add(to);
            }
        }
    }

    private static void appendValuesFilter(List<String> conditions, List<Object> params,
                                           String colSql, String colType, Map<String, Object> spec) {
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) spec.get("values");
        if (values != null && !values.isEmpty()) {
            if ("date".equals(colType)) {
                // Q6: sargável — TRUNC(col) IN (d1..dn) ⟺ OR de ranges [di, di+1). Cada data DOBRA os
                // binds (par >=/< por valor); condições e params crescem JUNTOS, na ordem dos valores.
                List<String> ranges = new ArrayList<>();
                for (Object v : values) {
                    ranges.add("(" + colSql + " >= TO_DATE(?, 'YYYY-MM-DD') AND "
                            + colSql + " < TO_DATE(?, 'YYYY-MM-DD') + 1)");
                    params.add(v);
                    params.add(v);
                }
                conditions.add(String.join(" OR ", ranges));
            } else if ("bool".equals(colType)) {
                // Converter "true"/"false" para 1/0 (Oracle NUMBER(1))
                String placeholders = String.join(",", values.stream().map(v -> "?").toList());
                conditions.add(colSql + " IN (" + placeholders + ")");
                for (Object v : values) {
                    params.add("true".equalsIgnoreCase(v.toString()) ? 1 : 0);
                }
            } else {
                String placeholders = String.join(",", values.stream().map(v -> "?").toList());
                conditions.add(colSql + " IN (" + placeholders + ")");
                params.addAll(values);
            }
        }
    }

    private static void setParams(Query q, List<Object> params) {
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
    }

    private static Object convertValue(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            if (n.longValue() == n.doubleValue()) return n.longValue();
            return n;
        }
        // Delega tratamento de CLOB e toString para NativeQueryUtils
        return NativeQueryUtils.str(v);
    }
}
