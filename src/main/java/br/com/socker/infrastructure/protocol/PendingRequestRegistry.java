package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Correlates outbound ISO 8583 requests with their asynchronous responses.
 *
 * <p>One instance per {@link br.com.socker.adapter.out.connectionpool.MultiplexedConnection}.
 *
 * <p>Lifecycle of a request:
 * <ol>
 *   <li>{@link #register(String)} — called before the frame is sent; stores a
 *       {@link CompletableFuture} keyed by NSU and arms the per-request timeout.</li>
 *   <li>{@link #complete(String, IsoMessage)} — called by the {@code ReadLoop}
 *       when the matching response arrives.</li>
 *   <li>On any completion (success, timeout, explicit failure) the entry is
 *       removed automatically via {@code whenComplete}.</li>
 * </ol>
 *
 * <h2>Confirmations</h2>
 * <ul>
 *   <li>Rejects duplicate NSU — {@link DuplicateNsuException} if NSU already in flight.</li>
 *   <li>Per-request timeout via {@code CompletableFuture.orTimeout()}.</li>
 *   <li>{@link #failAll(Throwable)} fails every pending future on connection close.</li>
 * </ul>
 */
public class PendingRequestRegistry {

    private static final Logger log = LoggerFactory.getLogger(PendingRequestRegistry.class);

    private final ConcurrentHashMap<String, CompletableFuture<IsoMessage>> pending =
            new ConcurrentHashMap<>();

    private final long requestTimeoutMs;

    public PendingRequestRegistry(long requestTimeoutMs) {
        if (requestTimeoutMs <= 0) {
            throw new IllegalArgumentException("requestTimeoutMs must be > 0");
        }
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /**
     * Register a pending request for the given NSU.
     *
     * <p>Returns a {@link CompletableFuture} that will be completed when
     * the matching response arrives or when the timeout fires.
     *
     * @throws DuplicateNsuException if a request with the same NSU is already in flight
     */
    public CompletableFuture<IsoMessage> register(String nsu) {
        CompletableFuture<IsoMessage> future = new CompletableFuture<>();

        // Reject if NSU is already in flight — prevents silent response misrouting
        CompletableFuture<IsoMessage> existing = pending.putIfAbsent(nsu, future);
        if (existing != null) {
            throw new DuplicateNsuException("NSU already in flight: " + nsu);
        }

        // Auto-cleanup on any completion (success, timeout, explicit failure)
        future.whenComplete((msg, ex) -> {
            pending.remove(nsu, future);
            if (ex != null) {
                log.debug("Pending NSU={} completed exceptionally: {}", nsu, ex.getMessage());
            }
        });

        // Arm per-request timeout — does NOT affect the socket or the ReadLoop
        future.orTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS);

        return future;
    }

    /**
     * Deliver a response to the caller waiting for {@code nsu}.
     *
     * @return {@code true} if a pending request was found and completed;
     *         {@code false} if the NSU is unknown (expired, cancelled, or duplicate response)
     */
    public boolean complete(String nsu, IsoMessage response) {
        CompletableFuture<IsoMessage> future = pending.get(nsu);
        if (future == null) {
            return false;
        }
        return future.complete(response);
    }

    /**
     * Fail every pending future with the given cause.
     *
     * <p>Called when the owning connection dies (EOF, IOException, or explicit close).
     * The {@code whenComplete} handlers will clean up the map entries.
     */
    public void failAll(Throwable cause) {
        // Snapshot to avoid ConcurrentModificationException during iteration
        CompletableFuture<?>[] snapshot = pending.values().toArray(new CompletableFuture[0]);
        for (CompletableFuture<?> f : snapshot) {
            f.completeExceptionally(cause);
        }
        log.debug("failAll invoked — failed {} pending requests", snapshot.length);
    }

    /** Number of requests currently awaiting a response. */
    public int pendingCount() {
        return pending.size();
    }

    // -------------------------------------------------------------------------

    /** Thrown when a request with the same NSU is already registered. */
    public static class DuplicateNsuException extends RuntimeException {
        public DuplicateNsuException(String message) {
            super(message);
        }
    }
}
