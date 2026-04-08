package br.com.socker.infrastructure.concentrator;

import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.infrastructure.protocol.FrameWriter;
import br.com.socker.infrastructure.protocol.IsoMessageEncoder;
import br.com.socker.infrastructure.protocol.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a single inbound Concentrador TCP connection with a serialized write path.
 *
 * <p>Each instance owns:
 * <ul>
 *   <li>The accepted {@link Socket} (Concentrador → GwCel direction).</li>
 *   <li>A bounded {@link ArrayBlockingQueue} of outbound ISO 8583 messages.</li>
 *   <li>One Virtual Thread writer that drains the queue, encodes each message,
 *       applies the 2-byte frame header, and writes to the socket's output stream.</li>
 * </ul>
 *
 * <p>Reading is performed externally by
 * {@link br.com.socker.adapter.in.socket.server.ConnectionHandler}
 * via {@link #getInputStream()}.
 *
 * <h2>Serialized write path</h2>
 * <p>All writes to the socket — both request-response messages (0210, 0430, etc.)
 * and proactively dispatched messages (0600 from REST) — must go through
 * {@link #enqueue(IsoMessage)} so that a single virtual thread serializes all
 * writes on this connection. Concurrent writes to an OutputStream are unsafe.
 *
 * <h2>Back-pressure</h2>
 * <p>{@link #enqueue(IsoMessage)} is non-blocking. If the queue is full, it throws
 * {@link ConnectionQueueFullException} immediately, allowing the caller to signal
 * HTTP 429.
 *
 * <h2>Lifecycle</h2>
 * <p>The writer thread starts in the constructor. Call {@link #close()} to drain
 * remaining messages (with a short grace period), stop the writer, and close the socket.
 * {@code close()} is idempotent — safe to call multiple times.
 */
public final class ConcentratorConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConcentratorConnection.class);

    /** Grace period given to the writer to flush remaining queued messages on close. */
    private static final long DRAIN_TIMEOUT_MS = 2_000;

    private final String connectionId;
    private final Socket socket;
    private final String remoteAddress;
    private final ArrayBlockingQueue<IsoMessage> queue;
    private final AtomicBoolean alive  = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread writerThread;
    private final IsoMessageEncoder encoder     = new IsoMessageEncoder();
    private final FrameWriter       frameWriter = new FrameWriter();

    /**
     * Create a connection wrapper and immediately start its serialized writer thread.
     *
     * @param connectionId  a short identifier for this connection (for logs and registry)
     * @param socket        the accepted inbound socket from the Concentrador
     * @param queueCapacity maximum number of outbound messages that can be queued
     */
    public ConcentratorConnection(String connectionId, Socket socket, int queueCapacity) {
        this.connectionId  = connectionId;
        this.socket        = socket;
        this.remoteAddress = socket.getRemoteSocketAddress().toString();
        this.queue         = new ArrayBlockingQueue<>(queueCapacity);
        this.writerThread  = Thread.ofVirtual()
            .name("concentrator-writer-" + connectionId)
            .start(this::runWriter);
        log.info("ConcentratorConnection opened: id={} remote={}", connectionId, remoteAddress);
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /** The unique identifier for this connection (short UUID prefix). */
    public String getConnectionId() {
        return connectionId;
    }

    /** The remote socket address of the Concentrador (e.g. {@code /127.0.0.1:54321}). */
    public String getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the socket input stream for the {@code ConnectionHandler} to read from.
     *
     * @throws IOException if the socket is already closed
     */
    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    /**
     * Returns {@code true} if the connection is alive (socket open, writer running).
     */
    public boolean isAlive() {
        return alive.get() && !socket.isClosed();
    }

    /**
     * Enqueue an ISO 8583 message for delivery to the Concentrador (non-blocking).
     *
     * @param message the message to send
     * @throws ConnectionQueueFullException if the queue is at capacity or the connection is dead
     */
    public void enqueue(IsoMessage message) {
        if (!alive.get()) {
            throw new ConnectionQueueFullException(connectionId);
        }
        if (!queue.offer(message)) {
            throw new ConnectionQueueFullException(connectionId);
        }
    }

    // ─── Writer thread ────────────────────────────────────────────────────────

    private void runWriter() {
        try {
            OutputStream out = socket.getOutputStream();
            while (alive.get() || !queue.isEmpty()) {
                IsoMessage message;
                try {
                    message = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (message == null) continue;
                sendOne(out, message);
            }
        } catch (IOException e) {
            log.error("ConcentratorConnection {} writer fatal I/O error: {}", connectionId, e.getMessage());
        } finally {
            alive.set(false);
            log.info("ConcentratorConnection writer stopped: id={}", connectionId);
        }
    }

    private void sendOne(OutputStream out, IsoMessage message) {
        String mti = message.getMessageType().getMti();
        String nsu = message.getNsu().orElse("?");
        try {
            String payload = encoder.encode(message);
            frameWriter.writeText(out, payload);
            log.debug("ConcentratorConnection {} sent MTI={} NSU={} payloadLen={}",
                connectionId, mti, nsu, payload.length());
        } catch (ProtocolException e) {
            // Encoding failure: bad field value. Log and skip.
            log.warn("ConcentratorConnection {} encoding failed MTI={} NSU={}: {}",
                connectionId, mti, nsu, e.getMessage());
        } catch (IOException e) {
            // Write failure: mark dead and stop writer.
            log.error("ConcentratorConnection {} write failed MTI={} NSU={}: {}",
                connectionId, mti, nsu, e.getMessage());
            alive.set(false);
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Stop this connection gracefully.
     *
     * <p>Signals the writer to stop, waits up to {@value #DRAIN_TIMEOUT_MS} ms for
     * the queue to drain, then closes the socket. Idempotent — safe to call multiple times.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // already closed — idempotent
        }
        alive.set(false);
        try {
            writerThread.join(DRAIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            writerThread.interrupt();
            closeSocket();
        }
        log.info("ConcentratorConnection closed: id={}", connectionId);
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
