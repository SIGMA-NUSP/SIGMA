package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.RegistroAnormalidadeAdmin;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static br.leg.senado.nusp.service.NativeQueryUtils.bindList;
import static br.leg.senado.nusp.service.NativeQueryUtils.boolVal;
import static br.leg.senado.nusp.service.NativeQueryUtils.inPlaceholders;
import static br.leg.senado.nusp.service.NativeQueryUtils.num;
import static br.leg.senado.nusp.service.NativeQueryUtils.partitionForIn;
import static br.leg.senado.nusp.service.NativeQueryUtils.str;

/**
 * Lógica de negócio dos dashboards admin.
 * Equivale a dashboard_operadores.py, dashboard_checklists.py,
 * dashboard_operacoes.py e dashboard_anormalidades.py do Python.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    // ══ Operadores ════════════════════════════════════════════

    // LinkedHashMap (não Map.of): buildOrderBy resolve sort desconhecido com o PRIMEIRO valor do
    // map, e a ordem de iteração do Map.of é randomizada por JVM — a listagem reordenava sozinha
    // entre reinícios. "nome" primeiro, em paridade com o defaultValue="nome" da rota.
    private static final Map<String, String> OP_SORT = new LinkedHashMap<>() {{
        put("nome", "o.NOME_COMPLETO"); put("email", "o.EMAIL");
    }};

    public PagedResult listOperadores(int page, int limit, String search, String sort, String dir,
                                       Map<String, Object> filters) {
        return listOperadores(page, limit, search, sort, dir, filters, false);
    }

    public PagedResult listOperadores(int page, int limit, String search, String sort, String dir,
                                       Map<String, Object> filters, boolean somenteDados) {
        return DashboardQueryHelper.executePagedQuery(em,
                "o.ID, o.NOME_COMPLETO, o.EMAIL, o.TURNO, o.PLENARIO_PRINCIPAL, o.PLENARIO_PRINCIPAL_FIXO, o.PARTICIPA_ESCALA",
                "FROM PES_OPERADOR o",
                null, OP_SORT, List.of("o.NOME_COMPLETO", "o.EMAIL"),
                Map.of("nome", "o.NOME_COMPLETO", "email", "o.EMAIL"),
                Map.of("nome", "text", "email", "text"),
                page, limit, search, sort, dir, null, filters, null, somenteDados);
    }

    // ══ Técnicos ══════════════════════════════════════════════

    private static final Map<String, String> TEC_SORT = new LinkedHashMap<>() {{
        put("nome", "t.NOME_COMPLETO"); put("email", "t.EMAIL");
    }};

    public PagedResult listTecnicos(int page, int limit, String search, String sort, String dir,
                                     Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "t.ID, t.NOME_COMPLETO, t.EMAIL",
                "FROM PES_TECNICO t",
                null, TEC_SORT, List.of("t.NOME_COMPLETO", "t.EMAIL"),
                Map.of("nome", "t.NOME_COMPLETO", "email", "t.EMAIL"),
                Map.of("nome", "text", "email", "text"),
                page, limit, search, sort, dir, null, filters);
    }

    // ══ Administradores (somente master — guarda no controller) ═

    private static final Map<String, String> ADM_SORT = new LinkedHashMap<>() {{
        put("nome", "a.NOME_COMPLETO"); put("email", "a.EMAIL");
    }};

    public PagedResult listAdministradores(int page, int limit, String search, String sort, String dir,
                                           Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "a.ID, a.NOME_COMPLETO, a.EMAIL",
                "FROM PES_ADMINISTRADOR a",
                null, ADM_SORT, List.of("a.NOME_COMPLETO", "a.EMAIL"),
                Map.of("nome", "a.NOME_COMPLETO", "email", "a.EMAIL"),
                Map.of("nome", "text", "email", "text"),
                page, limit, search, sort, dir, null, filters);
    }

    // ══ Checklists ════════════════════════════════════════════

    private static final Map<String, String> CL_SORT = new LinkedHashMap<>() {{
        put("data", "c.DATA_OPERACAO"); put("sala", "s.NOME"); put("nome", "o.NOME_COMPLETO");
    }};

    /**
     * Expressão SQL derivada do status do checklist: 'Falha' se houver qualquer resposta com falha,
     * 'Ok' se houver respostas (sem falha) e '--' se não houver respostas.
     * Reutilizada no SELECT (alias status) e como coluna filtrável/distinct ("Status").
     */
    private static final String CL_STATUS_EXPR =
            "CASE WHEN EXISTS (SELECT 1 FROM FRM_CHECKLIST_RESPOSTA r WHERE r.CHECKLIST_ID = c.ID AND r.STATUS = 'Falha') THEN 'Falha' " +
            "WHEN EXISTS (SELECT 1 FROM FRM_CHECKLIST_RESPOSTA r WHERE r.CHECKLIST_ID = c.ID) THEN 'Ok' ELSE '--' END";

    public PagedResult listChecklists(int page, int limit, String search, String sort, String dir,
                                       Map<String, Object> periodo, Map<String, Object> filters) {
        return listChecklists(page, limit, search, sort, dir, periodo, filters, false);
    }

    public PagedResult listChecklists(int page, int limit, String search, String sort, String dir,
                                       Map<String, Object> periodo, Map<String, Object> filters, boolean somenteDados) {
        return DashboardQueryHelper.executePagedQuery(em,
                "c.ID, c.DATA_OPERACAO AS data, s.NOME AS sala_nome, c.TURNO, " +
                "o.NOME_COMPLETO AS operador_nome, c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.EDITADO, " +
                "(SELECT MAX(h.EDITADO_EM) FROM FRM_CHECKLIST_HISTORICO h WHERE h.CHECKLIST_ID = c.ID) AS ultima_edicao_em, " +
                CL_STATUS_EXPR + " AS status",
                "FROM FRM_CHECKLIST c JOIN CAD_SALA s ON s.ID = c.SALA_ID LEFT JOIN PES_OPERADOR o ON o.ID = c.CRIADO_POR",
                "c.DATA_OPERACAO", CL_SORT, List.of("s.NOME", "o.NOME_COMPLETO"),
                Map.of("data", "c.DATA_OPERACAO", "sala", "s.NOME", "turno", "c.TURNO", "nome", "o.NOME_COMPLETO", "status", CL_STATUS_EXPR),
                Map.of("data", "date", "sala", "text", "turno", "text", "nome", "text", "status", "text"),
                page, limit, search, sort, dir, periodo, filters, "c.ID DESC", somenteDados);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getChecklistDetalhe(long checklistId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.ID, c.DATA_OPERACAO, c.SALA_ID, s.NOME AS SALA_NOME, c.TURNO,
                       c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.OBSERVACOES,
                       c.USB_01, c.USB_02, c.EDITADO, o.NOME_COMPLETO AS OPERADOR_NOME,
                       c.CRIADO_POR
                FROM FRM_CHECKLIST c
                JOIN CAD_SALA s ON s.ID = c.SALA_ID
                LEFT JOIN PES_OPERADOR o ON o.ID = c.CRIADO_POR
                WHERE c.ID = ?1
                """).setParameter(1, checklistId).getResultList();
        if (rows.isEmpty()) return null;

        Object[] h = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(h[0])); result.put("data_operacao", str(h[1]));
        result.put("sala_id", num(h[2])); result.put("sala_nome", str(h[3]));
        result.put("turno", str(h[4])); result.put("hora_inicio_testes", str(h[5]));
        result.put("hora_termino_testes", str(h[6])); result.put("observacoes", str(h[7]));
        result.put("usb_01", str(h[8])); result.put("usb_02", str(h[9]));
        result.put("editado", boolVal(h[10])); result.put("operador_nome", str(h[11]));
        result.put("criado_por", str(h[12]));

        List<Object[]> itens = em.createNativeQuery("""
                SELECT r.ID, r.ITEM_TIPO_ID, t.NOME AS ITEM_NOME, r.STATUS,
                       r.DESCRICAO_FALHA, r.VALOR_TEXTO, r.EDITADO, t.TIPO_WIDGET
                FROM FRM_CHECKLIST_RESPOSTA r
                JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID
                LEFT JOIN FRM_CHECKLIST_SALA_CONFIG sc ON sc.ITEM_TIPO_ID = t.ID AND sc.SALA_ID = ?2
                WHERE r.CHECKLIST_ID = ?1
                ORDER BY sc.ORDEM ASC, t.ID ASC
                """).setParameter(1, checklistId).setParameter(2, num(h[2])).getResultList();

        List<Map<String, Object>> itensList = new ArrayList<>();
        for (Object[] it : itens) {
            itensList.add(DashboardQueryHelper.checklistItemMap(num(it[0]), num(it[1]),
                    str(it[2]), str(it[3]), str(it[4]), str(it[5]), boolVal(it[6]),
                    str(it[7]) != null ? str(it[7]) : "radio"));
        }
        result.put("itens", itensList);

        // Operadores do Plenário Principal (junction table)
        @SuppressWarnings("unchecked")
        List<Object[]> opRows = em.createNativeQuery("""
                SELECT co.PAPEL, o.NOME_COMPLETO
                FROM FRM_CHECKLIST_OPERADOR co
                JOIN PES_OPERADOR o ON o.ID = co.OPERADOR_ID
                WHERE co.CHECKLIST_ID = ?1
                ORDER BY co.PAPEL, o.NOME_COMPLETO
                """).setParameter(1, checklistId).getResultList();
        Map<String, List<String>> porPapel = splitOperadoresPorPapel(opRows, 0, 1);
        List<String> cabine = porPapel.get("cabine"), plenario = porPapel.get("plenario");
        if (!cabine.isEmpty() || !plenario.isEmpty()) {
            result.put("operadores_cabine", cabine);
            result.put("operadores_plenario", plenario);
        }

        return result;
    }

    // ══ Operações (sessões) ═══════════════════════════════════

    private static final Map<String, String> OP_SESS_SORT = new LinkedHashMap<>() {{
        put("data", "r.DATA"); put("sala", "s.NOME");
        put("inicio", "ult.HORARIO_INICIO"); put("fim", "ult.HORARIO_TERMINO");
    }};

    public PagedResult listOperacoes(int page, int limit, String search, String sort, String dir,
                                      Map<String, Object> periodo, Map<String, Object> filters) {
        return listOperacoes(page, limit, search, sort, dir, periodo, filters, false);
    }

    public PagedResult listOperacoes(int page, int limit, String search, String sort, String dir,
                                      Map<String, Object> periodo, Map<String, Object> filters, boolean somenteDados) {
        return DashboardQueryHelper.executePagedQuery(em,
                "r.ID, r.DATA AS data, s.NOME AS sala_nome, " +
                "r.NOME_DEMAIS_SALAS AS nome_demais_salas, " +
                "CASE WHEN r.CHECKLIST_DO_DIA_ID IS NOT NULL THEN 1 ELSE 0 END AS checklist_do_dia_ok, " +
                "ult.NOME_EVENTO AS ultimo_evento, " +
                "c.NOME AS comissao_nome, " +
                "ult.HORARIO_PAUTA AS ultimo_pauta, " +
                "ult.HORARIO_INICIO AS ultimo_inicio, " +
                "ult.HORARIO_TERMINO AS ultimo_termino, " +
                "(SELECT MAX(eh.EDITADO) FROM OPR_REGISTRO_ENTRADA eh WHERE eh.REGISTRO_ID = r.ID) AS editado, " +
                "(SELECT MAX(h.EDITADO_EM) FROM OPR_REGISTRO_ENTRADA_HIST h " +
                " JOIN OPR_REGISTRO_ENTRADA eh ON eh.ID = h.ENTRADA_ID " +
                " WHERE eh.REGISTRO_ID = r.ID) AS ultima_edicao_em",
                "FROM OPR_REGISTRO_AUDIO r JOIN CAD_SALA s ON s.ID = r.SALA_ID " +
                "JOIN (SELECT REGISTRO_ID, NOME_EVENTO, HORARIO_PAUTA, HORARIO_INICIO, HORARIO_TERMINO, COMISSAO_ID, " +
                "ROW_NUMBER() OVER (PARTITION BY REGISTRO_ID ORDER BY ORDEM DESC, SEQ DESC, ID DESC) AS RN " +
                "FROM OPR_REGISTRO_ENTRADA) ult ON ult.REGISTRO_ID = r.ID AND ult.RN = 1 " +
                "LEFT JOIN CAD_COMISSAO c ON c.ID = ult.COMISSAO_ID",
                "r.DATA", OP_SESS_SORT, List.of("s.NOME", "ult.NOME_EVENTO", "c.NOME"),
                Map.of("data", "r.DATA", "sala", "s.NOME"),
                Map.of("data", "date", "sala", "text"),
                page, limit, search, sort, dir, periodo, filters, "r.ID DESC", somenteDados);
    }

    // ══ Operações (entradas) ══════════════════════════════════

    private static final Map<String, String> ENT_SORT = new LinkedHashMap<>() {{
        put("data", "r.DATA"); put("sala", "s.NOME"); put("operador", "o.NOME_COMPLETO");
        put("inicio", "e.HORARIO_INICIO"); put("fim", "e.HORARIO_TERMINO");
    }};

    public PagedResult listOperacoesEntradas(int page, int limit, String search, String sort, String dir,
                                              Map<String, Object> periodo, Map<String, Object> filters) {
        return listOperacoesEntradas(page, limit, search, sort, dir, periodo, filters, false);
    }

    public PagedResult listOperacoesEntradas(int page, int limit, String search, String sort, String dir,
                                              Map<String, Object> periodo, Map<String, Object> filters, boolean somenteDados) {
        return DashboardQueryHelper.executePagedQuery(em,
                "e.ID, r.DATA AS data, s.NOME AS sala_nome, " +
                "r.NOME_DEMAIS_SALAS AS nome_demais_salas, " +
                "o.NOME_COMPLETO AS operador_nome, " +
                "e.TIPO_EVENTO, e.NOME_EVENTO, e.HORARIO_PAUTA, e.HORARIO_INICIO, e.HORARIO_TERMINO, " +
                "e.HOUVE_ANORMALIDADE, e.EDITADO, " +
                "(SELECT MAX(h.EDITADO_EM) FROM OPR_REGISTRO_ENTRADA_HIST h WHERE h.ENTRADA_ID = e.ID) AS ultima_edicao_em",
                "FROM OPR_REGISTRO_ENTRADA e " +
                "JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID " +
                "JOIN CAD_SALA s ON s.ID = r.SALA_ID " +
                "JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID",
                "r.DATA", ENT_SORT, List.of("s.NOME", "o.NOME_COMPLETO", "e.NOME_EVENTO"),
                Map.of("data", "r.DATA", "sala", "s.NOME", "operador", "o.NOME_COMPLETO", "tipo_evento", "e.TIPO_EVENTO"),
                Map.of("data", "date", "sala", "text", "operador", "text", "tipo_evento", "text"),
                page, limit, search, sort, dir, periodo, filters, null, somenteDados);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getEntradaDetalhe(long entradaId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT e.ID, e.REGISTRO_ID, r.DATA, s.NOME AS SALA_NOME, o.NOME_COMPLETO,
                       e.ORDEM, e.SEQ, e.NOME_EVENTO, e.HORARIO_PAUTA, e.HORARIO_INICIO,
                       e.HORARIO_TERMINO, e.TIPO_EVENTO, e.USB_01, e.USB_02, e.OBSERVACOES,
                       e.COMISSAO_ID, e.RESPONSAVEL_EVENTO, e.HORA_ENTRADA, e.HORA_SAIDA,
                       e.HOUVE_ANORMALIDADE, e.EDITADO, r.SALA_ID, c.NOME AS COMISSAO_NOME,
                       r.NOME_DEMAIS_SALAS, s.MULTI_OPERADOR
                FROM OPR_REGISTRO_ENTRADA e
                JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID
                JOIN CAD_SALA s ON s.ID = r.SALA_ID
                JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID
                LEFT JOIN CAD_COMISSAO c ON c.ID = e.COMISSAO_ID
                WHERE e.ID = ?1
                """).setParameter(1, entradaId).getResultList();
        if (rows.isEmpty()) return null;

        Object[] r = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("registro_id", num(r[1]));
        result.put("data", str(r[2])); result.put("sala_nome", str(r[3]));
        result.put("operador_nome", str(r[4])); result.put("ordem", num(r[5]));
        result.put("seq", num(r[6])); result.put("nome_evento", str(r[7]));
        result.put("horario_pauta", str(r[8])); result.put("horario_inicio", str(r[9]));
        result.put("horario_termino", str(r[10])); result.put("tipo_evento", str(r[11]));
        result.put("usb_01", str(r[12])); result.put("usb_02", str(r[13]));
        result.put("observacoes", str(r[14])); result.put("comissao_id", num(r[15]));
        result.put("responsavel_evento", str(r[16])); result.put("hora_entrada", str(r[17]));
        result.put("hora_saida", str(r[18])); result.put("houve_anormalidade", boolVal(r[19]));
        result.put("editado", boolVal(r[20])); result.put("sala_id", num(r[21]));
        result.put("comissao_nome", str(r[22]));
        result.put("nome_demais_salas", str(r[23]));

        // Detectar multi_operador (Plenário Principal) — Q14: flag da sala já vem no JOIN (col 24),
        // sem SELECT extra em CAD_SALA.
        boolean isMultiOp = boolVal(r[24]);
        result.put("multi_operador", isMultiOp);

        // Operadores da junction table (Plenário Principal)
        @SuppressWarnings("unchecked")
        List<Object> opRows = em.createNativeQuery("""
                SELECT o.NOME_COMPLETO
                FROM OPR_ENTRADA_OPERADOR eo
                JOIN PES_OPERADOR o ON o.ID = eo.OPERADOR_ID
                WHERE eo.ENTRADA_ID = ?1
                ORDER BY o.NOME_COMPLETO
                """).setParameter(1, entradaId).getResultList();
        if (!opRows.isEmpty()) {
            result.put("operadores_sessao", opRows.stream().map(o -> o != null ? o.toString() : "").toList());
        }

        // Suspensões
        @SuppressWarnings("unchecked")
        List<Object[]> suspRows = em.createNativeQuery("""
                SELECT HORA_SUSPENSAO, HORA_REABERTURA, ORDEM
                FROM OPR_SUSPENSAO WHERE ENTRADA_ID = ?1 ORDER BY ORDEM
                """).setParameter(1, entradaId).getResultList();
        if (!suspRows.isEmpty()) {
            List<Map<String, Object>> suspList = new ArrayList<>();
            for (Object[] sr : suspRows) {
                suspList.add(Map.of("hora_suspensao", str(sr[0]), "hora_reabertura", str(sr[1]), "ordem", num(sr[2])));
            }
            result.put("suspensoes", suspList);
        }

        return result;
    }

    // ══ Entradas de uma sessão ═════════════════════════════════

    public Map<String, Object> listEntradasDeSessao(long registroId) {
        return listEntradasDeSessaoEmLote(List.of(registroId)).get(registroId);
    }

    /**
     * Versão em lote do listEntradasDeSessao (Q2/Q3), em 3 níveis de IN por blocos:
     * (1) flag multi-operador da sala por registro; (2) entradas por REGISTRO_ID, mantendo a
     * subquery escalar de última edição (MAX de vazio = NULL) e a ordem intra-registro
     * (ORDEM, SEQ); (3) operadores da junction por ENTRADA_ID, só para registros do Plenário
     * Principal — ordem eo.ID, contrato DESTE caminho (getEntradaDetalhe ordena por
     * NOME_COMPLETO; não unificar). Todo registroId pedido tem resultado: inexistente →
     * is_plenario_principal=false e entradas=[], como no caminho single.
     */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> listEntradasDeSessaoEmLote(Collection<Long> registroIds) {
        Set<Long> ids = new LinkedHashSet<>(registroIds);

        // Nível 1: flag multi_operador (Plenário Principal) por registro
        Map<Long, Boolean> ppPorRegistro = new LinkedHashMap<>();
        for (List<Long> bloco : partitionForIn(ids)) {
            Query q = em.createNativeQuery(
                    "SELECT r.ID, s.MULTI_OPERADOR " +
                    "FROM OPR_REGISTRO_AUDIO r JOIN CAD_SALA s ON s.ID = r.SALA_ID " +
                    "WHERE r.ID IN (" + inPlaceholders(bloco.size()) + ")");
            bindList(q, bloco, 1);
            for (Object[] r : (List<Object[]>) q.getResultList()) {
                ppPorRegistro.put(num(r[0]), boolVal(r[1]));
            }
        }

        // Nível 2: entradas por registro (ordem intra-registro ORDEM, SEQ — como no single)
        Map<Long, List<Object[]>> entradasPorRegistro = new LinkedHashMap<>();
        List<Long> entradaIdsPP = new ArrayList<>();
        for (List<Long> bloco : partitionForIn(ids)) {
            Query q = em.createNativeQuery(
                    "SELECT e.REGISTRO_ID, e.ID, e.ORDEM, o.NOME_COMPLETO, e.TIPO_EVENTO, " +
                    "       e.NOME_EVENTO, e.HORARIO_PAUTA, e.HORARIO_INICIO, e.HORARIO_TERMINO, " +
                    "       e.HOUVE_ANORMALIDADE, e.HORA_ENTRADA, e.HORA_SAIDA, e.OBSERVACOES, " +
                    "       e.EDITADO, " +
                    "       (SELECT MAX(h.EDITADO_EM) FROM OPR_REGISTRO_ENTRADA_HIST h WHERE h.ENTRADA_ID = e.ID) AS ULTIMA_EDICAO_EM " +
                    "FROM OPR_REGISTRO_ENTRADA e " +
                    "JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID " +
                    "WHERE e.REGISTRO_ID IN (" + inPlaceholders(bloco.size()) + ") " +
                    "ORDER BY e.REGISTRO_ID, e.ORDEM ASC, e.SEQ ASC");
            bindList(q, bloco, 1);
            for (Object[] r : (List<Object[]>) q.getResultList()) {
                Long registroId = num(r[0]);
                entradasPorRegistro.computeIfAbsent(registroId, k -> new ArrayList<>()).add(r);
                if (Boolean.TRUE.equals(ppPorRegistro.get(registroId))) entradaIdsPP.add(num(r[1]));
            }
        }

        // Nível 3: operadores da junction, apenas para entradas do Plenário Principal
        Map<Long, List<String>> operadoresPorEntrada = fetchOperadoresPorEntrada(entradaIdsPP, "o.NOME_COMPLETO");

        Map<Long, Map<String, Object>> porRegistro = new LinkedHashMap<>();
        for (Long registroId : ids) {
            boolean isPlenarioPrincipal = Boolean.TRUE.equals(ppPorRegistro.get(registroId));
            List<Map<String, Object>> entradas = new ArrayList<>();
            for (Object[] r : entradasPorRegistro.getOrDefault(registroId, List.of())) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (isPlenarioPrincipal) {
                    // Plenário Principal: preenchido_por, evento, anormalidade + lista de operadores
                    Long entradaId = num(r[1]);
                    List<String> operadores = operadoresPorEntrada.getOrDefault(entradaId, List.of())
                            .stream().map(o -> o != null ? o : "").toList();
                    m.put("id", entradaId);
                    m.put("preenchido_por", str(r[3]) != null ? str(r[3]) : "");
                    m.put("evento", str(r[5]) != null ? str(r[5]) : "");
                    m.put("anormalidade", boolVal(r[9]));
                    m.put("operadores", operadores);
                    m.put("editado", boolVal(r[13]));
                    m.put("ultima_edicao_em", str(r[14]));
                } else {
                    // Plenários numerados: Nº, Operador, Início Operação, Fim Operação, Observações, Anom?
                    m.put("id", num(r[1]));
                    m.put("ordem", num(r[2]));
                    m.put("operador", str(r[3]) != null ? str(r[3]) : "");
                    m.put("hora_entrada", str(r[10]) != null ? str(r[10]) : "");
                    m.put("hora_saida", str(r[11]) != null ? str(r[11]) : "");
                    m.put("observacoes", str(r[12]) != null ? str(r[12]) : "");
                    m.put("anormalidade", boolVal(r[9]));
                    m.put("editado", boolVal(r[13]));
                    m.put("ultima_edicao_em", str(r[14]));
                }
                entradas.add(m);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("is_plenario_principal", isPlenarioPrincipal);
            result.put("entradas", entradas);
            porRegistro.put(registroId, result);
        }
        return porRegistro;
    }

    // ══ Anormalidades ═════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSalasComAnormalidades(String search) {
        String sql = """
                SELECT DISTINCT s.ID, s.NOME
                FROM OPR_ANORMALIDADE a
                JOIN CAD_SALA s ON s.ID = a.SALA_ID
                """ + (search != null && !search.isBlank() ? "WHERE UPPER(s.NOME) LIKE ?1" : "") +
                " ORDER BY s.NOME";
        Query q = em.createNativeQuery(sql);
        if (search != null && !search.isBlank()) q.setParameter(1, "%" + search.toUpperCase() + "%");
        List<Object[]> rows = q.getResultList();
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).intValue());
            m.put("nome", r[1].toString());
            return m;
        }).toList();
    }

    private static final Map<String, String> ANOM_SORT = new LinkedHashMap<>() {{
        put("data", "a.DATA"); put("sala", "s.NOME"); put("nome_evento", "a.NOME_EVENTO");
    }};

    public PagedResult listAnormalidades(int page, int limit, String search, String sort, String dir,
                                          Map<String, Object> periodo, Map<String, Object> filters, Integer salaId) {
        return listAnormalidades(page, limit, search, sort, dir, periodo, filters, salaId, false);
    }

    public PagedResult listAnormalidades(int page, int limit, String search, String sort, String dir,
                                          Map<String, Object> periodo, Map<String, Object> filters, Integer salaId,
                                          boolean somenteDados) {
        String fromJoins = "FROM OPR_ANORMALIDADE a " +
                "JOIN CAD_SALA s ON s.ID = a.SALA_ID " +
                "LEFT JOIN OPR_REGISTRO_AUDIO ra ON ra.ID = a.REGISTRO_ID " +
                "LEFT JOIN PES_OPERADOR o ON o.ID = a.CRIADO_POR";
        if (salaId != null) {
            fromJoins += " AND a.SALA_ID = " + salaId;  // safe: int value
        }

        return DashboardQueryHelper.executePagedQuery(em,
                "a.ID, a.DATA AS data, s.NOME AS sala_nome, " +
                "ra.NOME_DEMAIS_SALAS AS nome_demais_salas, " +
                "a.NOME_EVENTO, " +
                "o.NOME_COMPLETO AS registrado_por, a.DESCRICAO_ANORMALIDADE, " +
                "a.DATA_SOLUCAO, a.HOUVE_PREJUIZO, a.HOUVE_RECLAMACAO, a.RESOLVIDA_PELO_OPERADOR",
                fromJoins, "a.DATA", ANOM_SORT, List.of("s.NOME", "a.NOME_EVENTO", "o.NOME_COMPLETO"),
                Map.of("data", "a.DATA", "sala", "s.NOME", "nome_evento", "a.NOME_EVENTO"),
                Map.of("data", "date", "sala", "text", "nome_evento", "text"),
                page, limit, search, sort, dir, periodo, filters, "a.ID DESC", somenteDados);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAnormalidadeDetalhe(long anomId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.ID, a.REGISTRO_ID, a.ENTRADA_ID, a.DATA, a.SALA_ID, s.NOME AS SALA_NOME,
                       a.NOME_EVENTO, a.HORA_INICIO_ANORMALIDADE, a.DESCRICAO_ANORMALIDADE,
                       a.HOUVE_PREJUIZO, a.DESCRICAO_PREJUIZO,
                       a.HOUVE_RECLAMACAO, a.AUTORES_CONTEUDO_RECLAMACAO,
                       a.ACIONOU_MANUTENCAO, a.HORA_ACIONAMENTO_MANUTENCAO,
                       a.RESOLVIDA_PELO_OPERADOR, a.PROCEDIMENTOS_ADOTADOS,
                       a.DATA_SOLUCAO, a.HORA_SOLUCAO, a.RESPONSAVEL_EVENTO,
                       o.NOME_COMPLETO AS CRIADO_POR_NOME,
                       adm.OBSERVACAO_SUPERVISOR, adm.OBSERVACAO_CHEFE
                FROM OPR_ANORMALIDADE a
                JOIN CAD_SALA s ON s.ID = a.SALA_ID
                LEFT JOIN PES_OPERADOR o ON o.ID = a.CRIADO_POR
                LEFT JOIN OPR_ANORMALIDADE_ADMIN adm ON adm.REGISTRO_ANORMALIDADE_ID = a.ID
                WHERE a.ID = ?1
                """).setParameter(1, anomId).getResultList();
        if (rows.isEmpty()) return null;

        Object[] r = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("registro_id", num(r[1]));
        result.put("entrada_id", num(r[2])); result.put("data", str(r[3]));
        result.put("sala_id", num(r[4])); result.put("sala_nome", str(r[5]));
        AnormalidadeService.putCamposAnormalidade(result, r, 6);
        result.put("criado_por_nome", str(r[20]));
        result.put("observacao_supervisor", str(r[21])); result.put("observacao_chefe", str(r[22]));
        result.put("anormalidade_solucionada", str(r[17]) != null || str(r[18]) != null);
        return result;
    }

    // ══ Observações de anormalidade ═══════════════════════════

    @Transactional
    public void salvarObservacaoSupervisor(long anomId, String observacao, String userId) {
        salvarObservacao("OBSERVACAO_SUPERVISOR", anomId, observacao, userId);
    }

    @Transactional
    public void salvarObservacaoChefe(long anomId, String observacao, String userId) {
        salvarObservacao("OBSERVACAO_CHEFE", anomId, observacao, userId);
    }

    private void salvarObservacao(String coluna, long anomId, String observacao, String userId) {
        upsertAnormalidadeAdmin(anomId, userId);
        em.createNativeQuery(String.format(
                "UPDATE OPR_ANORMALIDADE_ADMIN SET %s = ?1, ATUALIZADO_POR = ?2, ATUALIZADO_EM = SYSTIMESTAMP WHERE REGISTRO_ANORMALIDADE_ID = ?3", coluna))
                .setParameter(1, observacao).setParameter(2, userId).setParameter(3, anomId).executeUpdate();
    }

    private void upsertAnormalidadeAdmin(long anomId, String userId) {
        int exists = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM OPR_ANORMALIDADE_ADMIN WHERE REGISTRO_ANORMALIDADE_ID = ?1")
                .setParameter(1, anomId).getSingleResult()).intValue();
        if (exists == 0) {
            RegistroAnormalidadeAdmin admin = new RegistroAnormalidadeAdmin();
            admin.setRegistroAnormalidadeId(anomId);
            admin.setCriadoPor(userId);
            em.persist(admin);
            em.flush();
        }
    }

    // ══ RDS (Registro Diário de Sessões) ═════════════════════════

    /** Lista anos distintos com registros de operação. */
    @SuppressWarnings("unchecked")
    public List<Integer> listRdsAnos() {
        String sql = "SELECT DISTINCT EXTRACT(YEAR FROM DATA) AS ANO FROM OPR_REGISTRO_AUDIO ORDER BY ANO ASC";
        List<Object> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream().map(o -> ((Number) o).intValue()).toList();
    }

    /** Lista meses distintos para um ano. */
    @SuppressWarnings("unchecked")
    public List<Integer> listRdsMeses(int ano) {
        String sql = """
                SELECT DISTINCT EXTRACT(MONTH FROM DATA) AS MES
                FROM OPR_REGISTRO_AUDIO
                WHERE EXTRACT(YEAR FROM DATA) = ?1
                ORDER BY MES ASC
                """;
        List<Object> rows = em.createNativeQuery(sql).setParameter(1, ano).getResultList();
        return rows.stream().map(o -> ((Number) o).intValue()).toList();
    }

    /** Busca dados brutos para geração do RDS XLSX. Equivale a rds_db.fetch_rds_rows(). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchRdsRows(int ano, int mes) {
        java.time.LocalDate start = java.time.LocalDate.of(ano, mes, 1);
        java.time.LocalDate end = mes == 12 ? java.time.LocalDate.of(ano + 1, 1, 1) : java.time.LocalDate.of(ano, mes + 1, 1);

        String sql = """
                SELECT
                    ra.ID                    AS REGISTRO_ID,
                    ra.DATA                  AS DATA,
                    ra.EM_ABERTO             AS EM_ABERTO,
                    s.NOME                   AS SALA_NOME,
                    ra.NOME_DEMAIS_SALAS     AS NOME_DEMAIS_SALAS,
                    rop.ID                   AS ENTRADA_ID,
                    rop.ORDEM                AS ORDEM,
                    rop.SEQ                  AS SEQ,
                    rop.NOME_EVENTO          AS NOME_EVENTO,
                    rop.HORARIO_PAUTA        AS HORARIO_PAUTA,
                    rop.HORARIO_INICIO       AS HORARIO_INICIO,
                    rop.HORARIO_TERMINO      AS HORARIO_TERMINO,
                    op.NOME_EXIBICAO         AS OPERADOR_NOME_EXIBICAO,
                    c.NOME                   AS COMISSAO_NOME
                FROM OPR_REGISTRO_AUDIO ra
                JOIN CAD_SALA s ON s.ID = ra.SALA_ID
                JOIN OPR_REGISTRO_ENTRADA rop ON rop.REGISTRO_ID = ra.ID
                JOIN PES_OPERADOR op ON op.ID = rop.OPERADOR_ID
                LEFT JOIN CAD_COMISSAO c ON c.ID = rop.COMISSAO_ID
                WHERE ra.DATA >= ?1 AND ra.DATA < ?2
                ORDER BY ra.DATA ASC, rop.HORARIO_PAUTA ASC NULLS LAST,
                         rop.HORARIO_INICIO ASC NULLS LAST, s.NOME ASC,
                         ra.ID ASC, rop.ORDEM ASC, rop.SEQ ASC, rop.ID ASC
                """;

        Query q = em.createNativeQuery(sql)
                .setParameter(1, java.sql.Date.valueOf(start))
                .setParameter(2, java.sql.Date.valueOf(end));

        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> multiOpEntradaIds = new HashSet<>();
        for (Object[] r : rows) {
            Map<String, Object> m = mapRdsRow(r);
            result.add(m);

            if ("Plenário Principal".equals(str(r[3])) && r[5] != null) {
                multiOpEntradaIds.add(((Number) r[5]).longValue());
            }
        }

        // Para Plenário Principal: buscar operadores da junction table OPR_ENTRADA_OPERADOR
        if (!multiOpEntradaIds.isEmpty()) {
            Map<Long, List<String>> opsByEntrada = fetchOperadoresPorEntrada(multiOpEntradaIds, "o.NOME_EXIBICAO");
            injetarMultiOpNames(result, opsByEntrada);
        }

        return result;
    }

    private Map<String, Object> mapRdsRow(Object[] r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registro_id", r[0]);
        m.put("data", rdsDataToLocalDate(r[1]));
        m.put("em_aberto", r[2] != null && ((Number) r[2]).intValue() == 1);
        m.put("sala_nome", str(r[3]));
        m.put("nome_demais_salas", str(r[4]));
        m.put("entrada_id", r[5]);
        m.put("ordem", r[6]);
        m.put("seq", r[7]);
        m.put("nome_evento", str(r[8]));
        m.put("horario_pauta", str(r[9]));
        m.put("horario_inicio", str(r[10]));
        m.put("horario_termino", str(r[11]));
        m.put("operador_nome_exibicao", str(r[12]));
        m.put("comissao_nome", str(r[13]));
        return m;
    }

    private Object rdsDataToLocalDate(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (v instanceof java.sql.Date sd) return sd.toLocalDate();
        return v;
    }

    /**
     * Nomes dos operadores da junction OPR_ENTRADA_OPERADOR agrupados por entrada
     * (ordem eo.ENTRADA_ID, eo.ID), em blocos IN. A coluna projetada é constante interna
     * dos callers (RDS usa o.NOME_EXIBICAO; entradas de sessão usam o.NOME_COMPLETO).
     */
    @SuppressWarnings("unchecked")
    private Map<Long, List<String>> fetchOperadoresPorEntrada(java.util.Collection<Long> entradaIds, String colunaNome) {
        Map<Long, List<String>> opsByEntrada = new LinkedHashMap<>();
        for (List<Long> bloco : partitionForIn(entradaIds)) {
            // IN clause com parâmetros posicionais (native query não expande coleções)
            Query opQuery = em.createNativeQuery(
                    "SELECT eo.ENTRADA_ID, " + colunaNome + " " +
                    "FROM OPR_ENTRADA_OPERADOR eo " +
                    "JOIN PES_OPERADOR o ON o.ID = eo.OPERADOR_ID " +
                    "WHERE eo.ENTRADA_ID IN (" + inPlaceholders(bloco.size()) + ") " +
                    "ORDER BY eo.ENTRADA_ID, eo.ID");
            bindList(opQuery, bloco, 1);
            for (Object[] or2 : (List<Object[]>) opQuery.getResultList()) {
                Long eid = ((Number) or2[0]).longValue();
                opsByEntrada.computeIfAbsent(eid, k -> new ArrayList<>()).add(str(or2[1]));
            }
        }
        return opsByEntrada;
    }

    private void injetarMultiOpNames(List<Map<String, Object>> result, Map<Long, List<String>> opsByEntrada) {
        // Injetar nomes na lista de resultados
        for (Map<String, Object> m : result) {
            Object eidObj = m.get("entrada_id");
            if (eidObj == null) continue;
            Long eid = ((Number) eidObj).longValue();
            List<String> ops = opsByEntrada.get(eid);
            if (ops != null && !ops.isEmpty()) {
                m.put("multi_op_names", ops);
            }
        }
    }

    // ══ Histórico de Checklists ═══════════════════════════════

    public List<Map<String, Object>> listChecklistHistorico(long checklistId) {
        return listHistorico("FRM_CHECKLIST_HISTORICO", "CHECKLIST_ID", checklistId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listHistorico(String tabela, String colunaFk, long id) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT h.ID, h.EDITADO_POR, h.EDITADO_EM, " +
                "       COALESCE(a.NOME_COMPLETO, o.NOME_COMPLETO) AS EDITADO_POR_NOME " +
                "FROM " + tabela + " h " +
                "LEFT JOIN PES_OPERADOR o ON o.ID = h.EDITADO_POR " +
                "LEFT JOIN PES_ADMINISTRADOR a ON a.ID = h.EDITADO_POR " +
                "WHERE h." + colunaFk + " = ?1 " +
                "ORDER BY h.EDITADO_EM ASC, h.ID ASC")
                .setParameter(1, id).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        int n = 1;
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("historico_id", num(r[0]));
            m.put("numero_versao", n++);
            m.put("editado_por", str(r[1]));
            m.put("editado_em", str(r[2]));
            m.put("editado_por_nome", str(r[3]));
            result.add(m);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getChecklistVersao(long historicoId) {
        List<Object[]> histRows = em.createNativeQuery("""
                SELECT CHECKLIST_ID, SNAPSHOT, EDITADO_POR, EDITADO_EM
                FROM FRM_CHECKLIST_HISTORICO WHERE ID = ?1
                """).setParameter(1, historicoId).getResultList();
        if (histRows.isEmpty()) return null;
        Object[] hist = histRows.get(0);
        long checklistId = num(hist[0]);
        String snapJson = str(hist[1]);
        String editadoPor = str(hist[2]);
        String editadoEm = str(hist[3]);

        Map<String, Object> snap = parseSnapshot(snapJson);
        Map<String, Object> header = (Map<String, Object>) snap.getOrDefault("header", Map.of());

        // Dados imutáveis do checklist (criado_por, operador_nome)
        List<Object[]> baseRows = em.createNativeQuery("""
                SELECT o.NOME_COMPLETO, c.CRIADO_POR
                FROM FRM_CHECKLIST c
                LEFT JOIN PES_OPERADOR o ON o.ID = c.CRIADO_POR
                WHERE c.ID = ?1
                """).setParameter(1, checklistId).getResultList();
        String operadorNome = !baseRows.isEmpty() ? str(baseRows.get(0)[0]) : null;
        String criadoPor    = !baseRows.isEmpty() ? str(baseRows.get(0)[1]) : null;

        // Resolver sala_nome a partir do sala_id do snapshot
        Long salaId = toLong(header.get("sala_id"));
        String salaNome = resolverNomeSala(salaId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", checklistId);
        result.put("data_operacao", header.get("data_operacao"));
        result.put("sala_id", salaId);
        result.put("sala_nome", salaNome);
        result.put("turno", header.get("turno"));
        result.put("hora_inicio_testes", header.get("hora_inicio_testes"));
        result.put("hora_termino_testes", header.get("hora_termino_testes"));
        result.put("observacoes", header.get("observacoes"));
        result.put("usb_01", header.get("usb_01"));
        result.put("usb_02", header.get("usb_02"));
        result.put("editado", true);
        result.put("operador_nome", operadorNome);
        result.put("criado_por", criadoPor);

        // Itens (resolver item_nome e tipo_widget em lote)
        List<Map<String, Object>> snapItens = (List<Map<String, Object>>) snap.getOrDefault("itens", List.of());
        List<Map<String, Object>> itensList = new ArrayList<>();
        if (!snapItens.isEmpty()) {
            Set<Long> tipoIds = new HashSet<>();
            for (Map<String, Object> si : snapItens) {
                Long id = toLong(si.get("item_tipo_id"));
                if (id != null) tipoIds.add(id);
            }
            Map<Long, Object[]> tipoInfo = new HashMap<>();
            if (!tipoIds.isEmpty()) {
                Query tipoQ = em.createNativeQuery(
                        "SELECT ID, NOME, TIPO_WIDGET FROM FRM_CHECKLIST_ITEM_TIPO WHERE ID IN (" + inPlaceholders(tipoIds.size()) + ")");
                bindList(tipoQ, tipoIds, 1);
                List<Object[]> tipoRows = tipoQ.getResultList();
                for (Object[] tr : tipoRows) tipoInfo.put(((Number) tr[0]).longValue(), tr);
            }
            for (Map<String, Object> si : snapItens) {
                Long tipoId = toLong(si.get("item_tipo_id"));
                Object[] ti = tipoId != null ? tipoInfo.get(tipoId) : null;
                String tipoWidget = ti != null && ti[2] != null ? str(ti[2]) : "radio";
                String itemNome = ti != null ? str(ti[1]) : null;

                itensList.add(DashboardQueryHelper.checklistItemMap(si.get("resposta_id"), tipoId,
                        itemNome, si.get("status"), si.get("descricao_falha"), si.get("valor_texto"),
                        false, tipoWidget));
            }
        }
        result.put("itens", itensList);

        // Operadores (Plenário Principal): snapshot tem IDs, detalhe mostra nomes
        List<String> cabineIds = (List<String>) snap.get("operadores_cabine");
        List<String> plenarioIds = (List<String>) snap.get("operadores_plenario");
        if (cabineIds != null || plenarioIds != null) {
            Set<String> allIds = new HashSet<>();
            if (cabineIds != null) allIds.addAll(cabineIds);
            if (plenarioIds != null) allIds.addAll(plenarioIds);
            Map<String, String> nomes = resolverNomesOperadores(allIds);
            if (cabineIds != null) {
                result.put("operadores_cabine",
                        cabineIds.stream().map(id -> nomes.getOrDefault(id, "?")).toList());
            }
            if (plenarioIds != null) {
                result.put("operadores_plenario",
                        plenarioIds.stream().map(id -> nomes.getOrDefault(id, "?")).toList());
            }
        }

        result.put("versao", versaoMeta(historicoId, editadoPor, editadoEm));
        return result;
    }

    // ══ Histórico de Entradas (ROA) ═══════════════════════════

    public List<Map<String, Object>> listEntradaHistorico(long entradaId) {
        return listHistorico("OPR_REGISTRO_ENTRADA_HIST", "ENTRADA_ID", entradaId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getEntradaVersao(long historicoId) {
        List<Object[]> histRows = em.createNativeQuery("""
                SELECT ENTRADA_ID, SNAPSHOT, EDITADO_POR, EDITADO_EM
                FROM OPR_REGISTRO_ENTRADA_HIST WHERE ID = ?1
                """).setParameter(1, historicoId).getResultList();
        if (histRows.isEmpty()) return null;
        Object[] hist = histRows.get(0);
        long entradaId = num(hist[0]);
        String snapJson = str(hist[1]);
        String editadoPor = str(hist[2]);
        String editadoEm = str(hist[3]);

        Map<String, Object> snap = parseSnapshot(snapJson);

        // Dados imutáveis ou auxiliares (registro, data, operador, ordem, seq, multi_operador)
        List<Object[]> baseRows = em.createNativeQuery("""
                SELECT e.REGISTRO_ID, r.DATA, o.NOME_COMPLETO, e.ORDEM, e.SEQ,
                       r.NOME_DEMAIS_SALAS, s.MULTI_OPERADOR
                FROM OPR_REGISTRO_ENTRADA e
                JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID
                JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID
                JOIN CAD_SALA s ON s.ID = r.SALA_ID
                WHERE e.ID = ?1
                """).setParameter(1, entradaId).getResultList();
        if (baseRows.isEmpty()) return null;
        Object[] base = baseRows.get(0);

        Long salaId = toLong(snap.get("sala_id"));
        String salaNome = resolverNomeSala(salaId);
        Long comissaoId = toLong(snap.get("comissao_id"));
        String comissaoNome = resolverNomeComissao(comissaoId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entradaId);
        result.put("registro_id", num(base[0]));
        result.put("data", str(base[1]));
        result.put("sala_nome", salaNome);
        result.put("operador_nome", str(base[2]));
        result.put("ordem", num(base[3]));
        result.put("seq", num(base[4]));
        result.put("nome_evento", snap.get("nome_evento"));
        result.put("horario_pauta", snap.get("horario_pauta"));
        result.put("horario_inicio", snap.get("horario_inicio"));
        result.put("horario_termino", snap.get("horario_termino"));
        result.put("tipo_evento", snap.get("tipo_evento"));
        result.put("usb_01", snap.get("usb_01"));
        result.put("usb_02", snap.get("usb_02"));
        result.put("observacoes", snap.get("observacoes"));
        result.put("comissao_id", comissaoId);
        result.put("responsavel_evento", snap.get("responsavel_evento"));
        result.put("hora_entrada", snap.get("hora_entrada"));
        result.put("hora_saida", snap.get("hora_saida"));
        result.put("houve_anormalidade", Boolean.TRUE.equals(snap.get("houve_anormalidade")));
        result.put("editado", true);
        result.put("sala_id", salaId);
        result.put("comissao_nome", comissaoNome);
        result.put("nome_demais_salas", str(base[5]));
        result.put("multi_operador", boolVal(base[6]));

        Object suspensoesSnap = snap.get("suspensoes");
        if (suspensoesSnap instanceof List<?> suspList && !suspList.isEmpty()) {
            result.put("suspensoes", suspList);
        }

        result.put("versao", versaoMeta(historicoId, editadoPor, editadoEm));
        return result;
    }

    // ── Helpers ──

    /**
     * Divide as rows da junction de operadores por papel (CABINE vs demais → plenário),
     * extraindo a coluna idxValor (nome ou id). Compartilhado com o detalhe do operador;
     * cada caller mantém seu gate e a decisão de emitir as chaves no payload.
     */
    static Map<String, List<String>> splitOperadoresPorPapel(List<Object[]> rows, int idxPapel, int idxValor) {
        List<String> cabine = new ArrayList<>(), plenario = new ArrayList<>();
        for (Object[] op : rows) {
            if ("CABINE".equals(str(op[idxPapel]))) cabine.add(str(op[idxValor]));
            else plenario.add(str(op[idxValor]));
        }
        Map<String, List<String>> porPapel = new LinkedHashMap<>();
        porPapel.put("cabine", cabine);
        porPapel.put("plenario", plenario);
        return porPapel;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    private Map<String, Object> parseSnapshot(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ServiceValidationException("Snapshot do histórico inválido.");
        }
    }

    @SuppressWarnings("unchecked")
    private String resolverNomeSala(Long salaId) {
        if (salaId == null) return null;
        List<Object> rows = em.createNativeQuery("SELECT NOME FROM CAD_SALA WHERE ID = ?1")
                .setParameter(1, salaId).getResultList();
        return rows.isEmpty() ? null : str(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private String resolverNomeComissao(Long comissaoId) {
        if (comissaoId == null) return null;
        List<Object> rows = em.createNativeQuery("SELECT NOME FROM CAD_COMISSAO WHERE ID = ?1")
                .setParameter(1, comissaoId).getResultList();
        return rows.isEmpty() ? null : str(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private String resolverNomeUsuario(String userId) {
        if (userId == null || userId.isBlank()) return null;
        List<Object> rows = em.createNativeQuery("""
                SELECT COALESCE(
                    (SELECT NOME_COMPLETO FROM PES_ADMINISTRADOR WHERE ID = ?1),
                    (SELECT NOME_COMPLETO FROM PES_OPERADOR WHERE ID = ?1)
                ) FROM dual
                """).setParameter(1, userId).getResultList();
        return rows.isEmpty() ? null : str(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> resolverNomesOperadores(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Query q = em.createNativeQuery(
                "SELECT ID, NOME_COMPLETO FROM PES_OPERADOR WHERE ID IN (" + inPlaceholders(ids.size()) + ")");
        bindList(q, ids, 1);
        List<Object[]> rows = q.getResultList();
        Map<String, String> result = new HashMap<>();
        for (Object[] r : rows) result.put(str(r[0]), str(r[1]));
        return result;
    }

    private Map<String, Object> versaoMeta(long historicoId, String editadoPor, String editadoEm) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("historico_id", historicoId);
        v.put("editado_por", editadoPor);
        v.put("editado_em", editadoEm);
        v.put("editado_por_nome", resolverNomeUsuario(editadoPor));
        return v;
    }

    // ══ Enriquecimento de rows para relatórios (PDF/DOCX) ═════
    // Renomeia campos das listagens para as chaves esperadas pelos geradores,
    // deriva campos calculados e recarrega dados por linha (recarga intencional).

    /** Rows do relatório de checklists: operador/início/término/duração + itens em lote (Q1). */
    public List<Map<String, Object>> enriquecerRowsParaRelatorioChecklists(List<Map<String, Object>> rawRows) {
        Set<Long> checklistIds = new LinkedHashSet<>();
        for (Map<String, Object> r : rawRows) {
            Object id = r.get("id");
            if (id != null) checklistIds.add(((Number) id).longValue());
        }
        Map<Long, List<Map<String, Object>>> itensPorChecklist = fetchItensPorChecklist(checklistIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("operador", r.getOrDefault("operador_nome", "--"));
            m.put("inicio", r.getOrDefault("hora_inicio_testes", ""));
            m.put("termino", r.getOrDefault("hora_termino_testes", ""));
            // Calcular duração
            String ini = String.valueOf(r.getOrDefault("hora_inicio_testes", ""));
            String ter = String.valueOf(r.getOrDefault("hora_termino_testes", ""));
            m.put("duracao", calcDuracao(ini, ter));
            // Itens do lote; checklist fora do mapa (apagado entre listagem e lote) → chave ausente,
            // como no caminho single quando o detalhe retornava null
            Object id = r.get("id");
            if (id != null) {
                List<Map<String, Object>> itens = itensPorChecklist.get(((Number) id).longValue());
                if (itens != null) m.put("itens", itens);
            }
            rows.add(m);
        }
        return rows;
    }

    /**
     * Itens (respostas) de vários checklists numa só leitura por bloco IN (Q1) — mesma projeção,
     * conversões e ordem intra-checklist da query single do detalhe (sc.ORDEM, t.ID), com a sala
     * resolvida por checklist (sc.SALA_ID = c.SALA_ID). Parte de FRM_CHECKLIST com LEFT JOIN para
     * reproduzir o single: checklist sem respostas → lista vazia; inexistente → fora do mapa.
     */
    @SuppressWarnings("unchecked")
    private Map<Long, List<Map<String, Object>>> fetchItensPorChecklist(Collection<Long> checklistIds) {
        Map<Long, List<Map<String, Object>>> porChecklist = new LinkedHashMap<>();
        for (List<Long> bloco : partitionForIn(checklistIds)) {
            Query q = em.createNativeQuery(
                    "SELECT c.ID AS CHECKLIST_ID, r.ID, r.ITEM_TIPO_ID, t.NOME AS ITEM_NOME, r.STATUS, " +
                    "       r.DESCRICAO_FALHA, r.VALOR_TEXTO, r.EDITADO, t.TIPO_WIDGET " +
                    "FROM FRM_CHECKLIST c " +
                    "LEFT JOIN FRM_CHECKLIST_RESPOSTA r ON r.CHECKLIST_ID = c.ID " +
                    "LEFT JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID " +
                    "LEFT JOIN FRM_CHECKLIST_SALA_CONFIG sc ON sc.ITEM_TIPO_ID = t.ID AND sc.SALA_ID = c.SALA_ID " +
                    "WHERE c.ID IN (" + inPlaceholders(bloco.size()) + ") " +
                    "ORDER BY c.ID, sc.ORDEM ASC, t.ID ASC");
            bindList(q, bloco, 1);
            for (Object[] it : (List<Object[]>) q.getResultList()) {
                List<Map<String, Object>> itens = porChecklist.computeIfAbsent(num(it[0]), k -> new ArrayList<>());
                if (it[1] == null) continue; // checklist existente sem respostas: fica no mapa com lista vazia
                itens.add(DashboardQueryHelper.checklistItemMap(num(it[1]), num(it[2]),
                        str(it[3]), str(it[4]), str(it[5]), str(it[6]), boolVal(it[7]),
                        str(it[8]) != null ? str(it[8]) : "radio"));
            }
        }
        return porChecklist;
    }

    /** Rows do relatório de operações (sessões): sala/verificação/evento_display + entradas em lote (Q2). */
    public List<Map<String, Object>> enriquecerRowsParaRelatorioOperacoes(List<Map<String, Object>> rawRows) {
        Set<Long> registroIds = new LinkedHashSet<>();
        for (Map<String, Object> r : rawRows) {
            Object id = r.get("id");
            if (id != null) registroIds.add(((Number) id).longValue());
        }
        Map<Long, Map<String, Object>> sessoesPorRegistro = listEntradasDeSessaoEmLote(registroIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("sala", r.getOrDefault("sala_nome", "--"));
            Object chk = r.get("checklist_do_dia_ok");
            m.put("verificacao", (chk != null && ((Number) chk).intValue() == 1) ? "Realizado" : "Não Realizado");
            // Evento formatado (sigla comissão + nome_evento)
            String comNome = r.get("comissao_nome") != null ? r.get("comissao_nome").toString() : "";
            String ultEvento = r.get("ultimo_evento") != null ? r.get("ultimo_evento").toString() : "";
            if (!comNome.isEmpty() && !ultEvento.isEmpty()) {
                int idx2 = comNome.indexOf(" - ");
                String sigla = idx2 >= 0 ? comNome.substring(0, idx2).trim() : comNome.trim();
                m.put("evento_display", sigla + " - " + ultEvento);
            } else {
                m.put("evento_display", ultEvento);
            }
            Object id = r.get("id");
            if (id != null) {
                Map<String, Object> sessaoData = sessoesPorRegistro.get(((Number) id).longValue());
                m.put("entradas", sessaoData.get("entradas"));
                m.put("is_plenario_principal", sessaoData.get("is_plenario_principal"));
            }
            rows.add(m);
        }
        return rows;
    }

    /** Rows do relatório de entradas: renomeações diretas de campos. */
    public List<Map<String, Object>> enriquecerRowsParaRelatorioEntradas(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("sala", r.getOrDefault("sala_nome", "--"));
            m.put("operador", r.getOrDefault("operador_nome", "--"));
            m.put("tipo", r.getOrDefault("tipo_evento", "--"));
            m.put("evento", r.getOrDefault("nome_evento", "--"));
            m.put("pauta", r.getOrDefault("horario_pauta", ""));
            m.put("inicio", r.getOrDefault("horario_inicio", ""));
            m.put("fim", r.getOrDefault("horario_termino", ""));
            m.put("anormalidade", r.getOrDefault("houve_anormalidade", false));
            rows.add(m);
        }
        return rows;
    }

    /** Rows do relatório de anormalidades: sala/descrição/solucionada. */
    public List<Map<String, Object>> enriquecerRowsParaRelatorioAnormalidades(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("sala", r.getOrDefault("sala_nome", "--"));
            m.put("descricao", r.getOrDefault("descricao_anormalidade", ""));
            Object resolvida = r.get("resolvida_pelo_operador");
            m.put("solucionada", resolvida != null && ((Number) resolvida).intValue() == 1);
            rows.add(m);
        }
        return rows;
    }

    /** Duração HH:MM:SS entre dois horários; vazio quando ausente, inválido ou não-positivo. */
    private static String calcDuracao(String inicio, String termino) {
        try {
            if (inicio == null || termino == null || inicio.isEmpty() || termino.isEmpty()) return "";
            String[] ip = inicio.split(":"); String[] tp = termino.split(":");
            int iSec = Integer.parseInt(ip[0]) * 3600 + Integer.parseInt(ip[1]) * 60 + (ip.length > 2 ? Integer.parseInt(ip[2]) : 0);
            int tSec = Integer.parseInt(tp[0]) * 3600 + Integer.parseInt(tp[1]) * 60 + (tp.length > 2 ? Integer.parseInt(tp[2]) : 0);
            int diff = tSec - iSec;
            if (diff <= 0) return "";
            return String.format("%d:%02d:%02d", diff / 3600, (diff % 3600) / 60, diff % 60);
        } catch (Exception e) { return ""; }
    }
}
