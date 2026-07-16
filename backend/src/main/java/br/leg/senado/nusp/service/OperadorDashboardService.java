package br.leg.senado.nusp.service;

import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

import static br.leg.senado.nusp.service.NativeQueryUtils.boolVal;
import static br.leg.senado.nusp.service.NativeQueryUtils.num;
import static br.leg.senado.nusp.service.NativeQueryUtils.str;

/**
 * Equivale a operador_dashboard views + dashboard_home.py do Python.
 * 7 endpoints: meus-checklists, minhas-operacoes, detalhes de cada.
 * Todos com verificação de ownership (criado_por == userId).
 */
@Service
@RequiredArgsConstructor
public class OperadorDashboardService {

    private final EntityManager em;

    /** Nome canônico do Plenário Principal (referência em CAD_SALA.NOME). */
    private static final String SALA_PLENARIO_PRINCIPAL = "Plenário Principal";

    /** True quando o usuário é fixo do Plenário Principal — concede leitura de todos os registros do PP. */
    private boolean ehFixoPlenarioPrincipal(String userId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT 1 FROM PES_OPERADOR WHERE ID = ?1 AND PLENARIO_PRINCIPAL_FIXO = 1")
                .setParameter(1, userId).getResultList();
        return !rows.isEmpty();
    }

    /** Expressão de ownership do WHERE + os binds-base que ela introduz (Q13). */
    private record Ownership(String sql, List<Object> params) {}

    /**
     * Reconstrói a expressão de ownership do WHERE (dono direto OU junction OU fixo do Plenário
     * Principal). Q13: o userId vira bind posicional {@code ?} (2 ocorrências: dono e junction) →
     * o SQL fica idêntico entre operadores e compartilha 1 cursor no shared pool. O
     * {@code acessoFixo} é literal CONSTANTE (nome canônico do PP, não userId) → permanece
     * interpolado (gotcha 13). Os 2 binds {@code [userId, userId]} são prependados via
     * {@code baseParams} do executePagedQuery, pois o WHERE de ownership está no {@code fromJoins}
     * (aparece no SQL antes das condições de busca/período/filtros).
     */
    private Ownership ownershipWhere(String colDono, String junctionComAlias, String condJuncao, String aliasJuncao, String userId) {
        String acessoFixo = ehFixoPlenarioPrincipal(userId)
                ? " OR s.NOME = '" + SALA_PLENARIO_PRINCIPAL + "'"
                : "";
        String sql = "(" + colDono + " = ? " +
               "OR EXISTS (SELECT 1 FROM " + junctionComAlias + " WHERE " + condJuncao +
               " AND " + aliasJuncao + ".OPERADOR_ID = ?)" +
               acessoFixo + ")";
        return new Ownership(sql, List.of(userId, userId));
    }

