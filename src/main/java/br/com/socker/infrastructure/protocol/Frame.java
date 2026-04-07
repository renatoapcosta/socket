package br.com.socker.infrastructure.protocol;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A complete TCP frame: {@link FrameHeader} + ASCII payload bytes.
 *
 * <p>This is the lowest-level transport unit. It contains the raw payload bytes
 * and knows how to expose them as a String for ISO 8583 parsing.
 *
 * <p>The GwCel spec uses ASCII for all field values. Binary fields (type B) are
 * already converted to their ASCII hex representation before being placed in the payload.
 */
public record Frame(FrameHeader header, byte[] payload) {

    /** Standard charset for all GwCel payloads. */
    public static final Charset CHARSET = StandardCharsets.US_ASCII;

    public Frame {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        if (header.payloadLength() != payload.length) {
            throw new IllegalArgumentException(
                "Header declares length " + header.payloadLength() +
                " but payload has " + payload.length + " bytes");
        }
    }

    /**
     * Build a Frame from a String payload (ASCII encoding).
     */
    public static Frame of(String payloadText) {
        byte[] bytes = payloadText.getBytes(CHARSET);
        return new Frame(new FrameHeader(bytes.length), bytes);
    }

    /**
     * Decode the payload bytes as an ASCII string.
     */
    public String payloadAsString() {
        return new String(payload, CHARSET);
    }

    /**
     * Total wire size: header bytes + payload bytes.
     */
    public int totalWireSize() {
        return FrameHeader.WIRE_SIZE + payload.length;
    }
}
