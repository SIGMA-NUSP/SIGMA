package br.leg.senado.nusp.service;

import br.leg.senado.nusp.exception.ServiceValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unitário do {@link AjudaChatService}: validação de entrada, rate limit, leitura do
 * manual (com corte da seção interna), montagem da requisição Chat Completions e
 * tratamento das falhas do provedor. Nenhum teste toca a rede — o {@link HttpClient}
 * é mockado, como no {@link AgendaLegislativaServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AjudaChatService — chat de ajuda com IA sobre o manual da página")
class AjudaChatServiceTest {

    private static final String MANUAL = "## Manual do banco de horas\nConteúdo do manual piloto.";
    private static final String RESPOSTA_OK = """
            {"choices":[{"message":{"role":"assistant","content":"Folgas só de amanhã em diante."}}]}
            """;

    @Mock
    private HttpClient httpClient;

    @Mock
    private Clock clock;

    @TempDir
    Path manuais;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AjudaChatService service;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(manuais.resolve("ponto-banco.md"), MANUAL);
        service = new AjudaChatService(httpClient, objectMapper, clock);
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "apiKey", "chave-teste");
        ReflectionTestUtils.setField(service, "model", "modelo-mini");
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.exemplo.test/v1");
        ReflectionTestUtils.setField(service, "manualDir", manuais.toString());
        ReflectionTestUtils.setField(service, "maxTokens", 700);
        ReflectionTestUtils.setField(service, "timeoutSec", 5L);
        ReflectionTestUtils.setField(service, "rateLimitPerMinute", 3);
        ReflectionTestUtils.setField(service, "reasoningEffort", "none");
        lenient().when(clock.millis()).thenReturn(0L);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private void provedorResponde(int status, String corpo) throws IOException, InterruptedException {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resposta = mock(HttpResponse.class);
        when(resposta.statusCode()).thenReturn(status);
        lenient().when(resposta.body()).thenReturn(corpo);
        when(httpClient.<String>send(any(), any())).thenReturn(resposta);
    }

    private HttpRequest requisicaoEnviada() throws IOException, InterruptedException {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).<String>send(captor.capture(), any());
        return captor.getValue();
    }

    /** Extrai o corpo publicado num HttpRequest (BodyPublishers.ofString entrega síncrono). */
    private static String corpoDe(HttpRequest request) throws InterruptedException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        CountDownLatch fim = new CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                saida.writeBytes(bytes);
            }
            @Override public void onError(Throwable t) { fim.countDown(); }
            @Override public void onComplete() { fim.countDown(); }
        });
        assertTrue(fim.await(5, TimeUnit.SECONDS), "corpo da requisição não foi publicado");
        return saida.toString(StandardCharsets.UTF_8);
    }

    private ServiceValidationException falha(Runnable acao) {
        return assertThrows(ServiceValidationException.class, acao::run);
    }

    // ── Casos ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Requisição ao provedor")
    class Requisicao {

        @Test
        @DisplayName("sucesso: monta o Chat Completions com system prompt (guard-rails + manual) e devolve o content")
        void sucesso() throws Exception {
            provedorResponde(200, RESPOSTA_OK);

            String resposta = service.responder("u1", "ponto-banco", "Posso marcar hoje?", null);

            assertEquals("Folgas só de amanhã em diante.", resposta);
            HttpRequest req = requisicaoEnviada();
            assertEquals("https://api.exemplo.test/v1/chat/completions", req.uri().toString());
            assertEquals("Bearer chave-teste", req.headers().firstValue("Authorization").orElseThrow());
            assertEquals("application/json", req.headers().firstValue("Content-Type").orElseThrow());

            JsonNode corpo = objectMapper.readTree(corpoDe(req));
            assertEquals("modelo-mini", corpo.path("model").asText());
            assertEquals(700, corpo.path("max_completion_tokens").asInt());
            assertEquals("none", corpo.path("reasoning_effort").asText());
            JsonNode messages = corpo.path("messages");
            assertEquals(2, messages.size());
            assertEquals("system", messages.get(0).path("role").asText());
            assertTrue(messages.get(0).path("content").asText().contains(MANUAL),
                    "system prompt deve embutir o manual");
            assertTrue(messages.get(0).path("content").asText().contains("NUNCA invente"),
                    "system prompt deve conter os guard-rails");
            assertEquals("user", messages.get(1).path("role").asText());
            assertEquals("Posso marcar hoje?", messages.get(1).path("content").asText());
        }

        @Test
        @DisplayName("seção 'Referência técnica' do manual é cortada antes do prompt (não entra no contexto)")
        void referenciaTecnicaCortada() throws Exception {
            Files.writeString(manuais.resolve("ponto-banco.md"), MANUAL
                    + "\n\n## 10. Referência técnica (não citar ao usuário)\nSEGREDO-TECNICO endpoints e tabelas.");
            provedorResponde(200, RESPOSTA_OK);

            service.responder("u1", "ponto-banco", "Oi?", null);

            String system = objectMapper.readTree(corpoDe(requisicaoEnviada()))
                    .path("messages").get(0).path("content").asText();
            assertTrue(system.contains("Conteúdo do manual piloto."), "parte pública do manual permanece");
            assertFalse(system.contains("SEGREDO-TECNICO"), "seção interna não pode entrar no prompt");
            assertFalse(system.contains("Referência técnica ("), "nem o cabeçalho da seção interna");
        }

        @Test
        @DisplayName("página 'admin-ponto' é aceita e lê o admin-ponto.md; o corte da seção 10 é genérico (vale p/ este manual também)")
        void paginaAdminPonto() throws Exception {
            Files.writeString(manuais.resolve("admin-ponto.md"),
                    "## Folhas de Ponto (admin)\nEnvie o PDF e publique o lote."
                            + "\n\n## 10. Referência técnica (não citar ao usuário)\nSEGREDO-ADMIN endpoints e tabelas.");
            provedorResponde(200, RESPOSTA_OK);

            service.responder("u1", "admin-ponto", "Como envio a folha de ponto?", null);

            String system = objectMapper.readTree(corpoDe(requisicaoEnviada()))
                    .path("messages").get(0).path("content").asText();
            assertTrue(system.contains("Envie o PDF e publique o lote."),
                    "o manual da folha do admin deve entrar no prompt");
            assertFalse(system.contains("SEGREDO-ADMIN"),
                    "a seção interna também é cortada neste manual (corte por título, genérico)");
        }

        @Test
        @DisplayName("histórico: mapeia de/texto → role/content e corta nas últimas 6 mensagens")
        void historicoTruncado() throws Exception {
            provedorResponde(200, RESPOSTA_OK);
            List<Map<String, String>> historico = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                historico.add(Map.of("de", i % 2 == 1 ? "usuario" : "assistente", "texto", "msg" + i));
            }

            service.responder("u1", "ponto-banco", "E agora?", historico);

            JsonNode messages = objectMapper.readTree(corpoDe(requisicaoEnviada())).path("messages");
            // system + 6 do histórico + pergunta
            assertEquals(8, messages.size());
            assertEquals("msg3", messages.get(1).path("content").asText());
            assertEquals("user", messages.get(1).path("role").asText());
            assertEquals("msg4", messages.get(2).path("content").asText());
            assertEquals("assistant", messages.get(2).path("role").asText());
            assertEquals("E agora?", messages.get(7).path("content").asText());
        }

        @Test
        @DisplayName("mensagem fora do teto NA PARTE CORTADA do histórico não reprova a requisição")
        void historicoAntigoForaDoTetoNaoTrava() throws Exception {
            provedorResponde(200, RESPOSTA_OK);
            List<Map<String, String>> historico = new ArrayList<>();
            // 1ª mensagem gigante (será cortada — só as 6 últimas seguem) + 6 curtas
            historico.add(Map.of("de", "assistente", "texto", "y".repeat(9000)));
            for (int i = 1; i <= 6; i++) {
                historico.add(Map.of("de", i % 2 == 1 ? "usuario" : "assistente", "texto", "msg" + i));
            }

            assertDoesNotThrow(() -> service.responder("u1", "ponto-banco", "E agora?", historico));
        }

        @Test
        @DisplayName("provedor não-openai: usa max_tokens e não envia reasoning_effort")
        void provedorGenerico() throws Exception {
            ReflectionTestUtils.setField(service, "provider", "gemini");
            provedorResponde(200, RESPOSTA_OK);

            service.responder("u1", "ponto-banco", "Oi?", null);

            JsonNode corpo = objectMapper.readTree(corpoDe(requisicaoEnviada()));
            assertEquals(700, corpo.path("max_tokens").asInt());
            assertTrue(corpo.path("max_completion_tokens").isMissingNode());
            assertTrue(corpo.path("reasoning_effort").isMissingNode());
        }

        @Test
        @DisplayName("reasoning-effort vazio: campo não vai ao corpo nem com provider=openai")
        void reasoningEffortVazioNaoEnviado() throws Exception {
            ReflectionTestUtils.setField(service, "reasoningEffort", "");
            provedorResponde(200, RESPOSTA_OK);

            service.responder("u1", "ponto-banco", "Oi?", null);

            JsonNode corpo = objectMapper.readTree(corpoDe(requisicaoEnviada()));
            assertEquals(700, corpo.path("max_completion_tokens").asInt());
            assertTrue(corpo.path("reasoning_effort").isMissingNode());
        }

        @Test
        @DisplayName("base-url com barra final não gera '//' na URI")
        void baseUrlComBarraFinal() throws Exception {
            ReflectionTestUtils.setField(service, "baseUrl", "https://api.exemplo.test/v1/");
            provedorResponde(200, RESPOSTA_OK);

            service.responder("u1", "ponto-banco", "Oi?", null);

            assertEquals("https://api.exemplo.test/v1/chat/completions",
                    requisicaoEnviada().uri().toString());
        }
    }

    @Nested
    @DisplayName("Validação de entrada")
    class Validacao {

        @Test
        @DisplayName("página fora da whitelist → 400 (id do cliente nunca vira path)")
        void paginaDesconhecida() {
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "../../etc/passwd", "Oi?", null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Página desconhecida.", ex.getMessage());
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("pergunta acima do teto de caracteres → 400")
        void perguntaLonga() {
            String longa = "x".repeat(2001);
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", longa, null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("histórico que não é lista, item torto, 'de' inválido ou mensagem gigante → 400")
        void historicoTorto() {
            // um usuário por chamada: aqui se testa o shape, não a cota (que vem antes e contaria as 4)
            assertEquals(HttpStatus.BAD_REQUEST,
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", "não-lista")).getStatus());
            assertEquals(HttpStatus.BAD_REQUEST,
                    falha(() -> service.responder("u2", "ponto-banco", "Oi?",
                            List.of(Map.of("de", "usuario")))).getStatus());
            assertEquals(HttpStatus.BAD_REQUEST,
                    falha(() -> service.responder("u3", "ponto-banco", "Oi?",
                            List.of(Map.of("de", "system", "texto", "hack")))).getStatus());
            assertEquals(HttpStatus.BAD_REQUEST,
                    falha(() -> service.responder("u4", "ponto-banco", "Oi?",
                            List.of(Map.of("de", "usuario", "texto", "z".repeat(8001))))).getStatus());
            verifyNoInteractions(httpClient);
        }
    }

    @Nested
    @DisplayName("Rate limit por usuário")
    class RateLimit {

        @Test
        @DisplayName("estoura o limite na mesma janela → 429; janela nova zera a contagem")
        void limitePorJanela() throws Exception {
            provedorResponde(200, RESPOSTA_OK);
            when(clock.millis()).thenReturn(0L);
            for (int i = 0; i < 3; i++) {
                service.responder("u1", "ponto-banco", "Oi?", null);
            }

            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());

            // outro usuário não é afetado; a janela seguinte libera o mesmo usuário
            assertDoesNotThrow(() -> service.responder("u2", "ponto-banco", "Oi?", null));
            when(clock.millis()).thenReturn(61_000L);
            assertDoesNotThrow(() -> service.responder("u1", "ponto-banco", "Oi?", null));
        }

        @Test
        @DisplayName("a cota é debitada ANTES das validações: request inválida também conta")
        void cotaAntesDaValidacao() {
            when(clock.millis()).thenReturn(0L);
            for (int i = 0; i < 3; i++) {
                falha(() -> service.responder("u1", "pagina-invalida", "Oi?", null));
            }
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "pagina-invalida", "Oi?", null));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
            verifyNoInteractions(httpClient);
        }
    }

    @Nested
    @DisplayName("Falhas de configuração e do provedor — sempre 503 amigável")
    class Falhas {

        @Test
        @DisplayName("configuração incompleta (chave vazia) → 503 sem tocar a rede")
        void semChave() {
            ReflectionTestUtils.setField(service, "apiKey", "");
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("manual ausente no diretório → 503 sem tocar a rede")
        void manualAusente() throws IOException {
            Files.delete(manuais.resolve("ponto-banco.md"));
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("config presente mas torta (base-url sem esquema) → 503, nunca 500 cru")
        void baseUrlSemEsquema() {
            ReflectionTestUtils.setField(service, "baseUrl", "api.exemplo.test/v1");
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("provedor devolve HTTP != 2xx → 503 com a mensagem amigável")
        void provedorErro() throws Exception {
            provedorResponde(500, "{\"error\":{\"message\":\"boom\"}}");
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            assertTrue(ex.getMessage().contains("indisponível"));
        }

        @Test
        @DisplayName("falha de rede/timeout (IOException) → 503")
        void provedorForaDoAr() throws Exception {
            when(httpClient.<String>send(any(), any())).thenThrow(new IOException("timed out"));
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        }

        @Test
        @DisplayName("InterruptedException do send → 503 com a flag de interrupção restaurada")
        void interrompido() throws Exception {
            when(httpClient.<String>send(any(), any())).thenThrow(new InterruptedException("stop"));
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            assertTrue(Thread.interrupted(), "flag de interrupção deve ser restaurada (e aqui limpa)");
        }

        @Test
        @DisplayName("200 sem choices/content utilizável → 503")
        void respostaSemConteudo() throws Exception {
            provedorResponde(200, "{\"choices\":[]}");
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        }

        @Test
        @DisplayName("200 com corpo que nem é JSON → 503")
        void respostaNaoJson() throws Exception {
            provedorResponde(200, "<html>gateway error</html>");
            ServiceValidationException ex =
                    falha(() -> service.responder("u1", "ponto-banco", "Oi?", null));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        }
    }
}
