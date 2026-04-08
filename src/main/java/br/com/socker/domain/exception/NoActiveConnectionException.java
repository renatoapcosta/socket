package br.com.socker.domain.exception;

/**
 * Thrown when a REST caller tries to dispatch a message but no Concentrador
 * connection is currently registered.
 *
 * <p>The correct flow is: the Concentrador initiates a TCP connection to the
 * GwCel socket server. GwCel then uses that inbound connection to send messages.
 * This exception signals that the Concentrador has not yet connected (or has
 * disconnected).
 *
 * <p>Callers should respond with HTTP 404.
 */
public class NoActiveConnectionException extends DomainException {

    public NoActiveConnectionException() {
        super("No active Concentrador connection registered. " +
              "The Concentrador must connect to the GwCel socket server first.");
    }
}
