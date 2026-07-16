package br.leg.senado.nusp.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static br.leg.senado.nusp.service.NativeQueryUtils.PT_BR_MONTHS;
import static br.leg.senado.nusp.service.NativeQueryUtils.hhmm;
import static br.leg.senado.nusp.service.NativeQueryUtils.intVal;

/**
 * Gera o RDS (Registro Diario de Sessoes) em XLSX usando Apache POI.
 * Equivale a rds_xlsx_service.py do Python (openpyxl).
 *
 * O template Modelo.xlsx tem 31 sheets (01..31), um para cada dia do mes.
 * Cada sheet tem header fixo + corpo de dados (linhas 11-40).
 */
@Service
public class RdsXlsxService {

    private static final Logger log = LoggerFactory.getLogger(RdsXlsxService.class);

    private static final String[] PT_BR_WEEKDAYS = {
            "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira",
            "sexta-feira", "sábado", "domingo"
    };

    private static final int START_ROW = 10; // row index 10 = linha 11 do Excel
    private static final int BASE_END_ROW = 39; // row index 39 = linha 40
    private static final int FIRST_OP_COL = 8;  // coluna I = índice 8
    private static final int DEFAULT_OP_COUNT = 3;

    public byte[] gerarRdsXlsx(int ano, int mes, List<Map<String, Object>> rawRows) {
        try {
            ClassPathResource res = new ClassPathResource("assets/Modelo.xlsx");
            Workbook wb = new XSSFWorkbook(res.getInputStream());

            int lastDay = YearMonth.of(ano, mes).lengthOfMonth();
            Map<Integer, List<Map<String, Object>>> linesByDay = buildLinesByDay(rawRows);

            for (int d = 1; d <= 31; d++) {
                String sheetName = String.format("%02d", d);
                Sheet ws = wb.getSheet(sheetName);
                if (ws == null) continue;

                // Capturar estilos do template ANTES de limpar
                CellStyle dataStyle = null;   // estilo das células de dados (coluna I)
                Row templateRow = ws.getRow(START_ROW);
                if (templateRow != null) {
                    Cell templateCell = templateRow.getCell(FIRST_OP_COL);
                    if (templateCell != null) dataStyle = templateCell.getCellStyle();
                }

                clearBody(ws);

                List<Map<String, Object>> lines = linesByDay.getOrDefault(d, List.of());
                if (d > lastDay || lines.isEmpty()) {
                    setCellValue(ws, 6, 0, null); // A7
                    continue;
                }

                renderDaySheet(ws, ano, mes, d, lines, dataStyle);
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            wb.write(buf);
            wb.close();
            return buf.toByteArray();
        } catch (Exception e) {
            log.error("Erro ao gerar RDS XLSX: {}", e.getMessage(), e);
            throw new RuntimeException("Erro ao gerar RDS XLSX", e);
        }
    }

    private void renderDaySheet(Sheet ws, int ano, int mes, int d, List<Map<String, Object>> lines, CellStyle dataStyle) {
        // Calcular maxOps para este dia
        int maxOps = DEFAULT_OP_COUNT;
        for (Map<String, Object> line : lines) {
            List<String> ops = getOperadores(line);
            if (ops != null && ops.size() > maxOps) maxOps = ops.size();
        }
        int obsCol = FIRST_OP_COL + maxOps;

        // Atualizar headers e larguras de operadores se houver mais de 3
        if (maxOps > DEFAULT_OP_COUNT) {
            updateOperadorHeaders(ws, maxOps);
        }

        // Preencher A7 com data extenso
        LocalDate date = LocalDate.of(ano, mes, d);
        setCellValue(ws, 6, 0, formatDataExtenso(date));

        ensureRows(ws, lines.size(), obsCol + 1);

        for (int idx = 0; idx < lines.size(); idx++) {
            int row = START_ROW + idx;
            fillLine(ws, row, lines.get(idx), maxOps, obsCol);
        }

        applyExtraColumnStyles(ws, maxOps, obsCol, lines.size(), dataStyle);
    }

    private void fillLine(Sheet ws, int row, Map<String, Object> line, int maxOps, int obsCol) {
        setCellValue(ws, row, 0, localDisplay(line));                   // A
        setCellValue(ws, row, 1, "SGM");                                // B
        setCellValue(ws, row, 2, objStr(line, "atividade_legislativa")); // C
        setCellValue(ws, row, 3, objStr(line, "nome_evento"));           // D
        setCellValue(ws, row, 4, hhmm(objStr(line, "horario_pauta")));      // E
        setCellValue(ws, row, 5, null);                                             // F
        setCellValue(ws, row, 6, hhmm(objStr(line, "horario_inicio")));      // G
        setCellValue(ws, row, 7, hhmm(objStr(line, "horario_termino")));     // H

        // Operadores (colunas dinâmicas a partir de I)
        List<String> ops = getOperadores(line);
        for (int i = 0; i < maxOps; i++) {
            String opName = (ops != null && i < ops.size()) ? ops.get(i) : null;
            setCellValue(ws, row, FIRST_OP_COL + i, opName);
        }

        setCellValue(ws, row, obsCol, objStr(line, "obs"));             // Observações
    }

    private void applyExtraColumnStyles(Sheet ws, int maxOps, int obsCol, int lineCount, CellStyle dataStyle) {
        // Aplicar estilo (bordas + fonte) nas colunas extras — todas as linhas do body (11-40)
        if (maxOps > DEFAULT_OP_COUNT && dataStyle != null) {
            int bodyEnd = Math.max(BASE_END_ROW, START_ROW + lineCount - 1);
            for (int r = START_ROW; r <= bodyEnd; r++) {
                Row row = ws.getRow(r);
                if (row == null) row = ws.createRow(r);
                for (int col = FIRST_OP_COL + DEFAULT_OP_COUNT; col <= obsCol; col++) {
                    Cell cell = row.getCell(col);
                    if (cell == null) cell = row.createCell(col);
                    cell.setCellStyle(dataStyle);
                }
            }
        }
    }

    // ══ Construção de linhas por dia ════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<Integer, List<Map<String, Object>>> buildLinesByDay(List<Map<String, Object>> rows) {
        // 1. Agrupar por registro_id (sessão)
        Map<Integer, Map<String, Object>> sessions = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            int rid = intVal(r, "registro_id");
            Map<String, Object> sess = sessions.computeIfAbsent(rid, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("registro_id", rid);
                m.put("data", r.get("data"));
                m.put("sala_nome", r.get("sala_nome"));
                m.put("nome_demais_salas", r.get("nome_demais_salas"));
                m.put("em_aberto", boolVal(r, "em_aberto"));
                m.put("rows", new ArrayList<Map<String, Object>>());
                return m;
            });
            ((List<Map<String, Object>>) sess.get("rows")).add(r);
        }

        Map<Integer, List<Map<String, Object>>> linesByDay = new LinkedHashMap<>();

        for (Map<String, Object> sess : sessions.values()) {
            List<Map<String, Object>> rawRows = (List<Map<String, Object>>) sess.get("rows");

            Map<Integer, Map<String, Object>> byOrdem = collapseByOrdem(rawRows);

            if (byOrdem.isEmpty()) continue;

            String fim = computeFimSessao(rawRows);

            boolean isOpen = boolVal(sess, "em_aberto") || fim == null;
            String fimOut = isOpen ? null : fim;
            String obs = isOpen ? "Evento não encerrado" : "";

            int maxOrdem = byOrdem.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            int numGroups = (maxOrdem + 2) / 3;

            for (int g = 0; g < numGroups; g++) {
                int start = g * 3 + 1;
                List<Map<String, Object>> groupEntries = new ArrayList<>();
                for (int o = start; o < start + 3; o++) {
                    if (byOrdem.containsKey(o)) groupEntries.add(byOrdem.get(o));
                }
                if (groupEntries.isEmpty()) continue;

                groupEntries.sort(Comparator.comparingInt(e -> intVal(e, "ordem")));

                String comissaoNome = chooseValue(groupEntries, "comissao_nome");
                String salaNome = objStr(sess, "sala_nome");
                String atividade = deriveAtividadeLegislativa(comissaoNome, salaNome);

                Map<String, Object> line = buildLine(sess, g, groupEntries, byOrdem, start, salaNome, atividade, fimOut, obs);

                Object dataObj = sess.get("data");
                int day = extractDay(dataObj);
                linesByDay.computeIfAbsent(day, k -> new ArrayList<>()).add(line);
            }
        }

        sortLinesWithinDay(linesByDay);
        return linesByDay;
    }

    private Map<Integer, Map<String, Object>> collapseByOrdem(List<Map<String, Object>> rawRows) {
        // Colapsar por ordem (pega a última por seq + entrada_id)
        Map<Integer, Map<String, Object>> byOrdem = new LinkedHashMap<>();
        for (Map<String, Object> rr : rawRows) {
            int ordem = intVal(rr, "ordem");
            Map<String, Object> cur = byOrdem.get(ordem);
            int rrSeq = intVal(rr, "seq");
            int rrEntId = intVal(rr, "entrada_id");
            if (cur == null) {
                byOrdem.put(ordem, rr);
            } else {
                int curSeq = intVal(cur, "seq");
                int curEntId = intVal(cur, "entrada_id");
                if (rrSeq > curSeq || (rrSeq == curSeq && rrEntId > curEntId)) {
                    byOrdem.put(ordem, rr);
                }
            }
        }
        return byOrdem;
    }

    private String computeFimSessao(List<Map<String, Object>> rawRows) {
        // FIM por sessão
        String fim = null;
        for (Map<String, Object> rr : rawRows) {
            Object ht = rr.get("horario_termino");
            if (ht != null && !ht.toString().isEmpty()) {
                if (fim == null || ht.toString().compareTo(fim) > 0) fim = ht.toString();
            }
        }
        return fim;
    }

    private String deriveAtividadeLegislativa(String comissaoNome, String salaNome) {
        String atividade = null;
        if (comissaoNome != null && comissaoNome.contains("-")) {
            atividade = comissaoNome.split("-")[0].trim();
        } else if (comissaoNome != null) {
            atividade = comissaoNome.trim();
        }

        if (atividade == null && "Plenário Principal".equals(salaNome)) {
            atividade = "Sessão Plenária";
        }
        return atividade;
    }

    private Map<String, Object> buildLine(Map<String, Object> sess, int g, List<Map<String, Object>> groupEntries,
            Map<Integer, Map<String, Object>> byOrdem, int start, String salaNome, String atividade, String fimOut, String obs) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("registro_id", sess.get("registro_id"));
        line.put("group_index", g);
        line.put("data", sess.get("data"));
        line.put("sala_nome", salaNome);
        line.put("nome_demais_salas", sess.get("nome_demais_salas"));
        line.put("atividade_legislativa", atividade);
        line.put("nome_evento", chooseValue(groupEntries, "nome_evento"));
        line.put("horario_pauta", chooseValue(groupEntries, "horario_pauta"));
        line.put("horario_inicio", chooseValue(groupEntries, "horario_inicio"));
        line.put("horario_termino", fimOut);
        // Operadores: lista dinâmica (suporta mais de 3)
        List<String> multiOps = getMultiOpNames(byOrdem, start);
        List<String> operadores = new ArrayList<>();
        if (multiOps != null) {
            operadores.addAll(multiOps);
        } else {
            for (int o = start; o < start + 3; o++) {
                operadores.add(objStr(byOrdem.getOrDefault(o, Map.of()), "operador_nome_exibicao"));
            }
        }
        line.put("operadores", operadores);
        line.put("obs", obs);
        return line;
    }

    private void sortLinesWithinDay(Map<Integer, List<Map<String, Object>>> linesByDay) {
        // Ordenar dentro do dia
        for (List<Map<String, Object>> lines : linesByDay.values()) {
            lines.sort(Comparator
                    .comparing((Map<String, Object> l) -> strForSort(l, "horario_pauta"))
                    .thenComparing(l -> strForSort(l, "horario_inicio"))
                    .thenComparing(l -> objStr(l, "sala_nome"))
                    .thenComparingInt(l -> intVal(l, "registro_id"))
                    .thenComparingInt(l -> intVal(l, "group_index")));
        }
    }

    /**
     * Para Plenário Principal: retorna os nomes dos operadores da junction table.
     * Retorna null se não houver multi_op_names (plenários normais).
     */
    @SuppressWarnings("unchecked")
    private List<String> getMultiOpNames(Map<Integer, Map<String, Object>> byOrdem, int start) {
        Map<String, Object> entry = byOrdem.get(start);
        if (entry == null) return null;
        Object multiOps = entry.get("multi_op_names");
        if (multiOps instanceof List<?> list && !list.isEmpty()) {
            return (List<String>) list;
        }
        return null;
    }

    // ══ choose_value — regra de divergência ═════════════════════

    private String chooseValue(List<Map<String, Object>> entries, String field) {
        List<String> nonEmpty = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            Object v = e.get(field);
            if (v != null && !v.toString().trim().isEmpty()) nonEmpty.add(v.toString());
        }

        Set<String> uniq = new LinkedHashSet<>(nonEmpty);

        if (uniq.size() <= 1) {
            // Sem divergência: pega a primeira (menor ordem)
            for (Map<String, Object> e : entries) {
                Object v = e.get(field);
                if (v != null && !v.toString().trim().isEmpty()) return v.toString();
            }
            return null;
        }

        // Com divergência: pega a última (maior ordem)
        List<Map<String, Object>> reversed = new ArrayList<>(entries);
        reversed.sort(Comparator.comparingInt((Map<String, Object> e) -> intVal(e, "ordem")).reversed());
        for (Map<String, Object> e : reversed) {
            Object v = e.get(field);
            if (v != null && !v.toString().trim().isEmpty()) return v.toString();
        }
        return null;
    }

    // ══ Sheet helpers ═══════════════════════════════════════════

    private void clearBody(Sheet ws) {
        int endRow = Math.max(ws.getLastRowNum(), BASE_END_ROW);
        for (int r = START_ROW; r <= endRow; r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            for (int c = 0; c <= row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell != null) cell.setBlank();
            }
        }
    }

    private void ensureRows(Sheet ws, int totalLines, int totalCols) {
        int baseCapacity = BASE_END_ROW - START_ROW + 1; // 30
        if (totalLines <= baseCapacity) return;

        int extra = totalLines - baseCapacity;
        int insertAt = BASE_END_ROW + 1;
        ws.shiftRows(insertAt, ws.getLastRowNum(), extra);

        for (int i = 0; i < extra; i++) {
            Row srcRow = ws.getRow(BASE_END_ROW);
            Row dstRow = ws.createRow(insertAt + i);
            if (srcRow != null) {
                dstRow.setHeight(srcRow.getHeight());
                for (int c = 0; c < totalCols; c++) {
                    Cell src = srcRow.getCell(c);
                    Cell dst = dstRow.createCell(c);
                    if (src != null) dst.setCellStyle(src.getCellStyle());
                }
            }
        }
    }

    /**
     * Atualiza os headers de operadores quando há mais de 3.
     * Escreve "Operador 1", "Operador 2", ..., "Operador N" e reposiciona "Observações".
     * Também ajusta larguras das colunas novas.
     */
    private void updateOperadorHeaders(Sheet ws, int maxOps) {
        Row headerRow = ws.getRow(START_ROW - 1); // linha de cabeçalho (row 10 no Excel)
        if (headerRow == null) return;

        // Copiar estilo e largura da primeira coluna de operador
        CellStyle opStyle = null;
        Cell firstOpCell = headerRow.getCell(FIRST_OP_COL);
        if (firstOpCell != null) opStyle = firstOpCell.getCellStyle();
        int opWidth = ws.getColumnWidth(FIRST_OP_COL);

        // Largura da coluna original de Observações (col L) para reutilizar
        int obsOrigWidth = ws.getColumnWidth(FIRST_OP_COL + DEFAULT_OP_COUNT);

        for (int i = 0; i < maxOps; i++) {
            int col = FIRST_OP_COL + i;
            // Remover célula existente (inlineStr do template) e recriar
            Cell existing = headerRow.getCell(col);
            if (existing != null) headerRow.removeCell(existing);
            Cell cell = headerRow.createCell(col);
            cell.setCellValue("OPERADOR " + (i + 1));
            if (opStyle != null) cell.setCellStyle(opStyle);
            if (i >= DEFAULT_OP_COUNT) {
                ws.setColumnWidth(col, opWidth);
                ws.setColumnHidden(col, false);
            }
        }

        // Reposicionar "Observações" após o último operador
        int obsCol = FIRST_OP_COL + maxOps;
        Cell obsExisting = headerRow.getCell(obsCol);
        if (obsExisting != null) headerRow.removeCell(obsExisting);
        Cell obsCell = headerRow.createCell(obsCol);
        obsCell.setCellValue("OBSERVAÇÕES");
        if (opStyle != null) obsCell.setCellStyle(opStyle);
        ws.setColumnWidth(obsCol, obsOrigWidth);
        ws.setColumnHidden(obsCol, false);

    }

    /** Extrai a lista de operadores de uma linha. */
    @SuppressWarnings("unchecked")
    private List<String> getOperadores(Map<String, Object> line) {
        Object ops = line.get("operadores");
        if (ops instanceof List<?> list) return (List<String>) list;
        return null;
    }

    private void setCellValue(Sheet ws, int rowIdx, int colIdx, String value) {
        Row row = ws.getRow(rowIdx);
        if (row == null) row = ws.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        if (cell.getCellType() == CellType.FORMULA) {
            cell.removeFormula();
        }
        if (value == null || value.isEmpty()) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
    }

    // ══ Data helpers ════════════════════════════════════════════

    private String formatDataExtenso(LocalDate d) {
        int dow = d.getDayOfWeek().getValue() - 1; // 0=segunda
        return String.format("%s, %d de %s de %d",
                PT_BR_WEEKDAYS[dow], d.getDayOfMonth(), PT_BR_MONTHS[d.getMonthValue() - 1], d.getYear());
    }

    private String objStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Retorna sala_nome ou "<sala_nome>: <nome livre>" quando há nome_demais_salas preenchido. */
    private String localDisplay(Map<String, Object> m) {
        String salaNome = objStr(m, "sala_nome");
        String nomeDemais = objStr(m, "nome_demais_salas");
        if (nomeDemais == null) return salaNome;
        return salaNome == null ? nomeDemais : (salaNome + ": " + nomeDemais);
    }

    private boolean boolVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return false;
    }

    // package-private (não private) para o teste corrige F53 exercitar a extração diretamente.
    int extractDay(Object dataObj) {
        if (dataObj instanceof LocalDate ld) return ld.getDayOfMonth();
        if (dataObj instanceof java.sql.Date sd) return sd.toLocalDate().getDayOfMonth();
        // F53 (C17): Timestamp (o que chega de query nativa Oracle p/ coluna DATE/TIMESTAMP,
        // sess.get("data")) e java.util.Date puro — extração determinística por millis + zona
        // explícita BRT. getTime() é o millis intrínseco do instante (não os getters de fuso
        // default); o antigo Calendar.getInstance() mudava o dia conforme o -Duser.timezone da JVM.
        if (dataObj instanceof java.util.Date ud)
            return java.time.Instant.ofEpochMilli(ud.getTime())
                    .atZone(java.time.ZoneId.of("America/Sao_Paulo")).getDayOfMonth();
        return 1;
    }

    private String strForSort(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return "23:59:59";
        String s = v.toString().trim();
        return s.isEmpty() ? "23:59:59" : s;
    }
}
