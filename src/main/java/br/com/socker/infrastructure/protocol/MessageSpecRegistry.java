package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.MessageType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of {@link MessageSpec} definitions for all GwCel message types.
 *
 * <p>Each entry is derived directly from the GwCel Interface Specification
 * version 006, edition 26. Request and response messages have independent specs
 * — they do not share field lists.
 *
 * <h2>Field presence legend</h2>
 * <ul>
 *   <li>{@code mandatory(N)} — field N must be present in every valid message of this type.</li>
 *   <li>{@code optional(N)}  — field N may be present or absent.</li>
 *   <li>{@code echo(N)}      — for response messages: copy field N verbatim from the request;
 *                              if absent in request, omit in response.</li>
 * </ul>
 *
 * <h2>Separation of request and response</h2>
 * <p>A 0200 request and its paired 0210 response are registered under different
 * {@link MessageType} keys and have completely independent field lists. The response
 * spec uses {@code echo} to indicate which bits must be mirrored — the assembler
 * ({@link ResponseBuilder}) performs the actual copying.
 */
public final class MessageSpecRegistry {

    private static final Map<MessageType, MessageSpec> SPECS;

    static {
        Map<MessageType, MessageSpec> m = new EnumMap<>(MessageType.class);

        // ─────────────────────────────────────────────────────────────────────
        // 0200  Transaction Request  (Concentrador → GwCel)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.TRANSACTION_REQUEST,
            MessageSpec.builder(MessageType.TRANSACTION_REQUEST)
                .mandatory(3)   // Processing Code
                .mandatory(4)   // Amount (centavos)
                .mandatory(7)   // Transmission DateTime MMDDhhmmss
                .mandatory(11)  // NSU (6 digits)
                .mandatory(12)  // Local Time hhmmss
                .mandatory(13)  // Local Date MMDD
                .mandatory(32)  // Branch Code (LL-VAR)
                .mandatory(40)  // Interface Version (006)
                .mandatory(41)  // Terminal ID
                .mandatory(42)  // Origin Code
                .mandatory(49)  // Currency Code (986)
                .optional(61)   // Value key / recharge info
                .optional(62)   // Request data
                .optional(63)   // Parameter versions sent by concentrador
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 0210  Transaction Response  (GwCel → Concentrador)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.TRANSACTION_RESPONSE,
            MessageSpec.builder(MessageType.TRANSACTION_RESPONSE)
                .echo(3)        // Processing Code — verbatim from 0200
                .echo(4)        // Amount — verbatim from 0200
                .echo(7)        // Transmission DateTime — verbatim from 0200
                .echo(11)       // NSU — verbatim from 0200
                .mandatory(12)  // Local Time (response server time)
                .mandatory(13)  // Local Date (response server date)
                .echo(32)       // Branch Code — echo if present in 0200
                .mandatory(39)  // Response Code
                .echo(41)       // Terminal ID — verbatim from 0200
                .echo(42)       // Origin Code — verbatim from 0200
                .optional(48)   // Authorization Code from Filial (when approved)
                .echo(49)       // Currency Code — verbatim from 0200
                .optional(58)   // Additional info
                .optional(62)   // Receipt / recharge info, or error message
                .optional(63)   // Parameter versions from GwCel (VGP/VFP)
                .optional(99)   // Additional info
                .optional(120)  // Recharge info
                .optional(127)  // NSU Filial or NSU GwCel (9 digits)
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 0202  Transaction Confirmation  (Concentrador → GwCel)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.TRANSACTION_CONFIRMATION,
            MessageSpec.builder(MessageType.TRANSACTION_CONFIRMATION)
                .mandatory(3)
                .mandatory(4)
                .mandatory(7)
                .mandatory(11)
                .mandatory(12)
                .mandatory(13)
                .mandatory(32)
                .mandatory(39)
                .mandatory(40)
                .mandatory(41)
                .mandatory(42)
                .mandatory(49)
                .optional(61)   // Payment form data
                .optional(99)   // Additional info (from 0210)
                .optional(127)  // NSU GwCel echoed from 0210
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 0420  Reversal Request  (Concentrador → GwCel)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.REVERSAL_REQUEST,
            MessageSpec.builder(MessageType.REVERSAL_REQUEST)
                .mandatory(3)
                .mandatory(4)
                .mandatory(7)
                .mandatory(11)
                .mandatory(12)
                .mandatory(13)
                .mandatory(32)
                .mandatory(39)
                .mandatory(40)
                .mandatory(41)
                .mandatory(42)
                .mandatory(49)
                .mandatory(90)  // Original transaction data (0200 fields compressed)
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 0430  Reversal Response  (GwCel → Concentrador)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.REVERSAL_RESPONSE,
            MessageSpec.builder(MessageType.REVERSAL_RESPONSE)
                .echo(3)        // Processing Code — from 0420 / original 0200
                .echo(4)        // Amount — from 0420 / original 0200
                .echo(7)        // Transmission DateTime — from 0420
                .echo(11)       // NSU — from 0420
                .echo(32)       // Branch Code — from 0420
                .mandatory(39)  // Response Code
                .echo(41)       // Terminal ID
                .echo(42)       // Origin Code
                .echo(49)       // Currency Code
                .echo(90)       // Original transaction data
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 0600  Probe Request  (GwCel → Concentrador)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.PROBE_REQUEST,
            MessageSpec.builder(MessageType.PROBE_REQUEST)
                .mandatory(3)
                .mandatory(7)
                .mandatory(11)
                .mandatory(12)
                .mandatory(13)
                .optional(32)   // Optional branch code
                .optional(41)   // Optional terminal ID
                .mandatory(42)
                .optional(99)
                .optional(125)  // Original NSU sequential
                .optional(127)  // NSU Filial (9 digits)
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 0610  Probe Response  (Concentrador → GwCel)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.PROBE_RESPONSE,
            MessageSpec.builder(MessageType.PROBE_RESPONSE)
                .echo(3)
                .echo(7)
                .echo(11)
                .mandatory(12)
                .mandatory(13)
                .echo(32)
                .mandatory(39)
                .mandatory(40)
                .echo(41)
                .echo(42)
                .optional(61)   // Payment form data
                .echo(99)
                .echo(125)
                .echo(127)
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 9100  Parameter Query Request  (Concentrador → GwCel)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.PARAMETER_QUERY_REQUEST,
            MessageSpec.builder(MessageType.PARAMETER_QUERY_REQUEST)
                .mandatory(3)   // Processing Code 091000
                .mandatory(7)
                .mandatory(11)
                .mandatory(12)
                .mandatory(13)
                .optional(32)   // Branch for which update is requested
                .mandatory(40)
                .mandatory(42)
                .mandatory(49)
                .optional(62)   // Additional query info
                .optional(63)   // Query data
                .optional(71)   // Parameter query sequential
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 9110  Parameter Query Response  (GwCel → Concentrador)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.PARAMETER_QUERY_RESPONSE,
            MessageSpec.builder(MessageType.PARAMETER_QUERY_RESPONSE)
                .echo(3)
                .echo(7)
                .echo(11)
                .mandatory(12)
                .mandatory(13)
                .echo(32)
                .mandatory(39)
                .echo(42)
                .echo(49)
                .optional(62)   // Error message (on failure)
                .optional(63)   // Parameter data payload
                .optional(71)   // Sequential echoed
                .optional(127)  // NSU GwCel (9 digits)
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 9300  Invoice Query Request  (Concentrador → GwCel)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.INVOICE_QUERY_REQUEST,
            MessageSpec.builder(MessageType.INVOICE_QUERY_REQUEST)
                .mandatory(3)
                .mandatory(7)
                .mandatory(11)
                .mandatory(12)
                .mandatory(13)
                .mandatory(32)
                .mandatory(40)
                .mandatory(41)
                .mandatory(42)
                .optional(62)   // Invoice query request data
                .optional(63)   // Parameter versions
                .build());

