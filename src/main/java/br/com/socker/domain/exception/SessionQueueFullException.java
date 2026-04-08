package br.com.socker.domain.exception;

/**
 * @deprecated Replaced by {@link ConnectionQueueFullException}.
 *
 * <p>This exception referred to a named session queue (wrong direction abstraction).
 * Use {@code ConnectionQueueFullException} when the Concentrador connection queue is full.
 */
@Deprecated(since = "2.0", forRemoval = true)
public class SessionQueueFullException extends DomainException {
    public SessionQueueFullException(String sessionId) {
        super("Session outbound queue is full, apply back-pressure: " + sessionId);
    }
}
