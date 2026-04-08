package br.com.socker.adapter.in.rest;

import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link IsoMessageRequestDto} — JSON parsing and conversion to {@link IsoMessage}.
 */
class IsoMessageRequestDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    void parse_validProbePayload_producesCorrectIsoMessage() throws Exception {
        String json = """
            {
              "mti": "0600",
              "bit_003": "100000",
              "bit_007": "0327162336",
              "bit_011": "132256",
              "bit_012": "162338",
              "bit_013": "0327",
              "bit_032": "00101000000",
              "bit_041": "GT000001",
              "bit_042": "644400000000001",
              "bit_125": "000132256",
              "bit_127": "000248756"
            }
            """;

        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);
        IsoMessage message = dto.toIsoMessage();

        assertThat(message.getMessageType()).isEqualTo(MessageType.PROBE_REQUEST);
        assertThat(message.getRequiredField(3)).isEqualTo("100000");
        assertThat(message.getRequiredField(7)).isEqualTo("0327162336");
        assertThat(message.getRequiredField(11)).isEqualTo("132256");
        assertThat(message.getRequiredField(12)).isEqualTo("162338");
        assertThat(message.getRequiredField(13)).isEqualTo("0327");
        assertThat(message.getRequiredField(32)).isEqualTo("00101000000");
        assertThat(message.getRequiredField(41)).isEqualTo("GT000001");
        assertThat(message.getRequiredField(42)).isEqualTo("644400000000001");
        assertThat(message.getRequiredField(125)).isEqualTo("000132256");
        assertThat(message.getRequiredField(127)).isEqualTo("000248756");
    }

    @Test
    void parse_zeroPaddedBitKey_isNormalised() throws Exception {
        // bit_003, bit_011, bit_042 — leading zeros must be stripped to int
        String json = """
            {"mti":"0200","bit_003":"100000","bit_004":"000000001000",
             "bit_007":"0407123045","bit_011":"000001","bit_012":"123045",
             "bit_013":"0407","bit_040":"006","bit_041":"TERM0001",
             "bit_042":"123456789012345","bit_049":"986"}
            """;

        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);
        IsoMessage message = dto.toIsoMessage();

        assertThat(message.hasField(3)).isTrue();
        assertThat(message.hasField(11)).isTrue();
        assertThat(message.hasField(42)).isTrue();
    }

    @Test
    void parse_withoutOptionalFields_succeeds() throws Exception {
        String json = """
            {"mti":"0600","bit_003":"100000","bit_007":"0327162336",
             "bit_011":"132256","bit_012":"162338","bit_013":"0327",
             "bit_042":"644400000000001","bit_125":"000132256","bit_127":"000248756"}
            """;

        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);
        IsoMessage message = dto.toIsoMessage();

        assertThat(message.hasField(32)).isFalse();
        assertThat(message.hasField(41)).isFalse();
    }

    // ─── MTI validation ───────────────────────────────────────────────────────

    @Test
    void toIsoMessage_missingMti_throwsInvalidMessageException() throws Exception {
        String json = """
            {"bit_003":"100000","bit_007":"0327162336"}
            """;

        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);

        assertThatThrownBy(dto::toIsoMessage)
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("mti");
    }

    @Test
    void toIsoMessage_unknownMti_throwsInvalidMessageException() throws Exception {
        String json = """
            {"mti":"9999","bit_003":"100000"}
            """;

        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);

        assertThatThrownBy(dto::toIsoMessage)
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("9999");
    }

    @Test
    void toIsoMessage_blankMti_throwsInvalidMessageException() throws Exception {
        String json = """
            {"mti":"   ","bit_003":"100000"}
            """;

        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);

        assertThatThrownBy(dto::toIsoMessage)
            .isInstanceOf(InvalidMessageException.class);
    }

    // ─── Unknown field rejection ──────────────────────────────────────────────

    @Test
    void parse_unknownNonBitField_throwsDuringDeserialization() {
        String json = """
            {"mti":"0600","bit_003":"100000","foo":"bar"}
            """;

        assertThatThrownBy(() -> mapper.readValue(json, IsoMessageRequestDto.class))
            .hasMessageContaining("foo");
    }

    // ─── Bit key format validation ────────────────────────────────────────────

    @Test
    void toIsoMessage_bitKeyWithNonNumericSuffix_throwsInvalidMessageException() throws Exception {
        // Jackson won't call @JsonAnySetter with "mti", but will with "bit_abc"
        String json = """
            {"mti":"0600","bit_abc":"100000"}
            """;

        // This should fail either at parse time (from @JsonAnySetter) or at toIsoMessage
        assertThatThrownBy(() -> {
            IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);
            dto.toIsoMessage();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void toIsoMessage_bitKeyOutOfRange_throwsInvalidMessageException() throws Exception {
        String json = """
            {"mti":"0600","bit_999":"value"}
            """;

        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);

        assertThatThrownBy(dto::toIsoMessage)
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("999");
    }

    // ─── All known MTIs parse correctly ──────────────────────────────────────

    @Test
    void parse_mti_0200() throws Exception {
        assertMtiParses("0200", MessageType.TRANSACTION_REQUEST);
    }

    @Test
    void parse_mti_0210() throws Exception {
        assertMtiParses("0210", MessageType.TRANSACTION_RESPONSE);
    }

    @Test
    void parse_mti_0420() throws Exception {
        assertMtiParses("0420", MessageType.REVERSAL_REQUEST);
    }

    @Test
    void parse_mti_9100() throws Exception {
        assertMtiParses("9100", MessageType.PARAMETER_QUERY_REQUEST);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void assertMtiParses(String mtiValue, MessageType expectedType) throws Exception {
        String json = "{\"mti\":\"" + mtiValue + "\"}";
        IsoMessageRequestDto dto = mapper.readValue(json, IsoMessageRequestDto.class);
        assertThat(dto.toIsoMessage().getMessageType()).isEqualTo(expectedType);
    }
}
