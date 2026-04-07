package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests encode→decode round-trip for ISO 8583 messages.
 */
class IsoMessageCodecTest {

    private final IsoMessageEncoder encoder = new IsoMessageEncoder();
    private final IsoMessageDecoder decoder = new IsoMessageDecoder();

    @Test
    void roundTrip_transaction0200() throws Exception {
        IsoMessage original = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, "000001")
            .field(12, "123045")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();

        String encoded = encoder.encode(original);
        IsoMessage decoded = decoder.decode(encoded);

        assertThat(decoded.getMessageType()).isEqualTo(MessageType.TRANSACTION_REQUEST);
        assertThat(decoded.getRequiredField(3)).isEqualTo("100000");
        assertThat(decoded.getRequiredField(4)).isEqualTo("000000001000");
        assertThat(decoded.getRequiredField(7)).isEqualTo("0407123045");
        assertThat(decoded.getRequiredField(11)).isEqualTo("000001");
        assertThat(decoded.getRequiredField(32)).isEqualTo("12345678901");
        assertThat(decoded.getRequiredField(41)).isEqualTo("TERM0001");
        assertThat(decoded.getRequiredField(42)).isEqualTo("123456789012345");
        assertThat(decoded.getRequiredField(49)).isEqualTo("986");
    }

    @Test
    void roundTrip_transaction0210WithResponseCode() throws Exception {
        IsoMessage response = IsoMessage.builder(MessageType.TRANSACTION_RESPONSE)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, "000001")
            .field(12, "123046")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(39, "00")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();

        String encoded = encoder.encode(response);
        IsoMessage decoded = decoder.decode(encoded);

        assertThat(decoded.getMessageType()).isEqualTo(MessageType.TRANSACTION_RESPONSE);
        assertThat(decoded.getField(39)).contains(ResponseCode.APPROVED.getCode());
    }

    @Test
    void roundTrip_llVarField32() throws Exception {
        // Branch code is LL-VAR; verify length prefix is correct
        IsoMessage msg = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, "000001")
            .field(12, "123045")
            .field(13, "0407")
            .field(32, "99887")      // 5-digit branch code
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();

        String encoded = encoder.encode(msg);

        // Verify the encoded string contains "05" (LL prefix for 5-char value) + "99887"
        assertThat(encoded).contains("0599887");

        IsoMessage decoded = decoder.decode(encoded);
        assertThat(decoded.getRequiredField(32)).isEqualTo("99887");
    }

    @Test
    void roundTrip_lllVarField62() throws Exception {
        String additionalData = "SOME_ADDITIONAL_DATA_FOR_FIELD_62";
        IsoMessage msg = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, "000001")
            .field(12, "123045")
            .field(13, "0407")
            .field(32, "12345")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .field(62, additionalData)
            .build();

        String encoded = encoder.encode(msg);
        IsoMessage decoded = decoder.decode(encoded);

        assertThat(decoded.getField(62)).contains(additionalData);
    }

    @Test
    void roundTrip_reversal0420() throws Exception {
        String originalData = "0200000001040712304512345678901" + "00000000000"; // 42 chars
        IsoMessage reversal = IsoMessage.builder(MessageType.REVERSAL_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407130000")
            .field(11, "000002")
            .field(12, "130000")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(39, "00")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .field(90, originalData)
            .build();

        String encoded = encoder.encode(reversal);
        IsoMessage decoded = decoder.decode(encoded);

        assertThat(decoded.getMessageType()).isEqualTo(MessageType.REVERSAL_REQUEST);
        assertThat(decoded.getField(90)).contains(originalData);
    }

    @Test
    void decode_throwsOnUnknownMti() {
        // Construct a minimal valid-looking payload with unknown MTI "9999"
        // Primary bitmap: 0000000000000000 (no fields set) — 16 hex zeros
        String invalidPayload = "9999" + "0000000000000000";

        assertThatThrownBy(() -> decoder.decode(invalidPayload))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("MTI");
    }

    @Test
    void decode_throwsOnTooShortPayload() {
        assertThatThrownBy(() -> decoder.decode("0200"))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("too short");
    }

    @Test
    void decode_throwsOnTrailingBytes() {
        // A valid 0200 header + empty bitmap + unexpected trailing bytes
        // "0200" + 16-char zeroed bitmap + extra chars
        String payload = "0200" + "0000000000000000" + "UNEXPECTED_EXTRA";
        assertThatThrownBy(() -> decoder.decode(payload))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("trailing");
    }
}
