package br.com.socker.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads configuration from {@code application.properties} on the classpath.
 *
 * <p>Exposes typed accessors for server and client settings.
 * All properties have defaults so the application starts with no file.
 */
public class AppConfig {

    private final Properties props;

    public AppConfig() {
        props = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            // defaults will be used
        }
    }

    // ---  Server  ---

    public int serverPort() {
        return intProp("server.port", 9000);
    }

    public int serverBacklog() {
        return intProp("server.backlog", 128);
    }

    /** Read timeout for a connected client socket in milliseconds. */
    public int serverReadTimeoutMs() {
        return intProp("server.read.timeout.ms", 30_000);
    }

    /** Maximum payload size the server will accept. */
    public int serverMaxPayloadBytes() {
        return intProp("server.max.payload.bytes", 8_192);
    }

    // --- Client / Connection Pool ---

    public String clientHost() {
        return prop("client.host", "127.0.0.1");
    }

    public int clientPort() {
        return intProp("client.port", 9000);
    }

    /** Connection establishment timeout in milliseconds. */
    public int clientConnectTimeoutMs() {
        return intProp("client.connect.timeout.ms", 5_000);
    }

    /** Read timeout when waiting for a response in milliseconds. */
    public int clientReadTimeoutMs() {
        return intProp("client.read.timeout.ms", 30_000);
    }

    /** Maximum payload size the client will read from the server. */
    public int clientMaxPayloadBytes() {
        return intProp("client.max.payload.bytes", 8_192);
    }

    public int poolMinSize() {
        return intProp("pool.min.size", 5);
    }

    public int poolMaxSize() {
        return intProp("pool.max.size", 50);
    }

    /** Maximum time (ms) a thread waits to borrow a connection before giving up. */
    public int poolBorrowTimeoutMs() {
        return intProp("pool.borrow.timeout.ms", 5_000);
    }

    /** Maximum time (ms) a connection may sit idle before being validated. */
    public int poolIdleTimeoutMs() {
        return intProp("pool.idle.timeout.ms", 60_000);
    }

    /** How long (ms) to wait before attempting reconnection after a failure. */
    public int poolReconnectDelayMs() {
        return intProp("pool.reconnect.delay.ms", 2_000);
    }

    // --- Logging ---

    public boolean isDebugPayloadEnabled() {
        return boolProp("log.debug.payload", false);
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

    private boolean boolProp(String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value.trim());
    }
}
