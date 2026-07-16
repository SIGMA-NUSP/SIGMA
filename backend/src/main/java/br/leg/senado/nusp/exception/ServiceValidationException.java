package br.leg.senado.nusp.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Equivale ao ServiceValidationError do Python. */
public class ServiceValidationException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> extraFields;

    public ServiceValidationException(String message) {
        super(message);
        this.status = HttpStatus.BAD_REQUEST;
        this.extraFields = null;
    }

    public ServiceValidationException(String message, HttpStatus status) {
        super(message);
        this.status = status;
        this.extraFields = null;
    }

    /** Para erros com campos extras (ex: "invalid_payload" + {"missing": "campo1, campo2"}). */
    public ServiceValidationException(String errorCode, HttpStatus status, Map<String, Object> extraFields) {
        super(errorCode);
        this.status = status;
        this.extraFields = extraFields;
    }

    public HttpStatus getStatus() { return status; }
    public Map<String, Object> getExtraFields() { return extraFields; }
}
