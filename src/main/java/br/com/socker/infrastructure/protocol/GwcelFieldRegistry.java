package br.com.socker.infrastructure.protocol;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static br.com.socker.infrastructure.protocol.FieldDefinition.DataType.*;
import static br.com.socker.infrastructure.protocol.FieldDefinition.*;

/**
 * Registry of all ISO 8583 field definitions used by the GwCel interface.
 *
 * <p>Based on the GwCel Interface specification version 006, edition 26.
 * Fields not listed here are unknown and will cause a parse error.
 */
public final class GwcelFieldRegistry {

    private static final Map<Integer, FieldDefinition> FIELDS;

    static {
        Map<Integer, FieldDefinition> m = new HashMap<>();

        // Bit 3  — Processing Code (N6, fixed)
        m.put(3,   fixed(3,   6,  NUMERIC));
        // Bit 4  — Amount in centavos (N12, fixed)
        m.put(4,   fixed(4,   12, NUMERIC));
        // Bit 7  — Transmission Date/Time MMDDhhmmss (N10, fixed)
        m.put(7,   fixed(7,   10, NUMERIC));
        // Bit 11 — NSU / System Trace Audit Number (N6, fixed)
        m.put(11,  fixed(11,  6,  NUMERIC));
        // Bit 12 — Local time hhmmss (N6, fixed)
        m.put(12,  fixed(12,  6,  NUMERIC));
        // Bit 13 — Local date MMDD (N4, fixed)
        m.put(13,  fixed(13,  4,  NUMERIC));
        // Bit 32 — Branch Code (LL-VAR, N, max 11 digits)
        m.put(32,  llVar(32,  NUMERIC));
        // Bit 39 — Response Code (A2, fixed)
        m.put(39,  fixed(39,  2,  ALPHANUMERIC));
        // Bit 40 — Interface Version (A3, fixed)
        m.put(40,  fixed(40,  3,  ALPHANUMERIC));
        // Bit 41 — Terminal ID (A8, fixed)
        m.put(41,  fixed(41,  8,  ALPHANUMERIC));
        // Bit 42 — Origin Code (A15, fixed)
        m.put(42,  fixed(42,  15, ALPHANUMERIC));
        // Bit 48 — Authorization Code (LLL-VAR, A)
        m.put(48,  lllVar(48, ALPHANUMERIC));
        // Bit 49 — Currency Code (A3, fixed) — "986" for BRL
        m.put(49,  fixed(49,  3,  ALPHANUMERIC));
        // Bit 58 — Additional info (LLL-VAR, A)
        m.put(58,  lllVar(58, ALPHANUMERIC));
        // Bit 61 — Value key / recharge info (LLL-VAR, A)
        m.put(61,  lllVar(61, ALPHANUMERIC));
        // Bit 62 — Additional request data (LLL-VAR, A)
        m.put(62,  lllVar(62, ALPHANUMERIC));
        // Bit 63 — Parameter versions / supplementary data (LLL-VAR, N)
        m.put(63,  lllVar(63, NUMERIC));
        // Bit 71 — Parameter query sequential (N4, fixed)
        m.put(71,  fixed(71,  4,  NUMERIC));
        // Bit 90 — Original transaction data (A42, fixed)
        m.put(90,  fixed(90,  42, ALPHANUMERIC));
        // Bit 99 — Additional info (LL-VAR, A)
        m.put(99,  llVar(99,  ALPHANUMERIC));
        // Bit 120 — Recharge info (LLL-VAR, A)
        m.put(120, lllVar(120, ALPHANUMERIC));
        // Bit 125 — Original NSU sequential (LLL-VAR, N)
        m.put(125, lllVar(125, NUMERIC));
        // Bit 127 — NSU from branch/GwCel (LLL-VAR, N9)
        m.put(127, lllVar(127, NUMERIC));

        FIELDS = Collections.unmodifiableMap(m);
    }

    private GwcelFieldRegistry() {}

    public static Optional<FieldDefinition> get(int bit) {
        return Optional.ofNullable(FIELDS.get(bit));
    }

    public static FieldDefinition getRequired(int bit) {
        return get(bit).orElseThrow(() ->
            new IllegalArgumentException("No field definition for bit " + bit));
    }

    public static boolean isDefined(int bit) {
        return FIELDS.containsKey(bit);
    }
}
