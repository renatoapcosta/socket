package br.com.socker.domain.model;

import java.util.Optional;

/**
 * Domain value object representing the result of processing an ISO transaction.
 *
 * <p>Returned by use cases — carries the response message and status.
 * Never references transport, sockets, or encoding details.
 */
public final class TransactionResult {

    private final IsoMessage responseMessage;
    private final boolean success;
    private final String errorMessage;

    private TransactionResult(IsoMessage responseMessage, boolean success, String errorMessage) {
        this.responseMessage = responseMessage;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static TransactionResult success(IsoMessage responseMessage) {
        return new TransactionResult(responseMessage, true, null);
    }

    public static TransactionResult failure(IsoMessage responseMessage, String reason) {
        return new TransactionResult(responseMessage, false, reason);
    }

    public static TransactionResult protocolError(String reason) {
        return new TransactionResult(null, false, reason);
    }

    public IsoMessage getResponseMessage() {
        return responseMessage;
    }

    public boolean isSuccess() {
        return success;
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public boolean hasResponse() {
        return responseMessage != null;
    }

    @Override
    public String toString() {
        return "TransactionResult{success=" + success + ", mti=" +
               (responseMessage != null ? responseMessage.getMessageType().getMti() : "none") + "}";
    }
}
