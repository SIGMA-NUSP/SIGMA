package br.leg.senado.nusp.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.service.NativeQueryUtils.bool;
import static br.leg.senado.nusp.service.NativeQueryUtils.intVal;
import static br.leg.senado.nusp.service.NativeQueryUtils.localDisplay;
import static br.leg.senado.nusp.service.NativeQueryUtils.nonEmpty;
import static br.leg.senado.nusp.service.NativeQueryUtils.str;
import static br.leg.senado.nusp.service.ReportConfig.*;

/**
 * Gera relatorios em PDF usando OpenPDF + overlay com Modelo.pdf.
 * Equivale a report_pdf_service.py do Python (ReportLab + PyMuPDF).
 */
@Service
public class ReportPdfService {

    private static final Logger log = LoggerFactory.getLogger(ReportPdfService.class);

    private static final float TOP_MARGIN = 170f;
    private static final float BOTTOM_MARGIN = 90f;
    private static final float LEFT_MARGIN = 40f;
    private static final float RIGHT_MARGIN = 40f;

    private static final float PAGE_WIDTH = PageSize.A4.getWidth() - LEFT_MARGIN - RIGHT_MARGIN;

    // ══ Relatórios flat ═════════════════════════════════════════

    public byte[] gerarRelatorioOperadores(List<Map<String, Object>> rows) {
        return buildFlatReport("Operadores de Áudio", rows,
                HDR_OPERADORES, COLS_OPERADORES,
                (tbl, r, nf) -> {
                    tbl.addCell(cell(str(r, "nome_completo", "nome"), nf, false));
                    tbl.addCell(cell(str(r, "email"), nf, false));
                }, 10f, 12f);
    }

    public byte[] gerarRelatorioAnormalidades(List<Map<String, Object>> rows) {
        return buildFlatReport("Relatórios de Anormalidades", rows,
                HDR_ANORMALIDADES,
                COLS_ANORMALIDADES,
                (tbl, r, nf) -> {
                    tbl.addCell(cell(fmtDate(r.get("data")), nf, false));
                    tbl.addCell(cell(localDisplay(r, "sala_nome"), nf, false));
                    tbl.addCell(cell(str(r, "registrado_por"), nf, false));
                    tbl.addCell(cell(str(r, "descricao"), nf, false));
                    tbl.addCell(boolCell(bool(r, "solucionada"), nf, COLOR_GREEN, COLOR_RED));
                    tbl.addCell(boolCell(bool(r, "houve_prejuizo"), nf, COLOR_RED, COLOR_MUTED));
                    tbl.addCell(boolCell(bool(r, "houve_reclamacao"), nf, COLOR_RED, COLOR_MUTED));
                }, 9f, 11f);
    }

    public byte[] gerarRelatorioOperacoesEntradas(List<Map<String, Object>> rows) {
        return buildFlatReport("Registros de Operação (Entradas)", rows,
                HDR_OPERACOES_ENTRADAS,
                COLS_OPERACOES_ENTRADAS,
                (tbl, r, nf) -> {
                    tbl.addCell(cell(localDisplay(r, "sala_nome"), nf, true));
                    tbl.addCell(cell(fmtDate(r.get("data")), nf, false));
                    tbl.addCell(cell(str(r, "operador"), nf, false));
                    tbl.addCell(cell(str(r, "tipo"), nf, false));
                    tbl.addCell(cell(str(r, "evento"), nf, false));
                    tbl.addCell(centerCell(fmtTime(r.get("pauta")), nf));
                    tbl.addCell(centerCell(fmtTime(r.get("inicio")), nf));
                    tbl.addCell(centerCell(fmtTime(r.get("fim")), nf));
                    boolean anom = bool(r, "anormalidade");
                    tbl.addCell(boolCell(anom, nf, anom ? COLOR_RED : COLOR_GREEN, anom ? COLOR_RED : COLOR_GREEN));
                }, 8f, 10f);
    }

    public byte[] gerarRelatorioMeusChecklists(List<Map<String, Object>> rows) {
        return buildFlatReport("Verificação de Salas", rows,
                new String[]{"Sala", "Data", "Qtde. OK", "Qtde. Falha"},
                COLS_MEUS_CHECKLISTS,
                (tbl, r, nf) -> {
                    tbl.addCell(cell(str(r, "sala_nome"), nf, true));
                    tbl.addCell(cell(fmtDate(r.get("data")), nf, false));
                    int ok = intVal(r, "qtde_ok");
                    int falha = intVal(r, "qtde_falha");
                    tbl.addCell(colorCenter(String.valueOf(ok), nf, ok > 0 ? COLOR_GREEN : COLOR_SLATE));
                    tbl.addCell(colorCenter(String.valueOf(falha), nf, falha > 0 ? COLOR_RED : COLOR_SLATE));
                }, 10f, 12f);
    }

