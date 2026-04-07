package br.com.socker.adapter.in.socket.server;

import br.com.socker.application.port.in.ProcessReversalUseCase;
import br.com.socker.application.port.in.ProcessTransactionUseCase;
import br.com.socker.application.port.in.QueryParametersUseCase;
import br.com.socker.application.port.out.ObservabilityPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter IN — TCP server that accepts connections and dispatches each to a Virtual Thread.
 *
 * <p>Uses Java 25 Virtual Threads via {@link Executors#newVirtualThreadPerTaskExecutor()}.
 * Each accepted connection is handled in an independent virtual thread —
 * no thread pool contention for 20.000 req/min.
 *
 * <p>The accept loop runs on a dedicated platform thread (not a virtual thread)
 * to avoid unfair scheduling under extreme load.
 */
public class SocketServerAdapter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SocketServerAdapter.class);

    private final int port;
    private final int backlog;
    private final int readTimeoutMs;
    private final int maxPayloadBytes;

    private final ProcessTransactionUseCase processTransaction;
    private final ProcessReversalUseCase processReversal;
    private final QueryParametersUseCase queryParameters;
    private final ObservabilityPort observability;

    private ServerSocket serverSocket;
    private ExecutorService virtualThreadExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SocketServerAdapter(int port,
                               int backlog,
                               int readTimeoutMs,
                               int maxPayloadBytes,
                               ProcessTransactionUseCase processTransaction,
                               ProcessReversalUseCase processReversal,
                               QueryParametersUseCase queryParameters,
                               ObservabilityPort observability) {
        this.port               = port;
        this.backlog            = backlog;
        this.readTimeoutMs      = readTimeoutMs;
        this.maxPayloadBytes    = maxPayloadBytes;
        this.processTransaction = processTransaction;
        this.processReversal    = processReversal;
        this.queryParameters    = queryParameters;
        this.observability      = observability;
    }

    /**
     * Start the server.
     *
     * <p>Binds the {@link ServerSocket} and starts the accept loop on a new platform thread.
     * Returns immediately — the server runs in the background.
     *
     * @throws IOException if the port cannot be bound
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port, backlog);
        serverSocket.setReuseAddress(true);

        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        running.set(true);

        // Accept loop runs on a dedicated platform thread to ensure fairness
        Thread acceptThread = Thread.ofPlatform()
                .name("socker-accept-loop")
                .daemon(false)
                .start(this::acceptLoop);

        log.info("Server started on port {} (backlog={}, readTimeoutMs={})",
            port, backlog, readTimeoutMs);
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                configureClientSocket(clientSocket);

                String connectionId = UUID.randomUUID().toString().substring(0, 8);

                ConnectionHandler handler = new ConnectionHandler(
                    connectionId,
                    clientSocket,
                    processTransaction,
                    processReversal,
                    queryParameters,
                    observability,
                    maxPayloadBytes
                );

                // Dispatch to a Virtual Thread — zero platform thread cost per connection
                virtualThreadExecutor.submit(handler);

            } catch (IOException e) {
                if (running.get()) {
                    log.error("Accept loop error: {}", e.getMessage(), e);
                }
                // If closed normally, running==false and we exit the loop
            }
        }
        log.info("Accept loop terminated");
    }

    private void configureClientSocket(Socket socket) throws IOException {
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    @Override
    public void close() {
        running.set(false);
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdownNow();
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.warn("Error closing server socket: {}", e.getMessage());
            }
        }
        log.info("Server stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }
}
