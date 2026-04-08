package br.com.socker.bootstrap;

import br.com.socker.adapter.in.rest.MessageController;
import br.com.socker.adapter.in.rest.RestServerAdapter;
import br.com.socker.application.usecase.DispatchMessageUseCaseImpl;
import br.com.socker.infrastructure.concentrator.ConcentratorConnectionRegistry;
import br.com.socker.infrastructure.config.RestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the REST subsystem.
 *
 * <p>Wiring (outer → inner):
 * <pre>
 *   RestServerAdapter (Javalin/Jetty)
 *     └─ MessageController
 *          └─ DispatchMessageUseCaseImpl
 *               └─ ConcentratorConnectionRegistry  (implements ConcentratorGateway)
 * </pre>
 *
 * <h2>Connection lifecycle</h2>
 * <p>This bootstrap does NOT open any TCP socket to the Concentrador. The correct
 * flow is:
 * <ol>
 *   <li>The Concentrador initiates a TCP connection to the GwCel socket server.</li>
 *   <li>{@link br.com.socker.adapter.in.socket.server.SocketServerAdapter} accepts it
 *       and registers it in {@link ConcentratorConnectionRegistry}.</li>
 *   <li>REST callers can then dispatch messages via the registry.</li>
 * </ol>
 *
 * <p>Until a Concentrador connects, all dispatch requests return HTTP 404.
 *
 * <p>For a combined REST + socket server bootstrap, use {@link GwCelBootstrap}.
 *
 * <p>To run standalone:
 * {@code java -cp socker.jar br.com.socker.bootstrap.RestBootstrap}
 */
public class RestBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RestBootstrap.class);

    public static void main(String[] args) throws Exception {
        RestConfig config = new RestConfig();

        // 1. Registry — starts empty; Concentrador must connect via the socket server
        ConcentratorConnectionRegistry registry = new ConcentratorConnectionRegistry();

        // 2. Use case + controller
        DispatchMessageUseCaseImpl useCase = new DispatchMessageUseCaseImpl(registry);
        MessageController controller       = new MessageController(useCase);

        // 3. REST server
        RestServerAdapter restServer = new RestServerAdapter(config.restServerPort(), controller);

        // 4. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            log.info("Shutdown hook: closing REST server");
            restServer.close();
        }));

        log.info("RestBootstrap ready — POST http://localhost:{}/comunicacao/sessions/{{sessionId}}/messages",
            restServer.getPort());
        log.info("Note: HTTP 404 until a Concentrador connects to the socket server.");
    }
}
