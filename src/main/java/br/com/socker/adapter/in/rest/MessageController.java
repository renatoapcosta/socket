package br.com.socker.adapter.in.rest;

import br.com.socker.application.port.in.DispatchMessageUseCase;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.exception.SessionNotFoundException;
import br.com.socker.domain.exception.SessionQueueFullException;
import br.com.socker.domain.model.IsoMessage;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * REST adapter — handles {@code POST /comunicacao/sessions/{sessionId}/messages}.
 *
 * <p>This class is a pure adapter: it translates HTTP concepts (path params,
 * body, status codes) into domain calls and back. Zero business logic lives here.
 *
 * <h2>HTTP semantics</h2>
 * <table border="1">
 * <tr><th>Status</th><th>Meaning</th></tr>
 * <tr><td>202</td><td>Message accepted and enqueued for delivery</td></tr>
 * <tr><td>400</td><td>JSON malformed, MTI absent/unknown, or required bit missing</td></tr>
 * <tr><td>404</td><td>No active session found for the given sessionId</td></tr>
 * <tr><td>429</td><td>Session outbound queue is full — apply back-pressure</td></tr>
 * </table>
 *
 * <p>No response body is written in any case, per the specification.
 *
 * <h2>Logging</h2>
 * <p>MDC keys set during request processing:
 * <ul>
 *   <li>{@code sessionId}</li>
 *   <li>{@code mti}</li>
 *   <li>{@code nsu} (bit 11)</li>
 *   <li>{@code bit_003} (processing code)</li>
 *   <li>{@code bit_127} (NSU Filial, when present)</li>
 *   <li>{@code requestId} (from header {@code X-Request-Id}, when present)</li>
 * </ul>
 */
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    static final String PATH = "/comunicacao/sessions/{sessionId}/messages";
    static final String HEADER_REQUEST_ID = "X-Request-Id";

    private final DispatchMessageUseCase useCase;

    public MessageController(DispatchMessageUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * Register this controller's route on the given Javalin application.
     *
     * @param app the Javalin instance (must not be started yet)
     */
    public void register(io.javalin.Javalin app) {
        app.post(PATH, this::handlePost);
    }

    // ─── Handler ─────────────────────────────────────────────────────────────

    void handlePost(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        String requestId = ctx.header(HEADER_REQUEST_ID);

        MDC.put("sessionId", sessionId);
        if (requestId != null) MDC.put("requestId", requestId);

        try {
            IsoMessageRequestDto dto = parseBody(ctx);
            if (dto == null) return; // 400 already set

            IsoMessage message = convertDto(ctx, dto);
            if (message == null) return; // 400 already set

            populateMdc(message);
            dispatchMessage(ctx, sessionId, message);

        } finally {
            MDC.remove("sessionId");
            MDC.remove("requestId");
            MDC.remove("mti");
            MDC.remove("nsu");
            MDC.remove("bit_003");
            MDC.remove("bit_127");
        }
    }

    // ─── Steps ───────────────────────────────────────────────────────────────

    private IsoMessageRequestDto parseBody(Context ctx) {
        try {
            return ctx.bodyAsClass(IsoMessageRequestDto.class);
        } catch (InvalidMessageException e) {
            // Unknown JSON field (from @JsonAnySetter validation)
            log.warn("Payload rejected — unknown field: {}", e.getMessage());
            ctx.status(400);
            return null;
        } catch (Exception e) {
            log.warn("Payload rejected — malformed JSON: {}", e.getMessage());
            ctx.status(400);
            return null;
        }
    }

    private IsoMessage convertDto(Context ctx, IsoMessageRequestDto dto) {
        try {
            return dto.toIsoMessage();
        } catch (InvalidMessageException e) {
            log.warn("Payload rejected — invalid MTI or bit key: {}", e.getMessage());
            ctx.status(400);
            return null;
        }
    }

    private void dispatchMessage(Context ctx, String sessionId, IsoMessage message) {
        try {
            useCase.dispatch(sessionId, message);
            ctx.status(202);
            log.info("Accepted: MTI={} NSU={} sessionId={}",
                message.getMessageType().getMti(),
                message.getNsu().orElse("?"),
                sessionId);
        } catch (InvalidMessageException e) {
            log.warn("Payload rejected — use-case validation: {}", e.getMessage());
            ctx.status(400);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found: {}", e.getMessage());
            ctx.status(404);
        } catch (SessionQueueFullException e) {
            log.warn("Back-pressure: session queue full: {}", e.getMessage());
            ctx.status(429);
        }
    }

    // ─── MDC helpers ─────────────────────────────────────────────────────────

    private void populateMdc(IsoMessage message) {
        MDC.put("mti",     message.getMessageType().getMti());
        MDC.put("nsu",     message.getNsu().orElse("?"));
        MDC.put("bit_003", message.getField(3).orElse("?"));
        message.getField(127).ifPresent(v -> MDC.put("bit_127", v));
    }
}