    public byte[] gerarRelatorioMinhasOperacoes(List<Map<String, Object>> rows) {
        return buildFlatReport("Registros de Operação de Áudio", rows,
                new String[]{"Sala", "Data", "Início Operação", "Fim Operação", "Anormalidade?"},
                COLS_MINHAS_OPERACOES,
                (tbl, r, nf) -> {
                    tbl.addCell(cell(localDisplay(r, "sala_nome"), nf, true));
                    tbl.addCell(cell(fmtDate(r.get("data")), nf, false));
                    tbl.addCell(centerCell(fmtTime(r.get("hora_entrada")), nf));
                    tbl.addCell(centerCell(fmtTime(r.get("hora_saida")), nf));
                    boolean anom = bool(r, "houve_anormalidade");
                    tbl.addCell(boolCell(anom, nf, anom ? COLOR_RED : COLOR_GREEN, anom ? COLOR_RED : COLOR_GREEN));
                }, 9f, 11f);
    }

    /** "Minhas Solicitações" do banco de horas (E7/C-4): colunas C-4.2, cores C-4.3 + Cancelado cinza. */
    public byte[] gerarRelatorioMinhasSolicitacoes(List<Map<String, Object>> rows) {
        return buildFlatReport("Minhas Solicitações — Banco de Horas", rows,
                new String[]{"Dia solicitado", "Status", "Deliberado por", "Motivo"},
                COLS_MINHAS_SOLICITACOES,
                (tbl, r, nf) -> {
                    tbl.addCell(cell(fmtDate(r.get("data_folga")), nf, false));
                    ReportConfig.SolicitacaoStatusView st = solicitacaoStatusView(str(r, "status"));
                    tbl.addCell(colorCenter(st.label(), nf, st.corHex()));
                    tbl.addCell(cell(nonEmpty(r, "deliberado_por", "--"), nf, false));
                    tbl.addCell(cell(nonEmpty(r, "motivo", "--"), nf, false));
                }, 9f, 11f);
    }

    /** "Solicitações" do banco de horas (E8/D-1): visão admin com Nome + Saldo; cores C-4.3 + Cancelado cinza. */
    public byte[] gerarRelatorioSolicitacoesAdmin(List<Map<String, Object>> rows) {
        return buildFlatReport("Solicitações — Banco de Horas", rows,
                HDR_SOLICITACOES_ADMIN, COLS_SOLICITACOES_ADMIN,
                (tbl, r, nf) -> {
                    tbl.addCell(cell(nonEmpty(r, "nome", "--"), nf, false));
                    tbl.addCell(centerCell(str(r, "saldo"), nf));
                    tbl.addCell(cell(fmtDate(r.get("data_folga")), nf, false));
                    ReportConfig.SolicitacaoStatusView st = solicitacaoStatusView(str(r, "status"));
                    tbl.addCell(colorCenter(st.label(), nf, st.corHex()));
                    tbl.addCell(cell(nonEmpty(r, "deliberado_por", "--"), nf, false));
                    tbl.addCell(cell(nonEmpty(r, "motivo", "--"), nf, false));
                }, 9f, 11f);
    }

    // ══ Relatórios master/detail ════════════════════════════════

    @SuppressWarnings("unchecked")
    public byte[] gerarRelatorioChecklists(List<Map<String, Object>> checklists) {
        Font titleFont = pdfFont(14f, Font.BOLD, null);
        Font hdrFont = pdfFont(9f, Font.BOLD, hex(COLOR_DARK));
        Font nf = pdfFont(9f, Font.NORMAL, null);
        Font barFont = pdfFont(9f, Font.BOLD, null);
        Font smallHdr = pdfFont(9f, Font.BOLD, hex(COLOR_DARK));

        Document doc = new Document(PageSize.A4, LEFT_MARGIN, RIGHT_MARGIN, TOP_MARGIN, BOTTOM_MARGIN);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, buf);
        doc.open();

        doc.add(new Paragraph("Verificação de Plenários", titleFont));
        doc.add(new Paragraph(" "));

