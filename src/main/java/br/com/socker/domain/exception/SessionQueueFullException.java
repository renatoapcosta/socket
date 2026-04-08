package br.com.socker.domain.exception;

/**
 * Thrown when a message cannot be enqueued because the session's outbound
 * queue has reached its configured capacity.
 *
 * <p>Callers should respond with HTTP 429 Too Many Requests to signal
 * backpressure to the client.
 */
public class SessionQueueFullException extends DomainException {

    private final String sessionId;

    public SessionQueueFullException(String sessionId) {
        super("Session outbound queue is full, apply back-pressure: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
