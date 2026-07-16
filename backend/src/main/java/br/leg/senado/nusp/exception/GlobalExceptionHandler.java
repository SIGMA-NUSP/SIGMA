package br.leg.senado.nusp.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Equivale ao service_error_response() do Python — converte exceções em JSON. */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ServiceValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", ex.getMessage());
        if (ex.getExtraFields() != null) {
            body.putAll(ex.getExtraFields());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Requisição malformada — 400 (achados F6 e F36): parâmetro obrigatório ausente, tipo/formato
     * inválido no parâmetro, parte multipart ausente, requisição que deveria ser multipart e não é,
     * corpo JSON ilegível e data fora do ISO (os controllers que fazem {@code LocalDate.parse} do
     * parâmetro cru, como o da Agenda). Sem esses handlers, tudo isso caía no {@link #handleGeneral}
     * e virava 500.
     *
     * <p>A {@link MultipartException} entrou no F36: o upload do cartão-ponto declara
     * {@code @RequestParam MultipartFile}, e uma requisição que não é multipart (o cliente mandou
     * JSON, ou esqueceu o boundary) faz o resolver lançá-la — erro do CLIENTE que virava 500 com
     * {@code log.error} e stacktrace. O 413 do upload gigante NÃO é afetado: a
     * {@link MaxUploadSizeExceededException} é subclasse dela e tem handler próprio, que o Spring
     * escolhe por ser o mais específico.
     *
     * <p>O corpo repete o shape das demais respostas de erro ({@code ok}/{@code error}) com
     * mensagem genérica — nada do tipo interno da exceção nem stacktrace vaza para o cliente.
     *
     * <p><b>F64:</b> a {@code MultipartException} também é a exceção de falha de <b>parse</b> do
     * multipart (tmpdir cheio, conexão abortada, {@code IOException} do contêiner — casos com
     * {@code getCause()} preenchida): descartar a exceção do log apagava o diagnóstico de infra.
     * Nesses casos o WARN carrega o throwable (stacktrace/causa encadeada); o "não é multipart"
     * (sem causa) continua na linha enxuta. O critério é restrito à {@code MultipartException} de
     * propósito: as DEMAIS exceções da lista carregam causa também no erro puro de cliente
     * ({@code ?page=abc} → {@code ConversionFailedException}; JSON torto → {@code JsonParseException})
     * e voltariam a encher o log de stacktrace — o ruído que o F36 eliminou. Status, corpo e
     * precedência do 413 intocados.
     */
    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MultipartException.class,
            HttpMessageNotReadableException.class,
            DateTimeParseException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        if (ex instanceof MultipartException && ex.getCause() != null) {
            log.warn("Requisição inválida ({}): {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        } else {
            log.warn("Requisição inválida ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
        }
        return erro(HttpStatus.BAD_REQUEST, "Requisição inválida. Verifique os dados enviados.");
    }

    /**
     * Erros de PROTOCOLO do Spring MVC — cada um com o seu status (achado F26).
     *
     * <p>Sem estes handlers, o {@link #handleGeneral} os engolia e devolvia **500**: o
     * {@code ExceptionHandlerExceptionResolver} (que consulta este advice) roda ANTES do
     * {@code DefaultHandlerExceptionResolver} do Spring, então o tratamento padrão — que já
     * conhece o status certo de cada uma — nunca era alcançado. Dois danos: o cliente não
     * distinguia "erro meu" de "erro do servidor", e cada request torta virava um `log.error`
     * com stacktrace (um scanner de rotas enchia o log de ERRO).
     *
     * <p>O corpo segue o shape das demais respostas de erro; o log é WARN, sem stacktrace.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRotaInexistente(NoResourceFoundException ex) {
        log.warn("Recurso inexistente: {}", ex.getResourcePath());
        return erro(HttpStatus.NOT_FOUND, "Recurso não encontrado.");
    }

    /** Verbo errado — 405 com o cabeçalho {@code Allow}, como o RFC exige. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMetodoNaoSuportado(HttpRequestMethodNotSupportedException ex) {
        log.warn("Método não suportado: {}", ex.getMessage());
        ResponseEntity.BodyBuilder resposta = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            HttpHeaders headers = new HttpHeaders();
            headers.setAllow(ex.getSupportedHttpMethods());
            resposta.headers(headers);
        }
        return resposta.body(corpo("Método não permitido para este recurso."));
    }

    /**
     * {@code Content-Type} que a rota não consome — 415, com os headers que a própria exceção monta
     * ({@code Accept} com os tipos aceitos e, em PATCH, {@code Accept-Patch}). Mesma razão do
     * {@code Allow} no 405: ao interceptar a exceção, o advice tira do caminho o tratamento padrão do
     * Spring, que era quem punha esses cabeçalhos.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNaoSuportado(HttpMediaTypeNotSupportedException ex) {
        log.warn("Content-Type não suportado: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(new HttpHeaders(ex.getHeaders()))
                .body(corpo("Formato de conteúdo não suportado."));
    }

    /**
     * {@code Accept} que a rota não produz — 406.
     *
     * <p>⚠️ Alcance limitado, e vale registrar o porquê: o corpo {@code {ok,error}} ainda é negociado
     * contra o {@code Accept} do cliente. Se ele aceita JSON (o caso de quem pede JSON de uma rota que
     * só produz, por exemplo, {@code text/event-stream}), a resposta sai como aqui está. Se o
     * {@code Accept} EXCLUI JSON, escrever este corpo é impossível: o Spring relança a exceção de dentro
     * do handler e resolve o 406 pelo caminho padrão, **sem corpo** — que é o que já acontecia antes.
     * Só o {@code ProblemDetail} escapa dessa negociação, e adotá-lo trocaria o shape de erro de toda a
     * API. Ou seja, este handler melhora um dos dois casos e não piora o outro.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNaoAceitavel(HttpMediaTypeNotAcceptableException ex) {
        log.warn("Accept não atendível: {}", ex.getMessage());
        return erro(HttpStatus.NOT_ACCEPTABLE, "Formato de resposta não suportado.");
    }

    /**
     * Upload acima do limite — 413. É o caso mais visível do F26: o limite de 25 MB existe para o
     * cartão-ponto (PDF multipágina), e um arquivo maior devolvia ao admin um "Erro interno do
     * servidor" que não dizia qual era o problema.
     *
     * <p>Desde o F36 a superclasse ({@link MultipartException}) também tem tratamento — 400, no
     * {@link #handleBadRequest}. Este handler continua vencendo para o arquivo grande porque o
     * Spring escolhe o método mais específico para o tipo lançado; a precedência está travada em
     * teste ({@code GlobalExceptionHandlerTest}), não deixada à confiança.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadGrande(MaxUploadSizeExceededException ex) {
        log.warn("Upload acima do limite: {}", ex.getMessage());
        return erro(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo acima do limite permitido.");
    }

    /**
     * Acesso negado pela method security — 403 (achado F2).
     *
     * <p>Nas rotas cobertas pelo matcher {@code /api/admin/**} quem nega é o filtro, antes do
     * dispatch, e este handler nem é consultado. Ele existe para a camada nova: a negação do
     * {@code @AdminOnly} nasce DENTRO do dispatch e seria capturada pelo {@link #handleGeneral},
     * virando 500 — a defesa em profundidade barraria o acesso, mas com o status errado.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Acesso negado pela method security: {}", ex.getMessage());
        return erro(HttpStatus.FORBIDDEN, "Acesso negado.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Erro não tratado em {}", ex.getClass().getSimpleName(), ex);
        return erro(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno do servidor");
    }

    /** Resposta de erro no shape único da API: {@code {ok:false, error:"..."}}. */
    private static ResponseEntity<Map<String, Object>> erro(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).body(corpo(mensagem));
    }

    private static Map<String, Object> corpo(String mensagem) {
        return Map.of("ok", false, "error", mensagem);
    }
}