        if (checklists.isEmpty()) {
            doc.add(new Paragraph("Nenhum registro encontrado para os filtros aplicados.", nf));
            doc.close();
            return mergeWithTemplate(buf.toByteArray());
        }

        float[] mColW = scaleWeights(COLS_CHECKLISTS_MASTER, PAGE_WIDTH);

        for (int idx = 0; idx < checklists.size(); idx++) {
            Map<String, Object> chk = checklists.get(idx);
            List<Map<String, Object>> itens = (List<Map<String, Object>>) chk.get("itens");
            if (itens == null) itens = List.of();

            boolean hasFail = temFalha(itens);
            String statusTxt = hasFail ? "Falha" : "Ok";
            Color statusColor = hasFail ? hex(COLOR_RED) : hex(COLOR_GREEN);

            // Header
            PdfPTable hdr = new PdfPTable(mColW);
            hdr.setWidthPercentage(100);
            for (String h : HDR_CHECKLISTS_MASTER) {
                hdr.addCell(headerCell(h, hdrFont));
            }

            // Master
            PdfPTable master = new PdfPTable(mColW);
            master.setWidthPercentage(100);
            master.addCell(dataCell(str(chk, "sala_nome", "sala"), nf, true));
            master.addCell(dataCell(strOrEmpty(chk, "data"), nf, false));
            master.addCell(dataCell(str(chk, "operador"), nf, false));
            master.addCell(dataCell(strOrEmpty(chk, "inicio"), nf, false));
            master.addCell(dataCell(strOrEmpty(chk, "termino"), nf, false));
            master.addCell(dataCell(strOrEmpty(chk, "duracao"), nf, false));
            PdfPCell sc = dataCell(statusTxt, pdfFont(9f, Font.BOLD, statusColor), false);
            sc.setHorizontalAlignment(Element.ALIGN_CENTER);
            master.addCell(sc);

            // Detail bar
            PdfPTable bar = new PdfPTable(1);
            bar.setWidthPercentage(100);
            PdfPCell barC = new PdfPCell(new Phrase("Detalhes da Verificação:", barFont));
            barC.setBackgroundColor(hex(DETAIL_BAR_FILL));
            barC.setPadding(5f);
            barC.setBorderColor(hex(GRID_COLOR));
            bar.addCell(barC);

            // Items
            float[] itColW = {PAGE_WIDTH * 0.45f, PAGE_WIDTH * 0.15f, PAGE_WIDTH * 0.40f};
            PdfPTable itTbl = new PdfPTable(itColW);
            itTbl.setWidthPercentage(100);
            for (String h : HDR_CHECKLIST_ITENS) {
                itTbl.addCell(detailHeaderCell(h, smallHdr));
            }

            for (Map<String, Object> it : itens) {
                var view = itemChecklistView(it);
                itTbl.addCell(itemCell(str(it, "item"), nf));
                PdfPCell stC = itemCell(view.status(), pdfFont(9f, Font.BOLD, hex(view.corHex())));
                stC.setHorizontalAlignment(Element.ALIGN_CENTER);
                itTbl.addCell(stC);
                itTbl.addCell(itemCell(view.descricao(), nf));
            }

            doc.add(hdr);
            doc.add(master);
            doc.add(bar);
            doc.add(itTbl);

            if (idx < checklists.size() - 1) doc.add(new Paragraph(" "));
        }

