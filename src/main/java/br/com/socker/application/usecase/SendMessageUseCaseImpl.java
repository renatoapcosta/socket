package br.com.socker.application.usecase;

import br.com.socker.application.port.in.SendMessageUseCase;
import br.com.socker.application.port.out.GatewayException;
import br.com.socker.application.port.out.MessageGateway;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;

/**
 * Use case: send an ISO 8583 message via the outbound gateway and return the result.
 *
 * <p>This orchestrates retries, response validation, and error mapping —
 * without depending on Socket, InputStream, or any transport class.
 */
public class SendMessageUseCaseImpl implements SendMessageUseCase {

    private final MessageGateway gateway;

    public SendMessageUseCaseImpl(MessageGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public TransactionResult send(IsoMessage request) {
        try {
            IsoMessage response = gateway.sendAndReceive(request);
            return TransactionResult.success(response);
        } catch (GatewayException e) {
            return TransactionResult.protocolError(
                "Gateway error [" + e.getReason() + "]: " + e.getMessage());
        }
    }
}
