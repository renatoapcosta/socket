package br.com.socker.domain.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Domain model for an ISO 8583 message.
 *
 * <p>Represents a fully decoded ISO 8583 message with its MTI and field map.
 * This class is immutable and belongs to the domain layer — it has no dependency
 * on sockets, encoding formats, or logging frameworks.
 *
 * <p>Fields are keyed by their bit number (2–128). Bit 1 is reserved for the
 * secondary bitmap indicator and is not stored as a data field.
 */
public final class IsoMessage {

    private final MessageType messageType;
    private final Map<Integer, String> fields;

    private IsoMessage(MessageType messageType, Map<Integer, String> fields) {
        this.messageType = messageType;
        this.fields = Collections.unmodifiableMap(new HashMap<>(fields));
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public Optional<String> getField(int bit) {
        return Optional.ofNullable(fields.get(bit));
    }

    public String getRequiredField(int bit) {
        String value = fields.get(bit);
        if (value == null) {
            throw new IllegalStateException("Required field bit " + bit + " not present in " + messageType.getMti());
        }
        return value;
    }

    public Map<Integer, String> getFields() {
        return fields;
    }

    public boolean hasField(int bit) {
        return fields.containsKey(bit);
    }

    // Convenience accessors for common fields

    /** Field 3 — Processing Code (6 chars, numeric) */
    public Optional<String> getProcessingCode() {
        return getField(3);
    }

    /** Field 4 — Transaction Amount in centavos (12 chars, numeric) */
    public Optional<String> getAmount() {
        return getField(4);
    }

    /** Field 7 — Transmission date/time MMDDhhmmss (10 chars) */
    public Optional<String> getTransmissionDateTime() {
        return getField(7);
    }

    /** Field 11 — System Trace Audit Number / NSU (6 chars) */
    public Optional<String> getNsu() {
        return getField(11);
    }

    /** Field 12 — Local time hhmmss (6 chars) */
    public Optional<String> getLocalTime() {
        return getField(12);
    }

    /** Field 13 — Local date MMDD (4 chars) */
    public Optional<String> getLocalDate() {
        return getField(13);
    }

    /** Field 32 — Branch code (LL-VAR, numeric) */
    public Optional<String> getBranchCode() {
        return getField(32);
    }

    /** Field 39 — Response code (2 chars, alphanumeric) */
    public Optional<ResponseCode> getResponseCode() {
        return getField(39).map(code -> {
            try {
                return ResponseCode.fromCode(code);
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    /** Field 40 — Interface version (3 chars, alphanumeric) */
    public Optional<String> getInterfaceVersion() {
        return getField(40);
    }

    /** Field 41 — Terminal ID (8 chars, alphanumeric) */
    public Optional<String> getTerminalId() {
        return getField(41);
    }

    /** Field 42 — Origin code (15 chars, alphanumeric) */
    public Optional<String> getOriginCode() {
        return getField(42);
    }

    /** Field 49 — Currency code (3 chars, alphanumeric) — fixed "986" for BRL */
    public Optional<String> getCurrencyCode() {
        return getField(49);
    }

    /** Field 62 — Additional data / request info (LLL-VAR) */
    public Optional<String> getAdditionalData() {
        return getField(62);
    }

    /** Field 63 — Supplementary data / parameter versions (LLL-VAR) */
    public Optional<String> getSupplementaryData() {
        return getField(63);
    }

    /** Field 127 — NSU from branch/GwCel (LLL-VAR, 9 chars) */
    public Optional<String> getGwcelNsu() {
        return getField(127);
    }

    public static Builder builder(MessageType messageType) {
        return new Builder(messageType);
    }

    @Override
    public String toString() {
        return "IsoMessage{mti=" + messageType.getMti() + ", fields=" + fields.keySet() + "}";
    }

    public static final class Builder {

        private final MessageType messageType;
        private final Map<Integer, String> fields = new HashMap<>();

        private Builder(MessageType messageType) {
            this.messageType = messageType;
        }

        public Builder field(int bit, String value) {
            if (value != null && !value.isEmpty()) {
                fields.put(bit, value);
            }
            return this;
        }

        public Builder fieldIfPresent(int bit, Optional<String> value) {
            value.ifPresent(v -> fields.put(bit, v));
            return this;
        }

        public IsoMessage build() {
            return new IsoMessage(messageType, fields);
        }
    }
}
