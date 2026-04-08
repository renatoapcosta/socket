package br.com.socker.adapter.out.connectionpool;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.infrastructure.net.ReadLoop;
import br.com.socker.infrastructure.net.WriterThread;
import br.com.socker.infrastructure.protocol.Frame;
import br.com.socker.infrastructure.protocol.PendingRequestRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single TCP connection shared by multiple concurrent in-flight ISO 8583 requests.
 *
 * <p>Spawns two Virtual Threads on construction:
 * <ol>
 *   <li>{@link ReadLoop} — exclusively reads the socket InputStream and dispatches
 *       responses to {@link PendingRequestRegistry} by NSU.</li>
 *   <li>{@link WriterThread} — serializes frame writes to the socket OutputStream
 *       via a bounded {@link java.util.concurrent.ArrayBlockingQueue}.</li>
 * </ol>
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>Only the ReadLoop touches the InputStream.</li>
 *   <li>Only the WriterThread touches the OutputStream.</li>
 *   <li>Sender threads call {@link #sendAsync(String, Frame)} — which acquires a
 *       semaphore slot, registers the future, and enqueues the frame.</li>
 *   <li>No socket stream is ever shared across threads — no interleaving possible.</li>
 * </ul>
 *
 * <h2>Confirmations</h2>
 * <ul>
 *   <li>SO_TIMEOUT = 0 on the socket — ReadLoop owns lifecycle detection via
 *       EOFException / IOException, not socket timeout.</li>
 *   <li>{@code maxInFlight} is enforced by a {@link Semaphore}.</li>
 *   <li>{@code failAll} is called on any connection failure — all pending futures fail.</li>
 *   <li>{@code onConnectionDead} notifies the pool for discard and replenishment.</li>
 * </ul>
 */
public class MultiplexedConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MultiplexedConnection.class);

    private final String connectionId;
    private final Socket socket;
    private final PendingRequestRegistry registry;
    private final WriterThread writerThread;
    private final Semaphore inFlightSemaphore;
    private final int maxInFlight;
    private final int borrowTimeoutMs;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final Runnable onConnectionDead;

    private final Thread readLoopThread;
    private final Thread writerVirtualThread;

    /**
     * @param connectionId      unique identifier for logging
     * @param socket            connected TCP socket with SO_TIMEOUT = 0
     * @param maxInFlight       maximum concurrent in-flight requests (configurable)
     * @param requestTimeoutMs  per-request response timeout
     * @param borrowTimeoutMs   max wait to acquire an in-flight slot
     * @param maxPayloadBytes   maximum ISO payload the ReadLoop will accept
     * @param onConnectionDead  callback invoked exactly once when this connection dies
     */
    public MultiplexedConnection(String connectionId,
                                  Socket socket,
                                  int maxInFlight,
                                  int requestTimeoutMs,
                                  int borrowTimeoutMs,
                                  int maxPayloadBytes,
                                  Runnable onConnectionDead) {
        this.connectionId   = connectionId;
        this.socket         = socket;
        this.maxInFlight    = maxInFlight;
        this.borrowTimeoutMs = borrowTimeoutMs;
        this.onConnectionDead = onConnectionDead;
        this.inFlightSemaphore = new Semaphore(maxInFlight, true);
        this.registry = new PendingRequestRegistry(requestTimeoutMs);

        this.writerThread = new WriterThread(
            connectionId,
            outputStream(),
            maxInFlight * 2, // queue capacity: 2× for burst headroom
            this::onWriteError
        );

        ReadLoop readLoop = new ReadLoop(
            connectionId,
            inputStream(),
            maxPayloadBytes,
            registry,
            () -> markDead(new IOException("ReadLoop terminated on connection " + connectionId))
        );

        this.writerVirtualThread = Thread.ofVirtual()
            .name("writer-" + connectionId)
            .start(writerThread);

        this.readLoopThread = Thread.ofVirtual()
            .name("reader-" + connectionId)
            .start(readLoop);

        log.debug("MultiplexedConnection {} started (maxInFlight={})", connectionId, maxInFlight);
    }

    /**
     * Send a request asynchronously on this connection.
     *
     * <ol>
     *   <li>Acquires an in-flight slot (blocks up to {@code borrowTimeoutMs}).</li>
     *   <li>Registers the NSU in the {@link PendingRequestRegistry}.</li>
     *   <li>Releases the slot via {@code whenComplete} on the returned future.</li>
     *   <li>Enqueues the frame in the {@link WriterThread} queue.</li>
     * </ol>
     *
     * @return a {@link CompletableFuture} completed by the ReadLoop when the response arrives
     * @throws IOException if the connection is dead, the in-flight limit is exceeded,
     *                     or the writer queue is full
     */
    public CompletableFuture<IsoMessage> sendAsync(String nsu, Frame frame) throws IOException {
        if (!alive.get()) {
            throw new IOException("Connection " + connectionId + " is dead");
        }

        // Acquire slot — blocks up to borrowTimeoutMs
        try {
            if (!inFlightSemaphore.tryAcquire(borrowTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException(
                    "In-flight limit (" + maxInFlight + ") reached on connection " + connectionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring in-flight slot", e);
        }

        // Register future — release semaphore on any completion (success, timeout, failure)
        CompletableFuture<IsoMessage> future;
        try {
            future = registry.register(nsu);
        } catch (Exception e) {
            inFlightSemaphore.release();
            throw new IOException("Failed to register NSU=" + nsu + ": " + e.getMessage(), e);
        }

        future.whenComplete((msg, ex) -> inFlightSemaphore.release());

        // Enqueue frame for serialized write — non-blocking
        if (!writerThread.enqueue(frame)) {
            // Writer queue full — fail the future; whenComplete will release the semaphore
            future.completeExceptionally(
                new IOException("Writer queue full on connection " + connectionId));
        }

        return future;
    }

    /** True if the connection is alive and has at least one available in-flight slot. */
    public boolean isAlive() {
        return alive.get() && socket.isConnected() && !socket.isClosed();
    }

    /** True if the connection has capacity for at least one more in-flight request. */
    public boolean hasCapacity() {
        return alive.get() && inFlightSemaphore.availablePermits() > 0;
    }

    public int inFlightCount() {
        return maxInFlight - inFlightSemaphore.availablePermits();
    }

    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public void close() {
        markDead(new IOException("Connection " + connectionId + " explicitly closed"));
        readLoopThread.interrupt();
    }

    // --- Internal ---

    private void onWriteError() {
        markDead(new IOException("Write error on connection " + connectionId));
    }

    private void markDead(Throwable cause) {
        if (alive.compareAndSet(true, false)) {
            log.warn("Connection {} marked dead: {}", connectionId, cause.getMessage());
            registry.failAll(cause);
            writerThread.stop();
            writerVirtualThread.interrupt();
            closeSocket();
            onConnectionDead.run(); // notify pool exactly once
        }
    }

    private void closeSocket() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private java.io.InputStream inputStream() {
        try { return socket.getInputStream(); } catch (IOException e) { throw new RuntimeException(e); }
    }

    private java.io.OutputStream outputStream() {
        try { return socket.getOutputStream(); } catch (IOException e) { throw new RuntimeException(e); }
    }
}
