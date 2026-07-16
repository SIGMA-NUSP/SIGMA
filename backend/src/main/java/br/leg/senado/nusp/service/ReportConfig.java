package br.leg.senado.nusp.service;

import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;

import java.awt.Color;
import java.util.List;
import java.util.Map;

import static br.leg.senado.nusp.service.NativeQueryUtils.nonEmpty;
import static br.leg.senado.nusp.service.NativeQueryUtils.strOrDefault;

/**
 * Configuracoes compartilhadas entre os services de relatorio (PDF e DOCX).
 * Equivale a report_config.py do Python.
 */
public final class ReportConfig {

    private ReportConfig() {}

    // ── Cores (hex) ────────────────────────────────────────────────
    public static final String HEADER_FILL         = "#dbeafe";
    public static final String HEADER_DETAIL_FILL  = "#bfdbfe";
    public static final String DETAIL_BAR_FILL     = "#e0f2fe";
    public static final String DATA_ROW_FILL       = "#f8fafc";
    public static final String GRID_COLOR          = "#cbd5e1";

    public static final String COLOR_GREEN = "#16a34a";
    public static final String COLOR_RED   = "#dc2626";
    public static final String COLOR_BLUE  = "#2563eb";
    public static final String COLOR_MUTED = "#64748b";
    public static final String COLOR_DARK  = "#0f172a";
    public static final String COLOR_SLATE = "#334155";

    // ── Pesos de coluna por relatorio ──────────────────────────────
    public static final int[] COLS_OPERADORES                = {60, 40};
    public static final int[] COLS_CHECKLISTS_MASTER         = {70, 60, 150, 45, 50, 60, 50};
    public static final int[] COLS_ANORMALIDADES             = {70, 60, 110, 170, 70, 60, 70};
    public static final int[] COLS_OPERACOES_SESSOES_MASTER  = {90, 60, 130, 45, 45, 45, 70};
    public static final int[] COLS_OPERACOES_SESSOES_ENT_NORMAL = {30, 120, 50, 50, 120, 50};
    public static final int[] COLS_OPERACOES_SESSOES_ENT_PLENARIO = {300, 60};
    public static final int[] COLS_OPERACOES_ENTRADAS        = {80, 60, 110, 70, 170, 45, 45, 45, 70};
    public static final int[] COLS_MEUS_CHECKLISTS           = {180, 80, 100, 100};
    public static final int[] COLS_MINHAS_OPERACOES          = {150, 70, 90, 90, 80};
    public static final int[] COLS_MINHAS_SOLICITACOES       = {80, 70, 130, 180};
    public static final int[] COLS_SOLICITACOES_ADMIN        = {120, 60, 90, 70, 110, 150};

    // ── Titulos de coluna por relatorio (compartilhados PDF × DOCX) ──
    public static final String[] HDR_OPERADORES              = {"Nome", "E-mail"};
    public static final String[] HDR_CHECKLISTS_MASTER       = {"Local", "Data", "Verificado por", "Início", "Término", "Duração", "Status"};
    public static final String[] HDR_CHECKLIST_ITENS         = {"Item verificado", "Status", "Descrição"};
    public static final String[] HDR_ANORMALIDADES           = {"Data", "Local", "Registrado por", "Descrição", "Solucionada", "Prejuízo", "Reclamação"};
    public static final String[] HDR_OPERACOES_SESSOES_MASTER = {"Local", "Data", "Evento", "Pauta", "Início", "Fim", "Verificação"};
    public static final String[] HDR_OPERACOES_SESSOES_ENT_NORMAL = {"Nº", "Operador", "Início Op.", "Fim Op.", "Observações", "Anom?"};
    public static final String[] HDR_OPERACOES_SESSOES_ENT_PLENARIO = {"Operador", "Anom?"};
    public static final String[] HDR_OPERACOES_ENTRADAS      = {"Local", "Data", "Operador", "Tipo", "Evento", "Pauta", "Início", "Fim", "Anormalidade?"};
    public static final String[] HDR_SOLICITACOES_ADMIN      = {"Nome", "Saldo", "Dia solicitado", "Status", "Deliberado por", "Motivo"};

    // ── Helpers ────────────────────────────────────────────────────

    public static Color hex(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new Color(Integer.parseInt(h, 16));
    }

    public static float[] scaleWeights(int[] weights, float availableWidth) {
        int sum = 0;
        for (int w : weights) sum += w;
        float scale = availableWidth / sum;
        float[] result = new float[weights.length];
        for (int i = 0; i < weights.length; i++) {
            result[i] = weights[i] * scale;
        }
        return result;
    }

