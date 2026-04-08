package br.com.socker.application.usecase;

import br.com.socker.application.port.in.DispatchMessageUseCase;
import br.com.socker.application.port.out.SessionGateway;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;

import java.util.Set;

/**
 * Use case: validate and dispatch an ISO 8583 message for fire-and-forget transmission.
 *
 * <h2>V1 scope</h2>
 * <p>Only MTI {@code 0600} (Probe Request) is supported. The set of supported MTIs
 * is designed to be extended in future versions without modifying this class.
 *
 * <h2>Validation rules</h2>
 * <ol>
 *   <li>MTI must be in {@link #SUPPORTED_MTIS}.</li>
 *   <li>All bits in {@link #REQUIRED_BITS_0600} must be present for a 0600 message.</li>
 * </ol>
 *
 * <p>Field format validation (size, numeric range) is performed downstream by
 * {@link br.com.socker.infrastructure.protocol.IsoMessageEncoder} at encoding time.
 * Encoding failures are logged by the session worker and do not propagate back to
 * the REST caller — the REST contract is fire-and-forget.
 */
public class DispatchMessageUseCaseImpl implements DispatchMessageUseCase {

    /**
     * MTIs accepted by this use case.
     *
     * <p>V1: only 0600. Extend this set to support additional fire-and-forget messages
     * (e.g. future administrative MTIs) without changing the validation flow.
     */
    private static final Set<MessageType> SUPPORTED_MTIS = Set.of(
        MessageType.PROBE_REQUEST   // 0600
    );

    /**
     * Bits that MUST be present in a 0600 probe request per the GwCel spec.
     *
     * <p>From the spec: bits 3, 7, 11, 12, 13, 42, 125, 127 are required.
     * Bits 32 and 41 are optional.
     */
    private static final Set<Integer> REQUIRED_BITS_0600 = Set.of(
        3,   // Processing Code
        7,   // Transmission DateTime
        11,  // NSU
        12,  // Local Time
        13,  // Local Date
        42,  // Origin Code
        125, // Original NSU sequential
        127  // NSU Filial (9 digits)
    );

    private final SessionGateway sessionGateway;

    public DispatchMessageUseCaseImpl(SessionGateway sessionGateway) {
        this.sessionGateway = sessionGateway;
    }

    @Override
    public void dispatch(String sessionId, IsoMessage message) {
        validateMti(message);
        validateRequiredFields(message);
        sessionGateway.enqueue(sessionId, message);
    }

    private void validateMti(IsoMessage message) {
        if (!SUPPORTED_MTIS.contains(message.getMessageType())) {
            throw new InvalidMessageException(
                "MTI " + message.getMessageType().getMti() +
                " is not supported for fire-and-forget dispatch. " +
                "Supported in V1: 0600");
        }
    }

    private void validateRequiredFields(IsoMessage message) {
        Set<Integer> required = requiredBitsFor(message.getMessageType());
        for (int bit : required) {
            if (!message.hasField(bit)) {
                throw new InvalidMessageException(
                    "Missing required field bit_" + String.format("%03d", bit) +
                    " in MTI " + message.getMessageType().getMti(), bit);
            }
        }
    }

    private Set<Integer> requiredBitsFor(MessageType type) {
        return switch (type) {
            case PROBE_REQUEST -> REQUIRED_BITS_0600;
            default -> Set.of(); // unreachable in V1 — unsupported MTI caught earlier
        };
    }
}
