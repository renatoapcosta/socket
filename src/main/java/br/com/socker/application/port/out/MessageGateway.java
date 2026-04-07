package br.com.socker.application.port.out;

import br.com.socker.domain.model.IsoMessage;

/**
 * Port OUT — gateway for sending ISO 8583 messages over a transport channel.
 *
 * <p>This port abstracts the underlying network transport (TCP socket, TLS, etc.)
 * so that use cases remain decoupled from connection management.
 *
 * <p>Implementations live in the adapter layer ({@code adapter.out.socket.client}).
 */
public interface MessageGateway {

    /**
     * Send a request message and block until the response arrives.
     *
     * @param request the ISO 8583 message to transmit
     * @return the parsed response message from GwCel
     * @throws GatewayException if a transport error or timeout occurs
     */
    IsoMessage sendAndReceive(IsoMessage request) throws GatewayException;
}
