package br.com.socker.infrastructure.session;

import br.com.socker.application.port.out.SessionGateway;
import br.com.socker.domain.exception.SessionNotFoundException;
import br.com.socker.domain.exception.SessionQueueFullException;
import br.com.socker.domain.model.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infrastructure adapter — implements {@link SessionGateway} and manages the lifecycle
 * of all active {@link Session}s.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #openSession(String, Socket)} — register a new session after a TCP connection
 *       is established.</li>
 *   <li>{@link #closeSession(String)} — drain and remove a session when the connection ends.</li>
 *   <li>{@link #enqueue(String, IsoMessage)} — port-out implementation used by use cases.</li>
 * </ul>
 *
 * <p>The session map is thread-safe ({@link ConcurrentHashMap}). Session lifecycle
 * operations ({@code openSession}/{@code closeSession}) are typically called from the
 * bootstrap or a connection-management component; {@code enqueue} is called from
 * REST request threads concurrently.
 *
 * <h2>Session identity</h2>
 * <p>Sessions are identified by a caller-supplied {@code sessionId} string. The value is
 * opaque to this manager — any non-null, non-blank string is valid. Common choices:
 * a UUID, a stable hostname, or a sequential number.
 */
public final class SessionManager implements SessionGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final int queueCapacity;

    /**
     * @param queueCapacity maximum outbound messages per session queue
     *                      (triggers HTTP 429 when exceeded)
     */
    public SessionManager(int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, got: " + queueCapacity);
        }
        this.queueCapacity = queueCapacity;
    }

    // ─── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Register a new session backed by the given already-connected socket.
     *
     * <p>If a session with the same {@code sessionId} already exists, it is closed
     * and replaced by the new one.
     *
     * @param sessionId logical identifier for the session
     * @param socket    an already-connected TCP socket; must be open
     */
    public void openSession(String sessionId, Socket socket) {
        Session previous = sessions.put(sessionId, new Session(sessionId, socket, queueCapacity));
        if (previous != null) {
            log.warn("Replacing existing session {} with a new connection", sessionId);
            previous.close();
        }
        log.info("Session registered: sessionId={}", sessionId);
    }

    /**
     * Close and remove the session identified by {@code sessionId}.
     *
     * <p>No-op if the session does not exist.
     */
    public void closeSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            log.info("Session deregistered: sessionId={}", sessionId);
        }
    }

    /** Returns the number of currently active sessions. */
    public int activeCount() {
        return sessions.size();
    }

    /**
     * Returns an unmodifiable snapshot of active session IDs.
     *
     * <p>Useful for monitoring and diagnostics.
     */
    public Collection<String> activeSessions() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    // ─── SessionGateway port ──────────────────────────────────────────────────

    /**
     * Enqueue a message in the session's outbound queue (non-blocking).
     *
     * @throws SessionNotFoundException  if no session exists for {@code sessionId},
     *                                   or the session exists but is no longer alive
     * @throws SessionQueueFullException if the session's queue is at capacity
     */
    @Override
    public void enqueue(String sessionId, IsoMessage message) {
        Session session = sessions.get(sessionId);
        if (session == null || !session.isAlive()) {
            // Remove dead sessions eagerly to keep the map clean
            if (session != null) sessions.remove(sessionId, session);
            throw new SessionNotFoundException(sessionId);
        }
        if (!session.offer(message)) {
            throw new SessionQueueFullException(sessionId);
        }
        log.debug("Enqueued MTI={} NSU={} sessionId={}",
            message.getMessageType().getMti(),
            message.getNsu().orElse("?"),
            sessionId);
    }

    // ─── AutoCloseable ────────────────────────────────────────────────────────

    /**
     * Close all active sessions. Called on application shutdown.
     */
    @Override
    public void close() {
        log.info("Closing all {} sessions", sessions.size());
        sessions.values().forEach(Session::close);
        sessions.clear();
    }
}
