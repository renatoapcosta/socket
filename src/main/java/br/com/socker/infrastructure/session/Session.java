package br.com.socker.infrastructure.session;

/**
 * @deprecated Replaced by
 * {@link br.com.socker.infrastructure.concentrator.ConcentratorConnection}.
 *
 * <p>This class modelled sessions as outbound connections FROM GwCel TO Concentrador,
 * which is the wrong direction. The Concentrador initiates the TCP connection TO GwCel.
 * Use {@code ConcentratorConnection} to wrap an inbound accepted socket.
 */
@Deprecated(since = "2.0", forRemoval = true)
public final class Session {
    private Session() {}
}
