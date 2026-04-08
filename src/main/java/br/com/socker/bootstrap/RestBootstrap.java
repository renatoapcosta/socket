package br.com.socker.bootstrap;

import br.com.socker.adapter.in.rest.MessageController;
import br.com.socker.adapter.in.rest.RestServerAdapter;
import br.com.socker.application.usecase.DispatchMessageUseCaseImpl;
import br.com.socker.infrastructure.config.RestConfig;
import br.com.socker.infrastructure.net.SocketFactory;
import br.com.socker.infrastructure.net.SocketOptions;
import br.com.socker.infrastructure.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;

/**
 * Entry point for the REST-over-socket subsystem.
 *
 * <p>Wiring (outer → inner):
 * <pre>
 *   RestServerAdapter (Javalin/Jetty)
 *     └─ MessageController
 *          └─ DispatchMessageUseCaseImpl
 *               └─ SessionManager  (implements SessionGateway)
 *                    └─ Session (socket + bounded queue + virtual-thread worker)
 * </pre>
 *
 * <h2>Session lifecycle</h2>
 * <p>Sessions are opened explicitly before the REST server starts.
 * Each session wraps a TCP connection to GwCel and owns a bounded outbound queue.
 * The REST caller identifies which session to use via the {@code {sessionId}}
 * path parameter.
 *
 * <p>In production, session lifecycle would be managed by a supervision layer
 * that reconnects on failure. For V1, a single default session is opened at startup.
 *
 * <p>To run:
 * {@code java -cp socker.jar br.com.socker.bootstrap.RestBootstrap}
 */
public class RestBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RestBootstrap.class);

    /** Default session identifier — used when a single GwCel connection suffices. */
    public static final String DEFAULT_SESSION_ID = "default";

    public static void main(String[] args) throws Exception {
        RestConfig config = new RestConfig();

        // 1. Session infrastructure
        SessionManager sessionManager = new SessionManager(config.sessionQueueCapacity());

        // 2. Open the default session to GwCel
        //    SocketOptions reuse the same client configuration as the existing pool.
        //    readTimeoutMs=1 because the Session has no ReadLoop (fire-and-forget).
        SocketOptions options = new SocketOptions(
            config.clientHost(),
            config.clientPort(),
            config.clientConnectTimeoutMs(),
            1,   // placeholder — Session does not read from the socket
            config.clientMaxPayloadBytes()
        );
        SocketFactory socketFactory = new SocketFactory(options);
        Socket socket = socketFactory.create();
        sessionManager.openSession(DEFAULT_SESSION_ID, socket);
        log.info("Default session opened → GwCel at {}:{}", config.clientHost(), config.clientPort());

        // 3. Use case + controller
        DispatchMessageUseCaseImpl useCase = new DispatchMessageUseCaseImpl(sessionManager);
        MessageController controller = new MessageController(useCase);

        // 4. REST server
        RestServerAdapter restServer = new RestServerAdapter(config.restServerPort(), controller);

        // 5. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            log.info("Shutdown hook: closing REST server and sessions");
            restServer.close();
            sessionManager.close();
        }));

        log.info("RestBootstrap ready. POST http://localhost:{}/comunicacao/sessions/{}/messages",
            restServer.getPort(), DEFAULT_SESSION_ID);
    }
}