    /**
     * Gate 403 a partir dos dados JÁ LIDOS pela query fundida do detalhe (Q14). O 404 é decidido
     * ANTES, pela query fundida vazia — quando este método roda, a linha já existe, preservando a
     * ordem anti-enumeração 404→403 (gotcha 6). As checagens de acesso adicional (junction) e de
     * fixo do Plenário Principal só rodam quando o usuário NÃO é o dono (curto-circuito —
     * irrelevantes para o dono). Retorna {@code somenteLeitura}: dono e adicional têm leitura+escrita,
     * só o fixo do PP é somente-leitura — reproduz o antigo {@code !ehDono && !ehAdicional}.
     */
    private boolean gate403(long id, String userId, String dono, String salaNome, String sqlAdicional) {
        if (userId.equals(dono)) return false;                       // dono → leitura+escrita
        if (sqlAdicional != null) {
            @SuppressWarnings("unchecked")
            List<Object> adicionalRows = em.createNativeQuery(sqlAdicional)
                    .setParameter(1, id).setParameter(2, userId).getResultList();
            if (!adicionalRows.isEmpty()) return false;              // adicional (junction) → leitura+escrita
        }
        boolean ehFixoPP = SALA_PLENARIO_PRINCIPAL.equals(salaNome) && ehFixoPlenarioPrincipal(userId);
        if (!ehFixoPP) throw new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN);
        return true;                                                 // fixo do PP → somente leitura
    }

    // ══ Meus Checklists ═══════════════════════════════════════

    private static final String QTDE_OK_EXPR =
            "(SELECT COUNT(*) FROM FRM_CHECKLIST_RESPOSTA r JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID " +
            "WHERE r.CHECKLIST_ID = c.ID AND r.STATUS = 'Ok' AND t.TIPO_WIDGET != 'text')";
    private static final String QTDE_FALHA_EXPR =
            "(SELECT COUNT(*) FROM FRM_CHECKLIST_RESPOSTA r JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID " +
            "WHERE r.CHECKLIST_ID = c.ID AND r.STATUS = 'Falha' AND t.TIPO_WIDGET != 'text')";

    private static final Map<String, String> MC_SORT = new LinkedHashMap<>() {{
        put("data", "c.DATA_OPERACAO"); put("sala", "s.NOME");
        put("qtde_ok", QTDE_OK_EXPR); put("qtde_falha", QTDE_FALHA_EXPR);
    }};

    public PagedResult listMeusChecklists(String userId, int page, int limit,
                                           String sort, String dir, Map<String, Object> filters) {
        return listMeusChecklists(userId, page, limit, sort, dir, filters, false);
    }

    public PagedResult listMeusChecklists(String userId, int page, int limit,
                                           String sort, String dir, Map<String, Object> filters, boolean somenteDados) {
        Ownership own = ownershipWhere("c.CRIADO_POR", "FRM_CHECKLIST_OPERADOR co", "co.CHECKLIST_ID = c.ID", "co", userId);
        return DashboardQueryHelper.executePagedQuery(em,
                "c.ID, c.DATA_OPERACAO AS data, s.NOME AS sala_nome, c.TURNO, " +
                "c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.EDITADO, " +
                QTDE_OK_EXPR + " AS qtde_ok, " +
                QTDE_FALHA_EXPR + " AS qtde_falha",
                "FROM FRM_CHECKLIST c JOIN CAD_SALA s ON s.ID = c.SALA_ID WHERE " + own.sql(),
                "c.DATA_OPERACAO", MC_SORT, List.of("s.NOME"),
                Map.of("data", "c.DATA_OPERACAO", "sala", "s.NOME"),
                Map.of("data", "date", "sala", "text"),
                page, limit, null, sort, dir, null, filters, "c.ID DESC", somenteDados, own.params());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMeuChecklistDetalhe(long checklistId, String userId) {
        // Q14: head+det fundidos — a query decide o 404 (vazia) e já traz dono (c.CRIADO_POR), sala
        // (s.NOME), nome do criador (LEFT JOIN) e flag multi_operador (CAD_SALA já joinada).
        List<Object[]> detRows = em.createNativeQuery("""
                SELECT c.ID, c.DATA_OPERACAO, s.NOME AS SALA_NOME, c.TURNO,
                       c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.OBSERVACOES,
                       c.USB_01, c.USB_02, c.EDITADO, c.SALA_ID, c.OBSERVACOES_EDITADO,
                       c.CRIADO_POR, cri.NOME_COMPLETO AS CRIADO_POR_NOME, cri.ID AS CRIADO_POR_OP_ID,
                       s.MULTI_OPERADOR
                FROM FRM_CHECKLIST c
                JOIN CAD_SALA s ON s.ID = c.SALA_ID
                LEFT JOIN PES_OPERADOR cri ON cri.ID = c.CRIADO_POR
                WHERE c.ID = ?1
                """).setParameter(1, checklistId).getResultList();
        if (detRows.isEmpty())
            throw new ServiceValidationException("Checklist não encontrado.", HttpStatus.NOT_FOUND);

        Object[] h = detRows.get(0);
        // 403 depois do 404, pela MESMA linha: dono=c.CRIADO_POR (h[12]), sala=s.NOME (h[2]).
        boolean somenteLeitura = gate403(checklistId, userId, str(h[12]), str(h[2]),
                "SELECT 1 FROM FRM_CHECKLIST_OPERADOR WHERE CHECKLIST_ID = ?1 AND OPERADOR_ID = ?2");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(h[0])); result.put("data_operacao", str(h[1]));
        result.put("sala_nome", str(h[2])); result.put("turno", str(h[3]));
        result.put("hora_inicio_testes", str(h[4])); result.put("hora_termino_testes", str(h[5]));
        result.put("observacoes", str(h[6])); result.put("usb_01", str(h[7]));
        result.put("usb_02", str(h[8])); result.put("editado", boolVal(h[9]));
        result.put("sala_id", num(h[10]));
        result.put("observacoes_editado", boolVal(h[11]));
        result.put("criado_por", str(h[12]));

        // Nome do criador (LEFT JOIN): põe a chave sse o operador existe (CRIADO_POR_OP_ID != null),
        // reproduzindo o put condicional anterior (valor NOME_COMPLETO pode ser null).
        if (str(h[14]) != null) result.put("criado_por_nome", str(h[13]));

        List<Object[]> itens = em.createNativeQuery("""
                SELECT r.ID, r.ITEM_TIPO_ID, t.NOME, t.TIPO_WIDGET, r.STATUS, r.DESCRICAO_FALHA, r.VALOR_TEXTO, r.EDITADO
                FROM FRM_CHECKLIST_RESPOSTA r
                JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID
                LEFT JOIN FRM_CHECKLIST_SALA_CONFIG sc ON sc.ITEM_TIPO_ID = t.ID AND sc.SALA_ID = ?2
                WHERE r.CHECKLIST_ID = ?1 ORDER BY sc.ORDEM ASC, t.ID ASC
                """).setParameter(1, checklistId).setParameter(2, num(h[10])).getResultList();

        List<Map<String, Object>> itensList = new ArrayList<>();
        for (Object[] it : itens) {
            itensList.add(DashboardQueryHelper.checklistItemMap(num(it[0]), num(it[1]),
                    str(it[2]), str(it[4]), str(it[5]), str(it[6]), boolVal(it[7]), str(it[3])));
        }
        result.put("itens", itensList);

        // Multi-operador: flag da sala (col 15, do JOIN) + operadores (IDs e nomes)
        boolean isMultiOp = boolVal(h[15]);
        result.put("multi_operador", isMultiOp);

        if (isMultiOp) {
            List<Object[]> opRows = em.createNativeQuery("""
                    SELECT co.PAPEL, o.NOME_COMPLETO, co.OPERADOR_ID
                    FROM FRM_CHECKLIST_OPERADOR co
                    JOIN PES_OPERADOR o ON o.ID = co.OPERADOR_ID
                    WHERE co.CHECKLIST_ID = ?1
                    ORDER BY co.PAPEL, o.NOME_COMPLETO
                    """).setParameter(1, checklistId).getResultList();

            Map<String, List<String>> nomes = AdminDashboardService.splitOperadoresPorPapel(opRows, 0, 1);
            Map<String, List<String>> ids = AdminDashboardService.splitOperadoresPorPapel(opRows, 0, 2);
            result.put("operadores_cabine", nomes.get("cabine"));
            result.put("operadores_plenario", nomes.get("plenario"));
            result.put("operadores_cabine_ids", ids.get("cabine"));
            result.put("operadores_plenario_ids", ids.get("plenario"));
        }

        result.put("somente_leitura", somenteLeitura);
        return result;
    }

    // ══ Minhas Operações ══════════════════════════════════════

    private static final Map<String, String> MO_SORT = new LinkedHashMap<>() {{
        put("data", "r.DATA"); put("sala", "s.NOME");
        put("hora_entrada", "e.HORA_ENTRADA"); put("hora_saida", "e.HORA_SAIDA");
        put("anormalidade", "e.HOUVE_ANORMALIDADE");
    }};

    public PagedResult listMinhasOperacoes(String userId, int page, int limit,
                                            String sort, String dir, Map<String, Object> filters) {
        return listMinhasOperacoes(userId, page, limit, sort, dir, filters, false);
    }

    public PagedResult listMinhasOperacoes(String userId, int page, int limit,
                                            String sort, String dir, Map<String, Object> filters, boolean somenteDados) {
        Ownership own = ownershipWhere("e.OPERADOR_ID", "OPR_ENTRADA_OPERADOR eo", "eo.ENTRADA_ID = e.ID", "eo", userId);
        return DashboardQueryHelper.executePagedQuery(em,
                "e.ID AS entrada_id, r.DATA AS data, s.NOME AS sala_nome, " +
                "r.NOME_DEMAIS_SALAS AS nome_demais_salas, " +
                "e.TIPO_EVENTO, e.NOME_EVENTO, c.NOME AS comissao_nome, " +
                "e.HORA_ENTRADA, e.HORA_SAIDA, e.HOUVE_ANORMALIDADE, " +
                "a.ID AS anormalidade_id",
                "FROM OPR_REGISTRO_ENTRADA e JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID JOIN CAD_SALA s ON s.ID = r.SALA_ID LEFT JOIN CAD_COMISSAO c ON c.ID = e.COMISSAO_ID LEFT JOIN OPR_ANORMALIDADE a ON a.ENTRADA_ID = e.ID WHERE " + own.sql(),
                "r.DATA", MO_SORT, List.of("s.NOME", "e.NOME_EVENTO"),
                Map.of("data", "r.DATA", "sala", "s.NOME", "anormalidade", "e.HOUVE_ANORMALIDADE"),
                Map.of("data", "date", "sala", "text", "anormalidade", "bool"),
                page, limit, null, sort, dir, null, filters, "e.ID DESC", somenteDados, own.params());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMinhaOperacaoDetalhe(long entradaId, String userId) {
        // Q14: head+det fundidos — a query decide o 404 (vazia) e já traz dono (e.OPERADOR_ID),
        // sala (s.NOME), nome do operador (LEFT JOIN) e flag multi_operador (CAD_SALA já joinada).
        List<Object[]> detRows = em.createNativeQuery("""
                SELECT e.ID, r.DATA, s.NOME AS SALA_NOME, e.NOME_EVENTO,
                       e.HORARIO_PAUTA, e.HORARIO_INICIO, e.HORARIO_TERMINO,
                       e.TIPO_EVENTO, e.USB_01, e.USB_02, e.OBSERVACOES,
                       e.RESPONSAVEL_EVENTO, e.HORA_ENTRADA, e.HORA_SAIDA,
                       e.HOUVE_ANORMALIDADE, e.EDITADO, r.SALA_ID, e.REGISTRO_ID,
                       e.COMISSAO_ID, e.ORDEM, c.NOME AS COMISSAO_NOME,
                       e.NOME_EVENTO_EDITADO, e.RESPONSAVEL_EVENTO_EDITADO,
                       e.HORARIO_PAUTA_EDITADO, e.HORARIO_INICIO_EDITADO,
                       e.HORARIO_TERMINO_EDITADO, e.USB_01_EDITADO, e.USB_02_EDITADO,
                       e.OBSERVACOES_EDITADO, e.COMISSAO_EDITADO, e.SALA_EDITADO,
                       e.HORA_ENTRADA_EDITADO, e.HORA_SAIDA_EDITADO,
                       e.SUSPENSOES_EDITADO, e.OPERADOR_ID,
                       r.DATA_EDITADO,
                       r.NOME_DEMAIS_SALAS, opr.NOME_COMPLETO AS OPERADOR_NOME, opr.ID AS OPERADOR_OP_ID,
                       s.MULTI_OPERADOR
                FROM OPR_REGISTRO_ENTRADA e
                JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID
                JOIN CAD_SALA s ON s.ID = r.SALA_ID
                LEFT JOIN CAD_COMISSAO c ON c.ID = e.COMISSAO_ID
                LEFT JOIN PES_OPERADOR opr ON opr.ID = e.OPERADOR_ID
                WHERE e.ID = ?1
                """).setParameter(1, entradaId).getResultList();
        if (detRows.isEmpty())
            throw new ServiceValidationException("Operação não encontrada.", HttpStatus.NOT_FOUND);

        Object[] r = detRows.get(0);
        // 403 depois do 404, pela MESMA linha: dono=e.OPERADOR_ID (r[34]), sala=s.NOME (r[2]).
        boolean somenteLeitura = gate403(entradaId, userId, str(r[34]), str(r[2]),
                "SELECT 1 FROM OPR_ENTRADA_OPERADOR WHERE ENTRADA_ID = ?1 AND OPERADOR_ID = ?2");

        Map<String, Object> result = mapDetalheEntrada(r);

        // Nome do operador (LEFT JOIN col 38=id, 37=nome): põe a chave sse o operador existe,
        // reproduzindo o put condicional anterior.
        if (str(r[38]) != null) result.put("operador_nome", str(r[37]));

        appendMultiOperador(result, boolVal(r[39]), entradaId);
        appendOperadorSeguinte(result, num(r[17]), num(r[19]));

        result.put("somente_leitura", somenteLeitura);
        return result;
    }

    /** Cria o LinkedHashMap do detalhe da entrada com os 37 campos (na ordem de projeção da query). */
    private Map<String, Object> mapDetalheEntrada(Object[] r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("data", str(r[1]));
        result.put("sala_nome", str(r[2])); result.put("nome_evento", str(r[3]));
        result.put("horario_pauta", str(r[4])); result.put("horario_inicio", str(r[5]));
        result.put("horario_termino", str(r[6])); result.put("tipo_evento", str(r[7]));
        result.put("usb_01", str(r[8])); result.put("usb_02", str(r[9]));
        result.put("observacoes", str(r[10])); result.put("responsavel_evento", str(r[11]));
        result.put("hora_entrada", str(r[12])); result.put("hora_saida", str(r[13]));
        result.put("houve_anormalidade", boolVal(r[14])); result.put("editado", boolVal(r[15]));
        result.put("sala_id", num(r[16])); result.put("registro_id", num(r[17]));
        result.put("comissao_id", num(r[18])); result.put("ordem", num(r[19]));
        result.put("comissao_nome", str(r[20]));
        result.put("nome_evento_editado", boolVal(r[21]));
        result.put("responsavel_evento_editado", boolVal(r[22]));
        result.put("horario_pauta_editado", boolVal(r[23]));
        result.put("horario_inicio_editado", boolVal(r[24]));
        result.put("horario_termino_editado", boolVal(r[25]));
        result.put("usb_01_editado", boolVal(r[26]));
        result.put("usb_02_editado", boolVal(r[27]));
        result.put("observacoes_editado", boolVal(r[28]));
        result.put("comissao_editado", boolVal(r[29]));
        result.put("sala_editado", boolVal(r[30]));
        result.put("hora_entrada_editado", boolVal(r[31]));
        result.put("hora_saida_editado", boolVal(r[32]));
        result.put("suspensoes_editado", boolVal(r[33]));
        result.put("operador_id", str(r[34]));
        result.put("data_editado", boolVal(r[35]));
        result.put("nome_demais_salas", str(r[36]));
        return result;
    }

    /** Multi-operador: flag da sala (Q14: já lida no JOIN do detalhe) + operadores da junction + suspensões. */
    private void appendMultiOperador(Map<String, Object> result, boolean isMultiOp, long entradaId) {
        result.put("multi_operador", isMultiOp);

        if (isMultiOp) {
            @SuppressWarnings("unchecked")
            List<Object[]> opRows = em.createNativeQuery("""
                    SELECT o.NOME_COMPLETO, eo.OPERADOR_ID
                    FROM OPR_ENTRADA_OPERADOR eo
                    JOIN PES_OPERADOR o ON o.ID = eo.OPERADOR_ID
                    WHERE eo.ENTRADA_ID = ?1
                    ORDER BY o.NOME_COMPLETO
                    """).setParameter(1, entradaId).getResultList();
            result.put("operadores_sessao", opRows.stream().map(o -> o[0] != null ? o[0].toString() : "").toList());
            result.put("operadores_sessao_ids", opRows.stream().map(o -> o[1] != null ? o[1].toString() : "").toList());

            @SuppressWarnings("unchecked")
            List<Object[]> suspRows = em.createNativeQuery("""
                    SELECT s.ID, s.HORA_SUSPENSAO, s.HORA_REABERTURA, s.ORDEM
                    FROM OPR_SUSPENSAO s WHERE s.ENTRADA_ID = ?1 ORDER BY s.ORDEM
                    """).setParameter(1, entradaId).getResultList();
            List<Map<String, Object>> suspList = new ArrayList<>();
            for (Object[] sr : suspRows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", num(sr[0]));
                m.put("hora_suspensao", str(sr[1]));
                m.put("hora_reabertura", str(sr[2]));
                m.put("ordem", num(sr[3]));
                suspList.add(m);
            }
            result.put("suspensoes", suspList);
        }
    }

    /** Dados do operador seguinte (para validação de hora_saida na edição). */
    private void appendOperadorSeguinte(Map<String, Object> result, long registroId, long ordem) {
        @SuppressWarnings("unchecked")
        List<Object[]> nextRows = em.createNativeQuery("""
                SELECT e2.HORA_ENTRADA, o2.NOME_COMPLETO
                FROM OPR_REGISTRO_ENTRADA e2
                JOIN PES_OPERADOR o2 ON o2.ID = e2.OPERADOR_ID
                WHERE e2.REGISTRO_ID = ?1 AND e2.ORDEM = ?2
                """).setParameter(1, registroId).setParameter(2, ordem + 1).getResultList();
        if (!nextRows.isEmpty()) {
            Object[] nr = nextRows.get(0);
            result.put("hora_entrada_seguinte", str(nr[0]));
            result.put("operador_nome_seguinte", str(nr[1]));
        }
    }

    public Map<String, Object> getMinhaAnormalidadeDetalhe(long anomId, String userId) {
        // Q14: head+det fundidos — a query decide o 404 (vazia) e já traz o nome do criador (LEFT
        // JOIN) e o dono (a.CRIADO_POR) p/ o 403. Anormalidade não tem acesso "adicional" (junction).
        @SuppressWarnings("unchecked")
        List<Object[]> detRows = em.createNativeQuery("""
                SELECT a.ID, a.DATA, s.NOME AS SALA_NOME, a.NOME_EVENTO,
                       a.HORA_INICIO_ANORMALIDADE, a.DESCRICAO_ANORMALIDADE,
                       a.HOUVE_PREJUIZO, a.DESCRICAO_PREJUIZO,
                       a.HOUVE_RECLAMACAO, a.AUTORES_CONTEUDO_RECLAMACAO,
                       a.ACIONOU_MANUTENCAO, a.HORA_ACIONAMENTO_MANUTENCAO,
                       a.RESOLVIDA_PELO_OPERADOR, a.PROCEDIMENTOS_ADOTADOS,
                       a.DATA_SOLUCAO, a.HORA_SOLUCAO, a.RESPONSAVEL_EVENTO,
                       o.NOME_COMPLETO AS CRIADO_POR_NOME,
                       adm.OBSERVACAO_SUPERVISOR, adm.OBSERVACAO_CHEFE, a.CRIADO_POR
                FROM OPR_ANORMALIDADE a
                JOIN CAD_SALA s ON s.ID = a.SALA_ID
                LEFT JOIN PES_OPERADOR o ON o.ID = a.CRIADO_POR
                LEFT JOIN OPR_ANORMALIDADE_ADMIN adm ON adm.REGISTRO_ANORMALIDADE_ID = a.ID
                WHERE a.ID = ?1
                """).setParameter(1, anomId).getResultList();
        if (detRows.isEmpty())
            throw new ServiceValidationException("Anormalidade não encontrada.", HttpStatus.NOT_FOUND);

        Object[] r = detRows.get(0);
        // 403 depois do 404, pela MESMA linha: dono=a.CRIADO_POR (r[20]), sala=s.NOME (r[2]); sqlAdicional=null.
        gate403(anomId, userId, str(r[20]), str(r[2]), null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("data", str(r[1]));
        result.put("sala_nome", str(r[2]));
        AnormalidadeService.putCamposAnormalidade(result, r, 3);
        result.put("criado_por_nome", str(r[17]));
        result.put("observacao_supervisor", str(r[18])); result.put("observacao_chefe", str(r[19]));
        result.put("anormalidade_solucionada", str(r[14]) != null || str(r[15]) != null);
        return result;
    }
}
