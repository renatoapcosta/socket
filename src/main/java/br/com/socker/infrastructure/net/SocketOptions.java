package br.com.socker.infrastructure.net;

/**
 * Immutable value object carrying all TCP socket configuration parameters.
 */
public record SocketOptions(
    String host,
    int port,
    int connectTimeoutMs,
    int readTimeoutMs,
    int maxPayloadBytes
) {

    public SocketOptions {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65535)       throw new IllegalArgumentException("port out of range: " + port);
        if (connectTimeoutMs <= 0)           throw new IllegalArgumentException("connectTimeoutMs must be > 0");
        if (readTimeoutMs <= 0)              throw new IllegalArgumentException("readTimeoutMs must be > 0");
        if (maxPayloadBytes <= 0)            throw new IllegalArgumentException("maxPayloadBytes must be > 0");
    }
}
