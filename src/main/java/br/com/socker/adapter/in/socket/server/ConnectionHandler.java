package br.com.socker.adapter.in.socket.server;

import br.com.socker.application.port.in.ProcessReversalUseCase;
import br.com.socker.application.port.in.ProcessTransactionUseCase;
import br.com.socker.application.port.in.QueryParametersUseCase;
import br.com.socker.application.port.out.ObservabilityPort;
import br.com.socker.domain.exception.DomainException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Adapter IN — handles a single accepted client socket connection.
 *
 * <p>This handler runs on a Virtual Thread (one per accepted connection).
 * It processes messages in a loop until:
 * <ul>
 *   <li>The client disconnects (EOF).</li>
 *   <li>A read timeout expires.</li>
 *   <li>An irrecoverable protocol error occurs.</li>
 * </ul>
 *
 * <p>Responsibilities (only):
 * <ol>
 *   <li>Read a {@link Frame} from the socket.</li>
 *   <li>Decode the payload to an {@link IsoMessage}.</li>
 *   <li>Route to the appropriate use case by MTI.</li>
 *   <li>Encode the response and write it back.</li>
 *   <li>Emit observability events.</li>
 * </ol>
 *
 * <p>No business logic lives here. No "god object" — each responsibility is delegated.
 */
public class ConnectionHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private final String connectionId;
    private final Socket socket;
    private final ProcessTransactionUseCase processTransaction;
    private final ProcessReversalUseCase processReversal;
    private final QueryParametersUseCase queryParameters;
    private final ObservabilityPort observability;
    private final FrameReader frameReader;
    private final FrameWriter frameWriter;
    private final IsoMessageDecoder decoder;
    private final IsoMessageEncoder encoder;

    public ConnectionHandler(String connectionId,
                             Socket socket,
                             ProcessTransactionUseCase processTransaction,
                             ProcessReversalUseCase processReversal,
                             QueryParametersUseCase queryParameters,
                             ObservabilityPort observability,
                             int maxPayloadBytes) {
        this.connectionId     = connectionId;
        this.socket           = socket;
        this.processTransaction = processTransaction;
        this.processReversal  = processReversal;
        this.queryParameters  = queryParameters;
        this.observability    = observability;
        this.frameReader      = new FrameReader(maxPayloadBytes);
        this.frameWriter      = new FrameWriter();
        this.decoder          = new IsoMessageDecoder();
        this.encoder          = new IsoMessageEncoder();
    }

    @Override
    public void run() {
        String remoteAddress = socket.getRemoteSocketAddress().toString();
        observability.recordConnectionEvent(connectionId, remoteAddress, "CONNECTED");

        try (socket) {
            InputStream  in  = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Process messages in a loop until the connection closes or times out
            while (!socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                processOneMessage(in, out, remoteAddress);
            }
        } catch (SocketTimeoutException e) {
            log.info("Connection {} timed out (read timeout)", connectionId);
            observability.recordConnectionEvent(connectionId, remoteAddress, "TIMEOUT");
        } catch (EOFException e) {
            log.debug("Connection {} closed by client", connectionId);
            observability.recordConnectionEvent(connectionId, remoteAddress, "CLIENT_CLOSED");
        } catch (IOException e) {
            log.warn("Connection {} I/O error: {}", connectionId, e.getMessage());
            observability.recordTransportError(connectionId, e.getMessage(), e);
        }

        observability.recordConnectionEvent(connectionId, remoteAddress, "DISCONNECTED");
    }

    private void processOneMessage(InputStream in, OutputStream out, String remoteAddress)
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

        // 4. Encode and write response
        if (result.hasResponse()) {
            String responsePayload;
            try {
                responsePayload = encoder.encode(result.getResponseMessage());
            } catch (ProtocolException e) {
                log.error("Failed to encode response on connection {}: {}", connectionId, e.getMessage(), e);
                observability.recordTransportError(connectionId, "ENCODE_ERROR: " + e.getMessage(), e);
                throw new IOException("Failed to encode response", e);
            }
            frameWriter.writeText(out, responsePayload);
        }

        // 5. Emit observability
        long processingTimeMs = System.currentTimeMillis() - startMs;
        observability.recordTransaction(request, result, processingTimeMs);
    }

    private TransactionResult route(IsoMessage request) {
        try {
            return switch (request.getMessageType()) {
                case TRANSACTION_REQUEST    -> processTransaction.process(request);
                case REVERSAL_REQUEST       -> processReversal.process(request);
                case PARAMETER_QUERY_REQUEST -> queryParameters.processParameterQuery(request);
                case INVOICE_QUERY_REQUEST  -> queryParameters.processInvoiceQuery(request);
                case PROBE_RESPONSE,
                     TRANSACTION_CONFIRMATION -> {
                    // Concentrador-side messages: acknowledge without a response
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
