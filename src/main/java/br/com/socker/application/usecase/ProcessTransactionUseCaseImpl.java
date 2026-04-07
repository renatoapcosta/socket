package br.com.socker.application.usecase;

import br.com.socker.application.port.in.ProcessTransactionUseCase;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Use case: process an incoming 0200 financial transaction and produce a 0210 response.
 *
 * <p>This class contains the routing logic for transaction types supported by
 * the GwCel interface: recharge, payment, withdrawal, PIN sale, etc.
 *
 * <p>No socket, stream, or logging dependency — all side effects go through ports.
 */
public class ProcessTransactionUseCaseImpl implements ProcessTransactionUseCase {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMdd");

    @Override
    public TransactionResult process(IsoMessage request) {
        validate(request);

        LocalDateTime now = LocalDateTime.now();
        String localTime = now.format(TIME_FMT);
        String localDate = now.format(DATE_FMT);

        // The actual processing code routing would call a domain service or branch gateway.
        // Here we produce an approved 0210 echoing back the mandatory mirrored fields.
        IsoMessage response = IsoMessage.builder(MessageType.TRANSACTION_RESPONSE)
                .field(3,  request.getRequiredField(3))   // processing code — mirror
                .field(4,  request.getRequiredField(4))   // amount — mirror
                .field(7,  request.getRequiredField(7))   // transmission datetime — mirror
                .field(11, request.getRequiredField(11))  // NSU — mirror
                .field(12, localTime)
                .field(13, localDate)
                .fieldIfPresent(32, request.getField(32)) // branch code — mirror
                .field(39, ResponseCode.APPROVED.getCode())
                .field(41, request.getRequiredField(41))  // terminal — mirror
                .field(42, request.getRequiredField(42))  // origin — mirror
                .field(49, request.getRequiredField(49))  // currency — mirror
                .build();

        return TransactionResult.success(response);
    }

    private void validate(IsoMessage request) {
        if (request.getMessageType() != MessageType.TRANSACTION_REQUEST) {
            throw new InvalidMessageException(
                "Expected 0200, received: " + request.getMessageType().getMti());
        }
        requireField(request, 3,  "Processing Code");
        requireField(request, 4,  "Amount");
        requireField(request, 7,  "Transmission DateTime");
        requireField(request, 11, "NSU");
        requireField(request, 12, "Local Time");
        requireField(request, 13, "Local Date");
        requireField(request, 32, "Branch Code");
        requireField(request, 40, "Interface Version");
        requireField(request, 41, "Terminal ID");
        requireField(request, 42, "Origin Code");
        requireField(request, 49, "Currency Code");
    }

    private void requireField(IsoMessage message, int bit, String name) {
        if (!message.hasField(bit)) {
            throw new InvalidMessageException("Missing required field: " + name + " (bit " + bit + ")", bit);
        }
    }
}
