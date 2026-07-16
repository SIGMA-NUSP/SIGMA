package br.leg.senado.nusp.service;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.service.NativeQueryUtils.bool;
import static br.leg.senado.nusp.service.NativeQueryUtils.localDisplay;
import static br.leg.senado.nusp.service.NativeQueryUtils.nonEmpty;
import static br.leg.senado.nusp.service.NativeQueryUtils.str;
import static br.leg.senado.nusp.service.ReportConfig.*;

/**
 * Gera relatorios em DOCX usando Apache POI XWPF.
 * Equivale a report_docx_service.py do Python (python-docx).
 */
@Service
public class ReportDocxService {

    private static final Logger log = LoggerFactory.getLogger(ReportDocxService.class);
    private static final int EMU_PER_MM = 36000;

    // ══ Relatórios flat ═════════════════════════════════════════

    public byte[] gerarRelatorioOperadores(List<Map<String, Object>> rows) {
        return buildFlat("Operadores de Áudio", rows,
                HDR_OPERADORES, COLS_OPERADORES,
                (tbl, i, r) -> {
                    setText(tbl, i, 0, str(r, "nome_completo", "nome"), false, null, ParagraphAlignment.LEFT, 9);
                    setText(tbl, i, 1, str(r, "email"), false, null, ParagraphAlignment.LEFT, 9);
                });
    }

