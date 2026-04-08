package br.com.socker.infrastructure.concentrator;

import br.com.socker.application.port.out.ConcentratorGateway;
import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.exception.NoActiveConnectionException;
import br.com.socker.domain.model.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry for the single active {@link ConcentratorConnection}.
 *
 * <p>GwCel is designed for one Concentrador at a time. When a new Concentrador
 * connects, it replaces the old connection (which is closed). When the Concentrador
 * disconnects, the registry becomes empty and REST callers receive HTTP 404 until
 * a new connection is established.
 *
 * <p>Implements {@link ConcentratorGateway} so use cases can send messages without
 * knowing infrastructure details.
 *
 * <h2>Thread safety</h2>
 * <p>All state is managed through an {@link AtomicReference} — all operations are
 * lock-free and safe to call from any thread.
 */
public final class ConcentratorConnectionRegistry implements ConcentratorGateway {

    private static final Logger log = LoggerFactory.getLogger(ConcentratorConnectionRegistry.class);

    private final AtomicReference<ConcentratorConnection> active = new AtomicReference<>();

    // ─── Lifecycle management ─────────────────────────────────────────────────

    /**
     * Register a newly accepted connection as the active Concentrador connection.
     *
     * <p>If an existing active connection is present, it is closed before the new
     * one is registered. This implements the "single active connection" invariant.
     *
     * @param connection the newly accepted connection; must not be null
     */
    public void register(ConcentratorConnection connection) {
        ConcentratorConnection old = active.getAndSet(connection);
        if (old != null && old != connection) {
            log.warn("Replacing active connection {} with new connection {}",
                old.getConnectionId(), connection.getConnectionId());
            old.close();
        }
        log.info("Concentrador connection registered: id={} remote={}",
            connection.getConnectionId(), connection.getRemoteAddress());
    }

    /**
     * Deregister and close the connection identified by {@code connectionId}.
     *
     * <p>This is a compare-and-set operation — if the current active connection has
     * a different id (i.e. a new connection was registered between the disconnect and
     * this call), this is a no-op. The new connection is left untouched.
     *
     * @param connectionId the id of the connection to deregister
     */
    public void deregister(String connectionId) {
        ConcentratorConnection current = active.get();
        if (current != null && current.getConnectionId().equals(connectionId)) {
            if (active.compareAndSet(current, null)) {
                current.close();
                log.info("Concentrador connection deregistered: id={}", connectionId);
            }
        }
    }

    /**
     * Returns the current active connection, if any.
     */
    public Optional<ConcentratorConnection> getActive() {
        return Optional.ofNullable(active.get());
    }

    // ─── ConcentratorGateway port ─────────────────────────────────────────────

    /**
     * Enqueue an ISO 8583 message for delivery to the active Concentrador connection.
     *
     * @param message the message to send
     * @throws NoActiveConnectionException  if no Concentrador is currently connected
     * @throws ConnectionQueueFullException if the outbound queue is at capacity
     */
    @Override
    public void send(IsoMessage message) {
        ConcentratorConnection connection = active.get();
        if (connection == null || !connection.isAlive()) {
            throw new NoActiveConnectionException();
        }
        connection.enqueue(message);
        log.debug("Queued for Concentrador: MTI={} NSU={}",
            message.getMessageType().getMti(), message.getNsu().orElse("?"));
    }
}
