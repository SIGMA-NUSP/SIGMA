package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.repository.SalaRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.Color;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static br.leg.senado.nusp.service.NativeQueryUtils.PT_BR_MONTHS;
import static br.leg.senado.nusp.service.NativeQueryUtils.mesPtParaNumero;
import static br.leg.senado.nusp.service.NativeQueryUtils.semAcento;

/**
 * Lê cessões de sala da planilha Google Sheets ("Reserva Plenários das comissões").
 *
 * Identificação de cessão: célula com cor de fundo creme ({@code #FFF2CC} ou {@code #FCE5CD})
 * e/ou, no layout novo, o marcador textual "Cessão". As demais cores representam comissões
 * regulares (azul/marrom) e não são lidas.
 *
 * A planilha existe em dois layouts (detectados dinamicamente — ver {@link #detectarLayout}):
 *
 *   - ANTIGO (até maio/2026): cada plenário ocupa 2 colunas — {@code [horário][evento]}.
 *       Cabeçalhos "Plenário N" espaçados de 2 em 2 (B, D, F, H, J, L, N, P).
 *   - NOVO (a partir de junho/2026): cada plenário ocupa 4 colunas —
 *       {@code [horário]["Cessão"][descrição][status]}. Cabeçalhos espaçados de 4 em 4
 *       (B, F, J, N, R, V, Z, AD).
 *
 * Em ambos: coluna A = data (forward-fill, vale para as próximas linhas até nova data) e
 * "Sala de Reuniões" à direita do último plenário (cabeçalho na 1ª linha).
 *
 * Polling a cada {@code app.sheets.refresh-interval-sec} segundos. Cache em memória.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CessaoSheetService {

    private final SalaRepository salaRepository;
    private final AlertEmailService alertEmailService;

    @Value("${app.sheets.credentials-path}")
    private String credentialsPath;

    @Value("${app.alerts.sheet-stale-minutes:30}")
    private long sheetStaleMinutes;

    @Value("${app.alerts.reminder-hours:24}")
    private long reminderHours;

    @Value("${app.sheets.spreadsheet-id:}")
    private String spreadsheetId;

    @Value("${app.sheets.cessao-colors-hex:FFF2CC,FCE5CD}")
    private String cessaoColorsHex;

    private static final String APPLICATION_NAME = "NUSP-SenadoApp";
    private static final double COLOR_TOLERANCE = 0.02;  // tolerância p/ comparar floats RGB

    // ── Cache ────────────────────────────────────────────────────
    private volatile List<Map<String, Object>> cacheCessoes = Collections.emptyList();
    private volatile String lastHash = "";
    private volatile boolean enabled = false;

    // ── Monitoramento de saúde da sincronização (para alertas por e-mail) ─
    private volatile Instant ultimoSucesso = null;   // última sincronização saudável
    private volatile Instant inicioFalha = null;     // início da sequência de falhas atual
    private volatile Instant ultimoAlerta = null;    // último e-mail enviado nesta falha
    private volatile String motivoFalhaAtual = null; // descrição da falha atual
    private volatile boolean emFalhaAlertada = false;// já enviou o alerta inicial desta falha

    // ── Cliente Sheets (lazy) ─────────────────────────────────────
    private volatile Sheets sheetsClient;

    // ── Cores-alvo (cada item = [r, g, b] em float 0..1) ─────────
    private List<float[]> targetColors = Collections.emptyList();

    // ── Cabeçalho de plenário na planilha (ex: "Plenário 6", "Plenário 13") ──
    private static final Pattern PLENARIO_HEADER = Pattern.compile(
            "plen[aá]rio\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // ── Mapeamento nome de sala → sala_id (carregado após DB up) ─
    private volatile Map<String, Integer> salaNomeToId = null;

    // ══ Inicialização ═══════════════════════════════════════════

    @PostConstruct
    public void init() {
        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            log.warn("CessaoSheetService desabilitado: app.sheets.spreadsheet-id não configurado");
            return;
        }
        if (!Files.exists(Path.of(credentialsPath))) {
            log.warn("CessaoSheetService desabilitado: credencial não encontrada em {}", credentialsPath);
            return;
        }

        List<String> hexes = new ArrayList<>();
        List<float[]> cores = parseCoresAlvo(hexes);
        if (cores.isEmpty()) {
            log.warn("CessaoSheetService desabilitado: nenhuma cor de cessão configurada");
            return;
        }
        this.targetColors = cores;
        try {
            this.sheetsClient = criarSheetsClient();
            this.enabled = true;
            log.info("CessaoSheetService inicializado — planilha {}, cores-alvo {}", spreadsheetId, hexes);
        } catch (Exception e) {
            log.error("Falha ao inicializar CessaoSheetService", e);
        }
    }

    /** Parse das cores-alvo (separadas por vírgula). Cada hex vira RGB float 0..1;
     *  os hexes normalizados são acumulados em {@code hexesOut} (para o log). */
    private List<float[]> parseCoresAlvo(List<String> hexesOut) {
        List<float[]> cores = new ArrayList<>();
        for (String raw : cessaoColorsHex.split(",")) {
            String hex = raw.trim().replace("#", "").toUpperCase();
            if (hex.isEmpty()) continue;
            if (hex.length() == 8) hex = hex.substring(2);  // remove alpha se presente
            float r = Integer.parseInt(hex.substring(0, 2), 16) / 255f;
            float g = Integer.parseInt(hex.substring(2, 4), 16) / 255f;
            float b = Integer.parseInt(hex.substring(4, 6), 16) / 255f;
            cores.add(new float[]{r, g, b});
            hexesOut.add(hex);
        }
        return cores;
    }

    /** Constrói o cliente Sheets (transporte + credencial read-only). */
    private Sheets criarSheetsClient() throws Exception {
        GoogleCredentials credentials;
        try (FileInputStream in = new FileInputStream(credentialsPath)) {
            credentials = GoogleCredentials.fromStream(in)
                    .createScoped(List.of(SheetsScopes.SPREADSHEETS_READONLY));
        }
        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    // ══ Polling ═════════════════════════════════════════════════

    @Scheduled(
            fixedRateString = "${app.sheets.refresh-interval-sec:60}000",
            initialDelayString = "10000")
    public void poll() {
        if (!enabled) return;
        String motivoFalha = null;  // null = sincronização saudável
        try {
            carregarMapeamentoSeNecessario();
            FetchResult r = fetchCessoesDetalhado(LocalDate.now());

            // Comportamento de cache inalterado: atualiza quando o resultado muda
            String hash = String.valueOf(r.cessoes().hashCode());
            if (!hash.equals(lastHash)) {
                cacheCessoes = r.cessoes();
                lastHash = hash;
                log.info("Cessões atualizadas: {} entradas hoje", r.cessoes().size());
            }

            // Classificação de saúde (para alertas)
            if (!r.abaEncontrada()) {
                motivoFalha = "a aba do mês não foi encontrada na planilha";
            } else if (!r.layoutOk()) {
                motivoFalha = "o layout/colunas da planilha não foram reconhecidos";
            } else if (r.datasParseadas() == 0) {
                motivoFalha = "nenhuma data foi interpretada (o formato de data pode ter mudado)";
            }
        } catch (Exception e) {
            log.error("Erro ao atualizar cessões da planilha", e);
            motivoFalha = "erro ao acessar a planilha (" + e.getClass().getSimpleName() + ")";
        }
        monitorarSaude(motivoFalha);
    }

    // ══ API pública ═════════════════════════════════════════════

    /** Cessões de hoje filtradas por sala_id */
    public List<Map<String, Object>> getCessoesPorSala(int salaId) {
        return cacheCessoes.stream()
                .filter(c -> Integer.valueOf(salaId).equals(c.get("sala_id")))
                .collect(Collectors.toList());
    }

    /** Todas as cessões de hoje */
    public List<Map<String, Object>> getCessoes() {
        return cacheCessoes;
    }

    /** Indica se o serviço está habilitado (credencial OK + planilha configurada) */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Busca cessões para uma data arbitrária (sob demanda, sem cache).
     * Útil quando o usuário consulta um dia diferente de hoje.
     */
    public List<Map<String, Object>> fetchCessoesParaData(LocalDate data) {
        if (!enabled) return Collections.emptyList();
        try {
            carregarMapeamentoSeNecessario();
            return fetchCessoes(data);
        } catch (Exception e) {
            log.error("Erro ao buscar cessões para data {}", data, e);
            return Collections.emptyList();
        }
    }

    // ══ Fetch — Sheets API ══════════════════════════════════════

    /** Compatibilidade: retorna apenas a lista de cessões (usado por {@link #fetchCessoesParaData}). */
    private List<Map<String, Object>> fetchCessoes(LocalDate hoje) throws Exception {
        return fetchCessoesDetalhado(hoje).cessoes();
    }

    /**
     * Diagnóstico de uma leitura da planilha: cessões + sinais de saúde usados pelos alertas.
     * @param abaEncontrada   a aba do mês existe na planilha
     * @param layoutOk        os cabeçalhos "Plenário N" foram reconhecidos
     * @param datasParseadas  quantas datas da coluna A foram interpretadas (0 = formato de data quebrou)
     */
    private record FetchResult(List<Map<String, Object>> cessoes, boolean abaEncontrada,
                               boolean layoutOk, int datasParseadas) {}

    private FetchResult fetchCessoesDetalhado(LocalDate hoje) throws Exception {
        String abaNome = nomeAbaParaMes(hoje);

        // Pede só os campos que precisamos: valor formatado + cor de fundo efetiva
        Spreadsheet resp = sheetsClient.spreadsheets().get(spreadsheetId)
                .setRanges(List.of(abaNome))
                .setFields("sheets(properties.title,data(rowData(values(formattedValue,effectiveFormat.backgroundColor))))")
                .execute();

        List<Sheet> sheets = resp.getSheets();
        if (sheets == null || sheets.isEmpty()) {
            log.warn("Aba '{}' não encontrada na planilha", abaNome);
            return new FetchResult(Collections.emptyList(), false, false, 0);
        }
        Sheet sheet = sheets.get(0);
        List<GridData> dataList = sheet.getData();
        if (dataList == null || dataList.isEmpty()) {
            return new FetchResult(Collections.emptyList(), true, false, 0);
        }

        List<RowData> rows = dataList.get(0).getRowData();
        if (rows == null) return new FetchResult(Collections.emptyList(), true, false, 0);

        // Detecta o layout (antigo 2 col / novo 4 col) a partir do cabeçalho da aba
        List<Bloco> blocos = detectarLayout(rows);
        if (blocos.isEmpty()) {
            log.warn("Layout da aba '{}' não reconhecido (nenhum cabeçalho de plenário)", abaNome);
            return new FetchResult(Collections.emptyList(), true, false, 0);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate dataAtual = null;
        int linhasNoBlocoAtual = 0;
        int datasParseadas = 0;  // datas interpretadas na coluna A (sinal de saúde)
        // Cada dia ocupa exatamente 3 linhas na planilha (3 horários por dia).
        // Após esse limite, descartamos o forward-fill para não capturar
        // dados de seções "template" da planilha (ex: semana modelo no rodapé).
        final int MAX_LINHAS_POR_DIA = 3;

        for (RowData row : rows) {
            if (row == null || row.getValues() == null) {
                if (dataAtual != null) {
                    linhasNoBlocoAtual++;
                    if (linhasNoBlocoAtual >= MAX_LINHAS_POR_DIA) {
                        dataAtual = null;
                    }
                }
                continue;
            }
            List<CellData> cells = row.getValues();

            // Coluna A → forward-fill da data (limitado a 3 linhas)
            LocalDate dataDaLinha = parseDataCelula(cells.size() > 0 ? cells.get(0) : null);
            if (dataDaLinha != null) {
                dataAtual = dataDaLinha;
                linhasNoBlocoAtual = 0;
                datasParseadas++;
            } else if (dataAtual != null) {
                linhasNoBlocoAtual++;
                if (linhasNoBlocoAtual >= MAX_LINHAS_POR_DIA) {
                    dataAtual = null;
                    continue;
                }
            }
            if (dataAtual == null) continue;
            if (!dataAtual.equals(hoje)) continue;  // só a data alvo

            // Para cada bloco de plenário detectado, checa cessão (cor/marcador) + valor
            for (Bloco b : blocos) {
                Map<String, Object> item = extrairCessao(cells, b, dataAtual);
                if (item != null) result.add(item);
            }
        }

        return new FetchResult(result, true, true, datasParseadas);
    }

    /**
     * Extrai a cessão de um bloco de plenário numa linha, ou {@code null} se o bloco não é
     * cessão válida (coluna do evento fora do range; célula ausente; não é cessão; evento vazio).
     */
    private Map<String, Object> extrairCessao(List<CellData> cells, Bloco b, LocalDate data) {
        if (b.colEvento() >= cells.size()) return null;
        CellData evCell = cells.get(b.colEvento());
        if (evCell == null) return null;

        // Cessão = evento creme OU (layout novo) marcador "Cessão" na coluna do meio
        boolean ehCessao = isCessao(evCell);
        if (!ehCessao && b.colMarcador() >= 0 && b.colMarcador() < cells.size()) {
            ehCessao = isMarcadorCessao(cells.get(b.colMarcador()));
        }
        if (!ehCessao) return null;

        String evento = evCell.getFormattedValue();
        if (evento == null || evento.isBlank()) return null;

        String horario = null;
        if (b.colHorario() >= 0 && b.colHorario() < cells.size()
                && cells.get(b.colHorario()) != null) {
            horario = cells.get(b.colHorario()).getFormattedValue();
        }

        String salaNome = b.salaNome();
        Integer salaId = salaNomeToId != null ? salaNomeToId.get(salaNome) : null;

        return montarItemCessao(data, salaNome, salaId, horario, evento);
    }

    /** Monta o mapa de resposta de uma cessão (ordem de inserção das chaves preservada). */
    private Map<String, Object> montarItemCessao(LocalDate data, String salaNome, Integer salaId,
                                                 String horario, String evento) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tipo", "cessao");
        item.put("data", data.toString());
        item.put("sala_nome", salaNome);
        item.put("sala_id", salaId);
        item.put("horario", horario != null ? horario.trim() : "");
        item.put("titulo", evento.trim());
        item.put("local", salaNome);
        return item;
    }

    // ══ Monitoramento de saúde + alertas por e-mail ═════════════

    /**
     * Atualiza o estado de saúde da sincronização e dispara e-mails:
     * alerta inicial após {@code sheetStaleMinutes} de falha contínua, lembretes a cada
     * {@code reminderHours}, e um e-mail de recuperação quando volta a sincronizar.
     * Um único sucesso zera a contagem → timeouts passageiros não geram falso alarme.
     *
     * @param motivoFalha descrição da falha, ou {@code null} se a sincronização foi saudável
     */
    private void monitorarSaude(String motivoFalha) {
        if (!alertEmailService.isEnabled()) return;  // alertas desligados
        Instant agora = Instant.now();

        if (motivoFalha == null) {
            boolean recuperou = emFalhaAlertada;
            Instant desde = inicioFalha;
            ultimoSucesso = agora;
            inicioFalha = null;
            ultimoAlerta = null;
            motivoFalhaAtual = null;
            emFalhaAlertada = false;
            if (recuperou) enviarRecuperacao(agora, desde);
            return;
        }

        // Falha
        if (inicioFalha == null) inicioFalha = agora;
        motivoFalhaAtual = motivoFalha;
        long minutosFalhando = Duration.between(inicioFalha, agora).toMinutes();
        if (minutosFalhando < sheetStaleMinutes) return;  // ainda dentro da tolerância

        if (!emFalhaAlertada) {
            emFalhaAlertada = true;
            ultimoAlerta = agora;
            enviarAlerta(false);
        } else if (ultimoAlerta == null
                || Duration.between(ultimoAlerta, agora).toHours() >= reminderHours) {
            ultimoAlerta = agora;
            enviarAlerta(true);
        }
    }

    /** Envelope HTML comum dos e-mails de alerta/recuperação (varia só a cor do header e o miolo). */
    private static String emailHtml(String corHeader, String miolo) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <div style="background:%s;padding:16px;text-align:center">
                    <h2 style="color:#ffffff;margin:0">NUSP — Agenda Legislativa</h2>
                  </div>
                  <div style="padding:24px;background:#f9f9f9;color:#333">
                %s  </div>
                </div>
                """.formatted(corHeader, miolo);
    }

    private void enviarAlerta(boolean lembrete) {
        String tipo = lembrete ? "LEMBRETE" : "ALERTA";
        String desde = inicioFalha != null ? formatarInstante(inicioFalha) : "agora";
        String ultimo = ultimoSucesso != null ? formatarInstante(ultimoSucesso)
                : "(nenhuma desde o início do monitoramento)";
        String miolo = """
                    <p><strong>%s:</strong> o sistema está há um tempo sem conseguir ler a
                       planilha de cessões de sala.</p>
                    <ul>
                      <li><strong>Motivo:</strong> %s</li>
                      <li><strong>Falhando desde:</strong> %s</li>
                      <li><strong>Última sincronização bem-sucedida:</strong> %s</li>
                    </ul>
                    <p>Enquanto isso, a Agenda pode estar sem as cessões de sala. O que verificar:</p>
                    <ul>
                      <li>A planilha do Google está acessível e a aba do mês existe?</li>
                      <li>O formato das colunas ou das datas mudou?</li>
                      <li>A credencial de acesso à planilha continua válida?</li>
                    </ul>
                    <p style="color:#999;font-size:12px">Você receberá um lembrete a cada %d h
                       enquanto a falha persistir, e um aviso quando normalizar.</p>
                """.formatted(tipo, motivoFalhaAtual, desde, ultimo, reminderHours);
        String html = emailHtml("#b00020", miolo);
        alertEmailService.enviarAlerta("⚠ Agenda: falha ao sincronizar a planilha de cessões", html);
    }

    private void enviarRecuperacao(Instant agora, Instant desde) {
        String periodo = desde != null ? formatarInstante(desde) : "?";
        String miolo = """
                    <p><strong>Sincronização normalizada.</strong> A leitura da planilha de
                       cessões voltou a funcionar.</p>
                    <ul>
                      <li><strong>Ficou falhando desde:</strong> %s</li>
                      <li><strong>Normalizou em:</strong> %s</li>
                    </ul>
                """.formatted(periodo, formatarInstante(agora));
        String html = emailHtml("#009739", miolo);
        alertEmailService.enviarAlerta("✓ Agenda: sincronização da planilha normalizada", html);
    }

    private static final DateTimeFormatter ALERT_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private String formatarInstante(Instant i) {
        return i.atZone(ZoneId.of("America/Sao_Paulo")).format(ALERT_FMT) + " (BRT)";
    }

    // ══ Mapeamento sala_nome → sala_id ══════════════════════════

    private void carregarMapeamentoSeNecessario() {
        if (salaNomeToId != null) return;
        Map<String, Integer> mapa = new HashMap<>();
        for (Sala sala : salaRepository.findAtivasOrdenadas()) {
            mapa.put(sala.getNome(), sala.getId());
        }
        salaNomeToId = mapa;
        log.info("Mapeamento sala_nome → sala_id carregado: {}", mapa);
    }

    // ══ Helpers ═════════════════════════════════════════════════

    /** Nome da aba para um mês — formato "Mês YY" (ex: "Abril 26"). */
    private String nomeAbaParaMes(LocalDate data) {
        String mes = PT_BR_MONTHS[data.getMonthValue() - 1];
        mes = Character.toUpperCase(mes.charAt(0)) + mes.substring(1);
        String ano2 = String.format("%02d", data.getYear() % 100);
        return mes + " " + ano2;
    }

    private static final Pattern DATA_PT = Pattern.compile(
            "(\\d{1,2})\\s+de\\s+(\\p{L}+)\\s+de\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** "DD/MM/YYYY" em qualquer posição (com ou sem prefixo, ex: "01/06/2026"). */
    private static final Pattern DATA_DMY = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    /** "DD/MM" sem ano, possivelmente com prefixo de dia da semana (ex: "seg, 1/6"). O
     *  lookbehind/lookahead evitam casar o trecho "DD/MM" de uma data "DD/MM/AAAA". */
    private static final Pattern DATA_DM = Pattern.compile("(?<!\\d/)(\\d{1,2})/(\\d{1,2})(?!/\\d)");

    /** Tenta extrair uma data de uma célula da coluna A. Formatos aceitos:
     *  "DD/MM/YYYY", "DD/MM", "seg, 1/6" (dia da semana + DD/MM) e "quarta-feira, 1 de abril de 2026". */
    private LocalDate parseDataCelula(CellData cell) {
        if (cell == null || cell.getFormattedValue() == null) return null;
        String txt = cell.getFormattedValue().trim();
        if (txt.isEmpty()) return null;

        // Formato "DD/MM/YYYY" (com ou sem prefixo)
        Matcher m1 = DATA_DMY.matcher(txt);
        if (m1.find()) {
            LocalDate d = dataOuNull(Integer.parseInt(m1.group(3)),
                    Integer.parseInt(m1.group(2)), Integer.parseInt(m1.group(1)));
            if (d != null) return d;
        }

        // Formato "DD/MM" sem ano (ex: "seg, 1/6") — usa o ano corrente
        Matcher m2 = DATA_DM.matcher(txt);
        if (m2.find()) {
            LocalDate d = dataOuNull(LocalDate.now().getYear(),
                    Integer.parseInt(m2.group(2)), Integer.parseInt(m2.group(1)));
            if (d != null) return d;
        }

        // Formato "quarta-feira, 1 de abril de 2026"
        Matcher m3 = DATA_PT.matcher(txt);
        if (m3.find()) {
            int dia = Integer.parseInt(m3.group(1));
            int mes = mesPtParaNumero(m3.group(2));
            int ano = Integer.parseInt(m3.group(3));
            if (mes > 0) {
                LocalDate d = dataOuNull(ano, mes, dia);
                if (d != null) return d;
            }
        }
        return null;
    }

    /** {@link LocalDate#of} tolerante: retorna {@code null} em vez de lançar para data inválida. */
    private static LocalDate dataOuNull(int ano, int mes, int dia) {
        try {
            return LocalDate.of(ano, mes, dia);
        } catch (Exception e) {
            return null;
        }
    }

    // ══ Detecção de layout (cabeçalho dinâmico) ═════════════════

    /** Bloco de colunas de um plenário/sala dentro de uma linha (colMarcador = -1 no layout antigo). */
    private record Bloco(int colHorario, int colEvento, int colMarcador, String salaNome) {}

    /**
     * Lê o cabeçalho da aba e monta os blocos de cada plenário, detectando se o layout é o
     * antigo (2 colunas/plenário) ou o novo (4 colunas/plenário) pela distância entre os
     * cabeçalhos "Plenário N". Retorna lista vazia se nenhum cabeçalho for encontrado.
     */
    private List<Bloco> detectarLayout(List<RowData> rows) {
        // 1. Linha de cabeçalho = a que tem mais células "Plenário N" entre as primeiras 8
        int headerRow = -1, melhor = 0;
        int limite = Math.min(8, rows.size());
        for (int i = 0; i < limite; i++) {
            int cnt = contarPlenarios(rows.get(i));
            if (cnt > melhor) { melhor = cnt; headerRow = i; }
        }
        if (headerRow < 0) return Collections.emptyList();

        // 2. Coluna → nome de sala (zero-padded p/ casar com CAD_SALA: "Plenário 06")
        TreeMap<Integer, String> plenarios = new TreeMap<>();
        List<CellData> hcells = rows.get(headerRow).getValues();
        for (int ci = 0; ci < hcells.size(); ci++) {
            Matcher m = PLENARIO_HEADER.matcher(valor(hcells.get(ci)));
            if (m.find()) {
                plenarios.put(ci, String.format("Plenário %02d", Integer.parseInt(m.group(1))));
            }
        }
        if (plenarios.isEmpty()) return Collections.emptyList();

        // 3. Largura do bloco = menor distância entre cabeçalhos consecutivos (2=antigo, 4=novo)
        List<Integer> cols = new ArrayList<>(plenarios.keySet());
        int largura = Integer.MAX_VALUE;
        for (int i = 1; i < cols.size(); i++) {
            largura = Math.min(largura, cols.get(i) - cols.get(i - 1));
        }
        if (largura == Integer.MAX_VALUE || largura < 2) largura = 2;  // 1 só plenário → assume antigo

        // 4. Offsets dentro do bloco
        int eventoOffset = largura >= 4 ? 2 : 1;     // descrição
        int marcadorOffset = largura >= 4 ? 1 : -1;  // "Cessão" (só no layout novo)

        List<Bloco> blocos = new ArrayList<>();
        for (Map.Entry<Integer, String> e : plenarios.entrySet()) {
            int s = e.getKey();
            int colMarc = marcadorOffset >= 0 ? s + marcadorOffset : -1;
            blocos.add(new Bloco(s, s + eventoOffset, colMarc, e.getValue()));
        }

        // 5. "Sala de Reuniões" (cabeçalho costuma ficar na 1ª linha, à direita dos plenários)
        Integer colReuniao = acharSalaReunioes(rows, headerRow);
        if (colReuniao != null) {
            int s = colReuniao;
            int colMarc = marcadorOffset >= 0 ? s + marcadorOffset : -1;
            blocos.add(new Bloco(s, s + eventoOffset, colMarc, "Sala de Reuniões"));
        }

        log.debug("Layout cessões detectado: headerRow={}, largura={}, blocos={}",
                headerRow, largura, blocos);
        return blocos;
    }

    /** Conta quantas células de uma linha são cabeçalho "Plenário N". */
    private int contarPlenarios(RowData row) {
        if (row == null || row.getValues() == null) return 0;
        int n = 0;
        for (CellData c : row.getValues()) {
            if (PLENARIO_HEADER.matcher(valor(c)).find()) n++;
        }
        return n;
    }

    /** Procura a coluna do cabeçalho "Sala de Reuniões" nas linhas 0..ateLinha (inclusive). */
    private Integer acharSalaReunioes(List<RowData> rows, int ateLinha) {
        for (int i = 0; i <= ateLinha && i < rows.size(); i++) {
            RowData row = rows.get(i);
            if (row == null || row.getValues() == null) continue;
            List<CellData> cells = row.getValues();
            for (int ci = 0; ci < cells.size(); ci++) {
                if (semAcento(valor(cells.get(ci))).startsWith("sala de reuni")) return ci;
            }
        }
        return null;
    }

    /** Valor textual (trim) de uma célula, ou "" se vazia. */
    private String valor(CellData cell) {
        return cell != null && cell.getFormattedValue() != null
                ? cell.getFormattedValue().trim() : "";
    }

    /** True se a célula contém o marcador textual "Cessão" (tolerante a acento/caixa). */
    private boolean isMarcadorCessao(CellData cell) {
        return semAcento(valor(cell)).contains("cessao");
    }

    /** Verifica se a cor de fundo da célula bate com alguma das cores-alvo. */
    private boolean isCessao(CellData cell) {
        if (cell.getEffectiveFormat() == null) return false;
        Color bg = cell.getEffectiveFormat().getBackgroundColor();
        if (bg == null) return false;
        float r = bg.getRed() != null ? bg.getRed() : 0f;
        float g = bg.getGreen() != null ? bg.getGreen() : 0f;
        float b = bg.getBlue() != null ? bg.getBlue() : 0f;
        for (float[] alvo : targetColors) {
            if (Math.abs(r - alvo[0]) < COLOR_TOLERANCE
                    && Math.abs(g - alvo[1]) < COLOR_TOLERANCE
                    && Math.abs(b - alvo[2]) < COLOR_TOLERANCE) {
                return true;
            }
        }
        return false;
    }
}
