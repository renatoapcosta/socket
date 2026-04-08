package br.com.socker.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration for the REST server and session management.
 *
 * <p>Reads from {@code application.properties} on the classpath.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code rest.server.port}       — TCP port for the embedded Javalin/Jetty server (default: 8080)</li>
 *   <li>{@code session.queue.capacity} — max outbound messages per session queue (default: 500)</li>
 * </ul>
 */
public class RestConfig {

    private final Properties props;

    public RestConfig() {
        props = new Properties();
        try (InputStream in = RestConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // defaults will be used
        }
    }

    /** TCP port the embedded REST server listens on. */
    public int restServerPort() {
        return intProp("rest.server.port", 8080);
    }

    /**
     * Maximum number of ISO 8583 messages that can be waiting in a session's
     * outbound queue before the system returns HTTP 429.
     */
    public int sessionQueueCapacity() {
        return intProp("session.queue.capacity", 500);
    }

    // ─── Shared client keys (mirrors AppConfig) ───────────────────────────────

    public String clientHost() {
        return props.getProperty("client.host", "127.0.0.1");
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
