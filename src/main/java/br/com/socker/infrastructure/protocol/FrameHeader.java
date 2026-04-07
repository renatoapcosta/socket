package br.com.socker.infrastructure.protocol;

/**
 * Represents the 2-byte Big-Endian header of the GwCel TCP frame.
 *
 * <p>The header encodes the payload length as an unsigned 16-bit integer (0–65535).
 * It precedes the ASCII payload in every TCP message.
 *
 * <pre>
 *  Byte 0 (MSB)  Byte 1 (LSB)
 *  +------------+------------+
 *  |  len high  |  len low   |
 *  +------------+------------+
 * </pre>
 */
public record FrameHeader(int payloadLength) {

    /** Maximum payload allowed (64 KB — full range of the 2-byte header). */
    public static final int MAX_PAYLOAD_LENGTH = 65535;

    /** Wire size of the header in bytes. */
    public static final int WIRE_SIZE = 2;

    public FrameHeader {
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                "Payload length out of range [0, " + MAX_PAYLOAD_LENGTH + "]: " + payloadLength);
        }
    }

    /**
     * Encode this header into a 2-byte array (Big-Endian).
     */
    public byte[] encode() {
        return new byte[]{
            (byte) ((payloadLength >> 8) & 0xFF),
            (byte) (payloadLength & 0xFF)
        };
    }

    /**
     * Decode a 2-byte Big-Endian array into a FrameHeader.
     *
     * @param bytes exactly 2 bytes
     * @throws ProtocolException if the array is null or not 2 bytes
     */
    public static FrameHeader decode(byte[] bytes) throws ProtocolException {
        if (bytes == null || bytes.length != WIRE_SIZE) {
            throw new ProtocolException(
                "Header must be exactly " + WIRE_SIZE + " bytes, got: " +
                (bytes == null ? "null" : bytes.length));
        }
        int length = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        return new FrameHeader(length);
    }
}
