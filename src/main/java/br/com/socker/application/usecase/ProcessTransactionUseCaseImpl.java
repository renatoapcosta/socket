package br.com.socker.application.usecase;

import br.com.socker.application.port.in.ProcessTransactionUseCase;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.protocol.ResponseBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Use case: process an incoming 0200 financial transaction and produce a 0210 response.
 *
 * <p>The 0210 is assembled via {@link ResponseBuilder}, which:
 * <ol>
 *   <li>Loads the 0210 {@link br.com.socker.infrastructure.protocol.MessageSpec} from the registry.</li>
 *   <li>Automatically echoes all ECHO-tagged fields (bits 3, 4, 7, 11, 32, 41, 42, 49)
 *       from the incoming 0200.</li>
 *   <li>Accepts the explicitly computed fields (12, 13, 39, 62, 63, 127).</li>
 * </ol>
 *
 * <p>The bitmap is derived by {@link br.com.socker.infrastructure.protocol.IsoMessageEncoder}
 * from whichever fields are actually present in the assembled 0210 — it is never
 * inherited from or based on the 0200 structure.
 *
 * <h2>0210 fields produced by this stub</h2>
 * <table border="1">
 * <tr><th>Bit</th><th>Presence</th><th>Value</th></tr>
 * <tr><td>3</td><td>ECHO</td><td>Processing Code from 0200</td></tr>
 * <tr><td>4</td><td>ECHO</td><td>Amount from 0200</td></tr>
 * <tr><td>7</td><td>ECHO</td><td>Transmission DateTime from 0200</td></tr>
 * <tr><td>11</td><td>ECHO</td><td>NSU from 0200</td></tr>
 * <tr><td>12</td><td>MANDATORY</td><td>Server local time hhmmss</td></tr>
 * <tr><td>13</td><td>MANDATORY</td><td>Server local date MMDD</td></tr>
 * <tr><td>32</td><td>ECHO</td><td>Branch Code from 0200 (if present)</td></tr>
 * <tr><td>39</td><td>MANDATORY</td><td>Response Code (00 = approved)</td></tr>
 * <tr><td>41</td><td>ECHO</td><td>Terminal ID from 0200</td></tr>
 * <tr><td>42</td><td>ECHO</td><td>Origin Code from 0200</td></tr>
 * <tr><td>49</td><td>ECHO</td><td>Currency Code from 0200</td></tr>
 * <tr><td>62</td><td>OPTIONAL</td><td>Receipt / recharge info (stub text)</td></tr>
 * <tr><td>63</td><td>OPTIONAL</td><td>Parameter versions VGP+VFP</td></tr>
 * <tr><td>127</td><td>OPTIONAL</td><td>NSU GwCel — 9-digit unique return code</td></tr>
 * </table>
 */
public class ProcessTransactionUseCaseImpl implements ProcessTransactionUseCase {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMdd");

    /**
     * Stub parameter version string returned in bit 63.
     *
     * <p>Format: VGP (3 digits) + VFP (3 digits).
     * In production, GwCel sends its current global and branch parameter versions.
     */
    static final String STUB_PARAMETER_VERSIONS = "001001";

    /**
     * Prefix for the stub receipt text returned in bit 62.
     *
     * <p>In production, GwCel returns formatted receipt content (type "F" or "C").
     * This stub returns a plain approval notification for integration testing.
     */
    static final String STUB_RECEIPT_TEXT = "Transacao aprovada";

    @Override
    public TransactionResult process(IsoMessage request) {
        validate(request);

        LocalDateTime now = LocalDateTime.now();

        IsoMessage response = ResponseBuilder.forResponse(MessageType.TRANSACTION_RESPONSE)
            .echoingRequest(request)
            // MANDATORY computed fields
            .field(12, now.format(TIME_FMT))
            .field(13, now.format(DATE_FMT))
            .field(39, ResponseCode.APPROVED.getCode())
            // OPTIONAL fields — present in every stub 0210 per spec
            .field(62, STUB_RECEIPT_TEXT)
            .field(63, STUB_PARAMETER_VERSIONS)
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
                    // Non-numeric NSU (e.g. "A00001" in async tests) — use zeroed sequence
                    return String.format("000%s", nsu).substring(0, 9);
                }
            })
            .orElse("000000001");
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
            throw new InvalidMessageException(
                "Missing required field: " + name + " (bit " + bit + ")", bit);
        }
    }
}