        addTotalRow(doc, checklists.size(), nf);
        doc.close();
        return mergeWithTemplate(buf.toByteArray());
    }

    @SuppressWarnings("unchecked")
    public byte[] gerarRelatorioOperacoesSessoes(List<Map<String, Object>> sessoes) {
        Font titleFont = pdfFont(14f, Font.BOLD, null);
        Font hdrFont = pdfFont(9f, Font.BOLD, hex(COLOR_DARK));
        Font nf = pdfFont(9f, Font.NORMAL, null);
        Font sm = pdfFont(8f, Font.NORMAL, null);
        Font smHdr = pdfFont(8f, Font.BOLD, hex(COLOR_DARK));
        Font barFont = pdfFont(9f, Font.BOLD, null);

        Document doc = new Document(PageSize.A4, LEFT_MARGIN, RIGHT_MARGIN, TOP_MARGIN, BOTTOM_MARGIN);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, buf);
        doc.open();

        doc.add(new Paragraph("Registros de Operação (Sessões)", titleFont));
        doc.add(new Paragraph(" "));

        if (sessoes.isEmpty()) {
            doc.add(new Paragraph("Nenhum registro encontrado para os filtros aplicados.", nf));
            doc.close();
            return mergeWithTemplate(buf.toByteArray());
        }

        float[] mColW = scaleWeights(COLS_OPERACOES_SESSOES_MASTER, PAGE_WIDTH);

        for (int idx = 0; idx < sessoes.size(); idx++) {
            Map<String, Object> s = sessoes.get(idx);
            renderSessaoMaster(doc, s, mColW, hdrFont, nf, barFont);

            List<Map<String, Object>> entradas = (List<Map<String, Object>>) s.get("entradas");
            if (Boolean.TRUE.equals(s.get("is_plenario_principal"))) {
                renderEntradasPlenarioPrincipal(doc, entradas, sm, smHdr);
            } else {
                renderEntradasNumeradas(doc, entradas, sm, smHdr);
            }

            if (idx < sessoes.size() - 1) addDivider(doc);
        }

        addTotalRow(doc, sessoes.size(), nf);
        doc.close();
        return mergeWithTemplate(buf.toByteArray());
    }

    private void renderSessaoMaster(Document doc, Map<String, Object> s, float[] mColW,
                                    Font hdrFont, Font nf, Font barFont) {
        String vTxt = nonEmpty(s, "verificacao", "--");
        Color vColor = hex(verificacaoColorHex(vTxt));
        String evtDisplay = truncate(nonEmpty(s, "evento_display", "--"), 30);

        // Header
        PdfPTable hdr = new PdfPTable(mColW);
        hdr.setWidthPercentage(100);
        for (String h : HDR_OPERACOES_SESSOES_MASTER) {
            hdr.addCell(headerCell(h, hdrFont));
        }

        // Master
        PdfPTable master = new PdfPTable(mColW);
        master.setWidthPercentage(100);
        master.addCell(dataCell(localDisplay(s, "sala"), nf, false));
        master.addCell(dataCell(fmtDate(s.get("data")), nf, false));
        master.addCell(dataCell(evtDisplay, nf, false));
        master.addCell(dataCell(fmtTime(s.get("ultimo_pauta")), nf, false));
        master.addCell(dataCell(fmtTime(s.get("ultimo_inicio")), nf, false));
        master.addCell(dataCell(fmtTime(s.get("ultimo_termino")), nf, false));
        master.addCell(dataCell(vTxt, pdfFont(9f, Font.BOLD, vColor), false));

        // Detail bar
        PdfPTable bar = new PdfPTable(1);
        bar.setWidthPercentage(100);
        PdfPCell barC = new PdfPCell(new Phrase("Entradas da Operação:", barFont));
        barC.setBackgroundColor(hex(DETAIL_BAR_FILL));
        barC.setPadding(5f);
        barC.setBorderColor(hex(GRID_COLOR));
        bar.addCell(barC);

        doc.add(hdr); doc.add(master); doc.add(bar);
    }

    private void renderEntradasPlenarioPrincipal(Document doc, List<Map<String, Object>> entradas, Font sm, Font smHdr) {
        // Plenário Principal: Operador | Anom? (com rowspan)
        float[] eColW = scaleWeights(COLS_OPERACOES_SESSOES_ENT_PLENARIO, PAGE_WIDTH);
        PdfPTable eTbl = new PdfPTable(eColW);
        eTbl.setWidthPercentage(100);
        for (String h : HDR_OPERACOES_SESSOES_ENT_PLENARIO) {
            eTbl.addCell(detailHeaderCell(h, smHdr));
        }
        if (entradas != null && !entradas.isEmpty()) {
            for (Map<String, Object> ent : entradas) {
                addEntradaPlenario(eTbl, ent, sm);
            }
        } else {
            eTbl.addCell(emptyEntradaCell(2, sm));
        }
        doc.add(eTbl);
    }

    @SuppressWarnings("unchecked")
    private void addEntradaPlenario(PdfPTable eTbl, Map<String, Object> ent, Font sm) {
        boolean anom = bool(ent, "anormalidade");
        List<String> operadores = (List<String>) ent.get("operadores");
        if (operadores != null && !operadores.isEmpty()) {
            for (int oi = 0; oi < operadores.size(); oi++) {
                eTbl.addCell(itemCell(operadores.get(oi), sm));
                if (oi == 0) {
                    PdfPCell ac = anomCell(anom, 8f);
                    ac.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    ac.setRowspan(operadores.size());
                    eTbl.addCell(ac);
                }
            }
        } else {
            eTbl.addCell(itemCell(str(ent, "preenchido_por"), sm));
            eTbl.addCell(anomCell(anom, 8f));
        }
    }

    private void renderEntradasNumeradas(Document doc, List<Map<String, Object>> entradas, Font sm, Font smHdr) {
        // Plenários numerados: Nº | Operador | Início Op. | Fim Op. | Observações | Anom?
        float[] eColW = scaleWeights(COLS_OPERACOES_SESSOES_ENT_NORMAL, PAGE_WIDTH);
        PdfPTable eTbl = new PdfPTable(eColW);
        eTbl.setWidthPercentage(100);
        for (String h : HDR_OPERACOES_SESSOES_ENT_NORMAL) {
            eTbl.addCell(detailHeaderCell(h, smHdr));
        }
        if (entradas != null && !entradas.isEmpty()) {
            for (Map<String, Object> ent : entradas) {
                String oTxt = ordemDisplay(ent.get("ordem"));
                boolean anom = bool(ent, "anormalidade");
                String obs = truncate(str(ent, "observacoes"), 20);

                eTbl.addCell(centerItemCell(oTxt, sm));
                eTbl.addCell(itemCell(str(ent, "operador"), sm));
                eTbl.addCell(centerItemCell(fmtTime(ent.get("hora_entrada")), sm));
                eTbl.addCell(centerItemCell(fmtTime(ent.get("hora_saida")), sm));
                eTbl.addCell(itemCell(obs, sm));
                eTbl.addCell(anomCell(anom, 8f));
            }
        } else {
            eTbl.addCell(emptyEntradaCell(6, sm));
        }
        doc.add(eTbl);
    }

    private void addDivider(Document doc) {
        // Linha divisória entre registros
        PdfPTable divider = new PdfPTable(1);
        divider.setWidthPercentage(100);
        divider.setSpacingBefore(6f);
        divider.setSpacingAfter(6f);
        PdfPCell divCell = new PdfPCell();
        divCell.setBorder(com.lowagie.text.Rectangle.BOTTOM);
        divCell.setBorderColorBottom(hex(COLOR_SLATE));
        divCell.setBorderWidthBottom(2f);
        divCell.setFixedHeight(1f);
        divider.addCell(divCell);
        doc.add(divider);
    }

    // ══ Infraestrutura de geração ═══════════════════════════════

    @FunctionalInterface
    private interface RowBuilder {
        void build(PdfPTable table, Map<String, Object> row, Font normalFont);
    }

    private byte[] buildFlatReport(String title, List<Map<String, Object>> rows,
                                    String[] headers, int[] weights,
                                    RowBuilder builder, float fontSize, float leading) {
        Font titleFont = pdfFont(14f, Font.BOLD, null);
        Font hdrFont = pdfFont(fontSize, Font.BOLD, hex(COLOR_DARK));
        Font nf = pdfFont(fontSize, Font.NORMAL, null);

        Document doc = new Document(PageSize.A4, LEFT_MARGIN, RIGHT_MARGIN, TOP_MARGIN, BOTTOM_MARGIN);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, buf);
        doc.open();

        doc.add(new Paragraph(title, titleFont));
        doc.add(new Paragraph(" "));

        if (rows.isEmpty()) {
            doc.add(new Paragraph("Nenhum registro encontrado para os filtros aplicados.", nf));
            doc.close();
            return mergeWithTemplate(buf.toByteArray());
        }

        float[] colW = scaleWeights(weights, PAGE_WIDTH);
        PdfPTable tbl = new PdfPTable(colW);
        tbl.setWidthPercentage(100);
        tbl.setHeaderRows(1);

        for (String h : headers) tbl.addCell(headerCell(h, hdrFont));
        for (Map<String, Object> r : rows) builder.build(tbl, r, nf);

        doc.add(tbl);
        addTotalRow(doc, rows.size(), nf);
        doc.close();
        return mergeWithTemplate(buf.toByteArray());
    }

    private byte[] mergeWithTemplate(byte[] contentPdf) {
        try {
            ClassPathResource res = new ClassPathResource("assets/Modelo.pdf");
            if (!res.exists()) return contentPdf;

            PdfReader tmpl = new PdfReader(res.getInputStream());
            PdfReader content = new PdfReader(contentPdf);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(tmpl.getPageSizeWithRotation(1));
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();

            PdfContentByte cb = writer.getDirectContentUnder();
            PdfImportedPage tmplPage = writer.getImportedPage(tmpl, 1);

            for (int i = 1; i <= content.getNumberOfPages(); i++) {
                doc.newPage();
                // Template como fundo
                cb.addTemplate(tmplPage, 0, 0);
                // Conteúdo por cima
                PdfImportedPage contentPage = writer.getImportedPage(content, i);
                writer.getDirectContent().addTemplate(contentPage, 0, 0);
            }

            doc.close();
            tmpl.close();
            content.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Erro ao aplicar template PDF, retornando sem overlay: {}", e.getMessage());
            return contentPdf;
        }
    }

    private void addTotalRow(Document doc, int total, Font nf) {
        PdfPTable tbl = new PdfPTable(new float[]{PAGE_WIDTH * 0.80f, PAGE_WIDTH * 0.20f});
        tbl.setWidthPercentage(100);
        tbl.setSpacingBefore(10f);

        Font boldUnd = pdfFont(10f, Font.BOLD | Font.UNDERLINE, null);

        PdfPCell c1 = new PdfPCell(new Phrase("Total", boldUnd));
        c1.setBackgroundColor(hex(HEADER_FILL));
        c1.setBorderColor(hex(GRID_COLOR));
        c1.setPadding(5f);
        tbl.addCell(c1);

        PdfPCell c2 = new PdfPCell(new Phrase(String.valueOf(total), boldUnd));
        c2.setBackgroundColor(hex(HEADER_FILL));
        c2.setBorderColor(hex(GRID_COLOR));
        c2.setPadding(5f);
        c2.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tbl.addCell(c2);

        doc.add(tbl);
    }

    // ══ Cell helpers ════════════════════════════════════════════

    private PdfPCell headerCell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(hex(HEADER_FILL));
        c.setBorderColor(hex(GRID_COLOR));
        c.setPadding(4f);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private PdfPCell cell(String text, Font font, boolean bold) {
        Font f = bold ? pdfFont(font.getSize(), Font.BOLD, font.getColor()) : font;
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorderColor(hex(GRID_COLOR));
        c.setPadding(4f);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private PdfPCell centerCell(String text, Font font) {
        PdfPCell c = cell(text, font, false);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    private PdfPCell colorCenter(String text, Font font, String colorHex) {
        PdfPCell c = new PdfPCell(new Phrase(text, pdfFont(font.getSize(), Font.BOLD, hex(colorHex))));
        c.setBorderColor(hex(GRID_COLOR));
        c.setPadding(4f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private PdfPCell boolCell(boolean value, Font baseFont, String trueColor, String falseColor) {
        String txt = value ? "Sim" : "Não";
        Color c = hex(value ? trueColor : falseColor);
        Font f = pdfFont(baseFont.getSize(), Font.BOLD, c);
        PdfPCell cell = new PdfPCell(new Phrase(txt, f));
        cell.setBorderColor(hex(GRID_COLOR));
        cell.setPadding(4f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPCell dataCell(String text, Font font, boolean bold) {
        Font f = bold ? pdfFont(font.getSize(), Font.BOLD, font.getColor()) : font;
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(hex(DATA_ROW_FILL));
        c.setBorderColor(hex(GRID_COLOR));
        c.setPadding(4f);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private PdfPCell itemCell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorderColor(hex(GRID_COLOR));
        c.setPadding(4f);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        return c;
    }

    private PdfPCell centerItemCell(String text, Font font) {
        PdfPCell c = itemCell(text, font);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    private PdfPCell detailHeaderCell(String text, Font font) {
        PdfPCell hc = new PdfPCell(new Phrase(text, font));
        hc.setBackgroundColor(hex(HEADER_DETAIL_FILL));
        hc.setBorderColor(hex(GRID_COLOR));
        hc.setPadding(4f);
        return hc;
    }

    private PdfPCell anomCell(boolean anom, float fontSize) {
        PdfPCell ac = itemCell(anom ? "SIM" : "Não", pdfFont(fontSize, Font.BOLD, anom ? hex(COLOR_RED) : hex(COLOR_GREEN)));
        ac.setHorizontalAlignment(Element.ALIGN_CENTER);
        return ac;
    }

    private PdfPCell emptyEntradaCell(int colspan, Font font) {
        PdfPCell empty = itemCell("Nenhuma entrada registrada nesta sessão.", font);
        empty.setColspan(colspan);
        return empty;
    }

    // ══ Data helpers ════════════════════════════════════════════

    private String strOrEmpty(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }
}
