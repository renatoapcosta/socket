package br.com.socker.infrastructure.protocol;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a {@link Frame} to an {@link OutputStream}.
 *
 * <p>Protocol: write 2-byte Big-Endian header then the payload bytes in a single
 * {@code write} call to avoid partial frame delivery across TCP segments.
 *
 * <p>This class is stateless and thread-safe. The caller is responsible for
 * ensuring that the OutputStream is not shared across concurrent writers.
 */
public class FrameWriter {

    /**
     * Write a complete frame to the output stream and flush.
     *
     * @param out   the socket output stream — must not be null
     * @param frame the frame to write — must not be null
     * @throws IOException on I/O errors
     */
    public void write(OutputStream out, Frame frame) throws IOException {
        byte[] headerBytes = frame.header().encode();
        byte[] payload     = frame.payload();

        // Combine header + payload into one buffer to minimize syscalls
        byte[] wire = new byte[FrameHeader.WIRE_SIZE + payload.length];
        wire[0] = headerBytes[0];
        wire[1] = headerBytes[1];
        System.arraycopy(payload, 0, wire, FrameHeader.WIRE_SIZE, payload.length);

        out.write(wire);
        out.flush();
    }

    /**
     * Convenience: write a String payload (ASCII) as a frame.
     */
    public void writeText(OutputStream out, String text) throws IOException {
        write(out, Frame.of(text));
    }
}
