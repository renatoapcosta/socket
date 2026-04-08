package br.com.socker.infrastructure.net;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.infrastructure.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Continuously reads ISO 8583 frames from an {@link InputStream} and dispatches
 * each decoded response to the {@link PendingRequestRegistry} by NSU.
 *
 * <p>Runs on a dedicated Virtual Thread — one per {@code MultiplexedConnection}.
 * The thread blocks on {@link FrameReader#read(InputStream)} until a complete frame
 * arrives; it never competes with other threads for the same {@code InputStream}.
 *
 * <p>The owning socket must have {@code SO_TIMEOUT = 0} (no read timeout).
 * Per-request timeouts are managed by the {@link PendingRequestRegistry} via
 * {@code CompletableFuture.orTimeout()}, not by socket-level timeouts.
 *
 * <h2>Confirmations</h2>
 * <ul>
 *   <li>Single-reader contract — only this class reads from the connection's InputStream.</li>
 *   <li>{@code failAll} is guaranteed to run on any read failure.</li>
 *   <li>A malformed frame (decode error) logs a warning and continues the loop
 *       rather than killing the connection — protocol errors are per-frame, not fatal.</li>
 *   <li>An I/O error (EOF, socket reset) is fatal — terminates the loop and calls {@code onDead}.</li>
 * </ul>
 */
public class ReadLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ReadLoop.class);

    private final String connectionId;
    private final InputStream in;
    private final FrameReader frameReader;
    private final IsoMessageDecoder decoder;
    private final PendingRequestRegistry registry;
    private final Runnable onDead;

    public ReadLoop(String connectionId,
                    InputStream in,
                    int maxPayloadBytes,
                    PendingRequestRegistry registry,
                    Runnable onDead) {
        this.connectionId = connectionId;
        this.in           = in;
        this.frameReader  = new FrameReader(maxPayloadBytes);
        this.decoder      = new IsoMessageDecoder();
        this.registry     = registry;
        this.onDead       = onDead;
    }

    @Override
    public void run() {
        log.debug("ReadLoop started for connection {}", connectionId);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                readAndDispatch();
            }
        } catch (EOFException e) {
            log.info("Connection {} closed by server (EOF)", connectionId);
            registry.failAll(e);
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("Connection {} read error: {}", connectionId, e.getMessage());
                registry.failAll(e);
            }
        } finally {
            // Always notify the pool — even on clean shutdown
            onDead.run();
            log.debug("ReadLoop terminated for connection {}", connectionId);
        }
    }

    private void readAndDispatch() throws IOException {
        // FrameReader.read() blocks here until a complete [2-byte header + payload] frame arrives.
        // SO_TIMEOUT = 0 means this call can wait indefinitely — that is intentional.
        Frame frame;
        try {
            frame = frameReader.read(in);
        } catch (ProtocolException e) {
            // Protocol violation in framing is fatal — the stream is unrecoverable
            throw new IOException("Protocol violation in frame header: " + e.getMessage(), e);
        }

        // Decode the ISO 8583 payload
        IsoMessage response;
        try {
            response = decoder.decode(frame.payloadAsString());
        } catch (ProtocolException e) {
            // A malformed ISO payload is a per-frame error; connection remains alive
            log.warn("Connection {} received malformed ISO payload (skipping): {}",
                     connectionId, e.getMessage());
            return;
        }

        // Extract NSU (field 11) — the correlation key
        String nsu = response.getNsu().orElse(null);
        if (nsu == null) {
            log.warn("Connection {} received response without NSU (field 11): MTI={}",
                     connectionId, response.getMessageType().getMti());
            return;
        }

        // Route to the waiting CompletableFuture
        boolean matched = registry.complete(nsu, response);
        if (!matched) {
            // Response for an expired, timed-out, or unknown request
            log.warn("Connection {} received unsolicited/late response: NSU={} MTI={}",
                     connectionId, nsu, response.getMessageType().getMti());
        }
    }
}
