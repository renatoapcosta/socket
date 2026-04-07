package br.com.socker.infrastructure.protocol;

/**
 * Defines the wire format of a single ISO 8583 data element (field/bit).
 *
 * <p>The GwCel interface uses three length types:
 * <ul>
 *   <li>{@code FIXED} — exact byte count, no length prefix.</li>
 *   <li>{@code LL_VAR} — 2-digit decimal length prefix + value (max 99 bytes).</li>
 *   <li>{@code LLL_VAR} — 3-digit decimal length prefix + value (max 999 bytes).</li>
 * </ul>
 *
 * <p>The data type (N, A, B) controls validation and hex-conversion but not the
 * wire framing, so it is recorded here for completeness and future validation.
 */
public record FieldDefinition(int bit, LengthType lengthType, int fixedLength, DataType dataType) {

    public enum LengthType { FIXED, LL_VAR, LLL_VAR }
    public enum DataType   { NUMERIC, ALPHANUMERIC, BINARY }

    /** Factory for fixed-length fields. */
    public static FieldDefinition fixed(int bit, int length, DataType dataType) {
        return new FieldDefinition(bit, LengthType.FIXED, length, dataType);
    }

    /** Factory for LL-VAR fields (max 99). */
    public static FieldDefinition llVar(int bit, DataType dataType) {
        return new FieldDefinition(bit, LengthType.LL_VAR, 99, dataType);
    }

    /** Factory for LLL-VAR fields (max 999). */
    public static FieldDefinition lllVar(int bit, DataType dataType) {
        return new FieldDefinition(bit, LengthType.LLL_VAR, 999, dataType);
    }
}
