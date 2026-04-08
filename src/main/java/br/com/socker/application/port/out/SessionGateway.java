package br.com.socker.application.port.out;

/**
 * @deprecated Replaced by {@link ConcentratorGateway}.
 *
 * <p>This port modelled delivery to a named session (outbound direction), which is
 * the wrong abstraction. Use {@code ConcentratorGateway} which delivers to the single
 * active inbound Concentrador connection.
 */
@Deprecated(since = "2.0", forRemoval = true)
public interface SessionGateway {
}
