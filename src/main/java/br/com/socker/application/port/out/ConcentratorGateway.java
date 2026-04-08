package br.com.socker.application.port.out;

import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.exception.NoActiveConnectionException;
import br.com.socker.domain.model.IsoMessage;

/**
 * Port OUT — delivers an ISO 8583 message to the active Concentrador connection.
 *
 * <p>GwCel accepts inbound TCP connections from the Concentrador. This port
 * lets use cases push messages (e.g. 0600 probes) into the serialized writer
 * queue of that connection, without knowing any infrastructure details.
 *
 * <p>The implementation lives in
 * {@code infrastructure.concentrator.ConcentratorConnectionRegistry}.
 */
public interface ConcentratorGateway {

    /**
     * Enqueue an ISO 8583 message for fire-and-forget delivery to the
     * active Concentrador connection.
     *
     * <p>Returns as soon as the message is placed in the queue — does not wait
     * for the message to be written to the socket.
     *
     * @param message the ISO 8583 message to transmit
     * @throws NoActiveConnectionException  if no Concentrador is currently connected
     * @throws ConnectionQueueFullException if the connection's outbound queue is full
     */
    void send(IsoMessage message);
}
