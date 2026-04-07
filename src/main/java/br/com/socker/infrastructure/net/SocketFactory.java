package br.com.socker.infrastructure.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

/**
 * Creates and configures TCP {@link Socket} instances with production-safe defaults.
 *
 * <p>Best practices applied:
 * <ul>
 *   <li>Explicit connect timeout — avoids waiting indefinitely.</li>
 *   <li>SO_TIMEOUT (read timeout) — avoids threads stuck on blocking reads.</li>
 *   <li>TCP_NODELAY — reduces latency for small ISO payloads.</li>
 *   <li>SO_KEEPALIVE — OS-level dead peer detection.</li>
 *   <li>No Scanner, no BufferedReader — raw I/O only.</li>
 * </ul>
 */
public class SocketFactory {

    private final SocketOptions options;

    public SocketFactory(SocketOptions options) {
        this.options = options;
    }

    /**
     * Create a new, connected socket to the configured host:port.
     *
     * @return a connected and configured socket
     * @throws IOException if the connection fails or times out
     */
    public Socket create() throws IOException {
        Socket socket = new Socket();
        configure(socket);
        socket.connect(
            new InetSocketAddress(options.host(), options.port()),
            options.connectTimeoutMs()
        );
        // Set read timeout after connecting (cannot be set before connection on some JVMs)
        socket.setSoTimeout(options.readTimeoutMs());
        return socket;
    }

    /**
     * Apply socket options before connecting.
     */
    private void configure(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setReuseAddress(true);
    }

    public SocketOptions getOptions() {
        return options;
    }
}
