package br.com.socker.application.port.in;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;

/**
 * Port IN — use case for sending an ISO 8583 message to GwCel (client mode).
 *
 * <p>Used by the Concentrador side when sending 0200, 0420, 9100, 9300 requests
 * to GwCel and awaiting the corresponding response.
 */
public interface SendMessageUseCase {

    /**
     * Send a request message to GwCel and await the response.
     *
     * @param request the ISO 8583 message to send
     * @return the result containing the parsed response
     */
    TransactionResult send(IsoMessage request);
}
