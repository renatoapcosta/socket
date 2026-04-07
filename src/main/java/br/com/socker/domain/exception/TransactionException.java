package br.com.socker.domain.exception;

import br.com.socker.domain.model.ResponseCode;

/**
 * Thrown when a transaction cannot be processed due to a business rule violation.
 */
public class TransactionException extends DomainException {

    private final ResponseCode responseCode;

    public TransactionException(ResponseCode responseCode, String message) {
        super(message);
        this.responseCode = responseCode;
    }

    public TransactionException(ResponseCode responseCode, String message, Throwable cause) {
        super(message, cause);
        this.responseCode = responseCode;
    }

    public ResponseCode getResponseCode() {
        return responseCode;
    }
}
