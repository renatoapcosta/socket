package br.com.socker.adapter.in.socket.server;

import br.com.socker.application.port.in.ProcessReversalUseCase;
import br.com.socker.application.port.in.ProcessTransactionUseCase;
import br.com.socker.application.port.in.QueryParametersUseCase;
import br.com.socker.application.port.out.ObservabilityPort;
import br.com.socker.infrastructure.concentrator.ConcentratorConnection;
import br.com.socker.infrastructure.concentrator.ConcentratorConnectionRegistry;
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
 * Adapter IN — TCP server that accepts Concentrador connections and dispatches
 * each to a Virtual Thread via {@link ConnectionHandler}.
 *
 * <p>On each accepted connection, a {@link ConcentratorConnection} is created.
 * When a {@link ConcentratorConnectionRegistry} is provided (full constructor),
 * the connection is registered so the REST layer can dispatch messages to it.
 * When no registry is provided (convenience constructor), connections are
 * independent — suitable for standalone tests or multi-connection pool scenarios.
 *
 * <p>Uses Java 25 Virtual Threads via {@link Executors#newVirtualThreadPerTaskExecutor()}.
 * The accept loop runs on a dedicated platform thread for fairness under extreme load.
 */
public class SocketServerAdapter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SocketServerAdapter.class);

    /** Default queue capacity when using the convenience constructor. */
    private static final int DEFAULT_QUEUE_CAPACITY = 500;

    private final int port;
    private final int backlog;
    private final int readTimeoutMs;
    private final int maxPayloadBytes;
    private final int queueCapacity;

    private final ProcessTransactionUseCase processTransaction;
    private final ProcessReversalUseCase processReversal;
    private final QueryParametersUseCase queryParameters;
    private final ObservabilityPort observability;

    /**
     * Shared registry — {@code null} when using the convenience constructor.
     * When non-null, accepted connections are registered so the REST layer can
     * dispatch messages to them.
     */
    private final ConcentratorConnectionRegistry registry;

    private ServerSocket serverSocket;
    private ExecutorService virtualThreadExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Full constructor — inject an explicit registry and queue capacity.
     *
     * <p>Use this in production bootstraps where the registry is shared with the
     * REST subsystem so that REST callers can dispatch messages to Concentrador
     * connections accepted by this server.
     *
     * @param registry      the shared registry; may be {@code null} for standalone use
     * @param queueCapacity outbound queue capacity per accepted connection
     */
    public SocketServerAdapter(int port,
                               int backlog,
                               int readTimeoutMs,
                               int maxPayloadBytes,
                               ProcessTransactionUseCase processTransaction,
                               ProcessReversalUseCase processReversal,
                               QueryParametersUseCase queryParameters,
                               ObservabilityPort observability,
                               ConcentratorConnectionRegistry registry,
                               int queueCapacity) {
        this.port               = port;
        this.backlog            = backlog;
        this.readTimeoutMs      = readTimeoutMs;
        this.maxPayloadBytes    = maxPayloadBytes;
        this.processTransaction = processTransaction;
        this.processReversal    = processReversal;
        this.queryParameters    = queryParameters;
        this.observability      = observability;
        this.registry           = registry;
        this.queueCapacity      = queueCapacity;
    }

    /**
     * Convenience constructor — no registry, independent connections.
     *
     * <p>Each accepted connection is handled independently; there is no shared
     * registry for REST dispatch. Suitable for standalone server scenarios,
     * integration tests, and multi-connection pool benchmarks.
     */
    public SocketServerAdapter(int port,
                               int backlog,
                               int readTimeoutMs,
                               int maxPayloadBytes,
                               ProcessTransactionUseCase processTransaction,
                               ProcessReversalUseCase processReversal,
                               QueryParametersUseCase queryParameters,
                               ObservabilityPort observability) {
        this(port, backlog, readTimeoutMs, maxPayloadBytes,
             processTransaction, processReversal, queryParameters, observability,
             null, DEFAULT_QUEUE_CAPACITY);
    }

    // ─── Server lifecycle ─────────────────────────────────────────────────────

    /**
     * Bind the server socket and start the accept loop on a platform thread.
     * Returns immediately — the server runs in the background.
     *
     * @throws IOException if the port cannot be bound
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port, backlog);
        serverSocket.setReuseAddress(true);

        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        running.set(true);

        Thread.ofPlatform()
            .name("socker-accept-loop")
            .daemon(false)
            .start(this::acceptLoop);

        log.info("Server started on port {} (backlog={}, readTimeoutMs={}, queueCapacity={}, registry={})",
            serverSocket.getLocalPort(), backlog, readTimeoutMs, queueCapacity,
            registry != null ? "shared" : "none");
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                configureClientSocket(clientSocket);

                String connectionId = UUID.randomUUID().toString().substring(0, 8);

                // Create the ConcentratorConnection (wraps socket + writer thread)
                ConcentratorConnection connection =
                    new ConcentratorConnection(connectionId, clientSocket, queueCapacity);

                // Register in the shared registry so the REST layer can dispatch to it.
                // When registry is null (convenience constructor), connections are independent.
                if (registry != null) {
                    registry.register(connection);
                }

                ConnectionHandler handler = new ConnectionHandler(
                    connectionId,
                    connection,
                    registry,
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
