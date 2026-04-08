package br.com.socker.application.port.in;

import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.exception.SessionNotFoundException;
import br.com.socker.domain.exception.SessionQueueFullException;
import br.com.socker.domain.model.IsoMessage;

/**
 * Port IN — fire-and-forget dispatch of an ISO 8583 message via a named session.
 *
 * <p>The caller provides a validated {@link IsoMessage} and a {@code sessionId}
 * that identifies the TCP connection (session) through which the message must be sent.
 *
 * <p>The method returns as soon as the message is enqueued — it does NOT wait for
 * the remote counterpart to respond. This is the intended behaviour for 0600 probe
 * messages and any other fire-and-forget use case.
 *
 * <h2>Error semantics</h2>
 * <ul>
 *   <li>{@link InvalidMessageException}   — the message violates protocol rules
 *       (unsupported MTI, missing required field). Corresponds to HTTP 400.</li>
 *   <li>{@link SessionNotFoundException}  — no active session exists for the given id.
 *       Corresponds to HTTP 404.</li>
 *   <li>{@link SessionQueueFullException} — the session's outbound queue is at capacity.
 *       Corresponds to HTTP 429.</li>
 * </ul>
 */
public interface DispatchMessageUseCase {

    /**
     * Validate and enqueue an ISO 8583 message for fire-and-forget transmission.
     *
     * @param sessionId identifies the TCP session (connection) to use
     * @param message   the ISO 8583 message to send; must pass protocol validation
     * @throws InvalidMessageException   if the message is structurally invalid for dispatch
     * @throws SessionNotFoundException  if no active session exists for {@code sessionId}
     * @throws SessionQueueFullException if the session queue is at capacity
     */
    void dispatch(String sessionId, IsoMessage message);
}
