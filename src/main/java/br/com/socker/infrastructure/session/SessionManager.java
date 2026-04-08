package br.com.socker.infrastructure.session;

/**
 * @deprecated Replaced by
 * {@link br.com.socker.infrastructure.concentrator.ConcentratorConnectionRegistry}.
 *
 * <p>This class managed outbound sessions FROM GwCel TO Concentrador, which is the
 * wrong direction. The Concentrador initiates the TCP connection. Use
 * {@code ConcentratorConnectionRegistry} to manage the single active inbound connection.
 */
@Deprecated(since = "2.0", forRemoval = true)
public final class SessionManager {
    private SessionManager() {}
}
