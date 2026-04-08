package br.com.socker.adapter.in.rest;

import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON request body for {@code POST /comunicacao/sessions/{sessionId}/messages}.
 *
 * <h2>Wire format</h2>
 * <pre>
 * {
 *   "mti": "0600",
 *   "bit_003": "100000",
 *   "bit_007": "0327162336",
 *   "bit_011": "132256",
 *   "bit_012": "162338",
 *   "bit_013": "0327",
 *   "bit_032": "00101000000",
 *   "bit_041": "GT000001",
 *   "bit_042": "644400000000001",
 *   "bit_125": "000132256",
 *   "bit_127": "000248756"
 * }
 * </pre>
 *
 * <h2>Parsing rules</h2>
 * <ul>
 *   <li>{@code mti} — required; must match a known {@link MessageType}</li>
 *   <li>{@code bit_NNN} — zero-padded or plain bit number; value is a String</li>
 *   <li>Any other JSON key is rejected with {@link InvalidMessageException}</li>
 * </ul>
 *
 * <p>This class converts the raw DTO to an {@link IsoMessage} via {@link #toIsoMessage()}.
 * Business validation (required bits, supported MTI) is done in the use case — not here.
 * Structural parsing (valid MTI string, valid {@code bit_NNN} key format) is done here.
 */
public class IsoMessageRequestDto {

    private String mti;
    private final Map<String, String> bitFields = new LinkedHashMap<>();

    // ─── Jackson mapping ──────────────────────────────────────────────────────

    @JsonProperty("mti")
    public String getMti() {
        return mti;
    }

    @JsonProperty("mti")
    public void setMti(String mti) {
        this.mti = mti;
    }

    /**
     * Captures all {@code bit_NNN} fields from the JSON object.
     *
     * <p>Any key that does NOT start with {@code "bit_"} causes an
     * {@link InvalidMessageException} (mapped to HTTP 400 by the controller).
     */
    @JsonAnySetter
    public void setUnknown(String key, Object value) {
        if (!key.startsWith("bit_")) {
            throw new InvalidMessageException(
                "Unrecognised JSON field '" + key + "'. " +
                "Only 'mti' and 'bit_NNN' fields are accepted.");
        }
        // Store raw: parsing is deferred to toIsoMessage()
        bitFields.put(key, value != null ? value.toString() : "");
    }

    @JsonAnyGetter
    public Map<String, String> getBitFields() {
        return bitFields;
    }

    // ─── Conversion ───────────────────────────────────────────────────────────

    /**
     * Convert this DTO to a domain {@link IsoMessage}.
     *
     * <p>Validates the MTI string and bit key format. Business rules
     * (required fields, supported MTI) are validated in the use case.
     *
     * @throws InvalidMessageException if the MTI is absent/unknown or a bit key is malformed
     */
    public IsoMessage toIsoMessage() {
        MessageType messageType = resolveMessageType();
        IsoMessage.Builder builder = IsoMessage.builder(messageType);

        for (Map.Entry<String, String> entry : bitFields.entrySet()) {
            int bitNumber = parseBitKey(entry.getKey());
            String value  = entry.getValue();
            if (value != null && !value.isEmpty()) {
                builder.field(bitNumber, value);
            }
        }

        return builder.build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private MessageType resolveMessageType() {
        if (mti == null || mti.isBlank()) {
            throw new InvalidMessageException("Field 'mti' is required and must not be blank.");
        }
        return Arrays.stream(MessageType.values())
            .filter(mt -> mt.getMti().equals(mti.trim()))
            .findFirst()
            .orElseThrow(() -> new InvalidMessageException(
                "Unknown MTI value: '" + mti + "'. " +
                "Supported values: " + supportedMtis()));
    }

    private int parseBitKey(String key) {
        // key format: "bit_NNN" where NNN is 1–3 digits representing the bit number
        String digits = key.substring(4); // strip "bit_"
        int bitNumber;
        try {
            bitNumber = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new InvalidMessageException(
                "Invalid bit field key '" + key + "': suffix must be a numeric bit number (e.g. bit_011).");
        }
        if (bitNumber < 2 || bitNumber > 128) {
            throw new InvalidMessageException(
                "Bit number " + bitNumber + " in key '" + key + "' is out of range [2, 128].");
        }
        return bitNumber;
    }

    private static String supportedMtis() {
        StringBuilder sb = new StringBuilder();
        for (MessageType mt : MessageType.values()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(mt.getMti());
        }
        return sb.toString();
    }
}
