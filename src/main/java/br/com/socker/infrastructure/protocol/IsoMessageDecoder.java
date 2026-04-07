package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;

import java.util.BitSet;

/**
 * Decodes an ASCII ISO 8583 string into an {@link IsoMessage}.
 *
 * <p>Wire layout (GwCel spec, section 1.1):
 * <pre>
 *   [MTI 4 chars] [Bitmap 32 hex chars (double bitmap)] [fields...]
 *   or
 *   [MTI 4 chars] [Bitmap 16 hex chars (single bitmap)] [fields...]
 * </pre>
 *
 * <p>Bitmap is sent as ASCII hex (each byte → 2 hex chars).
 * Bit 1 of the primary bitmap indicates whether a secondary bitmap is present.
 *
 * <p>This class is stateless and thread-safe.
 */
public class IsoMessageDecoder {

    private static final int MTI_LENGTH        = 4;
    private static final int BITMAP_BYTES      = 8;   // single bitmap = 8 bytes = 16 hex chars
    private static final int BITMAP_HEX_LENGTH = 16;  // 8 bytes * 2 hex chars each

    /**
     * Decode the given ASCII payload string into a domain {@link IsoMessage}.
     *
     * @param payload ASCII string (from FrameReader output)
     * @return decoded message
     * @throws ProtocolException if the payload is too short, MTI is unknown, or field parsing fails
     */
    public IsoMessage decode(String payload) throws ProtocolException {
        if (payload == null || payload.length() < MTI_LENGTH + BITMAP_HEX_LENGTH) {
            throw new ProtocolException(
                "Payload too short: " + (payload == null ? 0 : payload.length()) +
                " chars (minimum " + (MTI_LENGTH + BITMAP_HEX_LENGTH) + ")");
        }

        // 1. Parse MTI
        String mtiString = payload.substring(0, MTI_LENGTH);
        MessageType messageType;
        try {
            messageType = MessageType.fromMti(mtiString);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown MTI: " + mtiString, e);
        }

        // 2. Parse primary bitmap (16 hex chars = 8 bytes)
        int pos = MTI_LENGTH;
        byte[] primaryBitmapBytes = hexToBytes(payload.substring(pos, pos + BITMAP_HEX_LENGTH));
        pos += BITMAP_HEX_LENGTH;
        BitSet bitmap = toBitSet(primaryBitmapBytes);

        // 3. Check if secondary bitmap is present (bit 1 of primary bitmap)
        if (bitmap.get(0)) { // bit index 0 = bit number 1
            if (payload.length() < pos + BITMAP_HEX_LENGTH) {
                throw new ProtocolException("Secondary bitmap declared but payload too short");
            }
            byte[] secondaryBitmapBytes = hexToBytes(payload.substring(pos, pos + BITMAP_HEX_LENGTH));
            pos += BITMAP_HEX_LENGTH;
            BitSet secondary = toBitSet(secondaryBitmapBytes);
            // Merge secondary bitmap into bits 65–128 (0-indexed: 64–127)
            for (int i = 0; i < 64; i++) {
                if (secondary.get(i)) {
                    bitmap.set(64 + i);
                }
            }
        }

        // 4. Parse fields according to bitmap (bits 2–128, skip bit 1 — bitmap indicator)
        IsoMessage.Builder builder = IsoMessage.builder(messageType);
        for (int bitIndex = 1; bitIndex < 128; bitIndex++) { // 0-based index → bit numbers 2–128
            if (bitmap.get(bitIndex)) {
                int bitNumber = bitIndex + 1;
                FieldDefinition def = GwcelFieldRegistry.get(bitNumber).orElseThrow(() ->
                    new ProtocolException("Bit " + bitNumber + " set in bitmap but no field definition found"));

                String[] result = new String[1];
                try {
                    pos = parseField(payload, pos, def, result);
                } catch (ProtocolException e) {
                    throw new ProtocolException("Error parsing bit " + bitNumber + ": " + e.getMessage(), e);
                }
                builder.field(bitNumber, result[0]);
            }
        }

        if (pos != payload.length()) {
            throw new ProtocolException(
                "Payload has " + (payload.length() - pos) + " trailing bytes after all declared fields");
        }

        return builder.build();
    }

    private int parseField(String payload, int pos, FieldDefinition def, String[] out)
            throws ProtocolException {
        return switch (def.lengthType()) {
            case FIXED -> {
                int len = def.fixedLength();
                if (pos + len > payload.length()) {
                    throw new ProtocolException(
                        "Not enough data for fixed field bit " + def.bit() +
                        ": need " + len + ", have " + (payload.length() - pos));
                }
                out[0] = payload.substring(pos, pos + len);
                yield pos + len;
            }
            case LL_VAR -> {
                if (pos + 2 > payload.length()) {
                    throw new ProtocolException("Not enough data for LL length prefix at bit " + def.bit());
                }
                int len = parseDecimalLength(payload, pos, 2, def.bit());
                pos += 2;
                if (pos + len > payload.length()) {
                    throw new ProtocolException("LL-VAR bit " + def.bit() + " declares length " + len + " but only " + (payload.length() - pos) + " remain");
                }
                out[0] = payload.substring(pos, pos + len);
                yield pos + len;
            }
            case LLL_VAR -> {
                if (pos + 3 > payload.length()) {
                    throw new ProtocolException("Not enough data for LLL length prefix at bit " + def.bit());
                }
                int len = parseDecimalLength(payload, pos, 3, def.bit());
                pos += 3;
                if (pos + len > payload.length()) {
                    throw new ProtocolException("LLL-VAR bit " + def.bit() + " declares length " + len + " but only " + (payload.length() - pos) + " remain");
                }
                out[0] = payload.substring(pos, pos + len);
                yield pos + len;
            }
        };
    }

    private int parseDecimalLength(String payload, int pos, int digits, int bit) throws ProtocolException {
        String lengthStr = payload.substring(pos, pos + digits);
        try {
            return Integer.parseInt(lengthStr);
        } catch (NumberFormatException e) {
            throw new ProtocolException(
                "Invalid decimal length '" + lengthStr + "' for bit " + bit);
        }
    }

    // --- Bitmap utilities ---

    private byte[] hexToBytes(String hex) throws ProtocolException {
        if (hex.length() % 2 != 0) {
            throw new ProtocolException("Hex string has odd length: " + hex.length());
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            String byteStr = hex.substring(i * 2, i * 2 + 2);
            try {
                bytes[i] = (byte) Integer.parseInt(byteStr, 16);
            } catch (NumberFormatException e) {
                throw new ProtocolException("Invalid hex byte '" + byteStr + "' in bitmap");
            }
        }
        return bytes;
    }

    private BitSet toBitSet(byte[] bytes) {
        BitSet bits = new BitSet(bytes.length * 8);
        for (int i = 0; i < bytes.length; i++) {
            for (int j = 0; j < 8; j++) {
                if ((bytes[i] & (0x80 >> j)) != 0) {
                    bits.set(i * 8 + j);
                }
            }
        }
        return bits;
    }
}
