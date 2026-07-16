package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.AdminOnly;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.BancoHorasService;
import br.leg.senado.nusp.service.GradeRetificacaoService;
import br.leg.senado.nusp.service.MarcacaoService;
import br.leg.senado.nusp.service.PontoExclusaoService;
import br.leg.senado.nusp.service.PontoService;
import br.leg.senado.nusp.service.PontoService.ArquivoPonto;
import br.leg.senado.nusp.service.PontoXlsxService;
import br.leg.senado.nusp.service.ReportDocxService;
import br.leg.senado.nusp.service.ReportPdfService;
import br.leg.senado.nusp.service.ReportService;
import br.leg.senado.nusp.service.RetificacaoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.controller.ControllerUtils.REPORT_LIMIT;
import static br.leg.senado.nusp.controller.ControllerUtils.getInt;
import static br.leg.senado.nusp.controller.ControllerUtils.optBooleano;
import static br.leg.senado.nusp.controller.ControllerUtils.optTexto;
import static br.leg.senado.nusp.controller.ControllerUtils.pagedResponse;
import static br.leg.senado.nusp.controller.ControllerUtils.parseJson;

/**
 * Ponto e Banco de Horas.
 * Admin:            upload/separação/vínculo/publicação em /api/admin/ponto/**
 * Operador/Técnico: listagem e download da própria folha em /api/ponto/**
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Ponto e Banco",
     description = "Cartões-ponto: upload/separação/vínculo (admin) e download individual (operador/técnico)")
public class PontoController {

    private final PontoService pontoService;
    private final PontoExclusaoService pontoExclusaoService;
    private final RetificacaoService retificacaoService;
    private final MarcacaoService marcacaoService;
    private final GradeRetificacaoService gradeRetificacaoService;
    private final PontoXlsxService pontoXlsxService;
    private final BancoHorasService bancoHorasService;
    private final ReportService reportService;
    private final ReportPdfService pdfService;
    private final ReportDocxService docxService;
    private final ObjectMapper objectMapper;

    // ══ Admin ═══════════════════════════════════════════════════

    @AdminOnly
    @PostMapping("/api/admin/ponto/upload")
    public ResponseEntity<?> upload(
            @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam("tipo") String tipo,
            @RequestParam("data_inicio") String dataInicio,
            @RequestParam("data_fim") String dataFim,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoService.upload(arquivo, tipo, dataInicio, dataFim, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", data));
    }

    /**
     * Lotes enviados. A resposta carrega {@code pode_excluir} — o backend comparando o principal ao
     * {@code app.admin.master-username} (F59). É essa flag, e nunca o username no front, que decide se
     * o X de exclusão aparece; a SEGURANÇA continua sendo o 403 do service.
     */
    @AdminOnly
    @GetMapping("/api/admin/ponto/lotes")
    public ResponseEntity<?> lotes(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "data", pontoService.listarLotes(),
                "pode_excluir", pontoExclusaoService.podeExcluir(principal.getUsername())));
    }

    @AdminOnly
    @GetMapping("/api/admin/ponto/lote/{id}")
    public ResponseEntity<?> lote(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.obterLote(id)));
    }

    @AdminOnly
    @GetMapping("/api/admin/ponto/pessoas")
    public ResponseEntity<?> pessoas() {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.listarPessoas()));
    }

    /**
     * Vincula a página a uma pessoa — ou a DESVINCULA, quando {@code pessoa_id} não vem no corpo
     * (é o {@code null} que devolve a página ao estado PENDENTE, e por isso os dois campos são
     * opcionais, não obrigatórios). Tipo errado no campo → 400 nomeando-o (F35).
     */
    @AdminOnly
    @PatchMapping("/api/admin/ponto/lote/{loteId}/pagina/{paginaId}")
    public ResponseEntity<?> vincular(
            @PathVariable String loteId,
            @PathVariable String paginaId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> data = pontoService.atualizarVinculo(loteId, paginaId,
                optTexto(body, "pessoa_id"), optTexto(body, "pessoa_tipo"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * Publica o lote. O aviso é o DEFAULT (corpo ausente ou sem o campo → emite); só o booleano
     * {@code false} o desliga, e um {@code emitir_aviso} de outro tipo é 400 nomeando o campo — a
     * string {@code "false"} publicava COM aviso, disparando um aviso pessoal por pessoa da folha
     * exatamente quando o cliente pediu silêncio (F35).
     */
    @AdminOnly
    @PostMapping("/api/admin/ponto/lote/{id}/publicar")
    public ResponseEntity<?> publicar(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        boolean emitirAviso = optBooleano(body, "emitir_aviso", true);
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.publicar(id, emitirAviso)));
    }

    @AdminOnly
    @GetMapping("/api/admin/ponto/pagina/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable String id) {
        return streamPdf(pontoService.previewPagina(id), false);
    }

    // ══ Exclusão de publicações (F59) — SOMENTE o admin master ═══
    //
    // O @AdminOnly barra o não-admin; o master é conferido no service (403 "forbidden"), que é onde a
    // permissão pode ser provada. As quatro rotas são master-only, preview inclusive: o preview conta
    // retificações e nomeia destinatários de aviso — não é informação de qualquer admin.

    /** O que a exclusão do LOTE vai destruir (consultivo — a verdade é a da transação de exclusão). */
    @AdminOnly
    @GetMapping("/api/admin/ponto/lote/{id}/exclusao/preview")
    public ResponseEntity<?> previewExclusaoLote(@PathVariable String id,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoExclusaoService.previewLote(id, principal.getUsername());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** O que a exclusão daquela FOLHA vai destruir. */
    @AdminOnly
    @GetMapping("/api/admin/ponto/lote/{loteId}/pagina/{paginaId}/exclusao/preview")
    public ResponseEntity<?> previewExclusaoPagina(@PathVariable String loteId,
                                                   @PathVariable String paginaId,
                                                   @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoExclusaoService.previewPagina(loteId, paginaId, principal.getUsername());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** Exclui o lote e tudo que a publicação dele criou. Irreversível — a trilha fica em PNT_EXCLUSAO_LOG. */
    @AdminOnly
    @DeleteMapping("/api/admin/ponto/lote/{id}")
    public ResponseEntity<?> excluirLote(@PathVariable String id,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoExclusaoService.excluirLote(id, principal.getUsername(), principal.getId());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** Exclui UMA folha do lote (o lote sobrevive, ainda que fique vazio — §4.2 do estágio). */
    @AdminOnly
    @DeleteMapping("/api/admin/ponto/lote/{loteId}/pagina/{paginaId}")
    public ResponseEntity<?> excluirPagina(@PathVariable String loteId,
                                           @PathVariable String paginaId,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoExclusaoService.excluirPagina(loteId, paginaId,
                principal.getUsername(), principal.getId());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * Backfill/manutenção do banco de horas (E2): parseia o BANCO_FINAL_MIN das
     * páginas publicadas que ainda não o têm e re-ancora os saldos de todas as
     * pessoas com folha. Idempotente; páginas sem banco extraível vêm listadas.
     */
    @AdminOnly
    @PostMapping("/api/admin/ponto/banco/reprocessar")
    public ResponseEntity<?> reprocessarBanco() {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.reprocessarBanco()));
    }

    /** Marcações do mês (globais + pessoa-dia) para o modal Configurar (qualquer admin — Q36). */
    @AdminOnly
    @GetMapping("/api/admin/ponto/marcacoes")
    public ResponseEntity<?> listarMarcacoes(@RequestParam int ano, @RequestParam int mes) {
        return ResponseEntity.ok(Map.of("ok", true, "data", marcacaoService.listar(ano, mes)));
    }

    /** Aplica em lote o modal Configurar: upsert/remoção de marcações globais e/ou de uma pessoa. */
    @AdminOnly
    @PutMapping("/api/admin/ponto/marcacoes")
    public ResponseEntity<?> aplicarMarcacoes(@RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        marcacaoService.aplicarLote(body, principal.getId());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Grade mensal de retificações (card admin "Retificações", E10): matriz
     * funcionários da categoria × dias do mês, com a precedência do §1 (horários →
     * banco → marcação pessoa-dia → marcação global → vazia) resolvida por célula
     * no backend. Categoria = operadores | tecnicos | administradores (SP=0 —
     * Q26/Q38). Qualquer admin (Q36).
     */
    @AdminOnly
    @GetMapping("/api/admin/ponto/retificacoes/grade")
    public ResponseEntity<?> gradeRetificacoes(@RequestParam String categoria,
                                               @RequestParam int ano,
                                               @RequestParam int mes) {
        return ResponseEntity.ok(Map.of("ok", true, "data", gradeRetificacaoService.montar(categoria, ano, mes)));
    }

    /** Exportação XLSX da grade (E11, B-5): mesmo conteúdo, formato de ponto_nusp.xlsx. Nome ponto_{categoria}_{AAMM}.xlsx (Q31). */
    @AdminOnly
    @GetMapping("/api/admin/ponto/retificacoes/grade/xlsx")
    public ResponseEntity<?> gradeRetificacoesXlsx(@RequestParam String categoria,
                                                   @RequestParam int ano,
                                                   @RequestParam int mes) {
        byte[] xlsx = pontoXlsxService.gerar(categoria, ano, mes);
        String nome = "ponto_" + categoria.strip().toLowerCase() + "_" + String.format("%02d%02d", ano % 100, mes);
        return reportService.respondXlsx(xlsx, nome);
    }

    // ══ Operador / Técnico ══════════════════════════════════════

    @GetMapping("/api/ponto/minhas-folhas")
    public ResponseEntity<?> minhasFolhas(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.minhasFolhas(principal.getId())));
    }

    @GetMapping("/api/ponto/folha/{paginaId}/download")
    public ResponseEntity<?> download(@PathVariable String paginaId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return streamPdf(pontoService.baixarFolha(paginaId, principal.getId(), principal.getRole()), true);
    }

    /** Dados parseados da folha (7 colunas por dia) para a tela de retificação. */
    @GetMapping("/api/ponto/folha/{paginaId}/dados")
    public ResponseEntity<?> dadosFolha(@PathVariable String paginaId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoService.dadosFolha(paginaId, principal.getId(), principal.getRole());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** Dias já retificados da folha + dia-limite/prazo (para a UI marcar e bloquear). */
    @GetMapping("/api/ponto/folha/{paginaId}/retificacoes")
    public ResponseEntity<?> listarRetificacoes(@PathVariable String paginaId,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = retificacaoService.listarRetificacoes(paginaId, principal.getId());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * Registra a retificação da própria folha publicada (Bloco B-1) — TODOS os dias num corpo só
     * ({@code {"dias":[…]}}), gravados numa única transação: ou o lote inteiro entra, ou nada entra
     * (F39). Substituiu o antigo POST por dia, que gravava N transações independentes e deixava
     * estado parcial IRREVERSÍVEL (a v1 não tem edição nem exclusão — Q1).
     */
    @PostMapping("/api/ponto/folha/{paginaId}/retificacoes")
    public ResponseEntity<?> criarRetificacoes(@PathVariable String paginaId,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = retificacaoService.criarRetificacoes(paginaId, principal.getId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", data));
    }

    // ══ Banco de Horas (Bloco C / E7) ═══════════════════════════

    /** Saldo + folgas do mês + dias bloqueados do mês pedido ({ano, mes} do seletor). */
    @GetMapping("/api/ponto/banco")
    public ResponseEntity<?> banco(@RequestParam int ano, @RequestParam int mes,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = bancoHorasService.consultar(principal.getId(), principal.getRole(), ano, mes);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** Solicita folgas ({dias: ["YYYY-MM-DD", ...]}) — 1 linha PENDENTE por dia (C-5.3/C-5.4). */
    @PostMapping("/api/ponto/banco/solicitar")
    public ResponseEntity<?> solicitarFolgas(@RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = bancoHorasService.solicitar(principal.getId(), principal.getRole(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", data));
    }

    /** Cancela uma solicitação PENDENTE do próprio dono (Q19). */
    @PatchMapping("/api/ponto/banco/solicitacao/{id}/cancelar")
    public ResponseEntity<?> cancelarSolicitacao(@PathVariable String id,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = bancoHorasService.cancelar(id, principal.getId(), principal.getRole());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** Tabela "Minhas Solicitações" (C-4) — paginada, filtros de coluna, facetas. */
    @GetMapping("/api/ponto/banco/solicitacoes")
    public ResponseEntity<?> minhasSolicitacoes(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "data_folga") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(bancoHorasService.listMinhasSolicitacoes(principal.getId(), principal.getRole(),
                p, l, sort, direction, parseJson(objectMapper, filters)), p, l);
    }

    /** "Gerar Relatório" das Minhas Solicitações — PDF com os filtros aplicados (C-4.1). */
    @GetMapping("/api/ponto/banco/solicitacoes/relatorio")
    public ResponseEntity<?> minhasSolicitacoesRelatorio(
            @RequestParam(defaultValue = "data_folga") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<Map<String, Object>> rows = bancoHorasService.listMinhasSolicitacoes(principal.getId(),
                principal.getRole(), 1, REPORT_LIMIT, sort, direction, parseJson(objectMapper, filters), true).data();
        return reportService.respondPdf(
                pdfService.gerarRelatorioMinhasSolicitacoes(rows), "relatorio_banco_horas");
    }

    // ══ Banco de Horas — Deliberação do admin (Bloco D / E8) ════

    /** Tabela "Solicitações" (D-1): paginada, filtros de coluna + Buscar (D-1.3), ordenação default D-4.1. */
    @AdminOnly
    @GetMapping("/api/admin/ponto/banco/solicitacoes")
    public ResponseEntity<?> solicitacoesAdmin(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "padrao") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(bancoHorasService.listSolicitacoesAdmin(principal.getId(),
                p, l, sort, direction, search, parseJson(objectMapper, filters)), p, l);
    }

    /** Aprova uma solicitação PENDENTE (confirmação na UI — Q14; T-1.2/T-1.3 no service — T-1.4). */
    @AdminOnly
    @PostMapping("/api/admin/ponto/banco/solicitacao/{id}/aprovar")
    public ResponseEntity<?> aprovarSolicitacao(@PathVariable String id,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of("ok", true, "data", bancoHorasService.aprovar(id, principal.getId())));
    }

    /**
     * Rejeita uma solicitação PENDENTE — motivo obrigatório (D-3.2); T-1.2/T-1.3 no service (T-1.4).
     * A obrigatoriedade e o teto de 300 caracteres continuam no service; aqui só se exige que o campo,
     * quando vier, seja TEXTO: um {@code {"motivo":{"a":1}}} gravava o literal {@code "{a=1}"} como
     * motivo da rejeição (F35).
     */
    @AdminOnly
    @PostMapping("/api/admin/ponto/banco/solicitacao/{id}/rejeitar")
    public ResponseEntity<?> rejeitarSolicitacao(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        String motivo = optTexto(body, "motivo");
        return ResponseEntity.ok(Map.of("ok", true, "data", bancoHorasService.rejeitar(id, principal.getId(), motivo)));
    }

    /** Relatório "Solicitações" (D-1.3) — PDF/DOCX com os filtros aplicados (Q27). */
    @AdminOnly
    @GetMapping("/api/admin/ponto/banco/solicitacoes/relatorio")
    public ResponseEntity<?> solicitacoesAdminRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "padrao") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<Map<String, Object>> raw = bancoHorasService.listSolicitacoesAdmin(principal.getId(),
                1, REPORT_LIMIT, sort, direction, search, parseJson(objectMapper, filters), true).data();
        List<Map<String, Object>> rows = bancoHorasService.enriquecerRowsParaRelatorioSolicitacoesAdmin(raw);
        return reportService.respond(format, "relatorio_solicitacoes_banco",
                () -> pdfService.gerarRelatorioSolicitacoesAdmin(rows),
                () -> docxService.gerarRelatorioSolicitacoesAdmin(rows));
    }

    // ══ Helper ══════════════════════════════════════════════════

    private ResponseEntity<ByteArrayResource> streamPdf(ArquivoPonto arq, boolean attachment) {
        ContentDisposition cd = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(arq.nomeArquivo()).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(new ByteArrayResource(arq.conteudo()));
    }
}
