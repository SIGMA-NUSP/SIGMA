package br.leg.senado.nusp.service;

import br.leg.senado.nusp.service.GradeRetificacaoService.Celula;
import br.leg.senado.nusp.service.GradeRetificacaoService.Dia;
import br.leg.senado.nusp.service.GradeRetificacaoService.Funcionario;
import br.leg.senado.nusp.service.GradeRetificacaoService.Grade;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Exportação XLSX da grade de retificações (E11, Bloco B-5) — geração do zero
 * com Apache POI (Q30), reproduzindo o formato de `assets/ponto_nusp.xlsx`
 * (Seção 7.2) SEM os bugs do arquivo manual: 1 aba `AAMM`, todos os
 * funcionários da categoria (sem paginação — F#7), cores estáticas (não
 * formatação condicional — 7.2.5), fórmula "Folgas" com o range correto do
 * mês (Q28), observações como comentários de célula (F#6) e arquivo limpo
 * (sem Tables/linhas fantasma — Q29). Mesma fonte de dados da grade
 * ({@link GradeRetificacaoService#montarGrade}); a precedência do §1 já vem
 * resolvida. O arquivo de referência é intocável (gotcha 8).
 */
@Service
@RequiredArgsConstructor
public class PontoXlsxService {

    private final GradeRetificacaoService gradeService;

    /** Gera o XLSX do mês/categoria. Retorna os bytes do workbook. */
    public byte[] gerar(String categoria, int ano, int mes) {
        Grade g = gradeService.montarGrade(categoria, ano, mes);
        int nFunc = g.funcionarios().size();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet(String.format("%02d%02d", ano % 100, mes));   // AAMM (ex.: 2606)
            sheet.createFreezePane(1, 1);   // painel congelado em B1 (coluna A + linha 1)

            // Cores (RGB estáticos — 7.2.5)
            XSSFColor vermelho = cor(0xFF, 0x00, 0x00);
            XSSFColor azulCabecalho = cor(0x0B, 0x53, 0x94);
            XSSFColor cinza = cor(0x43, 0x43, 0x43);
            XSSFColor azulClaro = cor(0xDE, 0xEA, 0xF6);
            XSSFColor amarelo = cor(0xFF, 0xFF, 0x00);
            XSSFColor branco = cor(0xFF, 0xFF, 0xFF);
            short fmtData = wb.createDataFormat().getFormat("[$-416]d\\-mmm");   // "1-jun" (locale pt-BR)

            // Estilos (um por papel — reusados em todas as células)
            XSSFCellStyle corner = estilo(wb, vermelho, null, false, false, HorizontalAlignment.CENTER, (short) 0);
            XSSFCellStyle cabecalho = estilo(wb, azulCabecalho, branco, false, true, HorizontalAlignment.CENTER, (short) 0);
            XSSFCellStyle folgasRotulo = estilo(wb, cinza, branco, true, false, HorizontalAlignment.LEFT, (short) 0);
            XSSFCellStyle folgasCel = estilo(wb, cinza, branco, false, false, HorizontalAlignment.CENTER, (short) 0);
            XSSFCellStyle dataUtil = estilo(wb, azulClaro, null, false, false, HorizontalAlignment.LEFT, fmtData);
            XSSFCellStyle dataFds = estilo(wb, cinza, branco, false, false, HorizontalAlignment.LEFT, fmtData);
            XSSFCellStyle celPreenchida = estilo(wb, amarelo, vermelho, false, false, HorizontalAlignment.CENTER, (short) 0);
            XSSFCellStyle celVazia = estilo(wb, azulClaro, null, false, false, HorizontalAlignment.CENTER, (short) 0);
            XSSFCellStyle celFds = estilo(wb, cinza, branco, false, false, HorizontalAlignment.CENTER, (short) 0);

            // Larguras (7.2.2): A ≈ 7; funcionários ~15
            sheet.setColumnWidth(0, 7 * 256);
            for (int c = 0; c < nFunc; c++) sheet.setColumnWidth(c + 1, 15 * 256);

            // ── Linha 1: A1 vermelha (Q29) + nomes (wrap, sem \n manuais — F#3) ──
            XSSFRow r0 = sheet.createRow(0);
            r0.setHeightInPoints(64);
            r0.createCell(0).setCellStyle(corner);
            for (int c = 0; c < nFunc; c++) {
                XSSFCell cell = r0.createCell(c + 1);
                cell.setCellValue(g.funcionarios().get(c).nome());
                cell.setCellStyle(cabecalho);
            }

            // ── Linha 2: "Folgas" + fórmula COUNTIF do mês real (Q28) ──
            XSSFRow r1 = sheet.createRow(1);
            XSSFCell a2 = r1.createCell(0);
            a2.setCellValue("Folgas");
            a2.setCellStyle(folgasRotulo);
            int ultimaLinhaDados = 2 + g.diasNoMes();   // linha do último dia (1-based)
            for (int c = 0; c < nFunc; c++) {
                XSSFCell cell = r1.createCell(c + 1);
                String col = CellReference.convertNumToColString(c + 1);   // B, C, …
                cell.setCellFormula("COUNTIF(" + col + "3:" + col + ultimaLinhaDados + ",\"b*\")");
                cell.setCellStyle(folgasCel);
            }

            // ── Linhas 3+: um dia por linha; conteúdo pela precedência do §1 ──
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            CreationHelper helper = wb.getCreationHelper();
            for (Dia dia : g.dias()) {
                XSSFRow row = sheet.createRow(1 + dia.dia());   // dia 1 → linha 3 (índice 2)
                XSSFCell dcell = row.createCell(0);
                dcell.setCellValue(dia.data());                  // data real → numFmt formata
                dcell.setCellStyle(dia.fimDeSemana() ? dataFds : dataUtil);

                for (int c = 0; c < nFunc; c++) {
                    XSSFCell cell = row.createCell(c + 1);
                    Celula cel = celula(g, g.funcionarios().get(c), dia.dia());
                    if (cel != null) cell.setCellValue(cel.texto());   // texto normalizado (7.2.6)
                    // Fim de semana pinta a linha inteira de cinza, vencendo o conteúdo (B-3.4)
                    if (dia.fimDeSemana()) {
                        cell.setCellStyle(celFds);
                    } else if (cel != null) {
                        cell.setCellStyle(celPreenchida);
                        if (cel.temObs()) addComentario(drawing, helper, cell, cel.obs());   // F#6
                    } else {
                        cell.setCellStyle(celVazia);
                    }
                }
            }

            wb.setForceFormulaRecalculation(true);   // Folgas recalcula ao abrir (Q28)

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            wb.write(buf);
            return buf.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar o XLSX da grade de ponto", e);
        }
    }

    private static Celula celula(Grade g, Funcionario f, int dia) {
        return g.celulas().getOrDefault(f.id(), Map.of()).get(dia);
    }

    /** Observação da retificação como comentário de célula (marca vermelha no canto — F#6/B-3.7). */
    private static void addComentario(XSSFDrawing drawing, CreationHelper helper, XSSFCell cell, String texto) {
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 3);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 4);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(texto));
        cell.setCellComment(comment);
    }

    private static XSSFColor cor(int r, int g, int b) {
        // ARGB (alpha FF + RGB) — 8 dígitos, formato válido do OOXML (6 hex não tem alpha).
        return new XSSFColor(new byte[]{(byte) 0xFF, (byte) r, (byte) g, (byte) b}, null);
    }

    private static XSSFCellStyle estilo(XSSFWorkbook wb, XSSFColor fill, XSSFColor fontColor,
                                        boolean bold, boolean wrap, HorizontalAlignment halign, short dataFormat) {
        XSSFCellStyle s = wb.createCellStyle();
        if (fill != null) {
            s.setFillForegroundColor(fill);
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        XSSFFont f = wb.createFont();
        f.setFontName("Calibri");
        f.setFontHeightInPoints((short) 11);
        f.setBold(bold);
        if (fontColor != null) f.setColor(fontColor);
        s.setFont(f);
        s.setWrapText(wrap);
        s.setAlignment(halign);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        if (dataFormat != 0) s.setDataFormat(dataFormat);
        return s;
    }
}
