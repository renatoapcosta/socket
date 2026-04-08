package br.com.socker.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Async-specific configuration loaded from {@code application.properties}.
 *
 * <p>Follows the same pattern as {@link AppConfig} — reads all keys from the same
 * properties file on the classpath, so both configs can coexist without conflicts.
 *
 * <p>Keys specific to the async multiplexed client:
 * <ul>
 *   <li>{@code async.pool.min.connections}              — minimum pre-warmed connections (default: 2)</li>
 *   <li>{@code async.pool.max.connections}              — maximum simultaneous connections (default: 10)</li>
 *   <li>{@code async.pool.max.inflight.per.connection}  — semaphore permits per connection (default: 100)</li>
 *   <li>{@code async.client.request.timeout.ms}         — CompletableFuture.orTimeout value (default: 30000)</li>
 *   <li>{@code async.client.borrow.timeout.ms}          — max wait to acquire in-flight slot (default: 5000)</li>
 *   <li>{@code async.pool.reconnect.delay.ms}           — delay before re-opening dead connection (default: 2000)</li>
 *   <li>{@code async.await.timeout.ms}                  — how long send() blocks on the future (default: 32000)</li>
 * </ul>
 *
 * <p>The shared keys ({@code client.host}, {@code client.port}, etc.) are also
 * accessible here, avoiding the need to instantiate two separate config objects.
 */
public class AsyncClientConfig {

    private final Properties props;

    public AsyncClientConfig() {
        props = new Properties();
        try (InputStream in = AsyncClientConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // defaults will be used
        }
    }

    // --- Shared client keys (mirrors AppConfig) ---

    public String clientHost() {
        return prop("client.host", "127.0.0.1");
    }

    public int clientPort() {
        return intProp("client.port", 9000);
    }

    public int clientConnectTimeoutMs() {
        return intProp("client.connect.timeout.ms", 5_000);
    }

    public int clientMaxPayloadBytes() {
        return intProp("client.max.payload.bytes", 8_192);
    }

    // --- Async pool keys ---

    /** Minimum number of pre-warmed {@code MultiplexedConnection} instances. */
    public int asyncPoolMinConnections() {
        return intProp("async.pool.min.connections", 2);
    }

    /** Maximum number of simultaneous {@code MultiplexedConnection} instances. */
    public int asyncPoolMaxConnections() {
        return intProp("async.pool.max.connections", 10);
    }

    /**
     * Maximum concurrent in-flight requests per {@code MultiplexedConnection}.
     *
     * <p>With 10 connections × 100 in-flight = 1,000 simultaneous pending requests.
     */
    public int asyncMaxInFlightPerConnection() {
        return intProp("async.pool.max.inflight.per.connection", 100);
    }

    /**
     * Per-request response timeout in milliseconds (via {@code CompletableFuture.orTimeout}).
     *
     * <p>The TCP socket has SO_TIMEOUT=0; this is the only response timeout mechanism.
     */
    public int asyncRequestTimeoutMs() {
        return intProp("async.client.request.timeout.ms", 30_000);
    }

    /**
     * Maximum time a caller waits to acquire an in-flight semaphore slot before
     * {@code sendAsync} throws an IOException.
     */
    public int asyncBorrowTimeoutMs() {
        return intProp("async.client.borrow.timeout.ms", 5_000);
    }

    /** Delay (ms) before attempting to re-open a dead connection. */
    public int asyncReconnectDelayMs() {
        return intProp("async.pool.reconnect.delay.ms", 2_000);
    }

    /**
     * How long the synchronous {@code send()} method blocks while waiting for the
     * async future. Should be slightly greater than {@link #asyncRequestTimeoutMs()}.
     */
    public long asyncAwaitTimeoutMs() {
        return intProp("async.await.timeout.ms", 32_000);
    }

    // --- Helpers ---

    private String prop(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private int intProp(String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
