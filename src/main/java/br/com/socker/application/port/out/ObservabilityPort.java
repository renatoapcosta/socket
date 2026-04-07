package br.com.socker.application.port.out;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;

/**
 * Port OUT — observability abstraction for recording metrics and structured events.
 *
 * <p>Use cases emit events through this port. The adapter implementation
 * decides how to record them (Micrometer, OpenTelemetry, log, etc.).
 *
 * <p>The domain and application layer never import logging or metrics libraries directly.
 */
public interface ObservabilityPort {

    /**
     * Record a completed transaction processing cycle.
     *
     * @param request         the incoming request message
     * @param result          the resulting response and outcome
     * @param processingTimeMs elapsed time in milliseconds
     */
    void recordTransaction(IsoMessage request, TransactionResult result, long processingTimeMs);

    /**
     * Record a transport-level error.
     *
     * @param connectionId unique ID for the connection that failed
     * @param reason       error description
     * @param cause        underlying exception (may be null)
     */
    void recordTransportError(String connectionId, String reason, Throwable cause);

    /**
     * Record a connection lifecycle event (accepted, closed, timeout, etc.).
     *
     * @param connectionId  unique connection identifier
     * @param remoteAddress remote socket address string
     * @param event         lifecycle event label
     */
    void recordConnectionEvent(String connectionId, String remoteAddress, String event);
}
