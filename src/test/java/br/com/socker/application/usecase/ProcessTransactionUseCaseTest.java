package br.com.socker.application.usecase;

import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.protocol.IsoMessageDecoder;
import br.com.socker.infrastructure.protocol.IsoMessageEncoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProcessTransactionUseCaseTest {

    private final ProcessTransactionUseCaseImpl useCase = new ProcessTransactionUseCaseImpl();

    // ─── Existing behaviour (must keep passing) ───────────────────────────────

    @Test
    void process_approvesValidTransaction() {
        TransactionResult result = useCase.process(validRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasResponse()).isTrue();
        assertThat(result.getResponseMessage().getMessageType())
            .isEqualTo(MessageType.TRANSACTION_RESPONSE);
        assertThat(result.getResponseMessage().getField(39))
            .contains(ResponseCode.APPROVED.getCode());
    }

    @Test
    void process_mirrorsNsuInResponse() {
        TransactionResult result = useCase.process(validRequest());

        assertThat(result.getResponseMessage().getRequiredField(11)).isEqualTo("000001");
    }

    @Test
    void process_mirrorsBranchCodeInResponse() {
        TransactionResult result = useCase.process(validRequest());

        assertThat(result.getResponseMessage().getRequiredField(32)).isEqualTo("12345678901");
    }

    @Test
    void process_rejectsWrongMti() {
        IsoMessage wrongType = IsoMessage.builder(MessageType.REVERSAL_REQUEST)
            .field(3,  "100000").field(4,  "000000001000").field(7,  "0407123045")
            .field(11, "000001").field(12, "123045").field(13, "0407")
            .field(32, "12345678901").field(40, "006").field(41, "TERM0001")
            .field(42, "123456789012345").field(49, "986").build();

        assertThatThrownBy(() -> useCase.process(wrongType))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("Expected 0200");
    }

    @Test
    void process_rejectsMissingNsu() {
        IsoMessage missingNsu = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000").field(4,  "000000001000").field(7,  "0407123045")
            // bit 11 (NSU) intentionally missing
            .field(12, "123045").field(13, "0407").field(32, "12345678901")
            .field(40, "006").field(41, "TERM0001").field(42, "123456789012345")
            .field(49, "986").build();

        assertThatThrownBy(() -> useCase.process(missingNsu))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit 11");
    }

    @Test
    void process_populatesLocalTimeInResponse() {
        String localTime = useCase.process(validRequest())
            .getResponseMessage().getRequiredField(12);

        assertThat(localTime).hasSize(6).containsOnlyDigits();
    }

    // ─── 0210 must NOT inherit 0200-only fields ───────────────────────────────

    @Test
    void response0210_doesNotContainInterfaceVersion_bit40() {
        // Bit 40 (interface version) is in the 0200 request but NOT in the 0210 response
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        assertThat(response.hasField(40)).isFalse();
    }

    // ─── New 0210 fields: bits 62, 63, 127 ───────────────────────────────────

    @Test
    void response0210_containsRechargeInfo_bit62() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        assertThat(response.hasField(62)).isTrue();
        assertThat(response.getField(62)).isPresent();
        assertThat(response.getField(62).get()).isNotBlank();
    }

    @Test
    void response0210_bit62_contains_stubReceiptText() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        assertThat(response.getField(62))
            .contains(ProcessTransactionUseCaseImpl.STUB_RECEIPT_TEXT);
    }

    @Test
    void response0210_containsParameterVersions_bit63() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        assertThat(response.hasField(63)).isTrue();
        assertThat(response.getField(63))
            .contains(ProcessTransactionUseCaseImpl.STUB_PARAMETER_VERSIONS);
    }

    @Test
    void response0210_containsGwcelNsu_bit127() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        assertThat(response.hasField(127)).isTrue();
        assertThat(response.getField(127)).isPresent();
    }

    @Test
    void response0210_bit127_isNineDigitsNumeric() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        String gwcelNsu = response.getRequiredField(127);
        assertThat(gwcelNsu).hasSize(9).containsOnlyDigits();
    }

    @Test
    void response0210_bit127_derivedFromRequestNsu() {
        // Request NSU is "000001" → GwCel NSU must be "000000001" (zero-padded to 9)
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        assertThat(response.getRequiredField(127)).isEqualTo("000000001");
    }

    @Test
    void response0210_bit127_adapts_to_different_nsu() {
        IsoMessage request = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000").field(4,  "000000001000").field(7,  "0407123045")
            .field(11, "001234")
            .field(12, "123045").field(13, "0407").field(32, "12345678901")
            .field(40, "006").field(41, "TERM0001").field(42, "123456789012345")
            .field(49, "986").build();

        String gwcelNsu = useCase.process(request).getResponseMessage().getRequiredField(127);
        assertThat(gwcelNsu).isEqualTo("000001234");
    }

    // ─── Bitmap correctness ───────────────────────────────────────────────────

    @Test
    void response0210_bitmap_includesSecondaryBitmap_because_bit127_isPresent() throws Exception {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        // Encode and verify: secondary bitmap must be present (because bit 127 > 64)
        IsoMessageEncoder encoder = new IsoMessageEncoder();
        String encoded = encoder.encode(response);

        // Layout: MTI (4) + primary bitmap (16) + secondary bitmap (16) + fields
        // Total header without fields = 36 chars
        assertThat(encoded.length()).isGreaterThan(36);

        // Primary bitmap starts at position 4. Its first hex nibble indicates bit 1.
        // If bit 1 is set (secondary bitmap present), the first byte of the bitmap
        // has its MSB set → first hex char is 8, 9, A–F (value >= 8).
        String primaryBitmapFirstByte = encoded.substring(4, 6);
        int firstByteValue = Integer.parseInt(primaryBitmapFirstByte, 16);
        assertThat(firstByteValue & 0x80)
            .as("Bit 1 (secondary bitmap indicator) must be set in primary bitmap, got: %s",
                primaryBitmapFirstByte)
            .isNotEqualTo(0);
    }

    @Test
    void response0210_bitmap_hasBit39_set() throws Exception {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        // Round-trip: encode → decode → check field 39 present
        IsoMessageEncoder encoder = new IsoMessageEncoder();
        IsoMessageDecoder decoder = new IsoMessageDecoder();
        String encoded = encoder.encode(response);
        IsoMessage decoded = decoder.decode(encoded);

        assertThat(decoded.hasField(39)).isTrue();
        assertThat(decoded.getField(39)).contains(ResponseCode.APPROVED.getCode());
    }

    @Test
    void response0210_encodeDecode_roundtrip_preservesBits_62_63_127() throws Exception {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        IsoMessageEncoder encoder = new IsoMessageEncoder();
        IsoMessageDecoder decoder = new IsoMessageDecoder();
        String encoded = encoder.encode(response);
        IsoMessage decoded = decoder.decode(encoded);

        assertThat(decoded.hasField(62)).isTrue();
        assertThat(decoded.hasField(63)).isTrue();
        assertThat(decoded.hasField(127)).isTrue();
        assertThat(decoded.getRequiredField(62))
            .isEqualTo(ProcessTransactionUseCaseImpl.STUB_RECEIPT_TEXT);
        assertThat(decoded.getRequiredField(63))
            .isEqualTo(ProcessTransactionUseCaseImpl.STUB_PARAMETER_VERSIONS);
        assertThat(decoded.getRequiredField(127)).isEqualTo("000000001");
    }

    @Test
    void response0210_encodeDecode_roundtrip_preservesAllEchoFields() throws Exception {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();

        IsoMessageEncoder encoder = new IsoMessageEncoder();
        IsoMessageDecoder decoder = new IsoMessageDecoder();
        IsoMessage decoded = decoder.decode(encoder.encode(response));

        assertThat(decoded.getRequiredField(3)).isEqualTo("100000");
        assertThat(decoded.getRequiredField(4)).isEqualTo("000000001000");
        assertThat(decoded.getRequiredField(7)).isEqualTo("0407123045");
        assertThat(decoded.getRequiredField(11)).isEqualTo("000001");
        assertThat(decoded.getRequiredField(32)).isEqualTo("12345678901");
        assertThat(decoded.getRequiredField(41)).isEqualTo("TERM0001");
        assertThat(decoded.getRequiredField(42)).isEqualTo("123456789012345");
        assertThat(decoded.getRequiredField(49)).isEqualTo("986");
    }

    // ─── Echo correctness ─────────────────────────────────────────────────────

    @Test
    void response0210_echoes_processingCode_bit3() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();
        assertThat(response.getRequiredField(3)).isEqualTo("100000");
    }

    @Test
    void response0210_echoes_amount_bit4() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();
        assertThat(response.getRequiredField(4)).isEqualTo("000000001000");
    }

    @Test
    void response0210_echoes_originCode_bit42() {
        IsoMessage response = useCase.process(validRequest()).getResponseMessage();
        assertThat(response.getRequiredField(42)).isEqualTo("123456789012345");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private IsoMessage validRequest() {
        return IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
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
    }
}