    public byte[] gerarRelatorioOperacoesEntradas(List<Map<String, Object>> rows) {
        return buildFlat("Registros de Operação (Entradas)", rows,
                HDR_OPERACOES_ENTRADAS,
                COLS_OPERACOES_ENTRADAS,
                (tbl, i, r) -> {
                    boolean anom = bool(r, "anormalidade");
                    setText(tbl, i, 0, localDisplay(r, "sala"), true, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 1, fmtDate(r.get("data")), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 2, str(r, "operador"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 3, str(r, "tipo"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 4, str(r, "evento"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 5, fmtTime(r.get("pauta")), false, null, ParagraphAlignment.CENTER, 8);
                    setText(tbl, i, 6, fmtTime(r.get("inicio")), false, null, ParagraphAlignment.CENTER, 8);
                    setText(tbl, i, 7, fmtTime(r.get("fim")), false, null, ParagraphAlignment.CENTER, 8);
                    setAnomCell(tbl, i, 8, anom);
                });
    }

    public byte[] gerarRelatorioAnormalidades(List<Map<String, Object>> rows) {
        return buildFlat("Relatórios de Anormalidades", rows,
                HDR_ANORMALIDADES,
                COLS_ANORMALIDADES,
                (tbl, i, r) -> {
                    boolean sol = bool(r, "solucionada");
                    boolean prej = bool(r, "houve_prejuizo");
                    boolean recl = bool(r, "houve_reclamacao");
                    setText(tbl, i, 0, fmtDate(r.get("data")), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 1, localDisplay(r, "sala"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 2, str(r, "registrado_por"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 3, str(r, "descricao"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 4, sol ? "Sim" : "Não", true, sol ? COLOR_GREEN : COLOR_RED, ParagraphAlignment.CENTER, 8);
                    setText(tbl, i, 5, prej ? "Sim" : "Não", true, prej ? COLOR_RED : COLOR_MUTED, ParagraphAlignment.CENTER, 8);
                    setText(tbl, i, 6, recl ? "Sim" : "Não", true, recl ? COLOR_RED : COLOR_MUTED, ParagraphAlignment.CENTER, 8);
                });
    }

    /** "Solicitações" do banco de horas (E8/D-1): visão admin com Nome + Saldo; cores C-4.3 + Cancelado cinza. */
    public byte[] gerarRelatorioSolicitacoesAdmin(List<Map<String, Object>> rows) {
        return buildFlat("Solicitações — Banco de Horas", rows,
                HDR_SOLICITACOES_ADMIN, COLS_SOLICITACOES_ADMIN,
                (tbl, i, r) -> {
                    ReportConfig.SolicitacaoStatusView st = solicitacaoStatusView(str(r, "status"));
                    setText(tbl, i, 0, nonEmpty(r, "nome", "--"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 1, str(r, "saldo"), false, null, ParagraphAlignment.CENTER, 8);
                    setText(tbl, i, 2, fmtDate(r.get("data_folga")), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 3, st.label(), true, st.corHex(), ParagraphAlignment.CENTER, 8);
                    setText(tbl, i, 4, nonEmpty(r, "deliberado_por", "--"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(tbl, i, 5, nonEmpty(r, "motivo", "--"), false, null, ParagraphAlignment.LEFT, 8);
                });
    }

    // ══ Relatórios master/detail ════════════════════════════════

    @SuppressWarnings("unchecked")
    public byte[] gerarRelatorioOperacoesSessoes(List<Map<String, Object>> sessoes) {
        XWPFDocument doc = initDoc(null);
        // Título customizado: centralizado, negrito, tamanho 18
        XWPFParagraph titleP = doc.createParagraph();
        titleP.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titleP.createRun();
        titleRun.setText("Registros de Operação (Sessões)");
        titleRun.setBold(true);
        titleRun.setFontSize(18);
        if (sessoes.isEmpty()) {
            doc.createParagraph().createRun().setText("Nenhum registro encontrado para os filtros aplicados.");
            return finalize(doc, 0);
        }

        for (int idx = 0; idx < sessoes.size(); idx++) {
            Map<String, Object> s = sessoes.get(idx);
            renderSessaoMaster(doc, s);

            // Parágrafo zero-height entre tabelas para que fiquem coladas
            addSpacerParagraph(doc, 0, false, false);

            XWPFTable bar = addTable(doc, 1, 1, new int[]{100});
            setCellShading(bar.getRow(0).getCell(0), DETAIL_BAR_FILL);
            setText(bar, 0, 0, "Entradas da Operação:", true, null, ParagraphAlignment.LEFT, 9);

            // Parágrafo zero-height entre bar e subtabela
            addSpacerParagraph(doc, 0, false, false);

            List<Map<String, Object>> entradas = (List<Map<String, Object>>) s.get("entradas");
            int entCount = (entradas != null && !entradas.isEmpty()) ? entradas.size() : 1;

            if (Boolean.TRUE.equals(s.get("is_plenario_principal"))) {
                renderEntradasPlenarioPrincipal(doc, entradas);
            } else {
                renderEntradasNormais(doc, entradas, entCount);
            }

            if (idx < sessoes.size() - 1) {
                // Linha divisória com espaçamento mínimo (~10px = 15 twips each)
                addSpacerParagraph(doc, 15, true, true);
            }
        }

        return finalize(doc, sessoes.size());
    }

    private void renderSessaoMaster(XWPFDocument doc, Map<String, Object> s) {
        String vTxt = nonEmpty(s, "verificacao", "--");
        String vColor = verificacaoColorHex(vTxt);
        String evtDisplay = truncate(nonEmpty(s, "evento_display", "--"), 30);

        XWPFTable tm = addTable(doc, 2, 7, COLS_OPERACOES_SESSOES_MASTER);
        renderHeader(tm, HDR_OPERACOES_SESSOES_MASTER, HEADER_FILL);
        setShading(tm.getRow(1), DATA_ROW_FILL);
        setText(tm, 1, 0, localDisplay(s, "sala"), false, null, ParagraphAlignment.LEFT, 9);
        setText(tm, 1, 1, fmtDate(s.get("data")), false, null, ParagraphAlignment.LEFT, 9);
        setText(tm, 1, 2, evtDisplay, false, null, ParagraphAlignment.LEFT, 9);
        setText(tm, 1, 3, fmtTime(s.get("ultimo_pauta")), false, null, ParagraphAlignment.CENTER, 9);
        setText(tm, 1, 4, fmtTime(s.get("ultimo_inicio")), false, null, ParagraphAlignment.CENTER, 9);
        setText(tm, 1, 5, fmtTime(s.get("ultimo_termino")), false, null, ParagraphAlignment.CENTER, 9);
        setText(tm, 1, 6, vTxt, true, vColor, ParagraphAlignment.LEFT, 9);
    }

    private void renderEntradasPlenarioPrincipal(XWPFDocument doc, List<Map<String, Object>> entradas) {
        // Contar total de linhas de operadores
        int totalOpRows = contarLinhasOperadores(entradas);
        XWPFTable te = addTable(doc, 1 + totalOpRows, 2, COLS_OPERACOES_SESSOES_ENT_PLENARIO);
        renderHeader(te, HDR_OPERACOES_SESSOES_ENT_PLENARIO, HEADER_DETAIL_FILL);
        if (entradas != null && !entradas.isEmpty()) {
            int ri = 1;
            for (Map<String, Object> ent : entradas) {
                ri = renderEntradaPlenario(te, ri, ent);
            }
        } else {
            setText(te, 1, 0, "Nenhuma entrada registrada nesta sessão.", false, null, ParagraphAlignment.LEFT, 8);
        }
    }

    @SuppressWarnings("unchecked")
    private int contarLinhasOperadores(List<Map<String, Object>> entradas) {
        int totalOpRows = 0;
        if (entradas != null && !entradas.isEmpty()) {
            for (Map<String, Object> ent : entradas) {
                List<String> ops = (List<String>) ent.get("operadores");
                totalOpRows += (ops != null && !ops.isEmpty()) ? ops.size() : 1;
            }
        } else {
            totalOpRows = 1;
        }
        return totalOpRows;
    }

    @SuppressWarnings("unchecked")
    private int renderEntradaPlenario(XWPFTable te, int ri, Map<String, Object> ent) {
        boolean anom = bool(ent, "anormalidade");
        List<String> ops = (List<String>) ent.get("operadores");
        if (ops == null || ops.isEmpty()) {
            setText(te, ri, 0, str(ent, "preenchido_por"), false, null, ParagraphAlignment.LEFT, 8);
            setAnomCell(te, ri, 1, anom);
            return ri + 1;
        }
        for (int oi = 0; oi < ops.size(); oi++) {
            setText(te, ri + oi, 0, ops.get(oi), false, null, ParagraphAlignment.LEFT, 8);
            if (oi == 0) {
                setAnomCell(te, ri, 1, anom);
                if (ops.size() > 1) {
                    // Vertical merge: restart na primeira, continue nas seguintes
                    te.getRow(ri).getCell(1).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.RESTART);
                    for (int mi = 1; mi < ops.size(); mi++) {
                        te.getRow(ri + mi).getCell(1).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
                    }
                }
            }
        }
        return ri + ops.size();
    }

    private void renderEntradasNormais(XWPFDocument doc, List<Map<String, Object>> entradas, int entCount) {
        XWPFTable te = addTable(doc, 1 + entCount, 6, COLS_OPERACOES_SESSOES_ENT_NORMAL);
        renderHeader(te, HDR_OPERACOES_SESSOES_ENT_NORMAL, HEADER_DETAIL_FILL);
        if (entradas != null && !entradas.isEmpty()) {
            for (int ei = 0; ei < entradas.size(); ei++) {
                int ri = ei + 1;
                Map<String, Object> ent = entradas.get(ei);
                String oTxt = ordemDisplay(ent.get("ordem"));
                boolean anom = bool(ent, "anormalidade");
                String obs = truncate(str(ent, "observacoes"), 20);

                setText(te, ri, 0, oTxt, false, null, ParagraphAlignment.CENTER, 8);
                setText(te, ri, 1, str(ent, "operador"), false, null, ParagraphAlignment.LEFT, 8);
                setText(te, ri, 2, fmtTime(ent.get("hora_entrada")), false, null, ParagraphAlignment.CENTER, 8);
                setText(te, ri, 3, fmtTime(ent.get("hora_saida")), false, null, ParagraphAlignment.CENTER, 8);
                setText(te, ri, 4, obs, false, null, ParagraphAlignment.LEFT, 8);
                setAnomCell(te, ri, 5, anom);
            }
        } else {
            setText(te, 1, 0, "Nenhuma entrada registrada nesta sessão.", false, null, ParagraphAlignment.LEFT, 8);
        }
    }

    @SuppressWarnings("unchecked")
    public byte[] gerarRelatorioChecklists(List<Map<String, Object>> checklists) {
        XWPFDocument doc = initDoc("Verificação de Plenários");
        if (checklists.isEmpty()) {
            doc.createParagraph().createRun().setText("Nenhum registro encontrado para os filtros aplicados.");
            return finalize(doc, 0);
        }

        for (int idx = 0; idx < checklists.size(); idx++) {
            Map<String, Object> chk = checklists.get(idx);
            List<Map<String, Object>> itens = (List<Map<String, Object>>) chk.get("itens");
            if (itens == null) itens = List.of();

            boolean hasFail = temFalha(itens);
            String stTxt = hasFail ? "Falha" : "Ok";
            String stColor = hasFail ? COLOR_RED : COLOR_GREEN;

            XWPFTable tm = addTable(doc, 2, 7, COLS_CHECKLISTS_MASTER);
            renderHeader(tm, HDR_CHECKLISTS_MASTER, HEADER_FILL);
            setShading(tm.getRow(1), DATA_ROW_FILL);
            setText(tm, 1, 0, str(chk, "sala_nome", "sala"), true, null, ParagraphAlignment.LEFT, 8);
            setText(tm, 1, 1, fmtDate(chk.get("data")), false, null, ParagraphAlignment.LEFT, 8);
            setText(tm, 1, 2, str(chk, "operador"), false, null, ParagraphAlignment.LEFT, 8);
            setText(tm, 1, 3, fmtTime(chk.get("inicio")), false, null, ParagraphAlignment.CENTER, 8);
            setText(tm, 1, 4, fmtTime(chk.get("termino")), false, null, ParagraphAlignment.CENTER, 8);
            setText(tm, 1, 5, nonEmpty(chk, "duracao", "--"), false, null, ParagraphAlignment.CENTER, 8);
            setText(tm, 1, 6, stTxt, true, stColor, ParagraphAlignment.CENTER, 8);

            doc.createParagraph();
            XWPFTable bar = addTable(doc, 1, 1, new int[]{100});
            setCellShading(bar.getRow(0).getCell(0), DETAIL_BAR_FILL);
            setText(bar, 0, 0, "Detalhes da Verificação:", true, null, ParagraphAlignment.LEFT, 9);

            int itCount = !itens.isEmpty() ? itens.size() : 1;
            XWPFTable ti = addTable(doc, 1 + itCount, 3, new int[]{45, 15, 40});
            renderHeader(ti, HDR_CHECKLIST_ITENS, HEADER_DETAIL_FILL);

            if (!itens.isEmpty()) {
                for (int ii = 0; ii < itens.size(); ii++) {
                    int ri = ii + 1;
                    Map<String, Object> it = itens.get(ii);
                    var view = itemChecklistView(it);
                    setText(ti, ri, 0, str(it, "item"), false, null, ParagraphAlignment.LEFT, 8);
                    setText(ti, ri, 1, view.status(), true, view.corHex(), ParagraphAlignment.CENTER, 8);
                    setText(ti, ri, 2, view.descricao(), false, null, ParagraphAlignment.LEFT, 8);
                }
            } else {
                setText(ti, 1, 0, "Nenhum item encontrado.", false, null, ParagraphAlignment.LEFT, 8);
            }

            if (idx < checklists.size() - 1) { doc.createParagraph(); doc.createParagraph(); }
        }

        return finalize(doc, checklists.size());
    }

    // ══ Infraestrutura ══════════════════════════════════════════

    @FunctionalInterface
    private interface DocxRowBuilder {
        void build(XWPFTable table, int rowIndex, Map<String, Object> data);
    }

    private byte[] buildFlat(String title, List<Map<String, Object>> rows,
                              String[] headers, int[] weights, DocxRowBuilder builder) {
        XWPFDocument doc = initDoc(title);
        if (rows.isEmpty()) {
            doc.createParagraph().createRun().setText("Nenhum registro encontrado para os filtros aplicados.");
            return finalize(doc, 0);
        }

        XWPFTable tbl = addTable(doc, 1 + rows.size(), headers.length, weights);
        renderHeader(tbl, headers, HEADER_FILL);
        for (int i = 0; i < rows.size(); i++) builder.build(tbl, i + 1, rows.get(i));

        return finalize(doc, rows.size());
    }

    private XWPFDocument initDoc(String title) {
        try {
            ClassPathResource res = new ClassPathResource("assets/Modelo.docx");
            XWPFDocument doc = res.exists() ? new XWPFDocument(res.getInputStream()) : new XWPFDocument();
            clearBody(doc);
            addPageNumberFooter(doc);

            if (title != null) {
                XWPFParagraph heading = doc.createParagraph();
                heading.setStyle("Heading2");
                heading.createRun().setText(title);
            }

            return doc;
        } catch (Exception e) {
            log.warn("Erro ao carregar template DOCX: {}", e.getMessage());
            XWPFDocument doc = new XWPFDocument();
            doc.createParagraph().createRun().setText(title);
            return doc;
        }
    }

    private byte[] finalize(XWPFDocument doc, int total) {
        if (total > 0) {
            doc.createParagraph();
            addTotalRow(doc, total);
        }
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            doc.write(buf);
            doc.close();
            return buf.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar DOCX", e);
        }
    }

    private void clearBody(XWPFDocument doc) {
        // Remove all body elements except section properties
        var body = doc.getDocument().getBody();
        var children = body.getDomNode().getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            var child = children.item(i);
            if (!"w:sectPr".equals(child.getNodeName())) {
                body.getDomNode().removeChild(child);
            }
        }
    }

    private void addPageNumberFooter(XWPFDocument doc) {
        try {
            XWPFHeaderFooterPolicy policy = doc.getHeaderFooterPolicy();
            if (policy == null) policy = doc.createHeaderFooterPolicy();
            XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
            XWPFParagraph p = footer.createParagraph();
            p.setAlignment(ParagraphAlignment.RIGHT);

            XWPFRun r1 = p.createRun();
            r1.setText("Página ");
            r1.setFontSize(8);

            addField(p, "PAGE", 8);

            XWPFRun r2 = p.createRun();
            r2.setText(" de ");
            r2.setFontSize(8);

            addField(p, "NUMPAGES", 8);
        } catch (Exception e) {
            log.debug("Erro ao adicionar footer: {}", e.getMessage());
        }
    }

    private void addField(XWPFParagraph p, String instruction, int fontSize) {
        CTSimpleField fld = p.getCTP().addNewFldSimple();
        fld.setInstr(instruction);
        CTR ctr = fld.addNewR();
        CTRPr rpr = ctr.addNewRPr();
        CTHpsMeasure sz = rpr.addNewSz();
        sz.setVal(BigInteger.valueOf(fontSize * 2L));
        CTText t = ctr.addNewT();
        t.setStringValue("1");
    }

    private XWPFTable addTable(XWPFDocument doc, int rows, int cols, int[] weights) {
        XWPFTable tbl = doc.createTable(rows, cols);
        try { tbl.getCTTbl().getTblPr().addNewTblStyle().setVal("TableGrid"); } catch (Exception ignored) {}
        tbl.setTableAlignment(TableRowAlign.CENTER);

        // Calculate column widths
        long totalWidth = 0;
        try {
            CTSectPr sectPr = doc.getDocument().getBody().getSectPr();
            if (sectPr != null && sectPr.getPgSz() != null) {
                totalWidth = ((java.math.BigInteger) sectPr.getPgSz().getW()).longValue();
            }
        } catch (Exception ignored) {}
        if (totalWidth == 0) totalWidth = 11906; // A4 width in twips

        int sum = 0;
        for (int w : weights) sum += w;

        for (int c = 0; c < cols; c++) {
            long colW = (totalWidth * weights[c]) / sum;
            for (int r = 0; r < rows; r++) {
                XWPFTableCell cell = tbl.getRow(r).getCell(c);
                CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                CTTblWidth tw = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                tw.setW(BigInteger.valueOf(colW));
                tw.setType(STTblWidth.DXA);
            }
        }

        return tbl;
    }

    private void renderHeader(XWPFTable tbl, String[] headers, String fillHex) {
        XWPFTableRow row = tbl.getRow(0);
        for (int j = 0; j < headers.length; j++) {
            XWPFTableCell cell = row.getCell(j);
            setCellShading(cell, fillHex);
            setCellText(cell, headers[j], true, null, null, 9);
        }
    }

    private void addTotalRow(XWPFDocument doc, int total) {
        XWPFTable tbl = addTable(doc, 1, 2, new int[]{80, 20});
        setCellShading(tbl.getRow(0).getCell(0), HEADER_FILL);
        setCellShading(tbl.getRow(0).getCell(1), HEADER_FILL);
        setCellText(tbl.getRow(0).getCell(0), "Total", true, null, null, 10);
        setCellText(tbl.getRow(0).getCell(1), String.valueOf(total), true, null, ParagraphAlignment.RIGHT, 10);
    }

    // ══ Cell helpers ════════════════════════════════════════════

    private void addSpacerParagraph(XWPFDocument doc, int beforeAfterTwips, boolean exactLineRule, boolean borderBottom) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(beforeAfterTwips);
        p.setSpacingAfter(beforeAfterTwips);
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTSpacing sp = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        sp.setBefore(BigInteger.valueOf(beforeAfterTwips));
        sp.setAfter(BigInteger.valueOf(beforeAfterTwips));
        sp.setLine(BigInteger.valueOf(1));
        if (exactLineRule) sp.setLineRule(STLineSpacingRule.EXACT);
        if (borderBottom) p.setBorderBottom(Borders.SINGLE);
        p.createRun().setFontSize(1);
    }

    private void setAnomCell(XWPFTable tbl, int row, int col, boolean anom) {
        setText(tbl, row, col, anom ? "SIM" : "Não", true, anom ? COLOR_RED : COLOR_GREEN, ParagraphAlignment.CENTER, 8);
    }

    private void setText(XWPFTable tbl, int row, int col, String text,
                          boolean bold, String colorHex, ParagraphAlignment align, int fontSize) {
        setCellText(tbl.getRow(row).getCell(col), text, bold, colorHex, align, fontSize);
    }

    private void setCellText(XWPFTableCell cell, String text,
                              boolean bold, String colorHex, ParagraphAlignment align, int fontSize) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        if (align != null) p.setAlignment(align);
        XWPFRun run = p.createRun();
        run.setText(text != null ? text : "");
        run.setBold(bold);
        run.setFontSize(fontSize);
        if (colorHex != null) {
            String h = colorHex.startsWith("#") ? colorHex.substring(1) : colorHex;
            run.setColor(h);
        }
    }

    private void setCellShading(XWPFTableCell cell, String fillHex) {
        String fill = fillHex.startsWith("#") ? fillHex.substring(1) : fillHex;
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(fill);
    }

    private void setShading(XWPFTableRow row, String fillHex) {
        for (int i = 0; i < row.getTableCells().size(); i++) {
            setCellShading(row.getCell(i), fillHex);
        }
    }

}
