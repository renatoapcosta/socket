package br.com.socker.adapter.out.logging;

import br.com.socker.application.port.out.ObservabilityPort;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Adapter OUT — implements {@link ObservabilityPort} using SLF4J with MDC fields.
 *
 * <p>Structured fields emitted per event:
 * <ul>
 *   <li>{@code mti} — ISO 8583 message type indicator</li>
 *   <li>{@code nsu} — system trace audit number (field 11)</li>
 *   <li>{@code originCode} — field 42</li>
 *   <li>{@code branchCode} — field 32</li>
 *   <li>{@code responseCode} — field 39</li>
 *   <li>{@code processingTimeMs} — total handler duration</li>
 *   <li>{@code status} — "success" or "failure"</li>
 *   <li>{@code connectionId} — unique ID assigned to the socket</li>
 *   <li>{@code remoteAddress} — peer IP:port</li>
 * </ul>
 *
 * <p>Payload contents are NOT logged at INFO level to protect sensitive data.
 * Enable {@code log.debug.payload=true} in application.properties for DEBUG logging.
 */
public class StructuredObservabilityAdapter implements ObservabilityPort {

    private static final Logger log = LoggerFactory.getLogger(StructuredObservabilityAdapter.class);

    private final boolean debugPayloadEnabled;

    public StructuredObservabilityAdapter(boolean debugPayloadEnabled) {
        this.debugPayloadEnabled = debugPayloadEnabled;
    }

    @Override
    public void recordTransaction(IsoMessage request, TransactionResult result, long processingTimeMs) {
        try {
            MDC.put("mti",              request.getMessageType().getMti());
            MDC.put("nsu",              request.getNsu().orElse("-"));
            MDC.put("originCode",       request.getOriginCode().orElse("-"));
            MDC.put("branchCode",       request.getBranchCode().orElse("-"));
            MDC.put("processingTimeMs", String.valueOf(processingTimeMs));
            MDC.put("status",           result.isSuccess() ? "success" : "failure");

            if (result.hasResponse()) {
                result.getResponseMessage()
                      .getField(39)
                      .ifPresent(rc -> MDC.put("responseCode", rc));
            }

            if (result.isSuccess()) {
                log.info("Transaction processed");
            } else {
                log.warn("Transaction failed: {}", result.getErrorMessage().orElse("unknown"));
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void recordTransportError(String connectionId, String reason, Throwable cause) {
        try {
            MDC.put("connectionId", connectionId);
            MDC.put("errorType",    "TRANSPORT");
            if (cause != null) {
                log.error("Transport error on connection {}: {}", connectionId, reason, cause);
            } else {
                log.error("Transport error on connection {}: {}", connectionId, reason);
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void recordConnectionEvent(String connectionId, String remoteAddress, String event) {
        try {
            MDC.put("connectionId",  connectionId);
            MDC.put("remoteAddress", remoteAddress);
            MDC.put("event",         event);
            log.info("Connection event: {}", event);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Log the raw payload in DEBUG mode only. Called from the connection handler
     * when {@code log.debug.payload=true}. The payload is hex-dumped to avoid
     * issues with non-printable characters.
     */
    public void debugPayload(String direction, String connectionId, byte[] payload) {
        if (!debugPayloadEnabled || !log.isDebugEnabled()) return;
        MDC.put("connectionId", connectionId);
        MDC.put("direction",    direction);
        MDC.put("payloadLen",   String.valueOf(payload.length));
        try {
            log.debug("Payload hex: {}", toHex(payload));
        } finally {
            MDC.clear();
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
