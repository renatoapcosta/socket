package br.com.socker.bootstrap;

import br.com.socker.adapter.in.socket.server.SocketServerAdapter;
import br.com.socker.adapter.out.logging.StructuredObservabilityAdapter;
import br.com.socker.application.port.out.ObservabilityPort;
import br.com.socker.application.usecase.ProcessReversalUseCaseImpl;
import br.com.socker.application.usecase.ProcessTransactionUseCaseImpl;
import br.com.socker.application.usecase.QueryParametersUseCaseImpl;
import br.com.socker.infrastructure.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Socket Server.
 *
 * <p>Wires all dependencies manually (no IoC framework needed for this scope).
 * This is the composition root — every object is instantiated here and injected
 * into the components that need them.
 *
 * <p>To start: {@code java -jar socker.jar}
 * To run only the client: use {@link ClientBootstrap}.
 */
public class ServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ServerBootstrap.class);

    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();

        // Observability
        ObservabilityPort observability = new StructuredObservabilityAdapter(
            config.isDebugPayloadEnabled()
        );

        // Use cases (no dependencies on transport)
        ProcessTransactionUseCaseImpl processTransaction = new ProcessTransactionUseCaseImpl();
        ProcessReversalUseCaseImpl    processReversal    = new ProcessReversalUseCaseImpl();
        QueryParametersUseCaseImpl    queryParameters    = new QueryParametersUseCaseImpl();

        // Server adapter
        SocketServerAdapter server = new SocketServerAdapter(
            config.serverPort(),
            config.serverBacklog(),
            config.serverReadTimeoutMs(),
            config.serverMaxPayloadBytes(),
            processTransaction,
            processReversal,
            queryParameters,
            observability
        );

        // Shutdown hook for graceful stop
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
            .name("shutdown-hook")
            .unstarted(() -> {
                log.info("Shutdown signal received — stopping server...");
                server.close();
            }));

        server.start();

        log.info("Server is running on port {}. Press Ctrl+C to stop.", config.serverPort());

        // Keep the main thread alive
        Thread.currentThread().join();
    }
}
