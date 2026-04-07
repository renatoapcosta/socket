package br.com.socker.adapter.out.socket.client;

import br.com.socker.adapter.out.connectionpool.ConnectionPool;
import br.com.socker.adapter.out.connectionpool.PooledConnection;
import br.com.socker.application.port.out.GatewayException;
import br.com.socker.application.port.out.MessageGateway;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.infrastructure.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * Adapter OUT — implements {@link MessageGateway} over a persistent TCP connection pool.
 *
 * <p>Lifecycle of a single send/receive:
 * <ol>
 *   <li>Borrow a {@link PooledConnection} from the pool.</li>
 *   <li>Encode the request {@link IsoMessage} to ASCII via {@link IsoMessageEncoder}.</li>
 *   <li>Write the frame (header + payload) via {@link FrameWriter}.</li>
 *   <li>Read the response frame via {@link FrameReader}.</li>
 *   <li>Decode the response to an {@link IsoMessage} via {@link IsoMessageDecoder}.</li>
 *   <li>Return the connection to the pool.</li>
 * </ol>
 *
 * <p>If any I/O error occurs, the connection is discarded (not returned to the pool).
 */
public class SocketClientAdapter implements MessageGateway {

    private static final Logger log = LoggerFactory.getLogger(SocketClientAdapter.class);

    private final ConnectionPool pool;
    private final IsoMessageEncoder encoder;
    private final IsoMessageDecoder decoder;

    public SocketClientAdapter(ConnectionPool pool) {
        this.pool    = pool;
        this.encoder = new IsoMessageEncoder();
        this.decoder = new IsoMessageDecoder();
    }

    @Override
    public IsoMessage sendAndReceive(IsoMessage request) throws GatewayException {
        PooledConnection conn = null;
        boolean success = false;

        try {
            conn = borrowConnection();

            // Encode request
            String requestPayload;
            try {
                requestPayload = encoder.encode(request);
            } catch (ProtocolException e) {
                throw new GatewayException(GatewayException.Reason.PROTOCOL_ERROR,
                    "Failed to encode request: " + e.getMessage(), e);
            }

            log.debug("Sending MTI={} via connection={} payloadLen={}",
                request.getMessageType().getMti(), conn.getConnectionId(), requestPayload.length());

            // Write frame
            Frame requestFrame = Frame.of(requestPayload);
            try {
                conn.getFrameWriter().write(conn.getSocket().getOutputStream(), requestFrame);
            } catch (IOException e) {
                throw new GatewayException(GatewayException.Reason.UNEXPECTED_DISCONNECT,
                    "Write failed: " + e.getMessage(), e);
            }

            // Read response frame
            Frame responseFrame;
            try {
                responseFrame = conn.getFrameReader().read(conn.getSocket().getInputStream());
            } catch (SocketTimeoutException e) {
                throw new GatewayException(GatewayException.Reason.READ_TIMEOUT,
                    "Read timed out waiting for response", e);
            } catch (EOFException e) {
                throw new GatewayException(GatewayException.Reason.UNEXPECTED_DISCONNECT,
                    "Server closed the connection mid-response", e);
            } catch (ProtocolException e) {
                throw new GatewayException(GatewayException.Reason.PROTOCOL_ERROR,
                    "Protocol violation reading response: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new GatewayException(GatewayException.Reason.UNEXPECTED_DISCONNECT,
                    "Read failed: " + e.getMessage(), e);
            }

            // Decode response
            IsoMessage response;
            try {
                response = decoder.decode(responseFrame.payloadAsString());
            } catch (ProtocolException e) {
                throw new GatewayException(GatewayException.Reason.INVALID_RESPONSE,
                    "Failed to decode response: " + e.getMessage(), e);
            }

            conn.markUsed();
            success = true;

            log.debug("Received MTI={} via connection={}",
                response.getMessageType().getMti(), conn.getConnectionId());

            return response;

        } finally {
            if (conn != null) {
                if (success) {
                    pool.returnConnection(conn);
                } else {
                    // Discard broken connection
                    conn.closeQuietly();
                    conn.returnToPool(); // update borrow state so pool can track it
                }
            }
        }
    }

    private PooledConnection borrowConnection() throws GatewayException {
        try {
            return pool.borrow();
        } catch (ConnectionPool.PoolExhaustedException e) {
            throw new GatewayException(GatewayException.Reason.POOL_EXHAUSTED,
                "Connection pool exhausted: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Reason.CONNECTION_REFUSED,
                "Could not establish connection: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayException.Reason.CONNECTION_REFUSED,
                "Interrupted while borrowing connection", e);
        }
    }
}
