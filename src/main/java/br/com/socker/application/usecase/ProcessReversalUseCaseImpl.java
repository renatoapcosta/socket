package br.com.socker.application.usecase;

import br.com.socker.application.port.in.ProcessReversalUseCase;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;

/**
 * Use case: process an incoming 0420 reversal and produce a 0430 response.
 *
 * <p>Reversals are sent when the Concentrador did not receive a 0210 response.
 * The use case returns a 0430 confirming the reversal was accepted (00) or
 * noting the transaction was already undone (86).
 */
public class ProcessReversalUseCaseImpl implements ProcessReversalUseCase {

    @Override
    public TransactionResult process(IsoMessage request) {
        validate(request);

        // Mirror mandatory fields; in production this would look up the original tx.
        IsoMessage response = IsoMessage.builder(MessageType.REVERSAL_RESPONSE)
                .field(3,  request.getRequiredField(3))   // processing code — mirror from original
                .field(4,  request.getRequiredField(4))   // amount — mirror
                .field(7,  request.getRequiredField(7))   // transmission datetime
                .field(11, request.getRequiredField(11))  // NSU
                .fieldIfPresent(32, request.getField(32)) // branch code
                .field(39, ResponseCode.APPROVED.getCode()) // 00 = transaction undone
                .field(41, request.getRequiredField(41))  // terminal
                .field(42, request.getRequiredField(42))  // origin
                .field(49, request.getRequiredField(49))  // currency
                .fieldIfPresent(90, request.getField(90)) // original transaction data
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
            throw new InvalidMessageException("Missing required field: " + name + " (bit " + bit + ")", bit);
        }
    }
}
