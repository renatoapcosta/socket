package br.com.socker.application.port.out;

import br.com.socker.domain.exception.SessionNotFoundException;
import br.com.socker.domain.exception.SessionQueueFullException;
import br.com.socker.domain.model.IsoMessage;

/**
 * Port OUT — delivers an ISO 8583 message to the outbound queue of a named session.
 *
 * <p>Each session owns a bounded {@link java.util.concurrent.ArrayBlockingQueue}
 * and a single virtual-thread worker that drains the queue, encodes messages,
 * applies the 2-byte TCP frame header, and writes to the socket.
 *
 * <p>Implementations live in the infrastructure layer
 * ({@code infrastructure.session.SessionManager}).
 */
public interface SessionGateway {

    /**
     * Enqueue a message for fire-and-forget delivery via the named session.
     *
     * <p>Returns as soon as the message is placed in the queue — does not wait
     * for the message to be written to the socket.
     *
     * @param sessionId identifies the target TCP session
     * @param message   the ISO 8583 message to transmit
     * @throws SessionNotFoundException  if no active session is registered for {@code sessionId}
     * @throws SessionQueueFullException if the session's queue is at capacity (back-pressure)
     */
    void enqueue(String sessionId, IsoMessage message);
}
