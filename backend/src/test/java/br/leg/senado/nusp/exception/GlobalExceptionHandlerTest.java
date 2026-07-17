package br.leg.senado.nusp.exception;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de erro do advice global, na unidade — um caso por ramo.
 *
 * <p>Os testes de MVC (ex.: {@code ProtocoloErroContratoTest}) provam os ramos ponta a ponta,
 * mas só alcançam o que uma rota real consegue provocar. Três ramos ficam fora do alcance deles:
 * {@code MissingServletRequestPartException} (as partes multipart opcionais das rotas testáveis
 * não a disparam), {@code MaxUploadSizeExceededException} (o limite é imposto pelo resolver de
 * multipart do contêiner — o MockMvc não o aplica) e {@code HttpMediaTypeNotAcceptableException}
 * (quando o {@code Accept} exclui JSON, o corpo {@code {ok,error}} não pode sequer ser escrito;
 * ver o javadoc do handler). Cada ramo é exercitado direto: é o teste que quebra se alguém tirar
 * um tipo da lista.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Os seis tipos de requisição malformada que o handler mapeia para 400. */
    static Stream<Arguments> requisicoesMalformadas() throws Exception {
        MethodParameter param = new MethodParameter(Alvo.class.getDeclaredMethod("metodo", String.class), 0);
        return Stream.of(
                Arguments.of("tipo de parâmetro incompatível (?page=abc)",
                        new MethodArgumentTypeMismatchException("abc", Integer.class, "page", param, null)),
                Arguments.of("parâmetro obrigatório ausente",
                        new MissingServletRequestParameterException("checklist_id", "long")),
                Arguments.of("parte multipart ausente",
                        new MissingServletRequestPartException("arquivo")),
                Arguments.of("requisição que deveria ser multipart e não é",
                        new MultipartException("Current request is not a multipart request")),
                Arguments.of("corpo JSON ilegível",
                        new HttpMessageNotReadableException("json torto", new MockHttpInputMessage(new byte[0]))),
                Arguments.of("data fora do ISO (LocalDate.parse do parâmetro cru)",
                        new DateTimeParseException("texto inválido", "09-07-2026", 0)));
    }

    @ParameterizedTest(name = "[{index}] {0} → 400 no shape padrão")
    @MethodSource("requisicoesMalformadas")
    @DisplayName("requisição malformada vira 400, não 500")
    void requisicaoMalformada_400(String cenario, Exception ex) {
        ResponseEntity<Map<String, Object>> r = handler.handleBadRequest(ex);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Requisição inválida. Verifique os dados enviados."));
    }

    @Test
    @DisplayName("rota inexistente vira 404, não 500")
    void rotaInexistente_404() {
        ResponseEntity<Map<String, Object>> r = handler.handleRotaInexistente(
                new NoResourceFoundException(HttpMethod.GET, "/api/rota-que-nao-existe"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Recurso não encontrado."));
    }

    @Test
    @DisplayName("verbo errado vira 405 com o cabeçalho Allow, não 500")
    void metodoNaoSuportado_405ComAllow() {
        ResponseEntity<Map<String, Object>> r = handler.handleMetodoNaoSuportado(
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(r.getHeaders().getAllow()).containsExactly(HttpMethod.POST); // o RFC exige o Allow no 405
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Método não permitido para este recurso."));
    }

    @Test
    @DisplayName("Content-Type não suportado vira 415 com o Accept dos tipos aceitos, não 500")
    void mediaTypeNaoSuportado_415ComAccept() {
        ResponseEntity<Map<String, Object>> r = handler.handleMediaTypeNaoSuportado(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        // Os headers vêm da própria exceção (Accept e, em PATCH, Accept-Patch): interceptá-la tirou do
        // caminho o tratamento padrão do Spring, que era quem os punha — mesmo caso do Allow no 405.
        assertThat(r.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Formato de conteúdo não suportado."));
    }

    @Test
    @DisplayName("Accept não atendível vira 406, não 500")
    void mediaTypeNaoAceitavel_406() {
        ResponseEntity<Map<String, Object>> r = handler.handleMediaTypeNaoAceitavel(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Formato de resposta não suportado."));
    }

    /**
     * O único caso que nenhum teste de MVC alcança: o limite de upload é
     * imposto pelo resolver de multipart do contêiner, não pelo controller — o MockMvc não o aplica.
     * O admin subia um cartão-ponto acima de 25 MB e recebia "Erro interno do servidor".
     */
    @Test
    @DisplayName("upload acima do limite vira 413, não 500")
    void uploadAcimaDoLimite_413() {
        ResponseEntity<Map<String, Object>> r = handler.handleUploadGrande(
                new MaxUploadSizeExceededException(25L * 1024 * 1024));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Arquivo acima do limite permitido."));
    }

    /**
     * A pergunta que o teste acima NÃO responde (ele chama o método direto): com a SUPERCLASSE
     * ({@code MultipartException}) na lista do 400, quem o Spring escolhe para o arquivo grande?
     *
     * <p>O {@link ExceptionHandlerMethodResolver} é exatamente o componente que o
     * {@code ExceptionHandlerExceptionResolver} usa no dispatch — perguntar a ele é perguntar ao Spring.
     * A regra é "o mais específico vence": {@code MaxUploadSizeExceededException} continua indo para o
     * 413, e a genérica para o 400. Sem esta prova, o handler do 400 poderia ter roubado o 413 em silêncio.
     */
    @Test
    @DisplayName("a precedência do 413 se mantém: o Spring escolhe o handler mais específico")
    void precedenciaDoHandlerMaisEspecifico() {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        assertThat(resolver.resolveMethod(new MaxUploadSizeExceededException(25L * 1024 * 1024)).getName())
                .isEqualTo("handleUploadGrande");
        assertThat(resolver.resolveMethod(new MultipartException("Current request is not a multipart request"))
                .getName()).isEqualTo("handleBadRequest");
    }

    /**
     * O SEGUNDO dano (o primeiro é o status): erro do cliente virava {@code log.error} com
     * stacktrace — um scanner de rotas, ou um cliente com o Content-Type errado, enchia o log de ERRO.
     * O handler do 400 loga WARN, sem stacktrace; é isso que se trava aqui.
     */
    @Test
    @DisplayName("o erro do cliente loga WARN (não ERROR com stacktrace)")
    void requisicaoNaoMultipart_logaWarn() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleBadRequest(new MultipartException("Current request is not a multipart request"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent evento = appender.list.get(0);
        assertThat(evento.getLevel()).isEqualTo(Level.WARN);
        assertThat(evento.getThrowableProxy()).isNull();               // sem stacktrace no log
        assertThat(evento.getFormattedMessage()).contains("MultipartException");
    }

    /**
     * A {@code MultipartException} da lista do 400 também é a exceção de falha de
     * <b>parse</b> do multipart (tmpdir cheio, conexão abortada, {@code IOException} do contêiner)
     * — e o {@code log.warn} sem o throwable apagava o diagnóstico de infra. Com causa anexada, o
     * WARN carrega a exceção; o contrato visível (400, corpo, precedência do 413 — travada em
     * {@link #precedenciaDoHandlerMaisEspecifico}) não muda.
     */
    @Test
    @DisplayName("multipart COM causa (falha de parse): mesmo 400, e o log PRESERVA a causa")
    void multipartComCausa_logPreservaACausa() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ResponseEntity<Map<String, Object>> r;
        try {
            r = handler.handleBadRequest(new MultipartException("Falha no parse do multipart",
                    new java.io.IOException("No space left on device")));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);   // comportamento visível intocado
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Requisição inválida. Verifique os dados enviados."));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent evento = appender.list.get(0);
        assertThat(evento.getLevel()).isEqualTo(Level.WARN);               // continua WARN, não ERROR
        assertThat(evento.getThrowableProxy()).isNotNull();                // ← a causa não some mais do log
        assertThat(evento.getThrowableProxy().getClassName()).isEqualTo(MultipartException.class.getName());
        assertThat(evento.getThrowableProxy().getCause().getMessage()).contains("No space left on device");
    }

    /**
     * O log com causa é RESTRITO à {@code MultipartException} de propósito: as demais exceções
     * da lista do 400 carregam causa TAMBÉM no erro puro de cliente ({@code ?page=abc} →
     * {@code ConversionFailedException}/{@code NumberFormatException}; JSON torto →
     * {@code JsonParseException}) — logar o throwable nelas devolveria o ruído ao log
     * (verificado no MVC real).
     */
    @Test
    @DisplayName("tipo errado de parâmetro COM causa (erro de cliente): log continua sem stacktrace")
    void tipoErradoComCausa_logContinuaEnxuto() throws Exception {
        MethodParameter param = new MethodParameter(Alvo.class.getDeclaredMethod("metodo", String.class), 0);
        Exception comCausa = new MethodArgumentTypeMismatchException("abc", Integer.class, "page", param,
                new NumberFormatException("For input string: \"abc\""));

        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ResponseEntity<Map<String, Object>> r;
        try {
            r = handler.handleBadRequest(comCausa);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();   // o 400 comum segue enxuto
    }

    /** O outro lado: sem causa é o erro puro de CLIENTE — a linha do log continua enxuta. */
    @Test
    @DisplayName("multipart SEM causa (erro de cliente): 400 idêntico e log sem stacktrace")
    void multipartSemCausa_logContinuaEnxuto() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ResponseEntity<Map<String, Object>> r;
        try {
            r = handler.handleBadRequest(new MultipartException("Current request is not a multipart request"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Requisição inválida. Verifique os dados enviados."));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();     // nada de stacktrace no 400 comum
    }

    @Test
    @DisplayName("acesso negado pela method security vira 403, não 500")
    void acessoNegado_403() {
        ResponseEntity<Map<String, Object>> r =
                handler.handleAccessDenied(new AccessDeniedException("Access Denied"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Acesso negado."));
    }

    @Test
    @DisplayName("o que não é do cliente continua 500 genérico, sem vazar o tipo interno")
    void erroInesperado_500() {
        ResponseEntity<Map<String, Object>> r =
                handler.handleGeneral(new IllegalStateException("NPE no service com stacktrace secreto"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ok", false, "error", "Erro interno do servidor"));
    }

    @Test
    @DisplayName("ServiceValidationException mantém status e mensagem do domínio (contrato pré-existente)")
    void validacaoDeDominio_preservada() {
        ResponseEntity<Map<String, Object>> r = handler.handleValidation(
                new ServiceValidationException("Data inválida (use AAAA-MM-DD)."));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("ok", false)
                .containsEntry("error", "Data inválida (use AAAA-MM-DD).");
    }

    /** Só existe para dar um {@link MethodParameter} real ao MethodArgumentTypeMismatchException. */
    @SuppressWarnings("unused")
    private static final class Alvo {
        void metodo(String page) {
        }
    }
}