    /** Formata data para DD/MM/YYYY. Aceita LocalDate, String, null. */
    public static String fmtDate(Object v) {
        if (v == null || "".equals(v)) return "--";
        if (v instanceof java.time.LocalDate ld) return String.format("%02d/%02d/%04d", ld.getDayOfMonth(), ld.getMonthValue(), ld.getYear());
        if (v instanceof java.time.LocalDateTime ldt) return String.format("%02d/%02d/%04d", ldt.getDayOfMonth(), ldt.getMonthValue(), ldt.getYear());
        // F53 (C17): extração determinística por millis + zona explícita BRT, sem o fuso implícito do
        // antigo Calendar.getInstance() (que mudava de resultado com o -Duser.timezone). Timestamp chega
        // de query nativa (r.get("data")); sql.Date/util.Date puro são defensivos — getTime() (o millis
        // intrínseco do instante, não os getters de fuso default) cobre os três de forma uniforme.
        if (v instanceof java.util.Date d) {
            java.time.LocalDate ld2 = java.time.Instant.ofEpochMilli(d.getTime())
                    .atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate();
            return String.format("%02d/%02d/%04d", ld2.getDayOfMonth(), ld2.getMonthValue(), ld2.getYear());
        }
        // String "yyyy-MM-dd..." → dd/MM/yyyy
        String s = v.toString().trim();
        if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            return s.substring(8, 10) + "/" + s.substring(5, 7) + "/" + s.substring(0, 4);
        }
        return s;
    }

    /** Formata hora para HH:MM. Aceita LocalTime, String "HH:MM:SS", null. */
    public static String fmtTime(Object v) {
        if (v == null || "".equals(v)) return "--";
        if (v instanceof java.time.LocalTime lt) return String.format("%02d:%02d", lt.getHour(), lt.getMinute());
        String s = v.toString();
        if (s.contains(":") && s.length() >= 5) return s.substring(0, 5);
        return s;
    }

    /** Cria Font OpenPDF com o tamanho e cor especificados. */
    public static Font pdfFont(float size, int style, Color color) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA, size, style);
        if (color != null) f.setColor(color);
        return f;
    }

    // ── Derivações de exibição compartilhadas (PDF × DOCX) ─────────

    /** Trunca em max caracteres, acrescentando "..." quando corta. */
    public static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** Ordem da entrada para exibição: "1º", "2º"... ("--" se ausente/vazia). */
    public static String ordemDisplay(Object ordem) {
        return (ordem != null && !"".equals(ordem.toString())) ? ordem + "º" : "--";
    }

    /** Cor (hex) do texto de verificação da sessão: "realizado" → verde, senão muted. */
    public static String verificacaoColorHex(String vTxt) {
        return "realizado".equalsIgnoreCase(vTxt.trim()) ? COLOR_GREEN : COLOR_MUTED;
    }

    /** Visão de exibição do status de uma solicitação de folga — regra única para PDF e DOCX (E7/E8). */
    public record SolicitacaoStatusView(String label, String corHex) {}

    /** Rótulo + cor do status da solicitação (C-4.3 + Cancelado em cinza). */
    public static SolicitacaoStatusView solicitacaoStatusView(String status) {
        String st = status == null ? "" : status.trim().toUpperCase();
        return switch (st) {
            case "APROVADO"  -> new SolicitacaoStatusView("Aprovado", COLOR_BLUE);
            case "REJEITADO" -> new SolicitacaoStatusView("Rejeitado", COLOR_RED);
            case "CANCELADO" -> new SolicitacaoStatusView("Cancelado", COLOR_MUTED);
            case "PENDENTE"  -> new SolicitacaoStatusView("Pendente", COLOR_DARK);
            default          -> new SolicitacaoStatusView(status != null ? status : "--", COLOR_DARK);
        };
    }

    /** Visão de exibição de um item de checklist: status, cor (hex) e descrição — regra única para PDF e DOCX. */
    public record ItemChecklistView(String status, String corHex, String descricao) {}

    /** Classifica um item de checklist (widget de texto × radio ok/falha) para renderização. */
    public static ItemChecklistView itemChecklistView(Map<String, Object> it) {
        boolean isText = "text".equals(strOrDefault(it, "tipo_widget", "radio"));
        if (isText) {
            return new ItemChecklistView("Texto", COLOR_SLATE, nonEmpty(it, "valor_texto", "-"));
        }
        String st = nonEmpty(it, "status", "--");
        String low = st.toLowerCase().trim();
        String cor = "falha".equals(low) ? COLOR_RED : "ok".equals(low) ? COLOR_GREEN : COLOR_SLATE;
        return new ItemChecklistView(st, cor, nonEmpty(it, "falha", "-"));
    }

    /** True se algum item da lista tem status "falha" (tolerante a caixa/espaços). */
    public static boolean temFalha(List<Map<String, Object>> itens) {
        return itens.stream().anyMatch(it ->
                "falha".equalsIgnoreCase(String.valueOf(it.getOrDefault("status", "")).trim()));
    }
}
