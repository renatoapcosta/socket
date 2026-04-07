package br.com.socker.infrastructure.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class FrameHeaderTest {

    @Test
    void encode_decodeSingleByte() throws ProtocolException {
        FrameHeader original = new FrameHeader(255);
        byte[] encoded = original.encode();
        FrameHeader decoded = FrameHeader.decode(encoded);

        assertThat(decoded.payloadLength()).isEqualTo(255);
    }

    @Test
    void encode_decodeZero() throws ProtocolException {
        FrameHeader header = new FrameHeader(0);
        assertThat(FrameHeader.decode(header.encode()).payloadLength()).isEqualTo(0);
    }

    @Test
    void encode_decodeMaxValue() throws ProtocolException {
        FrameHeader header = new FrameHeader(65535);
        byte[] encoded = header.encode();

        assertThat(encoded[0] & 0xFF).isEqualTo(0xFF);
        assertThat(encoded[1] & 0xFF).isEqualTo(0xFF);

        assertThat(FrameHeader.decode(encoded).payloadLength()).isEqualTo(65535);
    }

    @Test
    void encode_isBigEndian() {
        // payload length 256 = 0x0100 → bytes [0x01, 0x00]
        FrameHeader header = new FrameHeader(256);
        byte[] encoded = header.encode();

        assertThat(encoded[0] & 0xFF).isEqualTo(0x01);
        assertThat(encoded[1] & 0xFF).isEqualTo(0x00);
    }

    @Test
    void encode_typicalIsoLength() throws ProtocolException {
        // 200-byte ISO message is a common payload size
        FrameHeader header = new FrameHeader(200);
        byte[] encoded = header.encode();

        assertThat(encoded[0] & 0xFF).isEqualTo(0x00);
        assertThat(encoded[1] & 0xFF).isEqualTo(0xC8); // 200 = 0xC8

        assertThat(FrameHeader.decode(encoded).payloadLength()).isEqualTo(200);
    }

    @Test
    void decode_throwsOnNullInput() {
        assertThatThrownBy(() -> FrameHeader.decode(null))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("exactly 2 bytes");
    }

    @Test
    void decode_throwsOnWrongLength() {
        assertThatThrownBy(() -> FrameHeader.decode(new byte[]{0x00}))
            .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> FrameHeader.decode(new byte[]{0x00, 0x01, 0x02}))
            .isInstanceOf(ProtocolException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 65536, Integer.MAX_VALUE})
    void constructor_rejectsOutOfRangeLength(int length) {
        assertThatThrownBy(() -> new FrameHeader(length))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
