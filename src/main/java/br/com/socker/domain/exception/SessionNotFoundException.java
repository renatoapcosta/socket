package br.com.socker.domain.exception;

/**
 * @deprecated Replaced by {@link NoActiveConnectionException}.
 *
 * <p>This exception referred to a named session (wrong direction abstraction).
 * Use {@code NoActiveConnectionException} when no Concentrador has connected yet.
 */
@Deprecated(since = "2.0", forRemoval = true)
public class SessionNotFoundException extends DomainException {
    public SessionNotFoundException(String sessionId) {
        super("Session not found or no longer active: " + sessionId);
    }
}
