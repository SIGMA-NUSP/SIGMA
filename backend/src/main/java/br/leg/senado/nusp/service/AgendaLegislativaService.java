package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.repository.SalaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static br.leg.senado.nusp.service.NativeQueryUtils.semAcento;

/**
 * Monitora a Agenda Legislativa do Senado Federal em tempo real.
 *
 * Fontes:
 * - Comissões: API Dados Abertos (XML) — legis.senado.leg.br/dadosabertos/comissao/agenda/YYYYMMDD
 * - Plenário Principal: scraping da página de atividade — www25.senado.leg.br/web/atividade
 *
 * Polling a cada 30 segundos. Mudanças são enviadas via SSE aos operadores conectados.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgendaLegislativaService {

    private final SalaRepository salaRepository;
    private final CessaoSheetService cessaoSheetService;
    private final HttpClient httpClient;

    // ── Cache em memória ────────────────────────────────────────
    private volatile List<Map<String, Object>> cacheComissoes = Collections.emptyList();
    private volatile List<Map<String, Object>> cachePlenario = Collections.emptyList();
    private volatile String lastHashComissoes = "";
    private volatile String lastHashPlenario = "";
    /** Dia (yyyyMMdd) a que cada cache se refere — null enquanto não há fetch bem-sucedido. */
    private volatile String diaCacheComissoes = null;
    private volatile String diaCachePlenario = null;

    // ── SSE emitters ────────────────────────────────────────────
    private final CopyOnWriteArrayList<EmitterEntry> emitters = new CopyOnWriteArrayList<>();

    private static final String COMISSAO_API = "https://legis.senado.leg.br/dadosabertos/comissao/agenda/";
    private static final String PLENARIO_API = "https://legis.senado.leg.br/dadosabertos/plenario/agenda/dia/";
    private static final Pattern PLENARIO_NUM_PATTERN = Pattern.compile("Plen[aá]rio\\s+n[ºo°]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    /** Casa qualquer "auditorio … petr.nio … portel(l)a" tolerando ausência de acentos e variações de grafia. */
    private static final Pattern AUDITORIO_PETRONIO_PATTERN = Pattern.compile("auditorio.*petr.nio.*portell?a");
    private static final Pattern NUMERO_ORDINAL_PATTERN = Pattern.compile("(\\d+[ªº])");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // Mapeamento: número do plenário → sala_id (carregado na primeira execução)
    private volatile Map<Integer, Integer> plenarioToSalaId = null;
    private volatile Integer salaDemaisSalasId = null;
    private volatile Integer salaAuditorioPetronioId = null;

    // ══ Polling — a cada 30 segundos ════════════════════════════

    @Scheduled(fixedRate = 30_000, initialDelay = 5_000)
    public void poll() {
        pollParaDia(LocalDate.now().format(DATE_FMT));
    }

    /**
     * O dia é parâmetro (e não `LocalDate.now()` interno) para que a VIRADA DO DIA seja exercitável
     * em teste: é ela que decide se um cache preservado ainda pode ser servido — ver
     * {@link #cacheEhDeOutroDia}. Visibilidade de pacote: só o {@link #poll()} chama em produção.
     */
    void pollParaDia(String hoje) {
        try {
            carregarMapeamentoSeNecessario();
            atualizarComissoes(hoje);
            atualizarPlenario(hoje);
            broadcast();
        } catch (Exception e) {
            log.error("Erro ao atualizar agenda legislativa", e);
        }
    }

    private void atualizarComissoes(String hoje) {
        List<Map<String, Object>> novasComissoes = fetchComissoes(hoje);
        if (novasComissoes == null) {
            if (cacheEhDeOutroDia(diaCacheComissoes, hoje)) {
                log.warn("Agenda de comissões indisponível e o cache é do dia {} — descartado (não serve como agenda de {})",
                        diaCacheComissoes, hoje);
                cacheComissoes = Collections.emptyList();
                lastHashComissoes = "";
                diaCacheComissoes = null;
            } else {
                log.warn("Agenda de comissões indisponível — mantendo o cache anterior ({} reuniões)", cacheComissoes.size());
            }
            return;
        }
        diaCacheComissoes = hoje;
        String hashComissoes = String.valueOf(novasComissoes.hashCode());
        boolean comissoesChanged = !hashComissoes.equals(lastHashComissoes);
        if (comissoesChanged) {
            cacheComissoes = novasComissoes;
            lastHashComissoes = hashComissoes;
            log.info("Agenda comissões atualizada: {} reuniões", novasComissoes.size());
        }
    }

    private void atualizarPlenario(String hoje) {
        List<Map<String, Object>> novasPlenario = fetchPlenario(hoje);
        if (novasPlenario == null) {
            if (cacheEhDeOutroDia(diaCachePlenario, hoje)) {
                log.warn("Agenda do plenário indisponível e o cache é do dia {} — descartado (não serve como agenda de {})",
                        diaCachePlenario, hoje);
                cachePlenario = Collections.emptyList();
                lastHashPlenario = "";
                diaCachePlenario = null;
            } else {
                log.warn("Agenda do plenário indisponível — mantendo o cache anterior ({} sessões)", cachePlenario.size());
            }
            return;
        }
        diaCachePlenario = hoje;
        String hashPlenario = String.valueOf(novasPlenario.hashCode());
        boolean plenarioChanged = !hashPlenario.equals(lastHashPlenario);
        if (plenarioChanged) {
            cachePlenario = novasPlenario;
            lastHashPlenario = hashPlenario;
            log.info("Agenda plenário principal atualizada: {} sessões", novasPlenario.size());
        }
    }

    /**
     * Preservar o cache na falha (F9) vale enquanto ele for do dia corrente. Se a indisponibilidade
     * atravessa a meia-noite, o cache vira a agenda de ONTEM — e servi-lo como a de hoje (com o SSE
     * carimbando "atualizado agora") seria informação ativamente errada, pior que a ausência dela.
     */
    private boolean cacheEhDeOutroDia(String diaDoCache, String hoje) {
        return diaDoCache != null && !diaDoCache.equals(hoje);
    }

    // ══ API pública ═════════════════════════════════════════════

    /** Reuniões (comissões + cessões) de hoje filtradas por sala_id */
    public List<Map<String, Object>> getAgendaPorSala(int salaId) {
        List<Map<String, Object>> result = new ArrayList<>();
        cacheComissoes.stream()
                .filter(r -> salaId == toInt(r.get("sala_id")))
                .forEach(result::add);
        result.addAll(cessaoSheetService.getCessoesPorSala(salaId));
        return result;
    }

    /** Todas as reuniões (comissões + cessões) de hoje */
    public List<Map<String, Object>> getAgendaComissoes() {
        List<Map<String, Object>> result = new ArrayList<>(cacheComissoes);
        result.addAll(cessaoSheetService.getCessoes());
        return result;
    }

    /** Sessões plenárias de hoje (Plenário Principal) */
    public List<Map<String, Object>> getAgendaPlenario() {
        return cachePlenario;
    }

    /**
     * Busca agenda (comissões + cessões) para uma data arbitrária.
     * Se a data for hoje, retorna do cache; caso contrário, faz fetch sob demanda.
     */
    public List<Map<String, Object>> getAgendaParaData(LocalDate data, Integer salaId) {
        carregarMapeamentoSeNecessario();
        boolean ehHoje = data.equals(LocalDate.now());

        // Fetch sob demanda: falha (null) vira lista vazia — o cache é de HOJE e não serve a outra data.
        List<Map<String, Object>> comissoes = ehHoje
                ? cacheComissoes
                : ouVazio(fetchComissoes(data.format(DATE_FMT)));
        List<Map<String, Object>> cessoes = ehHoje
                ? cessaoSheetService.getCessoes()
                : cessaoSheetService.fetchCessoesParaData(data);

        List<Map<String, Object>> result = new ArrayList<>();
        if (salaId != null) {
            comissoes.stream().filter(r -> salaId == toInt(r.get("sala_id"))).forEach(result::add);
            cessoes.stream().filter(r -> salaId == toInt(r.get("sala_id"))).forEach(result::add);
        } else {
            result.addAll(comissoes);
            result.addAll(cessoes);
        }
        return result;
    }

    /** Plenário Principal para uma data arbitrária. */
    public List<Map<String, Object>> getAgendaPlenarioParaData(LocalDate data) {
        if (data.equals(LocalDate.now())) return cachePlenario;
        return ouVazio(fetchPlenario(data.format(DATE_FMT)));
    }

    /** Contrato público: nunca devolve null — a falha do fetch (null) vira lista vazia. */
    private List<Map<String, Object>> ouVazio(List<Map<String, Object>> fetched) {
        return fetched != null ? fetched : Collections.emptyList();
    }

    /** Subscribir via SSE — retorna emitter que recebe atualizações */
    public SseEmitter subscribe(Integer salaId, boolean plenarioPrincipal) {
        SseEmitter emitter = new SseEmitter(0L); // sem timeout
        EmitterEntry entry = new EmitterEntry(emitter, salaId, plenarioPrincipal);

        emitters.add(entry);
        emitter.onCompletion(() -> emitters.remove(entry));
        emitter.onTimeout(() -> emitters.remove(entry));
        emitter.onError(e -> emitters.remove(entry));

        // Enviar dados iniciais
        try {
            Map<String, Object> data = buildDataForEntry(entry);
            emitter.send(SseEmitter.event().name("agenda").data(data));
        } catch (Exception e) {
            emitters.remove(entry);
        }

        return emitter;
    }

    // ══ Fetch — Comissões (API XML) ═════════════════════════════

    /**
     * @return as reuniões da data (lista possivelmente VAZIA = a API respondeu e não há reunião),
     *         ou {@code null} = FALHA (HTTP não-2xx, erro de rede, corpo vazio ou XML inválido).
     *         Distinguir os dois é o que permite ao chamador preservar o cache só na falha.
     */
    private List<Map<String, Object>> fetchComissoes(String dataFormatada) {
        try {
            String xml = httpGet(COMISSAO_API + dataFormatada);
            if (xml == null || xml.isBlank()) return null;
            return parseXmlComissoes(xml);
        } catch (Exception e) {
            log.warn("Falha ao buscar agenda de comissões: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> parseXmlComissoes(String xml) throws Exception {
        var doc = parseXml(xml);

        NodeList reunioes = doc.getElementsByTagName("reuniao");
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < reunioes.getLength(); i++) {
            Element reuniao = (Element) reunioes.item(i);
            Map<String, Object> item = mapearReuniao(reuniao);
            // Só incluir se mapeou para uma sala do sistema
            if (item.get("sala_id") != null) result.add(item);
        }

        return result;
    }

    private Map<String, Object> mapearReuniao(Element reuniao) {
        Map<String, Object> item = new LinkedHashMap<>();

        item.put("tipo", "comissao");
        item.put("codigo", getTag(reuniao, "codigo"));
        item.put("titulo", getTag(reuniao, "titulo"));
        item.put("situacao", getTag(reuniao, "situacao"));

        // Comissão
        Element colegiado = getFirstElement(reuniao, "colegiadoCriador");
        if (colegiado != null) {
            item.put("comissao_sigla", getTag(colegiado, "sigla"));
            item.put("comissao_nome", getTag(colegiado, "nome"));
        }

        // Horário
        String dataInicio = getTag(reuniao, "dataInicio");
        if (dataInicio != null && dataInicio.contains("T")) {
            String horaParte = dataInicio.split("T")[1];
            if (horaParte.length() >= 5) {
                item.put("horario", NativeQueryUtils.hhmm(horaParte));
            }
        }
        String obsHorario = getTag(reuniao, "observacaoHorario");
        if (obsHorario != null && !obsHorario.isBlank()) {
            item.put("observacao_horario", obsHorario);
        }

        // Local e mapeamento para sala
        String local = getTag(reuniao, "local");
        item.put("local", local);

        Integer salaId = mapearLocalParaSala(local);
        item.put("sala_id", salaId);

        // Tipo
        Element tipo = getFirstElement(reuniao, "tipo");
        if (tipo != null) {
            item.put("tipo_descricao", getTag(tipo, "descricao"));
        }

        // Tipo presença
        item.put("tipo_presenca", getTag(reuniao, "tipoPresenca"));

        return item;
    }

    // ══ Fetch — Plenário Principal (API XML) ════════════════════

    /** Mesmo contrato de {@link #fetchComissoes}: lista = sucesso (pode ser vazia); {@code null} = falha. */
    private List<Map<String, Object>> fetchPlenario(String dataFormatada) {
        try {
            String xml = httpGet(PLENARIO_API + dataFormatada);
            if (xml == null || xml.isBlank()) return null;
            return parsePlenarioXml(xml);
        } catch (Exception e) {
            log.warn("Falha ao buscar agenda do plenário: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse do XML da API /plenario/agenda/dia/{YYYYMMDD}.
     * Filtra somente sessões do Senado Federal (Casa=SF), descartando Congresso Nacional.
     */
    private List<Map<String, Object>> parsePlenarioXml(String xml) throws Exception {
        var doc = parseXml(xml);

        NodeList sessoes = doc.getElementsByTagName("Sessao");
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < sessoes.getLength(); i++) {
            Element s = (Element) sessoes.item(i);
            Map<String, Object> item = mapearSessaoPlenario(s, i);
            if (item != null) result.add(item);
        }

        return result;
    }

    private Map<String, Object> mapearSessaoPlenario(Element s, int i) {
        String casa = getTag(s, "Casa");
        if (casa == null || !"SF".equalsIgnoreCase(casa)) return null;  // só Senado Federal

        Map<String, Object> item = new LinkedHashMap<>();
        String codigo = getTag(s, "CodigoSessao");
        item.put("codigo", codigo != null ? codigo : ("PLEN-" + (i + 1)));

        // Título: "Nº Sessão Tipo" — ex: "6ª Sessão Solene"
        // NumeroSessao vem como "6ª SESSÃO" — extraímos só o "6ª" para não duplicar com TipoSessao
        String numero = getTag(s, "NumeroSessao");
        String tipoSessao = getTag(s, "TipoSessao");
        item.put("titulo", montarTituloSessao(numero, tipoSessao));

        item.put("horario", getTag(s, "Hora"));
        item.put("local", getTag(s, "LocalSessao"));
        item.put("situacao", getTag(s, "SituacaoSessao"));
        item.put("tipo_descricao", "Sessão Plenária");

        String tipoPresenca = getTag(s, "DescricaoTipoPresenca");
        if (tipoPresenca != null && !tipoPresenca.isBlank()) {
            item.put("tipo_presenca", tipoPresenca);
        }

        // Descrição vem dentro de <Evento><DescricaoEvento>
        Element evento = getFirstElement(s, "Evento");
        if (evento != null) {
            String desc = getTag(evento, "DescricaoEvento");
            if (desc != null && !desc.isBlank()) item.put("descricao", desc);
        }

        return item;
    }

    private String montarTituloSessao(String numero, String tipoSessao) {
        StringBuilder titulo = new StringBuilder();
        if (numero != null) {
            Matcher m = NUMERO_ORDINAL_PATTERN.matcher(numero);
            if (m.find()) titulo.append(m.group(1)).append(" ");
        }
        if (tipoSessao != null) titulo.append(formatarMaiusculas(tipoSessao));
        return titulo.toString().trim();
    }

    /** Capitaliza primeira letra de cada palavra (ex: "SESSÃO SOLENE" → "Sessão Solene"). */
    private String formatarMaiusculas(String s) {
        if (s == null || s.isBlank()) return s;
        StringBuilder out = new StringBuilder(s.length());
        boolean inicioPalavra = true;
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isWhitespace(c)) {
                inicioPalavra = true;
                out.append(c);
            } else if (inicioPalavra) {
                out.append(Character.toUpperCase(c));
                inicioPalavra = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ══ SSE — Broadcast ═════════════════════════════════════════

    private void broadcast() {
        List<EmitterEntry> dead = new ArrayList<>();
        for (EmitterEntry entry : emitters) {
            try {
                Map<String, Object> data = buildDataForEntry(entry);
                entry.emitter.send(SseEmitter.event().name("agenda").data(data));
            } catch (Exception e) {
                dead.add(entry);
            }
        }
        emitters.removeAll(dead);
    }

    private Map<String, Object> buildDataForEntry(EmitterEntry entry) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (entry.plenarioPrincipal) {
            data.put("reunioes", cachePlenario);
            data.put("tipo", "plenario_principal");
        } else if (entry.salaId != null) {
            data.put("reunioes", getAgendaPorSala(entry.salaId));
            data.put("tipo", "comissao");
            data.put("sala_id", entry.salaId);
        } else {
            data.put("reunioes", getAgendaComissoes());
            data.put("tipo", "todas");
        }
        data.put("atualizado_em", java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDateTime().toString());
        return data;
    }

    // ══ Mapeamento plenário → sala ══════════════════════════════

    private void carregarMapeamentoSeNecessario() {
        if (plenarioToSalaId != null) return;

        Map<Integer, Integer> mapa = new HashMap<>();
        List<Sala> salas = salaRepository.findAtivasOrdenadas();
        Pattern numPattern = Pattern.compile("Plen[aá]rio\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
        Integer demaisId = null;
        Integer auditorioId = null;

        for (Sala sala : salas) {
            String nome = sala.getNome();
            Matcher m = numPattern.matcher(nome);
            if (m.find()) {
                int num = Integer.parseInt(m.group(1));
                mapa.put(num, sala.getId());
                continue;
            }
            String norm = semAcento(nome);
            if (norm.equals("demais salas")) demaisId = sala.getId();
            else if (AUDITORIO_PETRONIO_PATTERN.matcher(norm).find()) auditorioId = sala.getId();
        }

        plenarioToSalaId = mapa;
        salaDemaisSalasId = demaisId;
        salaAuditorioPetronioId = auditorioId;
        log.info("Mapeamento plenário → sala carregado: {}, demaisSalas={}, auditorio={}",
                mapa, demaisId, auditorioId);
    }

    /** Mapeia o campo 'local' da API do Senado para uma sala interna. */
    private Integer mapearLocalParaSala(String local) {
        if (local == null || plenarioToSalaId == null) return null;

        Matcher m = PLENARIO_NUM_PATTERN.matcher(local);
        if (m.find()) {
            int num = Integer.parseInt(m.group(1));
            return plenarioToSalaId.get(num);
        }

        String norm = semAcento(local).trim();

        // "Sala ..." (qualquer sala não catalogada) → Demais Salas
        if (salaDemaisSalasId != null && norm.startsWith("sala ")) {
            return salaDemaisSalasId;
        }

        // Variações de "Auditório Petrônio Portella" (acentos e grafia)
        if (salaAuditorioPetronioId != null && AUDITORIO_PETRONIO_PATTERN.matcher(norm).find()) {
            return salaAuditorioPetronioId;
        }

        return null;
    }

    // ══ HTTP ════════════════════════════════════════════════════

    private String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/xml, text/html, */*")
                    .header("User-Agent", "NUSP-SenadoApp/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                log.warn("HTTP {} ao buscar {}", response.statusCode(), url);
                return null;
            }
        } catch (Exception e) {
            log.warn("Erro HTTP ao buscar {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ══ XML helpers ═════════════════════════════════════════════

    private Document parseXml(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String getTag(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        Node node = nodes.item(0);
        return node.getTextContent() != null ? node.getTextContent().trim() : null;
    }

    private Element getFirstElement(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return -1;
    }

    // ══ Emitter entry ═══════════════════════════════════════════

    private record EmitterEntry(SseEmitter emitter, Integer salaId, boolean plenarioPrincipal) {}
}
