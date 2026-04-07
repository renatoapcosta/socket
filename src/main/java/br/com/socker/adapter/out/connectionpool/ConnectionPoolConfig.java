package br.com.socker.adapter.out.connectionpool;

/**
 * Immutable configuration for {@link ConnectionPool}.
 */
public record ConnectionPoolConfig(
    int minSize,
    int maxSize,
    int borrowTimeoutMs,
    int idleTimeoutMs,
    int reconnectDelayMs
) {

    public ConnectionPoolConfig {
        if (minSize < 0)           throw new IllegalArgumentException("minSize must be >= 0");
        if (maxSize < 1)           throw new IllegalArgumentException("maxSize must be >= 1");
        if (minSize > maxSize)     throw new IllegalArgumentException("minSize must be <= maxSize");
        if (borrowTimeoutMs <= 0)  throw new IllegalArgumentException("borrowTimeoutMs must be > 0");
        if (idleTimeoutMs <= 0)    throw new IllegalArgumentException("idleTimeoutMs must be > 0");
        if (reconnectDelayMs < 0)  throw new IllegalArgumentException("reconnectDelayMs must be >= 0");
    }

    public static ConnectionPoolConfig defaults() {
        return new ConnectionPoolConfig(5, 50, 5_000, 60_000, 2_000);
    }
}
