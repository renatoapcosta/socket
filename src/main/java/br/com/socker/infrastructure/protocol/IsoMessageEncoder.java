package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.IsoMessage;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encodes an {@link IsoMessage} into an ASCII ISO 8583 string ready for transport.
 *
 * <p>Wire layout (GwCel spec, section 1.1):
 * <pre>
 *   [MTI 4 chars] [Bitmap 32 hex chars] [fields in bit-order]
 * </pre>
 *
 * <p>A secondary bitmap (bits 65–128) is always included when any field above bit 64 is present.
 * Bit 1 of the primary bitmap indicates whether the secondary bitmap is present.
 *
 * <p>This class is stateless and thread-safe.
 */
public class IsoMessageEncoder {

    /**
     * Encode the given {@link IsoMessage} to an ASCII string.
     *
     * @param message the domain message to encode
     * @return ASCII string payload (without the 2-byte TCP frame header)
     * @throws ProtocolException if a field value has no definition or exceeds its declared length
     */
    public String encode(IsoMessage message) throws ProtocolException {
        Map<Integer, String> fields = new TreeMap<>(message.getFields()); // sorted by bit number

        // Determine if secondary bitmap is needed
        boolean needsSecondary = fields.keySet().stream().anyMatch(bit -> bit > 64);

        // Build bitmaps
        BitSet primaryBitmap   = new BitSet(64);
        BitSet secondaryBitmap = new BitSet(64);

        if (needsSecondary) {
            primaryBitmap.set(0); // bit 1 — secondary bitmap present
        }

        for (int bit : fields.keySet()) {
            if (bit < 2 || bit > 128) {
                throw new ProtocolException("Invalid bit number: " + bit);
            }
            if (bit <= 64) {
                primaryBitmap.set(bit - 1);
            } else {
                secondaryBitmap.set(bit - 65);
            }
        }

        StringBuilder sb = new StringBuilder(512);

        // 1. MTI
        sb.append(message.getMessageType().getMti());

        // 2. Primary bitmap (16 hex chars)
        sb.append(bitSetToHex(primaryBitmap, 8));

        // 3. Secondary bitmap (16 hex chars) if needed
        if (needsSecondary) {
            sb.append(bitSetToHex(secondaryBitmap, 8));
        }

        // 4. Fields in bit-number order
        for (Map.Entry<Integer, String> entry : fields.entrySet()) {
            int bit   = entry.getKey();
            String value = entry.getValue();

            FieldDefinition def = GwcelFieldRegistry.get(bit).orElseThrow(() ->
                new ProtocolException("No field definition for bit " + bit));

            appendField(sb, def, value);
        }

        return sb.toString();
    }

    private void appendField(StringBuilder sb, FieldDefinition def, String value)
            throws ProtocolException {
        switch (def.lengthType()) {
            case FIXED -> {
                int len = def.fixedLength();
                if (value.length() != len) {
                    // Right-pad alphanumeric, left-pad numeric
                    value = padField(def, value, len);
                }
                sb.append(value);
            }
            case LL_VAR -> {
                if (value.length() > 99) {
                    throw new ProtocolException(
                        "LL-VAR bit " + def.bit() + " value length " + value.length() + " exceeds 99");
                }
                sb.append(String.format("%02d", value.length()));
                sb.append(value);
            }
            case LLL_VAR -> {
                if (value.length() > 999) {
                    throw new ProtocolException(
                        "LLL-VAR bit " + def.bit() + " value length " + value.length() + " exceeds 999");
                }
                sb.append(String.format("%03d", value.length()));
                sb.append(value);
            }
        }
    }

    private String padField(FieldDefinition def, String value, int len) throws ProtocolException {
        if (value.length() > len) {
            throw new ProtocolException(
                "Fixed field bit " + def.bit() + " value length " + value.length() +
                " exceeds declared length " + len);
        }
        return switch (def.dataType()) {
            case NUMERIC -> String.format("%0" + len + "d", Long.parseLong(value)); // left-pad with zeros
            case ALPHANUMERIC -> String.format("%-" + len + "s", value);              // right-pad with spaces
            case BINARY -> String.format("%0" + len + "s", value).replace(' ', '0'); // left-pad zeros
        };
    }

    // --- Bitmap utilities ---

    private String bitSetToHex(BitSet bits, int byteCount) {
        byte[] bytes = new byte[byteCount];
        for (int i = 0; i < byteCount * 8; i++) {
            if (bits.get(i)) {
                bytes[i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }
        StringBuilder hex = new StringBuilder(byteCount * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }
}
