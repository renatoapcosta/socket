package br.com.socker.infrastructure.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a single {@link Frame} from an {@link InputStream}.
 *
 * <p>Protocol: read 2-byte Big-Endian header, then read exactly that many payload bytes.
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li>Never uses Scanner or BufferedReader — reads raw bytes only.</li>
 *   <li>Validates payload length against configured maximum before allocating.</li>
 *   <li>Uses {@code readFully} semantics — retries until all declared bytes are received.</li>
 *   <li>Throws {@link ProtocolException} on malformed frames, not silent data corruption.</li>
 * </ul>
 */
public class FrameReader {

    private final int maxPayloadBytes;

    public FrameReader(int maxPayloadBytes) {
        if (maxPayloadBytes <= 0 || maxPayloadBytes > FrameHeader.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                "maxPayloadBytes must be in [1, " + FrameHeader.MAX_PAYLOAD_LENGTH + "]");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /**
     * Read exactly one frame from the stream.
     *
     * @param in an open, readable InputStream (socket input stream)
     * @return the decoded frame
     * @throws ProtocolException if the frame is malformed or exceeds max payload
     * @throws EOFException      if the stream ends before the frame is complete
     * @throws IOException       on I/O errors (e.g., socket timeout, reset)
     */
    public Frame read(InputStream in) throws ProtocolException, IOException {
        // Step 1: read the 2-byte header
        byte[] headerBytes = readFully(in, FrameHeader.WIRE_SIZE);
        FrameHeader header = FrameHeader.decode(headerBytes);

        int payloadLength = header.payloadLength();

        if (payloadLength == 0) {
            return new Frame(header, new byte[0]);
        }

        // Step 2: guard against payload that exceeds configured limit
        if (payloadLength > maxPayloadBytes) {
            throw new ProtocolException(
                "Payload length " + payloadLength + " exceeds maximum " + maxPayloadBytes);
        }

        // Step 3: read exactly payloadLength bytes
        byte[] payload = readFully(in, payloadLength);
        return new Frame(header, payload);
    }

    /**
     * Read exactly {@code length} bytes from {@code in}, retrying partial reads.
     *
     * @throws EOFException  if the stream ends before {@code length} bytes are read
     * @throws IOException   on I/O error
     */
    private byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new EOFException(
                    "Stream ended after " + offset + " of " + length + " bytes");
            }
            offset += read;
        }
        return buffer;
    }
}
