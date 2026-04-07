package br.com.socker.domain.exception;

/**
 * Thrown when an ISO 8583 message fails validation rules.
 *
 * <p>Examples: missing mandatory field, wrong MTI for context, invalid field format.
 */
public class InvalidMessageException extends DomainException {

    private final int fieldBit;

    public InvalidMessageException(String message) {
        super(message);
        this.fieldBit = -1;
    }

    public InvalidMessageException(String message, int fieldBit) {
        super(message);
        this.fieldBit = fieldBit;
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
        this.fieldBit = -1;
    }

    /** Returns the bit number of the offending field, or -1 if not applicable. */
    public int getFieldBit() {
        return fieldBit;
    }
}
