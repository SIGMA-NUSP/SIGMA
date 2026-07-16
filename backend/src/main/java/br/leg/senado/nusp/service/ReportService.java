package br.leg.senado.nusp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Infraestrutura de relatórios — decide formato (PDF/DOCX/XLSX),
 * gera os bytes e devolve ResponseEntity com headers corretos.
 * Equivale a report_service.py do Python.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /**
     * Dispatcher — decide formato baseado em ?format= e chama o builder correspondente.
     * PDF: inline (abre no browser). DOCX: attachment (download).
     */
    public ResponseEntity<?> respond(String format, String filenameBase,
                                      Supplier<byte[]> pdfBuilder,
                                      Supplier<byte[]> docxBuilder) {
        String fmt = normalizeFormat(format);

        if ("invalid".equals(fmt)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "invalid_format"));
        }

        if ("docx".equals(fmt)) {
            if (docxBuilder == null) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "format_not_supported"));
            }
            return respondDocx(docxBuilder.get(), filenameBase);
        }

        return respondPdf(pdfBuilder.get(), filenameBase);
    }

    /**
     * Resposta placeholder para endpoints ainda não implementados.
     */
    public ResponseEntity<?> placeholderResponse(String format, String reportName) {
        return ResponseEntity.ok(Map.of(
                "ok", false,
                "error", "report_not_implemented",
                "message", "Relatório '" + reportName + "' em formato '" + format + "' será implementado na Fase 5."
        ));
    }

    public ResponseEntity<byte[]> respondPdf(byte[] pdfBytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + ".pdf\"")
                .body(pdfBytes);
    }

    public ResponseEntity<byte[]> respondDocx(byte[] docxBytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".docx\"")
                .body(docxBytes);
    }

    public ResponseEntity<byte[]> respondXlsx(byte[] xlsxBytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".xlsx\"")
                .body(xlsxBytes);
    }

    private String normalizeFormat(String raw) {
        if (raw == null || raw.isBlank()) return "pdf";
        String fmt = raw.strip().toLowerCase();
        if (fmt.startsWith(".")) fmt = fmt.substring(1);
        if ("pdf".equals(fmt) || "docx".equals(fmt)) return fmt;
        return "invalid";
    }
}
