package br.com.socker.application.port.out;

import br.com.socker.domain.model.IsoMessage;

import java.util.concurrent.CompletableFuture;

/**
 * Port OUT — asynchronous gateway for sending ISO 8583 messages over a multiplexed TCP channel.
 *
 * <p>Unlike {@link MessageGateway}, this port does not block the calling thread while waiting
 * for the server response. The caller receives a {@link CompletableFuture} immediately and
 * attaches callbacks or chains async stages without holding a thread.
 *
 * <p>Implementations live in the adapter layer ({@code adapter.out.socket.client}).
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>Transport errors that are detected synchronously (e.g. pool exhausted, dead connection)
 *       are thrown as {@link GatewayException} before the future is returned.</li>
 *   <li>Errors detected asynchronously (e.g. response timeout, connection death mid-flight)
 *       complete the returned future exceptionally.</li>
 * </ul>
 */
public interface AsyncMessageGateway {

    /**
     * Send a request message asynchronously.
     *
     * <p>The method returns immediately. The returned future is completed by the
     * connection's {@code ReadLoop} when the matching response arrives (correlated by NSU / field 11),
     * or completed exceptionally on timeout or connection failure.
     *
     * @param request the ISO 8583 message to transmit; must have a unique NSU (field 11)
     * @return a future that completes with the decoded response message
     * @throws GatewayException if a synchronous transport error prevents the message from being sent
     */
    CompletableFuture<IsoMessage> sendAsync(IsoMessage request) throws GatewayException;
}
