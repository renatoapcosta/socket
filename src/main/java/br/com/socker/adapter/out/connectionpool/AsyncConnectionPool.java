package br.com.socker.adapter.out.connectionpool;

import br.com.socker.infrastructure.net.SocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of {@link MultiplexedConnection} instances — each shared by N concurrent in-flight requests.
 *
 * <p>Acquisition strategy:
 * <ol>
 *   <li>Find the first alive connection with remaining capacity ({@link MultiplexedConnection#hasCapacity()}).</li>
 *   <li>If none found and pool is below {@code maxConnections}, open a new connection.</li>
 *   <li>If pool is at max and all connections are alive but saturated, return the first alive
 *       connection and let the caller wait on the in-flight semaphore.</li>
 *   <li>If no alive connection exists: throw {@link IOException}.</li>
 * </ol>
 *
 * <h2>Confirmations</h2>
 * <ul>
 *   <li>{@code maxInFlightPerConnection} is configurable.</li>
 *   <li>SO_TIMEOUT is overridden to 0 after the socket is created — the ReadLoop owns lifecycle.</li>
 *   <li>Dead connections are removed and replenished asynchronously on a Virtual Thread.</li>
 * </ul>
 */
public class AsyncConnectionPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncConnectionPool.class);

    private final SocketFactory socketFactory;
    private final int minConnections;
    private final int maxConnections;
    private final int maxInFlightPerConnection;
    private final int requestTimeoutMs;
    private final int borrowTimeoutMs;
    private final int maxPayloadBytes;
    private final int reconnectDelayMs;

    private final CopyOnWriteArrayList<MultiplexedConnection> connections = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AsyncConnectionPool(SocketFactory socketFactory,
                                int minConnections,
                                int maxConnections,
                                int maxInFlightPerConnection,
                                int requestTimeoutMs,
                                int borrowTimeoutMs,
                                int maxPayloadBytes,
                                int reconnectDelayMs) {
        this.socketFactory           = socketFactory;
        this.minConnections          = minConnections;
        this.maxConnections          = maxConnections;
        this.maxInFlightPerConnection = maxInFlightPerConnection;
        this.requestTimeoutMs        = requestTimeoutMs;
        this.borrowTimeoutMs         = borrowTimeoutMs;
        this.maxPayloadBytes         = maxPayloadBytes;
        this.reconnectDelayMs        = reconnectDelayMs;

        // Pre-warm minimum connections
        for (int i = 0; i < minConnections; i++) {
            try {
                connections.add(createConnection());
            } catch (IOException e) {
                log.warn("Failed to pre-warm async connection {}/{}: {}", i + 1, minConnections, e.getMessage());
            }
        }
        log.info("AsyncConnectionPool started: {} connections pre-warmed (max={}, maxInFlight={})",
            connections.size(), maxConnections, maxInFlightPerConnection);
    }

    /**
     * Acquire a {@link MultiplexedConnection} with available in-flight capacity.
     *
     * @throws IOException if no alive connection is available and pool is exhausted
     */
    public MultiplexedConnection acquire() throws IOException {
        checkNotClosed();

        // 1. First alive connection with remaining capacity
        for (MultiplexedConnection conn : connections) {
            if (conn.isAlive() && conn.hasCapacity()) {
                return conn;
            }
        }

        // 2. Open a new connection if below max
        if (totalConnections.get() < maxConnections) {
            MultiplexedConnection conn = createConnection();
            connections.add(conn);
            return conn;
        }

        // 3. Pool at max — return first alive (caller waits on in-flight semaphore)
        for (MultiplexedConnection conn : connections) {
            if (conn.isAlive()) {
                log.debug("All connections at maxInFlight={} — caller will wait on semaphore", maxInFlightPerConnection);
                return conn;
            }
        }

        throw new IOException(
            "AsyncConnectionPool exhausted: all " + totalConnections.get() + " connections are dead");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (MultiplexedConnection conn : connections) {
                conn.close();
            }
            connections.clear();
            log.info("AsyncConnectionPool closed");
        }
    }

    public int activeConnectionCount() {
        return (int) connections.stream().filter(MultiplexedConnection::isAlive).count();
    }

    // --- Internal ---

    private MultiplexedConnection createConnection() throws IOException {
        Socket socket = socketFactory.create();

        // CONFIRMATION: SO_TIMEOUT = 0 for async connections.
        // The ReadLoop blocks indefinitely on reads; per-request timeouts are
        // handled by CompletableFuture.orTimeout() in PendingRequestRegistry.
        socket.setSoTimeout(0);
        socket.setKeepAlive(true);    // OS-level dead-peer detection
        socket.setTcpNoDelay(true);

        String id = UUID.randomUUID().toString().substring(0, 8);
        totalConnections.incrementAndGet();

        MultiplexedConnection conn = new MultiplexedConnection(
            id, socket,
            maxInFlightPerConnection,
            requestTimeoutMs,
            borrowTimeoutMs,
            maxPayloadBytes,
            () -> onConnectionDead(id)
        );

        log.info("Async connection {} established to {}:{}",
            id, socketFactory.getOptions().host(), socketFactory.getOptions().port());
        return conn;
    }

    /**
     * Called by a dead {@link MultiplexedConnection} exactly once.
     * Removes it from the pool and schedules replenishment.
     */
    private void onConnectionDead(String connectionId) {
        connections.removeIf(c -> c.getConnectionId().equals(connectionId));
        totalConnections.decrementAndGet();
        log.warn("Async connection {} removed from pool (alive={})", connectionId, activeConnectionCount());
        replenishAsync();
    }

    private void replenishAsync() {
        if (closed.get()) return;
        Thread.ofVirtual().name("async-pool-replenish").start(() -> {
            try {
                Thread.sleep(reconnectDelayMs);
                while (!closed.get()
                       && connections.size() < minConnections
                       && totalConnections.get() < maxConnections) {
                    try {
                        MultiplexedConnection conn = createConnection();
                        connections.add(conn);
                        log.info("Replenished async connection {} (total={})",
                            conn.getConnectionId(), totalConnections.get());
                    } catch (IOException e) {
                        log.warn("Replenish attempt failed: {} — retrying in {}ms",
                            e.getMessage(), reconnectDelayMs);
                        Thread.sleep(reconnectDelayMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void checkNotClosed() {
        if (closed.get()) throw new IllegalStateException("AsyncConnectionPool is closed");
    }
}
