package br.leg.senado.nusp.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do ReportService (classe pura, sem dependências, sem acesso a banco).
 *
 * Prioridade: normalização de formato e a garantia de que só o Supplier do formato
 * escolhido é invocado — os builders de PDF/DOCX/XLSX fazem trabalho pesado (POI,
 * OpenPDF) e nunca podem rodar em dobro nem para o formato errado.
 */
class ReportServiceTest {

    private final ReportService service = new ReportService();

    private static Supplier<byte[]> naoDeveSerChamado() {
        return () -> { throw new AssertionError("Supplier não deveria ser invocado para este formato"); };
    }

    @Nested
    @DisplayName("respond — normalização de formato")
    class Normalizacao {

        @Test
        @DisplayName("format null → pdf (default)")
        void formatNull_defaultsToPdf() {
            ResponseEntity<?> resp = service.respond(null, "relatorio", () -> new byte[]{1}, naoDeveSerChamado());

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(MediaType.APPLICATION_PDF, resp.getHeaders().getContentType());
        }

        @Test
        @DisplayName("format em branco → pdf (default)")
        void formatBlank_defaultsToPdf() {
            ResponseEntity<?> resp = service.respond("   ", "relatorio", () -> new byte[]{1}, naoDeveSerChamado());

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(MediaType.APPLICATION_PDF, resp.getHeaders().getContentType());
        }

        @Test
        @DisplayName("espaços nas bordas + caixa mista são normalizados (\" PDF \", \"DOCX\")")
        void stripAndLowercaseBeforeMatching() {
            ResponseEntity<?> respPdf = service.respond(" PDF ", "relatorio", () -> new byte[]{1}, naoDeveSerChamado());
            assertEquals(MediaType.APPLICATION_PDF, respPdf.getHeaders().getContentType());

            ResponseEntity<?> respDocx = service.respond("DOCX", "relatorio", naoDeveSerChamado(), () -> new byte[]{2});
            assertEquals(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    respDocx.getHeaders().getContentType().toString());
        }

        @Test
        @DisplayName("\".pdf\" com ponto inicial é normalizado para \"pdf\"")
        void leadingDot_isStripped() {
            ResponseEntity<?> resp = service.respond(".pdf", "relatorio", () -> new byte[]{1}, naoDeveSerChamado());

            assertEquals(MediaType.APPLICATION_PDF, resp.getHeaders().getContentType());
        }

        @Test
        @DisplayName("\"xlsx\" passado a respond → 400 invalid_format (XLSX só via respondXlsx direto)")
        void xlsx_isInvalidThroughRespond() {
            ResponseEntity<?> resp = service.respond("xlsx", "relatorio", naoDeveSerChamado(), naoDeveSerChamado());

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            assertEquals(Map.of("ok", false, "error", "invalid_format"), resp.getBody());
        }

        @Test
        @DisplayName("formato desconhecido → 400 invalid_format")
        void unknownFormat_badRequest() {
            ResponseEntity<?> resp = service.respond("csv", "relatorio", naoDeveSerChamado(), naoDeveSerChamado());

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            assertEquals(Map.of("ok", false, "error", "invalid_format"), resp.getBody());
        }
    }

    @Nested
    @DisplayName("respond — docx sem builder disponível")
    class DocxNaoSuportado {

        @Test
        @DisplayName("docxBuilder == null com format=docx → 400 format_not_supported")
        void docxBuilderNull_notSupported() {
            ResponseEntity<?> resp = service.respond("docx", "relatorio", naoDeveSerChamado(), null);

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
            assertEquals(Map.of("ok", false, "error", "format_not_supported"), resp.getBody());
        }
    }

    @Nested
    @DisplayName("respond — só o Supplier do formato escolhido é invocado")
    class SupplierUnicoInvocado {

        @Test
        @DisplayName("pdf escolhido → docxBuilder nunca é chamado")
        void pdfChosen_docxSupplierNeverInvoked() {
            assertDoesNotThrow(() -> service.respond("pdf", "relatorio", () -> new byte[]{9}, naoDeveSerChamado()));
        }

        @Test
        @DisplayName("docx escolhido → pdfBuilder nunca é chamado")
        void docxChosen_pdfSupplierNeverInvoked() {
            assertDoesNotThrow(() -> service.respond("docx", "relatorio", naoDeveSerChamado(), () -> new byte[]{9}));
        }
    }

    @Nested
    @DisplayName("Content-Disposition: PDF inline, DOCX/XLSX attachment")
    class ContentDisposition {

        @Test
        @DisplayName("PDF → inline")
        void pdf_isInline() {
            ResponseEntity<byte[]> resp = service.respondPdf(new byte[]{1}, "relatorio");

            String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertNotNull(disposition);
            assertTrue(disposition.startsWith("inline;"));
            assertTrue(disposition.contains("relatorio.pdf"));
        }

        @Test
        @DisplayName("DOCX → attachment")
        void docx_isAttachment() {
            ResponseEntity<byte[]> resp = service.respondDocx(new byte[]{1}, "relatorio");

            String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertNotNull(disposition);
            assertTrue(disposition.startsWith("attachment;"));
            assertTrue(disposition.contains("relatorio.docx"));
        }

        @Test
        @DisplayName("XLSX → attachment")
        void xlsx_isAttachment() {
            ResponseEntity<byte[]> resp = service.respondXlsx(new byte[]{1}, "relatorio");

            String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertNotNull(disposition);
            assertTrue(disposition.startsWith("attachment;"));
            assertTrue(disposition.contains("relatorio.xlsx"));
        }
    }

    @Nested
    @DisplayName("respondPdf / respondDocx / respondXlsx — Content-Type e bytes")
    class RespondDireto {

        @Test
        @DisplayName("respondPdf — application/pdf, bytes preservados, HTTP 200")
        void respondPdf_contentTypeAndBytesPreserved() {
            byte[] bytes = {1, 2, 3, 4};

            ResponseEntity<byte[]> resp = service.respondPdf(bytes, "arq");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(MediaType.APPLICATION_PDF, resp.getHeaders().getContentType());
            assertArrayEquals(bytes, resp.getBody());
        }

        @Test
        @DisplayName("respondDocx — OOXML wordprocessing, bytes preservados")
        void respondDocx_contentTypeAndBytesPreserved() {
            byte[] bytes = {5, 6, 7};

            ResponseEntity<byte[]> resp = service.respondDocx(bytes, "arq");

            assertEquals(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    resp.getHeaders().getContentType().toString());
            assertArrayEquals(bytes, resp.getBody());
        }

        @Test
        @DisplayName("respondXlsx — OOXML spreadsheet, bytes preservados")
        void respondXlsx_contentTypeAndBytesPreserved() {
            byte[] bytes = {8, 9};

            ResponseEntity<byte[]> resp = service.respondXlsx(bytes, "arq");

            assertEquals(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    resp.getHeaders().getContentType().toString());
            assertArrayEquals(bytes, resp.getBody());
        }
    }

    @Nested
    @DisplayName("placeholderResponse")
    class Placeholder {

        @Test
        @DisplayName("HTTP 200 com ok:false no corpo (caracterização — endpoint ainda não implementado)")
        void returns200WithOkFalseInBody() {
            ResponseEntity<?> resp = service.placeholderResponse("pdf", "Relatório X");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertNotNull(body);
            assertEquals(false, body.get("ok"));
            assertEquals("report_not_implemented", body.get("error"));
            assertTrue(body.get("message").toString().contains("Relatório X"));
            assertTrue(body.get("message").toString().contains("pdf"));
        }
    }
}
