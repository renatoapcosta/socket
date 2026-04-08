package br.com.socker.domain.exception;

/**
 * Thrown when an operation targets a session that does not exist
 * or has already been closed.
 *
 * <p>This is a domain exception because the concept of a "session" is part of
 * the protocol domain — it represents a named, long-lived TCP connection to GwCel.
 */
public class SessionNotFoundException extends DomainException {

    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("Session not found or no longer active: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
