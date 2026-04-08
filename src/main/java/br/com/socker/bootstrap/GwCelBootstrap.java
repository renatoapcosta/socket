package br.com.socker.bootstrap;

import br.com.socker.adapter.in.rest.MessageController;
import br.com.socker.adapter.in.rest.RestServerAdapter;
import br.com.socker.adapter.in.socket.server.SocketServerAdapter;
import br.com.socker.adapter.out.logging.StructuredObservabilityAdapter;
import br.com.socker.application.port.out.ObservabilityPort;
import br.com.socker.application.usecase.DispatchMessageUseCaseImpl;
import br.com.socker.application.usecase.ProcessReversalUseCaseImpl;
import br.com.socker.application.usecase.ProcessTransactionUseCaseImpl;
import br.com.socker.application.usecase.QueryParametersUseCaseImpl;
import br.com.socker.infrastructure.concentrator.ConcentratorConnectionRegistry;
import br.com.socker.infrastructure.config.AppConfig;
import br.com.socker.infrastructure.config.RestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Combined entry point — starts both the GwCel TCP socket server and the REST
 * dispatch server, sharing a single {@link ConcentratorConnectionRegistry}.
 *
 * <h2>Wiring</h2>
 * <pre>
 *   ConcentratorConnectionRegistry  (shared, thread-safe)
 *       │
 *       ├─ SocketServerAdapter          (TCP server — accepts Concentrador connections,
 *       │    └─ ConnectionHandler           registers them in the registry)
 *       │
 *       └─ RestServerAdapter            (HTTP server — dispatches 0600 messages via
 *            └─ MessageController           the registry to the active connection)
 *                 └─ DispatchMessageUseCaseImpl
 * </pre>
 *
 * <h2>Connection flow</h2>
 * <ol>
 *   <li>Concentrador dials → GwCel TCP server accepts → ConcentratorConnection created
 *       and registered in registry.</li>
 *   <li>REST caller POSTs 0600 → MessageController → DispatchMessageUseCaseImpl →
 *       registry.send() → ConcentratorConnection.enqueue() → writer thread → socket.</li>
 *   <li>Concentrador sends 0200/0420/9100 → ConnectionHandler reads, routes to use case,
 *       enqueues response via connection → writer thread → socket.</li>
 * </ol>
 *
 * <p>To start: {@code java -cp socker.jar br.com.socker.bootstrap.GwCelBootstrap}
 */
public class GwCelBootstrap {

    private static final Logger log = LoggerFactory.getLogger(GwCelBootstrap.class);

    public static void main(String[] args) throws Exception {
        AppConfig  appConfig  = new AppConfig();
        RestConfig restConfig = new RestConfig();

        // ── Observability ──────────────────────────────────────────────────────
        ObservabilityPort observability = new StructuredObservabilityAdapter(
            appConfig.isDebugPayloadEnabled()
        );

        // ── Use cases ─────────────────────────────────────────────────────────
        ProcessTransactionUseCaseImpl processTransaction = new ProcessTransactionUseCaseImpl();
        ProcessReversalUseCaseImpl    processReversal    = new ProcessReversalUseCaseImpl();
        QueryParametersUseCaseImpl    queryParameters    = new QueryParametersUseCaseImpl();

        // ── Shared registry ───────────────────────────────────────────────────
        // Single source of truth for the active Concentrador connection.
        // Written by the socket server (on accept/disconnect), read by the REST server.
        ConcentratorConnectionRegistry registry = new ConcentratorConnectionRegistry();

        // ── TCP socket server ─────────────────────────────────────────────────
        SocketServerAdapter socketServer = new SocketServerAdapter(
            appConfig.serverPort(),
            appConfig.serverBacklog(),
            appConfig.serverReadTimeoutMs(),
            appConfig.serverMaxPayloadBytes(),
            processTransaction,
            processReversal,
            queryParameters,
            observability,
            registry,
            restConfig.sessionQueueCapacity()
        );

        // ── REST server ───────────────────────────────────────────────────────
        DispatchMessageUseCaseImpl dispatchUseCase = new DispatchMessageUseCaseImpl(registry);
        MessageController          controller      = new MessageController(dispatchUseCase);
        RestServerAdapter          restServer      = new RestServerAdapter(
            restConfig.restServerPort(), controller
        );

        // ── Shutdown hook ─────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
            .name("gwcel-shutdown")
            .unstarted(() -> {
                log.info("Shutdown signal received — stopping servers...");
                restServer.close();
                socketServer.close();
                registry.getActive().ifPresent(c -> c.close());
            }));

        // ── Start ─────────────────────────────────────────────────────────────
        socketServer.start();
        log.info("GwCel TCP socket server running on port {}", appConfig.serverPort());
        log.info("GwCel REST server running on port {}", restServer.getPort());
        log.info("POST http://localhost:{}/comunicacao/sessions/{{sessionId}}/messages",
            restServer.getPort());

        // Keep the main thread alive
        Thread.currentThread().join();
    }
}
