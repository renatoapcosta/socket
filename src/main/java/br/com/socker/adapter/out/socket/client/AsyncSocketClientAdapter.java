package br.com.socker.adapter.out.socket.client;

import br.com.socker.adapter.out.connectionpool.AsyncConnectionPool;
import br.com.socker.adapter.out.connectionpool.MultiplexedConnection;
import br.com.socker.application.port.out.AsyncMessageGateway;
import br.com.socker.application.port.out.GatewayException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.infrastructure.protocol.Frame;
import br.com.socker.infrastructure.protocol.IsoMessageEncoder;
import br.com.socker.infrastructure.protocol.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter OUT — implements {@link AsyncMessageGateway} over an {@link AsyncConnectionPool}.
 *
 * <p>Lifecycle of a single async send:
 * <ol>
 *   <li>Acquire a {@link MultiplexedConnection} from the pool (alive, preferably with capacity).</li>
 *   <li>Encode the request {@link IsoMessage} to ASCII via {@link IsoMessageEncoder}.</li>
 *   <li>Delegate to {@link MultiplexedConnection#sendAsync(String, Frame)} which:
 *       acquires an in-flight slot, registers the NSU in the {@code PendingRequestRegistry},
 *       and enqueues the frame for the {@code WriterThread}.</li>
 *   <li>Return the {@link CompletableFuture} immediately — no blocking.</li>
 * </ol>
 *
 * <p>The response arrives on the connection's {@code ReadLoop} virtual thread and completes
 * the future. The semaphore slot is released via {@code whenComplete} — also handled inside
 * {@code MultiplexedConnection}.
 *
 * <h2>Thread safety</h2>
 * This adapter is stateless beyond the {@code AsyncConnectionPool} and the encoder
 * (which is stateless). Multiple threads may call {@link #sendAsync} concurrently.
 */
public class AsyncSocketClientAdapter implements AsyncMessageGateway {

    private static final Logger log = LoggerFactory.getLogger(AsyncSocketClientAdapter.class);

    private final AsyncConnectionPool pool;
    private final IsoMessageEncoder encoder;

    public AsyncSocketClientAdapter(AsyncConnectionPool pool) {
        this.pool    = pool;
        this.encoder = new IsoMessageEncoder();
    }

    /**
     * Send a request asynchronously.
     *
     * @param request must contain field 11 (NSU) — used as the correlation key
     * @return a future completed when the matching response arrives
     * @throws GatewayException if encoding fails or no alive connection is available
     */
    @Override
    public CompletableFuture<IsoMessage> sendAsync(IsoMessage request) throws GatewayException {
        // Extract NSU before acquiring connection — fail fast on missing field
        String nsu = request.getNsu().orElseThrow(() ->
            new GatewayException(GatewayException.Reason.PROTOCOL_ERROR,
                "Request must have NSU (field 11) for async correlation"));

        // Encode the ISO 8583 payload
        String payload;
        try {
            payload = encoder.encode(request);
        } catch (ProtocolException e) {
            throw new GatewayException(GatewayException.Reason.PROTOCOL_ERROR,
                "Failed to encode request MTI=" + request.getMessageType().getMti()
                    + ": " + e.getMessage(), e);
        }

        Frame frame = Frame.of(payload);

        // Acquire a multiplexed connection from the async pool
        MultiplexedConnection conn;
        try {
            conn = pool.acquire();
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Reason.POOL_EXHAUSTED,
                "AsyncConnectionPool exhausted: " + e.getMessage(), e);
        }

        log.debug("Sending async MTI={} NSU={} via connection={} payloadLen={}",
            request.getMessageType().getMti(), nsu, conn.getConnectionId(), payload.length());

        // Delegate to the connection — this is non-blocking (enqueues the frame and returns)
        try {
            CompletableFuture<IsoMessage> future = conn.sendAsync(nsu, frame);

            // Log response when it arrives (avoids keeping a reference to conn in the closure)
            String connId = conn.getConnectionId();
            String mti    = request.getMessageType().getMti();
            future.whenComplete((response, ex) -> {
                if (ex != null) {
                    log.warn("Async request NSU={} MTI={} via connection={} failed: {}",
                        nsu, mti, connId, ex.getMessage());
                } else {
                    log.debug("Async response received NSU={} responseMTI={} via connection={}",
                        nsu, response.getMessageType().getMti(), connId);
                }
            });

            return future;

        } catch (IOException e) {
            // sendAsync throws IOException for dead connections, semaphore timeout, or full queue
            GatewayException.Reason reason = e.getMessage() != null && e.getMessage().contains("dead")
                ? GatewayException.Reason.UNEXPECTED_DISCONNECT
                : GatewayException.Reason.POOL_EXHAUSTED;
            throw new GatewayException(reason,
                "Failed to dispatch request NSU=" + nsu + ": " + e.getMessage(), e);
        }
    }
}
