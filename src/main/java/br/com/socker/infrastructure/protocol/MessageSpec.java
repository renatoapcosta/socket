package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.MessageType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-MTI definition of which ISO 8583 fields are expected and how they are populated.
 *
 * <p>A {@code MessageSpec} answers the question: "for this particular message type,
 * which bits are present, and are they mandatory, optional, or echoed from a request?"
 *
 * <p>This is the source of truth for response assembly. {@link ResponseBuilder} uses
 * a spec to know which fields to echo and which bitmap positions are valid.
 *
 * <p>Instances are immutable and obtained from {@link MessageSpecRegistry}.
 *
 * <h2>Example — 0210 response spec</h2>
 * <pre>
 *   bit  3 = ECHO      // processing code, verbatim from 0200
 *   bit 39 = MANDATORY // response code, set by the server
 *   bit 62 = OPTIONAL  // receipt / recharge info, provided when available
 *   bit127 = OPTIONAL  // NSU from GwCel (9 digits)
 * </pre>
 */
public final class MessageSpec {

    private final MessageType messageType;
    private final Map<Integer, FieldPresence> fields;

    private MessageSpec(MessageType messageType, Map<Integer, FieldPresence> fields) {
        this.messageType = messageType;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** The message type this spec describes. */
    public MessageType getMessageType() {
        return messageType;
    }

    /** Full field map: bit number → presence policy. */
    public Map<Integer, FieldPresence> getFields() {
        return fields;
    }

    /** Returns {@code true} if the bit is defined in this spec (any presence). */
    public boolean isDefined(int bit) {
        return fields.containsKey(bit);
    }

    /** Returns the presence policy for the given bit, or {@code null} if not defined. */
    public FieldPresence getPresence(int bit) {
        return fields.get(bit);
    }

    /** All bits with {@link FieldPresence#MANDATORY}. */
    public Set<Integer> mandatoryBits() {
        return fields.entrySet().stream()
            .filter(e -> e.getValue() == FieldPresence.MANDATORY)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** All bits with {@link FieldPresence#ECHO}. */
    public Set<Integer> echoBits() {
        return fields.entrySet().stream()
            .filter(e -> e.getValue() == FieldPresence.ECHO)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** All bits with {@link FieldPresence#OPTIONAL}. */
    public Set<Integer> optionalBits() {
        return fields.entrySet().stream()
            .filter(e -> e.getValue() == FieldPresence.OPTIONAL)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** All bit numbers defined in this spec (any presence). */
    public Set<Integer> allDefinedBits() {
        return fields.keySet();
    }

    /** Returns a new {@link Builder} for the given message type. */
    public static Builder builder(MessageType messageType) {
        return new Builder(messageType);
    }

    @Override
    public String toString() {
        return "MessageSpec{mti=" + messageType.getMti() + ", fields=" + fields + "}";
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {

        private final MessageType messageType;
        private final LinkedHashMap<Integer, FieldPresence> fields = new LinkedHashMap<>();

        private Builder(MessageType messageType) {
            this.messageType = messageType;
        }

        /** Mark the bit as {@link FieldPresence#MANDATORY}. */
        public Builder mandatory(int bit) {
            fields.put(bit, FieldPresence.MANDATORY);
            return this;
        }

        /** Mark the bit as {@link FieldPresence#OPTIONAL}. */
        public Builder optional(int bit) {
            fields.put(bit, FieldPresence.OPTIONAL);
            return this;
        }

        /**
         * Mark the bit as {@link FieldPresence#ECHO}.
         *
         * <p>In response messages, the field value will be automatically copied
         * from the corresponding request by {@link ResponseBuilder}.
         */
        public Builder echo(int bit) {
            fields.put(bit, FieldPresence.ECHO);
            return this;
        }

        public MessageSpec build() {
            return new MessageSpec(messageType, fields);
        }
    }
}
