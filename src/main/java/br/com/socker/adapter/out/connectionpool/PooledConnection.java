package br.com.socker.adapter.out.connectionpool;

import br.com.socker.infrastructure.protocol.FrameReader;
import br.com.socker.infrastructure.protocol.FrameWriter;

import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a single TCP {@link Socket} managed by the connection pool.
 *
 * <p>Each PooledConnection is owned by at most one thread at a time.
 * The pool enforces this via borrow/return semantics. Never share a
 * PooledConnection across threads — doing so corrupts the socket stream.
 *
 * <p>Health check: a connection is considered healthy if:
 * <ul>
 *   <li>The underlying socket is connected and not closed.</li>
 *   <li>It has not been idle beyond the pool's idle timeout.</li>
 * </ul>
 */
public class PooledConnection {

    private final String connectionId;
    private final Socket socket;
    private final FrameReader frameReader;
    private final FrameWriter frameWriter;

    private volatile Instant lastUsedAt;
    private final AtomicBoolean borrowed = new AtomicBoolean(false);

    public PooledConnection(String connectionId, Socket socket, int maxPayloadBytes) {
        this.connectionId = connectionId;
        this.socket       = socket;
        this.frameReader  = new FrameReader(maxPayloadBytes);
        this.frameWriter  = new FrameWriter();
        this.lastUsedAt   = Instant.now();
    }

    public String getConnectionId() {
        return connectionId;
    }

    public Socket getSocket() {
        return socket;
    }

    public FrameReader getFrameReader() {
        return frameReader;
    }

    public FrameWriter getFrameWriter() {
        return frameWriter;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void markUsed() {
        lastUsedAt = Instant.now();
    }

    /**
     * Attempt to mark this connection as borrowed.
     *
     * @return {@code true} if successfully borrowed; {@code false} if already in use.
     */
    public boolean tryBorrow() {
        return borrowed.compareAndSet(false, true);
    }

    /**
     * Return this connection to the pool.
     * The connection is only returned if it is still healthy.
     */
    public void returnToPool() {
        borrowed.set(false);
    }

    /**
     * True if the underlying TCP socket is still alive and connected.
     *
     * <p>This is a lightweight heuristic — it does not send a probe byte.
     * Use it for fast pre-check; a full validation requires actually writing/reading.
     */
    public boolean isHealthy() {
        return socket != null
            && socket.isConnected()
            && !socket.isClosed()
            && !socket.isInputShutdown()
            && !socket.isOutputShutdown();
    }

    /**
     * Close the underlying socket, suppressing any exception.
     */
    public void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort
        }
    }

    @Override
    public String toString() {
        return "PooledConnection{id=" + connectionId +
               ", connected=" + socket.isConnected() +
               ", borrowed=" + borrowed.get() + "}";
    }
}
