package br.com.socker.adapter.in.rest;

import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter IN — embedded REST server wrapping Javalin/Jetty.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Construct with the port and a configured {@link MessageController}.</li>
 *   <li>The constructor starts Javalin and begins accepting HTTP connections.</li>
 *   <li>Call {@link #close()} (or use try-with-resources) to stop the server.</li>
 * </ol>
 *
 * <p>Javalin is configured with Virtual Threads so that each HTTP request runs
 * on a virtual thread — consistent with the project-wide threading model.
 *
 * <h2>Global exception handling</h2>
 * <p>Any unhandled exception that escapes a route handler is caught here and
 * returns HTTP 500 without a body, to avoid leaking stack traces.
 */
public final class RestServerAdapter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RestServerAdapter.class);

    private final Javalin app;
    private final int port;

    /**
     * Build and start the REST server.
     *
     * @param port       the TCP port to listen on (e.g. 8080)
     * @param controller the controller whose routes will be registered
     */
    public RestServerAdapter(int port, MessageController controller) {
        this.port = port;
        this.app  = buildApp(controller);
        this.app.start(port);
        log.info("REST server started on port {}", port);
    }

    /** Returns the actual port the server is listening on (useful when port=0 in tests). */
    public int getPort() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
        log.info("REST server stopped (was port {})", port);
    }

    // ─── Javalin wiring ───────────────────────────────────────────────────────

    private Javalin buildApp(MessageController controller) {
        Javalin javalin = Javalin.create(config -> {
            // Virtual threads for every request handler — Java 25
            config.useVirtualThreads = true;

            // Suppress Javalin's default "404 not found" body — spec says no body
            config.router.ignoreTrailingSlashes = true;

            // CORS: allow all origins in V1 (tighten in production)
            config.bundledPlugins.enableCors(cors ->
                cors.addRule(CorsPluginConfig.CorsRule::anyHost));
        });

        // Register application routes
        controller.register(javalin);

        // Global fallback for unexpected exceptions — no body, no stack trace
        javalin.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}: {}", ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500);
        });

        return javalin;
    }
}
