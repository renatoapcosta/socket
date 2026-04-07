package br.com.socker.application.port.out;

/**
 * Thrown by {@link MessageGateway} when a transport-level error occurs.
 *
 * <p>This exception crosses the boundary from the adapter layer into the application layer,
 * so it lives here in the port package. It must remain free of transport-specific types.
 */
public class GatewayException extends Exception {

    public enum Reason {
        CONNECTION_REFUSED,
        CONNECTION_TIMEOUT,
        READ_TIMEOUT,
        POOL_EXHAUSTED,
        PROTOCOL_ERROR,
        UNEXPECTED_DISCONNECT,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public GatewayException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public GatewayException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
