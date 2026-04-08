package br.com.socker.application.usecase;

import br.com.socker.application.port.in.ProcessReversalUseCase;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.protocol.ResponseBuilder;

/**
 * Use case: process an incoming 0420 reversal and produce a 0430 response.
 *
 * <p>Reversals are sent when the Concentrador did not receive a 0210 response.
 * The use case returns a 0430 confirming the reversal was accepted (00).
 *
 * <p>The 0430 is assembled via {@link ResponseBuilder} using the 0430
 * {@link br.com.socker.infrastructure.protocol.MessageSpec}. The spec echoes
 * bits 3, 4, 7, 11, 32, 41, 42, 49, 90 from the incoming 0420 and sets bit 39
 * as mandatory. No bits 12/13 appear in the 0430 per the GwCel spec.
 */
public class ProcessReversalUseCaseImpl implements ProcessReversalUseCase {

    @Override
    public TransactionResult process(IsoMessage request) {
        validate(request);

        IsoMessage response = ResponseBuilder.forResponse(MessageType.REVERSAL_RESPONSE)
            .echoingRequest(request)
            .field(39, ResponseCode.APPROVED.getCode())
            .build();

        return TransactionResult.success(response);
    }

    private void validate(IsoMessage request) {
        if (request.getMessageType() != MessageType.REVERSAL_REQUEST) {
            throw new InvalidMessageException(
                "Expected 0420, received: " + request.getMessageType().getMti());
        }
        requireField(request, 3,  "Processing Code");
        requireField(request, 4,  "Amount");
        requireField(request, 7,  "Transmission DateTime");
        requireField(request, 11, "NSU");
        requireField(request, 41, "Terminal ID");
        requireField(request, 42, "Origin Code");
        requireField(request, 49, "Currency Code");
        requireField(request, 90, "Original Transaction Data");
    }

    private void requireField(IsoMessage message, int bit, String name) {
        if (!message.hasField(bit)) {
            throw new InvalidMessageException(
                "Missing required field: " + name + " (bit " + bit + ")", bit);
        }
    }
}
