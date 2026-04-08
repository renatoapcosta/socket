package br.com.socker.application.port.in;

import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.exception.NoActiveConnectionException;
import br.com.socker.domain.model.IsoMessage;

/**
 * Port IN — fire-and-forget dispatch of an ISO 8583 message to the active
 * Concentrador connection.
 *
 * <p>The method returns as soon as the message is enqueued — it does NOT wait
 * for the remote counterpart to respond. This is the intended behaviour for
 * 0600 probe messages and any other fire-and-forget use case.
 *
 * <h2>Error semantics</h2>
 * <ul>
 *   <li>{@link InvalidMessageException}        — MTI not supported or required bit missing.
 *       Corresponds to HTTP 400.</li>
 *   <li>{@link NoActiveConnectionException}    — no Concentrador is currently connected.
 *       Corresponds to HTTP 404.</li>
 *   <li>{@link ConnectionQueueFullException}   — outbound queue is at capacity.
 *       Corresponds to HTTP 429.</li>
 * </ul>
 */
public interface DispatchMessageUseCase {

    /**
     * Validate and enqueue an ISO 8583 message for fire-and-forget transmission.
     *
     * @param message the ISO 8583 message to send; must pass protocol validation
     * @throws InvalidMessageException      if the message is structurally invalid for dispatch
     * @throws NoActiveConnectionException  if no active Concentrador connection exists
     * @throws ConnectionQueueFullException if the outbound queue is at capacity
     */
    void dispatch(IsoMessage message);
}
