package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.MetabaseDashboard;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.MetabaseDashboardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static embedding de dashboards Metabase OSS.
 *
 * Para cada dashboard cadastrado em INT_METABASE_DASHBOARD, o service
 * assina um JWT HS256 com payload {@code {resource:{dashboard:N}, params:{...}, exp}}
 * e devolve a URL final {@code <METABASE_URL>/embed/dashboard/<JWT>#bordered=false&titled=false}.
 *
 * O segredo HMAC vem de {@code app.metabase.embedding-secret-key}
 * (configurado no Metabase em Admin Settings → Embedding → Static embedding)
 * e é tratado como string UTF-8 bruta — mesmo formato esperado pelo
 * Metabase ao verificar o JWT.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MetabaseEmbedService {

    private final MetabaseDashboardRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${app.metabase.url:}")
    private String metabaseUrl;

    @Value("${app.metabase.embedding-secret-key:}")
    private String embeddingSecretKey;

    @Value("${app.metabase.embed-ttl-sec:600}")
    private long embedTtlSeconds;

    public List<MetabaseDashboard> listarAtivos() {
        return repository.findByAtivoTrueOrderByOrdemAscTituloAsc();
    }

    /**
     * Gera a URL completa de embed para o dashboard (id interno = UUID do banco).
     * Retorna 404 se não existir, 400 se inativo, 503 se a chave não estiver configurada.
     */
    public String gerarEmbedUrl(String dashboardId) {
        if (embeddingSecretKey == null || embeddingSecretKey.isBlank()) {
            throw new ServiceValidationException(
                    "Static embedding do Metabase não está configurado no servidor.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (metabaseUrl == null || metabaseUrl.isBlank()) {
            throw new ServiceValidationException(
                    "URL do Metabase não configurada.", HttpStatus.SERVICE_UNAVAILABLE);
        }

        MetabaseDashboard dash = repository.findById(dashboardId)
                .orElseThrow(() -> new ServiceValidationException(
                        "Dashboard não encontrado.", HttpStatus.NOT_FOUND));
        if (Boolean.FALSE.equals(dash.getAtivo())) {
            throw new ServiceValidationException(
                    "Dashboard inativo.", HttpStatus.BAD_REQUEST);
        }

        String jwt = sign(dash.getMetabaseDashboardId(), parseParamsLocked(dash.getParamsLocked()));
        // Hash params (#) controlam UI do iframe — não afetam o JWT.
        return metabaseUrl + "/embed/dashboard/" + jwt + "#bordered=false&titled=false&theme=light";
    }

    private String sign(Integer metabaseDashboardId, Map<String, Object> params) {
        Key key = Keys.hmacShaKeyFor(embeddingSecretKey.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> resource = Map.of("dashboard", metabaseDashboardId);
        Date exp = Date.from(Instant.now().plusSeconds(embedTtlSeconds));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("resource", resource);
        claims.put("params", params);
        return Jwts.builder()
                .claims(claims)
                .expiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private Map<String, Object> parseParamsLocked(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("PARAMS_LOCKED inválido (não é JSON de objeto), ignorando: {}", e.getMessage());
            return Map.of();
        }
    }
}
