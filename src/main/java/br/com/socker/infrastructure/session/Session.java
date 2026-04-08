package br.com.socker.infrastructure.session;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.infrastructure.protocol.FrameWriter;
import br.com.socker.infrastructure.protocol.IsoMessageEncoder;
import br.com.socker.infrastructure.protocol.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A named, long-lived TCP session for fire-and-forget ISO 8583 message delivery.
 *
 * <p>Each session owns:
 * <ul>
 *   <li>One TCP {@link Socket} to GwCel.</li>
 *   <li>One bounded {@link ArrayBlockingQueue} for outbound messages.</li>
 *   <li>One Virtual Thread worker that drains the queue, encodes each message,
 *       applies the 2-byte frame header, and writes to the socket.</li>
 * </ul>
 *
 * <p>This class is intentionally simpler than
 * {@link br.com.socker.adapter.out.connectionpool.MultiplexedConnection} because
 * fire-and-forget messages (like 0600 probes) require no response correlation —
 * there is no {@code PendingRequestRegistry} or {@code ReadLoop} here.
 *
 * <h2>Back-pressure</h2>
 * <p>{@link #offer(IsoMessage)} is non-blocking. If the queue is full, it returns
 * {@code false} immediately, allowing the caller to signal HTTP 429.
 *
 * <h2>Lifecycle</h2>
 * <p>The worker thread starts in the constructor. Call {@link #close()} to drain
 * remaining messages (with a short grace period), stop the worker, and close the socket.
 */
public final class Session implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Session.class);

    /** Grace period given to the worker to flush remaining queued messages on close. */
    private static final long DRAIN_TIMEOUT_MS = 2_000;

    private final String sessionId;
    private final Socket socket;
    private final ArrayBlockingQueue<IsoMessage> queue;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final Thread workerThread;
    private final IsoMessageEncoder encoder = new IsoMessageEncoder();
    private final FrameWriter frameWriter = new FrameWriter();

    /**
     * Create a session and immediately start its worker thread.
     *
     * @param sessionId     the logical name for this session (used in logs and routing)
     * @param socket        an already-connected TCP socket to GwCel
     * @param queueCapacity maximum number of messages that can be queued (back-pressure limit)
     */
    public Session(String sessionId, Socket socket, int queueCapacity) {
        this.sessionId = sessionId;
        this.socket    = socket;
        this.queue     = new ArrayBlockingQueue<>(queueCapacity);
        this.workerThread = Thread.ofVirtual()
            .name("session-worker-" + sessionId)
            .start(this::runWorker);
        log.info("Session opened: sessionId={} remote={}", sessionId, socket.getRemoteSocketAddress());
    }

    /** The logical session identifier. */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns {@code true} if this session is alive (socket open, worker running).
     *
     * <p>A session becomes dead if the socket is closed by either side or if a
     * fatal write error occurs in the worker.
     */
    public boolean isAlive() {
        return alive.get() && !socket.isClosed();
    }

    /**
     * Non-blocking enqueue.
     *
     * @param message the ISO 8583 message to send
     * @return {@code true} if enqueued; {@code false} if the queue is full or the session is dead
     */
    public boolean offer(IsoMessage message) {
        if (!alive.get()) {
            log.warn("Rejected message for dead session {}", sessionId);
            return false;
        }
        return queue.offer(message);
    }

    /** Returns the current number of messages waiting in the queue. */
    public int pendingCount() {
        return queue.size();
    }

    // ─── Worker ───────────────────────────────────────────────────────────────

    private void runWorker() {
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
            log.error("Session {} worker fatal error getting OutputStream: {}", sessionId, e.getMessage());
        } finally {
            alive.set(false);
            log.info("Session worker stopped: sessionId={}", sessionId);
        }
    }

    private void sendOne(OutputStream out, IsoMessage message) {
        String mti = message.getMessageType().getMti();
        String nsu = message.getNsu().orElse("?");
        try {
            String payload = encoder.encode(message);
            frameWriter.writeText(out, payload);
            log.debug("Session {} sent MTI={} NSU={} payloadLen={}", sessionId, mti, nsu, payload.length());
        } catch (ProtocolException e) {
            // Encoding failure: bad field value. Log and skip — cannot return to caller.
            log.warn("Session {} encoding failed MTI={} NSU={}: {}", sessionId, mti, nsu, e.getMessage());
        } catch (IOException e) {
            // Socket write failure: mark session dead and stop the worker.
            log.error("Session {} write failed MTI={} NSU={}: {}", sessionId, mti, nsu, e.getMessage());
            alive.set(false);
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Stop the session gracefully.
     *
     * <p>Signals the worker to stop accepting new messages, waits up to
     * {@value #DRAIN_TIMEOUT_MS} ms for the queue to drain, then closes the socket.
     */
    @Override
    public void close() {
        alive.set(false);
        try {
            workerThread.join(DRAIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workerThread.interrupt();
            closeSocket();
        }
        log.info("Session closed: sessionId={}", sessionId);
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
