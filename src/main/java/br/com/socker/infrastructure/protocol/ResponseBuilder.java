package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a response {@link IsoMessage} conforming to its {@link MessageSpec}.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Loads the {@link MessageSpec} for the response MTI from {@link MessageSpecRegistry}.</li>
 *   <li>Automatically copies all {@link FieldPresence#ECHO} fields from the paired request.</li>
 *   <li>Accepts explicitly provided values for {@link FieldPresence#MANDATORY} and
 *       {@link FieldPresence#OPTIONAL} fields.</li>
 *   <li>Produces an {@link IsoMessage} with only the fields actually set — the encoder
 *       then derives the correct bitmap from those fields.</li>
 * </ol>
 *
 * <p>This means the bitmap is always derived from reality (which fields are present),
 * never from a fixed template or inherited from the request structure.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * IsoMessage response = ResponseBuilder.forResponse(MessageType.TRANSACTION_RESPONSE)
 *     .echoingRequest(request)          // auto-copies bits 3,4,7,11,32,41,42,49
 *     .field(12, localTime)             // MANDATORY — computed
 *     .field(13, localDate)             // MANDATORY — computed
 *     .field(39, ResponseCode.APPROVED.getCode()) // MANDATORY
 *     .field(62, receiptText)           // OPTIONAL
 *     .field(63, parameterVersions)     // OPTIONAL
 *     .field(127, gwcelNsu)             // OPTIONAL
 *     .build();
 * }</pre>
 *
 * <p>This class is NOT thread-safe; create one instance per response.
 */
public final class ResponseBuilder {

    private final MessageType responseType;
    private final MessageSpec spec;
    private final Map<Integer, String> explicitFields = new LinkedHashMap<>();
    private IsoMessage request;

    private ResponseBuilder(MessageType responseType) {
        this.responseType = responseType;
        this.spec = MessageSpecRegistry.getRequired(responseType);
    }

    /**
     * Start building a response of the given type.
     *
     * @param responseType the MTI of the response message to build
     * @throws IllegalArgumentException if no spec is registered for the type
     */
    public static ResponseBuilder forResponse(MessageType responseType) {
        return new ResponseBuilder(responseType);
    }

    /**
     * Provide the paired request message.
     *
     * <p>All fields marked {@link FieldPresence#ECHO} in the spec will be copied
     * automatically from this message when {@link #build()} is called.
     * Explicitly provided fields via {@link #field} take precedence over echoed values.
     *
     * @param request the incoming request that this response answers
     */
    public ResponseBuilder echoingRequest(IsoMessage request) {
        this.request = request;
        return this;
    }

    /**
     * Set an explicit field value.
     *
     * <p>Null or empty values are ignored (no-op), consistent with {@link IsoMessage.Builder}.
     *
     * @param bit   the ISO 8583 bit number (2–128)
     * @param value the field value
     */
    public ResponseBuilder field(int bit, String value) {
        if (value != null && !value.isEmpty()) {
            explicitFields.put(bit, value);
        }
        return this;
    }

    /**
     * Set a field from an {@link Optional} — no-op if empty.
     *
     * @param bit   the ISO 8583 bit number
     * @param value an optional value
     */
    public ResponseBuilder fieldIfPresent(int bit, Optional<String> value) {
        value.ifPresent(v -> field(bit, v));
        return this;
    }

    /**
     * Build the response {@link IsoMessage}.
     *
     * <p>Assembly order:
     * <ol>
     *   <li>ECHO fields are copied from the request (if a request was provided).</li>
     *   <li>Explicit fields override echo values (for bits where both apply).</li>
     * </ol>
     *
     * <p>Only bits with an actual value (echo or explicit) are included in the message.
     * The bitmap is then derived by {@link IsoMessageEncoder} from whichever bits are present.
     *
     * @return an immutable {@link IsoMessage} ready for encoding
     */
    public IsoMessage build() {
        IsoMessage.Builder builder = IsoMessage.builder(responseType);

        // Step 1: echo fields from the request
        if (request != null) {
            for (Map.Entry<Integer, FieldPresence> entry : spec.getFields().entrySet()) {
                if (entry.getValue() == FieldPresence.ECHO) {
                    int bit = entry.getKey();
                    request.getField(bit).ifPresent(v -> builder.field(bit, v));
                }
            }
        }

        // Step 2: explicit fields override (including MANDATORY and OPTIONAL)
        explicitFields.forEach(builder::field);

        return builder.build();
    }
}
