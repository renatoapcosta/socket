package br.com.socker.infrastructure.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

class FrameReaderWriterTest {

    private final FrameReader reader = new FrameReader(8192);
    private final FrameWriter writer = new FrameWriter();

    @Test
    void writeAndRead_roundTrip() throws Exception {
        String payload = "0200ABC12345";
        Frame original = Frame.of(payload);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, original);

        Frame decoded = reader.read(new ByteArrayInputStream(baos.toByteArray()));

        assertThat(decoded.payloadAsString()).isEqualTo(payload);
        assertThat(decoded.header().payloadLength()).isEqualTo(payload.length());
    }

    @Test
    void writeAndRead_emptyPayload() throws Exception {
        Frame empty = new Frame(new FrameHeader(0), new byte[0]);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, empty);

        Frame decoded = reader.read(new ByteArrayInputStream(baos.toByteArray()));

        assertThat(decoded.payloadAsString()).isEmpty();
        assertThat(decoded.header().payloadLength()).isEqualTo(0);
    }

    @Test
    void writeAndRead_largePayload() throws Exception {
        String payload = "A".repeat(4000);
        Frame original = Frame.of(payload);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, original);

        Frame decoded = reader.read(new ByteArrayInputStream(baos.toByteArray()));

        assertThat(decoded.payloadAsString()).isEqualTo(payload);
    }

    @Test
    void writeAndRead_multipleFrames() throws Exception {
        String payload1 = "FIRST_FRAME";
        String payload2 = "SECOND_FRAME";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, Frame.of(payload1));
        writer.write(baos, Frame.of(payload2));

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        assertThat(reader.read(bais).payloadAsString()).isEqualTo(payload1);
        assertThat(reader.read(bais).payloadAsString()).isEqualTo(payload2);
    }

    @Test
    void read_throwsEofOnEmptyStream() {
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(new byte[0])))
            .isInstanceOf(EOFException.class);
    }

    @Test
    void read_throwsEofOnTruncatedPayload() {
        // Header says 100 bytes, but stream only has 10 bytes of payload
        byte[] truncated = new byte[FrameHeader.WIRE_SIZE + 10];
        truncated[0] = 0x00;
        truncated[1] = 100; // declares 100 bytes

        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(truncated)))
            .isInstanceOf(EOFException.class);
    }

    @Test
    void read_throwsProtocolExceptionWhenPayloadExceedsMax() throws IOException {
        // Build a frame that exceeds maxPayloadBytes=50
        FrameReader smallReader = new FrameReader(50);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, Frame.of("A".repeat(100)));

        assertThatThrownBy(() -> smallReader.read(new ByteArrayInputStream(baos.toByteArray())))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("exceeds maximum");
    }

    @Test
    void wireFormat_headerPrecedesPayload() throws Exception {
        Frame frame = Frame.of("TEST");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, frame);

        byte[] wire = baos.toByteArray();

        // First 2 bytes are the header
        assertThat(wire[0]).isEqualTo((byte) 0x00);
        assertThat(wire[1]).isEqualTo((byte) 0x04); // 4 bytes = "TEST"
        // Remaining bytes are the ASCII payload
        assertThat(new String(wire, 2, 4, Frame.CHARSET)).isEqualTo("TEST");
    }
}
