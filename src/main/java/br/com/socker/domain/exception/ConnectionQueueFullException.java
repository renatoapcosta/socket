package br.com.socker.domain.exception;

/**
 * Thrown when a message cannot be enqueued because the active Concentrador
 * connection's outbound queue has reached its configured capacity.
 *
 * <p>Callers should respond with HTTP 429 Too Many Requests to signal
 * back-pressure to the REST client.
 */
public class ConnectionQueueFullException extends DomainException {

    private final String connectionId;

    public ConnectionQueueFullException(String connectionId) {
        super("Concentrador connection outbound queue is full, apply back-pressure: " + connectionId);
        this.connectionId = connectionId;
    }

    public String getConnectionId() {
        return connectionId;
    }
}
