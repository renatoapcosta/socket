package br.com.socker.application.usecase;

import br.com.socker.application.port.in.SendMessageUseCase;
import br.com.socker.application.port.out.AsyncMessageGateway;
import br.com.socker.application.port.out.GatewayException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Use case implementation that drives the async multiplexed gateway.
 *
 * <p>Offers two calling modes:
 * <ol>
 *   <li>{@link #send(IsoMessage)} — satisfies the synchronous {@link SendMessageUseCase} port;
 *       blocks the caller until the future completes (appropriate for migration or test purposes).</li>
 *   <li>{@link #sendAsync(IsoMessage)} — fully non-blocking; returns a
 *       {@link CompletableFuture} that the caller can chain, compose, or await.</li>
 * </ol>
 *
 * <p>Neither mode holds a TCP socket thread while waiting — the underlying
 * {@link AsyncMessageGateway} uses a multiplexed connection where a single
 * Virtual Thread ({@code ReadLoop}) serves all in-flight requests.
 */
public class SendMessageAsyncUseCaseImpl implements SendMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendMessageAsyncUseCaseImpl.class);

    private final AsyncMessageGateway gateway;
    private final long awaitTimeoutMs;

    /**
     * @param gateway       the async transport gateway
     * @param awaitTimeoutMs maximum milliseconds {@link #send} will block before failing;
     *                       should be >= the gateway's per-request timeout
     */
    public SendMessageAsyncUseCaseImpl(AsyncMessageGateway gateway, long awaitTimeoutMs) {
        this.gateway        = gateway;
        this.awaitTimeoutMs = awaitTimeoutMs;
    }

    /**
     * Synchronous facade — sends and blocks until the response is available or the timeout fires.
     *
     * <p>Suitable as a drop-in replacement for {@link SendMessageUseCaseImpl} in existing code.
     */
    @Override
    public TransactionResult send(IsoMessage request) {
        try {
            CompletableFuture<IsoMessage> future = gateway.sendAsync(request);
            IsoMessage response = future.get(awaitTimeoutMs, TimeUnit.MILLISECONDS);
            return TransactionResult.success(response);
        } catch (GatewayException e) {
            return TransactionResult.protocolError(
                "Gateway error [" + e.getReason() + "]: " + e.getMessage());
        } catch (TimeoutException e) {
            return TransactionResult.protocolError(
                "Request timed out after " + awaitTimeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransactionResult.protocolError("Thread interrupted while awaiting response");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("Async request failed exceptionally: {}", cause.getMessage());
            return TransactionResult.protocolError("Async error: " + cause.getMessage());
        }
    }

    /**
     * Fully asynchronous — returns immediately without blocking.
     *
     * <p>The returned future is completed by the {@code ReadLoop} virtual thread
     * when the matching response arrives, or completed exceptionally on timeout
     * ({@code TimeoutException}) or connection failure.
     *
     * <p>Callers must handle both normal and exceptional completion:
     * <pre>{@code
     * useCase.sendAsync(request)
     *         .thenApply(TransactionResult::success)
     *         .exceptionally(ex -> TransactionResult.protocolError(ex.getMessage()))
     *         .thenAccept(result -> log.info("Result: {}", result));
     * }</pre>
     *
     * @throws GatewayException if a synchronous error prevents dispatch (e.g. encoding failure,
     *                           pool exhausted)
     */
    public CompletableFuture<IsoMessage> sendAsync(IsoMessage request) throws GatewayException {
        return gateway.sendAsync(request);
    }
}
