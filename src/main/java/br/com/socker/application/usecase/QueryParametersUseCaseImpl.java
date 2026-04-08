package br.com.socker.application.usecase;

import br.com.socker.application.port.in.QueryParametersUseCase;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.protocol.ResponseBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Use case: process parameter queries (9100 → 9110) and invoice queries (9300 → 9310).
 *
 * <p>Both responses are assembled via {@link ResponseBuilder}, which loads the correct
 * per-MTI {@link br.com.socker.infrastructure.protocol.MessageSpec} and automatically
 * echoes request fields tagged as ECHO (bits 3, 7, 11, 32, 42, 49, 71 for 9110;
 * bits 3, 7, 11, 32, 41, 42 for 9310).
 *
 * <h2>9110  Parameter Query Response</h2>
 * <p>Per spec, the 9110 includes bit 127 (NSU GwCel, 9 digits). For this stub,
 * the value is derived from the request's NSU (bit 11).
 *
 * <h2>9310  Invoice Query Response</h2>
 * <p>Per spec, the 9310 includes bit 127 (NSU GwCel, 9 digits). Same derivation.
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

        IsoMessage response = ResponseBuilder.forResponse(MessageType.PARAMETER_QUERY_RESPONSE)
            .echoingRequest(request)
            // MANDATORY computed
            .field(12, now.format(TIME_FMT))
            .field(13, now.format(DATE_FMT))
            .field(39, ResponseCode.APPROVED.getCode())
            // OPTIONAL — echo parameter data and sequential if present
            .fieldIfPresent(63, request.getField(63))
            .fieldIfPresent(71, request.getField(71))
            // OPTIONAL — NSU GwCel (9 digits) per spec
            .field(127, gwcelNsu(request))
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

        IsoMessage response = ResponseBuilder.forResponse(MessageType.INVOICE_QUERY_RESPONSE)
            .echoingRequest(request)
            // MANDATORY computed
            .field(12, now.format(TIME_FMT))
            .field(13, now.format(DATE_FMT))
            .field(39, ResponseCode.APPROVED_NO_INVOICES.getCode()) // 22 = no open invoices
            // OPTIONAL — NSU GwCel (9 digits) per spec
            .field(127, gwcelNsu(request))
            .build();

        return TransactionResult.success(response);
    }

    /**
     * Generates a 9-digit GwCel NSU for the stub (bit 127).
     *
     * <p>In production, this is assigned by GwCel's internal sequencer.
     * Here we derive it from the request NSU (bit 11) by zero-padding to 9 digits.
     */
    private String gwcelNsu(IsoMessage request) {
        return request.getNsu()
            .map(nsu -> {
                try {
                    return String.format("%09d", Long.parseLong(nsu.trim()));
                } catch (NumberFormatException e) {
                    return "000000001";
                }
            })
            .orElse("000000001");
    }
}
