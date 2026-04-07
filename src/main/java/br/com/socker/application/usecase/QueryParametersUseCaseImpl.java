package br.com.socker.application.usecase;

import br.com.socker.application.port.in.QueryParametersUseCase;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Use case: process parameter queries (9100/9110) and invoice queries (9300/9310).
 */
public class QueryParametersUseCaseImpl implements QueryParametersUseCase {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMdd");

    @Override
    public TransactionResult processParameterQuery(IsoMessage request) {
        if (request.getMessageType() != MessageType.PARAMETER_QUERY_REQUEST) {
            throw new InvalidMessageException(
                "Expected 9100, received: " + request.getMessageType().getMti());
        }

        LocalDateTime now = LocalDateTime.now();

        IsoMessage response = IsoMessage.builder(MessageType.PARAMETER_QUERY_RESPONSE)
                .field(3,  request.getRequiredField(3))
                .field(7,  request.getRequiredField(7))
                .field(11, request.getRequiredField(11))
                .field(12, now.format(TIME_FMT))
                .field(13, now.format(DATE_FMT))
                .fieldIfPresent(32, request.getField(32))
                .field(39, ResponseCode.APPROVED.getCode())
                .field(42, request.getRequiredField(42))
                .field(49, request.getRequiredField(49))
                .fieldIfPresent(63, request.getField(63)) // echo parameter data
                .fieldIfPresent(71, request.getField(71)) // sequential
                .build();

        return TransactionResult.success(response);
    }

    @Override
    public TransactionResult processInvoiceQuery(IsoMessage request) {
        if (request.getMessageType() != MessageType.INVOICE_QUERY_REQUEST) {
            throw new InvalidMessageException(
                "Expected 9300, received: " + request.getMessageType().getMti());
        }

        LocalDateTime now = LocalDateTime.now();

        IsoMessage response = IsoMessage.builder(MessageType.INVOICE_QUERY_RESPONSE)
                .field(3,  request.getRequiredField(3))
                .field(7,  request.getRequiredField(7))
                .field(11, request.getRequiredField(11))
                .field(12, now.format(TIME_FMT))
                .field(13, now.format(DATE_FMT))
                .fieldIfPresent(32, request.getField(32))
                .field(39, ResponseCode.APPROVED_NO_INVOICES.getCode()) // 22 = no open invoices
                .field(41, request.getRequiredField(41))
                .field(42, request.getRequiredField(42))
                .build();

        return TransactionResult.success(response);
    }
}
