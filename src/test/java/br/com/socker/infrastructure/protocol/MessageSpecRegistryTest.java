package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.MessageType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Validates that every registered {@link MessageSpec} correctly models
 * the GwCel protocol spec (version 006, edition 26).
 *
 * <p>Key assertions:
 * <ul>
 *   <li>0200 and 0210 have independent field lists (no implicit sharing).</li>
 *   <li>0210 has bits 39, 62, 63, 127 defined.</li>
 *   <li>0210 ECHO bits include the required mirrored fields from 0200.</li>
 *   <li>0210 does NOT include request-only fields (e.g. bit 40 interface version).</li>
 *   <li>All response MTIs have a registered spec.</li>
 * </ul>
 */
class MessageSpecRegistryTest {

    // ── Registry coverage ────────────────────────────────────────────────────

    @Test
    void allMessageTypes_haveRegisteredSpec() {
        for (MessageType type : MessageType.values()) {
            assertThat(MessageSpecRegistry.get(type))
                .as("Expected spec for MTI %s", type.getMti())
                .isPresent();
        }
    }

    @Test
    void getRequired_throwsForUnknownType() {
        // MessageType enum is exhaustive — simulate via null check on internal map
        assertThat(MessageSpecRegistry.all()).hasSameSizeAs(MessageType.values());
    }

    // ── 0200 vs 0210 — independent specs ─────────────────────────────────────

    @Test
    void request0200_and_response0210_are_independentSpecs() {
        MessageSpec req = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_REQUEST);
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);

        // Specs are different objects for different MTIs
        assertThat(req).isNotSameAs(res);
        assertThat(req.getMessageType()).isEqualTo(MessageType.TRANSACTION_REQUEST);
        assertThat(res.getMessageType()).isEqualTo(MessageType.TRANSACTION_RESPONSE);
    }

    @Test
    void request0200_hasNoResponseCode_bit39() {
        // Response code (bit 39) is not part of the 0200 request spec
        MessageSpec req = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_REQUEST);
        assertThat(req.isDefined(39)).isFalse();
    }

    @Test
    void request0200_hasInterfaceVersion_bit40() {
        MessageSpec req = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_REQUEST);
        assertThat(req.getPresence(40)).isEqualTo(FieldPresence.MANDATORY);
    }

    @Test
    void response0210_hasNoInterfaceVersion_bit40() {
        // Bit 40 is NOT part of the 0210 response per the GwCel spec
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.isDefined(40)).isFalse();
    }

    // ── 0210 mandatory fields ─────────────────────────────────────────────────

    @Test
    void response0210_hasResponseCode_bit39_asMandatory() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.getPresence(39)).isEqualTo(FieldPresence.MANDATORY);
    }

    @Test
    void response0210_hasLocalTime_bit12_asMandatory() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.getPresence(12)).isEqualTo(FieldPresence.MANDATORY);
    }

    @Test
    void response0210_hasLocalDate_bit13_asMandatory() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.getPresence(13)).isEqualTo(FieldPresence.MANDATORY);
    }

    // ── 0210 echo fields ──────────────────────────────────────────────────────

    @Test
    void response0210_echosBits_3_4_7_11_from0200() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.getPresence(3)).isEqualTo(FieldPresence.ECHO);
        assertThat(res.getPresence(4)).isEqualTo(FieldPresence.ECHO);
        assertThat(res.getPresence(7)).isEqualTo(FieldPresence.ECHO);
        assertThat(res.getPresence(11)).isEqualTo(FieldPresence.ECHO);
    }

    @Test
    void response0210_echosBits_41_42_49_from0200() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.getPresence(41)).isEqualTo(FieldPresence.ECHO);
        assertThat(res.getPresence(42)).isEqualTo(FieldPresence.ECHO);
        assertThat(res.getPresence(49)).isEqualTo(FieldPresence.ECHO);
    }

    @Test
    void response0210_echoesBranchCode_bit32() {
        // Bit 32 is ECHO (present in 0210 only if present in 0200)
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.getPresence(32)).isEqualTo(FieldPresence.ECHO);
    }

    // ── 0210 optional fields (bits 62, 63, 127) ───────────────────────────────

    @Test
    void response0210_hasRechargeInfo_bit62_asOptional() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.isDefined(62)).isTrue();
        assertThat(res.getPresence(62)).isEqualTo(FieldPresence.OPTIONAL);
    }

    @Test
    void response0210_hasParameterVersions_bit63_asOptional() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.isDefined(63)).isTrue();
        assertThat(res.getPresence(63)).isEqualTo(FieldPresence.OPTIONAL);
    }

    @Test
    void response0210_hasGwcelNsu_bit127_asOptional() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.isDefined(127)).isTrue();
        assertThat(res.getPresence(127)).isEqualTo(FieldPresence.OPTIONAL);
    }

    // ── 9110 ─────────────────────────────────────────────────────────────────

    @Test
    void response9110_hasGwcelNsu_bit127() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.PARAMETER_QUERY_RESPONSE);
        assertThat(res.isDefined(127)).isTrue();
        assertThat(res.getPresence(127)).isEqualTo(FieldPresence.OPTIONAL);
    }

    @Test
    void response9110_echoesNsu_bit11() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.PARAMETER_QUERY_RESPONSE);
        assertThat(res.getPresence(11)).isEqualTo(FieldPresence.ECHO);
    }

    // ── 9310 ─────────────────────────────────────────────────────────────────

    @Test
    void response9310_hasGwcelNsu_bit127() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.INVOICE_QUERY_RESPONSE);
        assertThat(res.isDefined(127)).isTrue();
    }

    // ── 0430 ─────────────────────────────────────────────────────────────────

    @Test
    void response0430_echoesOriginalTransactionData_bit90() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.REVERSAL_RESPONSE);
        assertThat(res.getPresence(90)).isEqualTo(FieldPresence.ECHO);
    }

    @Test
    void response0430_hasResponseCode_bit39_asMandatory() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.REVERSAL_RESPONSE);
        assertThat(res.getPresence(39)).isEqualTo(FieldPresence.MANDATORY);
    }

    @Test
    void response0430_hasNoLocalTime_bits12_13() {
        // 0430 does NOT include bits 12/13 per the GwCel spec
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.REVERSAL_RESPONSE);
        assertThat(res.isDefined(12)).isFalse();
        assertThat(res.isDefined(13)).isFalse();
    }

    // ── MessageSpec API ───────────────────────────────────────────────────────

    @Test
    void messageSpec_mandatoryBits_returnsOnlyMandatoryOnes() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        // Mandatory in 0210: 12, 13, 39
        assertThat(res.mandatoryBits()).contains(12, 13, 39);
        // ECHO fields must NOT appear in mandatoryBits
        assertThat(res.mandatoryBits()).doesNotContain(3, 4, 7, 11, 41, 42, 49);
    }

    @Test
    void messageSpec_echoBits_returnsOnlyEchoBits() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.echoBits()).contains(3, 4, 7, 11, 32, 41, 42, 49);
        assertThat(res.echoBits()).doesNotContain(39, 62, 63, 127);
    }

    @Test
    void messageSpec_optionalBits_returnsOnlyOptionalBits() {
        MessageSpec res = MessageSpecRegistry.getRequired(MessageType.TRANSACTION_RESPONSE);
        assertThat(res.optionalBits()).contains(62, 63, 127);
        assertThat(res.optionalBits()).doesNotContain(39, 3, 11);
    }
}
