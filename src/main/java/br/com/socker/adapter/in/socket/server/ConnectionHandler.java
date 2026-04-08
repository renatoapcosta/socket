package br.com.socker.adapter.in.socket.server;

import br.com.socker.application.port.in.ProcessReversalUseCase;
import br.com.socker.application.port.in.ProcessTransactionUseCase;
import br.com.socker.application.port.in.QueryParametersUseCase;
import br.com.socker.application.port.out.ObservabilityPort;
import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.exception.DomainException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.concentrator.ConcentratorConnection;
import br.com.socker.infrastructure.concentrator.ConcentratorConnectionRegistry;
import br.com.socker.infrastructure.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * Adapter IN — handles a single accepted Concentrador socket connection.
 *
 * <p>This handler runs on a Virtual Thread (one per accepted connection).
 * It processes messages in a loop until:
 * <ul>
 *   <li>The Concentrador disconnects (EOF).</li>
 *   <li>A read timeout expires.</li>
 *   <li>An irrecoverable protocol error occurs.</li>
 * </ul>
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Read a {@link Frame} from the connection input stream.</li>
 *   <li>Decode the payload to an {@link IsoMessage}.</li>
 *   <li>Route to the appropriate use case by MTI.</li>
 *   <li>Enqueue the response message via {@link ConcentratorConnection#enqueue} —
 *       all writes are serialized by the connection's single writer thread.</li>
 *   <li>Emit observability events.</li>
 *   <li>Deregister (or close) the connection on exit.</li>
 * </ol>
 *
 * <p>No business logic lives here. No direct writes to the socket — all outbound
 * messages go through the connection's serialized writer queue.
 */
public class ConnectionHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private final String connectionId;
    private final ConcentratorConnection connection;

    /**
     * The shared registry — may be {@code null} when the server uses the convenience
     * constructor (no REST integration). When non-null, the connection is deregistered
     * here on exit. When null, the connection is closed directly.
     */
    private final ConcentratorConnectionRegistry registry;

    private final ProcessTransactionUseCase processTransaction;
    private final ProcessReversalUseCase processReversal;
    private final QueryParametersUseCase queryParameters;
    private final ObservabilityPort observability;
    private final FrameReader frameReader;
    private final IsoMessageDecoder decoder;

    public ConnectionHandler(String connectionId,
                             ConcentratorConnection connection,
                             ConcentratorConnectionRegistry registry,
                             ProcessTransactionUseCase processTransaction,
                             ProcessReversalUseCase processReversal,
                             QueryParametersUseCase queryParameters,
                             ObservabilityPort observability,
                             int maxPayloadBytes) {
        this.connectionId     = connectionId;
        this.connection       = connection;
        this.registry         = registry;
        this.processTransaction = processTransaction;
        this.processReversal  = processReversal;
        this.queryParameters  = queryParameters;
        this.observability    = observability;
        this.frameReader      = new FrameReader(maxPayloadBytes);
        this.decoder          = new IsoMessageDecoder();
    }

    @Override
    public void run() {
        String remoteAddress = connection.getRemoteAddress();
        observability.recordConnectionEvent(connectionId, remoteAddress, "CONNECTED");

        try {
            InputStream in = connection.getInputStream();

            // Process messages in a loop until the connection closes or times out
            while (connection.isAlive() && !Thread.currentThread().isInterrupted()) {
                processOneMessage(in, remoteAddress);
            }
        } catch (SocketTimeoutException e) {
            log.info("Connection {} timed out (read timeout)", connectionId);
            observability.recordConnectionEvent(connectionId, remoteAddress, "TIMEOUT");
        } catch (EOFException e) {
            log.debug("Connection {} closed by Concentrador", connectionId);
            observability.recordConnectionEvent(connectionId, remoteAddress, "CLIENT_CLOSED");
        } catch (IOException e) {
            log.warn("Connection {} I/O error: {}", connectionId, e.getMessage());
            observability.recordTransportError(connectionId, e.getMessage(), e);
        } finally {
            // When a registry is provided: deregister closes the connection via CAS.
            // When no registry (standalone mode): close the connection directly.
            if (registry != null) {
                registry.deregister(connectionId);
            } else {
                connection.close();
            }
            observability.recordConnectionEvent(connectionId, remoteAddress, "DISCONNECTED");
        }
    }

    private void processOneMessage(InputStream in, String remoteAddress)
            throws IOException, SocketTimeoutException, EOFException {

        long startMs = System.currentTimeMillis();

        // 1. Read frame
        Frame requestFrame;
        try {
            requestFrame = frameReader.read(in);
        } catch (ProtocolException e) {
            log.warn("Protocol error on connection {}: {}", connectionId, e.getMessage());
            observability.recordTransportError(connectionId, "PROTOCOL_ERROR: " + e.getMessage(), e);
            throw new IOException("Unrecoverable protocol error", e);
        }

        // 2. Decode ISO message
        IsoMessage request;
        try {
            request = decoder.decode(requestFrame.payloadAsString());
        } catch (ProtocolException e) {
            log.warn("ISO decode error on connection {}: {}", connectionId, e.getMessage());
            observability.recordTransportError(connectionId, "DECODE_ERROR: " + e.getMessage(), e);
            throw new IOException("Unrecoverable decode error", e);
        }

        // 3. Route to use case
        TransactionResult result = route(request);

        // 4. Enqueue response via the connection's serialized writer.
        //    All writes go through one queue + one virtual thread — concurrent-safe.
        if (result.hasResponse()) {
            try {
                connection.enqueue(result.getResponseMessage());
            } catch (ConnectionQueueFullException e) {
                log.warn("Connection {} response queue full, dropping response for MTI={}",
                    connectionId, request.getMessageType().getMti());
            }
        }

        // 5. Emit observability
        long processingTimeMs = System.currentTimeMillis() - startMs;
        observability.recordTransaction(request, result, processingTimeMs);
    }

    private TransactionResult route(IsoMessage request) {
        try {
            return switch (request.getMessageType()) {
                case TRANSACTION_REQUEST     -> processTransaction.process(request);
                case REVERSAL_REQUEST        -> processReversal.process(request);
                case PARAMETER_QUERY_REQUEST -> queryParameters.processParameterQuery(request);
                case INVOICE_QUERY_REQUEST   -> queryParameters.processInvoiceQuery(request);
                case PROBE_RESPONSE,
                     TRANSACTION_CONFIRMATION -> {
                    // Concentrador-side messages: no response expected
                    log.debug("Received {} on connection {} — no response needed",
                        request.getMessageType().getMti(), connectionId);
                    yield TransactionResult.success(null);
                }
                default -> {
                    log.warn("Unhandled MTI {} on connection {}",
                        request.getMessageType().getMti(), connectionId);
                    yield TransactionResult.protocolError(
                        "Unhandled MTI: " + request.getMessageType().getMti());
                }
            };
        } catch (DomainException e) {
            log.warn("Domain error on connection {}: {}", connectionId, e.getMessage());
            return TransactionResult.protocolError("Domain error: " + e.getMessage());
        }
    }
}
