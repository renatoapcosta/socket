package br.com.socker.adapter.out.connectionpool;

import br.com.socker.infrastructure.net.SocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe persistent connection pool for TCP sockets.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Uses a {@link BlockingQueue} to hold available (idle) connections.</li>
 *   <li>{@link #borrow()} dequeues a connection with a timeout.</li>
 *   <li>{@link #returnConnection(PooledConnection)} re-enqueues if healthy, or discards.</li>
 *   <li>Reconnection is attempted transparently when returning a broken connection.</li>
 *   <li>Concurrent writes to the same socket are prevented because each connection
 *       is owned by exactly one thread at a time.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 *   PooledConnection conn = pool.borrow();
 *   try {
 *       // use conn.getSocket(), conn.getFrameReader(), conn.getFrameWriter()
 *       conn.markUsed();
 *   } finally {
 *       pool.returnConnection(conn);
 *   }
 * }</pre>
 */
public class ConnectionPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);

    private final SocketFactory socketFactory;
    private final ConnectionPoolConfig config;
    private final int maxPayloadBytes;

    private final BlockingQueue<PooledConnection> available;
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private volatile boolean closed = false;

    public ConnectionPool(SocketFactory socketFactory,
                          ConnectionPoolConfig config,
                          int maxPayloadBytes) {
        this.socketFactory  = socketFactory;
        this.config         = config;
        this.maxPayloadBytes = maxPayloadBytes;
        this.available      = new ArrayBlockingQueue<>(config.maxSize());

        // Pre-warm minimum connections
        for (int i = 0; i < config.minSize(); i++) {
            try {
                PooledConnection conn = createConnection();
                available.offer(conn);
            } catch (IOException e) {
                log.warn("Failed to pre-warm connection {} of {}: {}",
                    i + 1, config.minSize(), e.getMessage());
            }
        }
    }

    /**
     * Borrow a connection from the pool.
     *
     * <p>Waits up to {@code borrowTimeoutMs} for an available connection.
     * If the pool has not reached {@code maxSize}, a new connection is created.
     *
     * @return a healthy, borrowed {@link PooledConnection}
     * @throws IOException          if a new connection cannot be established
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws PoolExhaustedException if no connection is available within the timeout
     */
    public PooledConnection borrow() throws IOException, InterruptedException, PoolExhaustedException {
        checkNotClosed();

        // Try immediate poll first (no blocking)
        PooledConnection conn = available.poll();
        if (conn != null) {
            conn = ensureHealthy(conn);
            if (conn != null) {
                conn.tryBorrow();
                return conn;
            }
        }

        // Try to create a new connection if pool has capacity
        if (totalConnections.get() < config.maxSize()) {
            conn = createConnection();
            conn.tryBorrow();
            return conn;
        }

        // Block and wait for a returned connection
        conn = available.poll(config.borrowTimeoutMs(), TimeUnit.MILLISECONDS);
        if (conn == null) {
            throw new PoolExhaustedException(
                "No connection available after " + config.borrowTimeoutMs() + "ms");
        }

        conn = ensureHealthy(conn);
        if (conn == null) {
            // Last resort: create one if we now have capacity
            conn = createConnection();
        }

        conn.tryBorrow();
        return conn;
    }

    /**
     * Return a connection to the pool.
     *
     * <p>If the connection is healthy, it is returned to the idle queue.
     * If unhealthy, it is discarded and a reconnection is attempted asynchronously.
     *
     * @param conn the connection to return — must not be null
     */
    public void returnConnection(PooledConnection conn) {
        if (conn == null || closed) {
            if (conn != null) conn.closeQuietly();
            return;
        }

        conn.markUsed();
        conn.returnToPool();

        if (conn.isHealthy()) {
            boolean offered = available.offer(conn);
            if (!offered) {
                // Queue is full — discard excess
                log.debug("Pool queue full, discarding connection {}", conn.getConnectionId());
                conn.closeQuietly();
                totalConnections.decrementAndGet();
            }
        } else {
            log.warn("Discarding unhealthy connection {}", conn.getConnectionId());
            conn.closeQuietly();
            totalConnections.decrementAndGet();

            // Attempt to replenish the pool asynchronously
            Thread.ofVirtual()
                  .name("pool-reconnect-" + conn.getConnectionId())
                  .start(this::replenishIfNeeded);
        }
    }

    /**
     * Evict connections that have been idle longer than {@code idleTimeoutMs}.
     * Call this from a scheduled background task.
     */
    public void evictIdleConnections() {
        List<PooledConnection> toCheck = new ArrayList<>();
        available.drainTo(toCheck);

        Instant threshold = Instant.now().minus(Duration.ofMillis(config.idleTimeoutMs()));
        for (PooledConnection conn : toCheck) {
            if (conn.getLastUsedAt().isBefore(threshold) || !conn.isHealthy()) {
                log.debug("Evicting idle/unhealthy connection {}", conn.getConnectionId());
                conn.closeQuietly();
                totalConnections.decrementAndGet();
            } else {
                available.offer(conn);
            }
        }
        replenishIfNeeded();
    }

    @Override
    public void close() {
        closed = true;
        PooledConnection conn;
        while ((conn = available.poll()) != null) {
            conn.closeQuietly();
            totalConnections.decrementAndGet();
        }
        log.info("ConnectionPool closed");
    }

    public int availableCount() {
        return available.size();
    }

    public int totalCount() {
        return totalConnections.get();
    }

    // --- Internal helpers ---

    private PooledConnection createConnection() throws IOException {
        Socket socket = socketFactory.create();
        String id     = UUID.randomUUID().toString().substring(0, 8);
        PooledConnection conn = new PooledConnection(id, socket, maxPayloadBytes);
        totalConnections.incrementAndGet();
        log.info("Created connection {} to {}:{}",
            id,
            socketFactory.getOptions().host(),
            socketFactory.getOptions().port());
        return conn;
    }

    /** Validate a connection; discard and return null if unhealthy. */
    private PooledConnection ensureHealthy(PooledConnection conn) {
        if (conn.isHealthy()) {
            return conn;
        }
        log.warn("Discarding stale connection {} from pool", conn.getConnectionId());
        conn.closeQuietly();
        totalConnections.decrementAndGet();
        return null;
    }

    private void replenishIfNeeded() {
        int current = totalConnections.get();
        if (current < config.minSize()) {
            try {
                Thread.sleep(config.reconnectDelayMs());
                PooledConnection conn = createConnection();
                available.offer(conn);
            } catch (IOException e) {
                log.warn("Reconnection failed: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void checkNotClosed() {
        if (closed) throw new IllegalStateException("ConnectionPool is closed");
    }

    // --- Inner exception ---

    public static class PoolExhaustedException extends Exception {
        public PoolExhaustedException(String message) {
            super(message);
        }
    }
}
