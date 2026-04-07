package br.com.socker.infrastructure.protocol;

/**
 * Thrown when the TCP framing protocol is violated.
 *
 * <p>Examples: negative payload length, payload exceeds max size,
 * incomplete read of declared bytes, invalid header bytes.
 */
public class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