        // ─────────────────────────────────────────────────────────────────────
        // 9310  Invoice Query Response  (GwCel → Concentrador)
        // ─────────────────────────────────────────────────────────────────────
        m.put(MessageType.INVOICE_QUERY_RESPONSE,
            MessageSpec.builder(MessageType.INVOICE_QUERY_RESPONSE)
                .echo(3)
                .echo(7)
                .echo(11)
                .mandatory(12)
                .mandatory(13)
                .echo(32)
                .mandatory(39)
                .echo(41)
                .echo(42)
                .optional(58)   // Additional info
                .optional(62)   // Invoice data
                .optional(63)   // Parameter versions
                .optional(127)  // NSU Filial or NSU GwCel (9 digits)
                .build());

        SPECS = Collections.unmodifiableMap(m);
    }

    private MessageSpecRegistry() {}

    /**
     * Returns the spec for the given message type, or {@link Optional#empty()} if unknown.
     */
    public static Optional<MessageSpec> get(MessageType messageType) {
        return Optional.ofNullable(SPECS.get(messageType));
    }

    /**
     * Returns the spec for the given message type.
     *
     * @throws IllegalArgumentException if no spec is registered for the type
     */
    public static MessageSpec getRequired(MessageType messageType) {
        return get(messageType).orElseThrow(() ->
            new IllegalArgumentException(
                "No MessageSpec registered for MTI: " + messageType.getMti()));
    }

    /** Returns the full registry (unmodifiable) — for inspection and testing. */
    public static Map<MessageType, MessageSpec> all() {
        return SPECS;
    }
}
