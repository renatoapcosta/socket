package br.com.socker.infrastructure.protocol;

/**
 * Describes how a field participates in a specific message type (MTI).
 *
 * <p>Used by {@link MessageSpec} to express per-MTI field rules, and by
 * {@link ResponseBuilder} to automatically echo fields from a paired request.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@code MANDATORY} — the field must be present; missing it constitutes a protocol error.</li>
 *   <li>{@code OPTIONAL}  — the field may or may not be present; both cases are valid.</li>
 *   <li>{@code ECHO}      — for response messages: the field value must be copied verbatim
 *                           from the corresponding request. If absent in the request,
 *                           it is absent in the response.</li>
 * </ul>
 */
public enum FieldPresence {

    /** Field must be present in the message. */
    MANDATORY,

    /** Field may be present or absent. */
    OPTIONAL,

    /**
     * Field value must mirror the corresponding field in the paired request.
     *
     * <p>Only meaningful for response specs. When the request does not carry
     * the field, the response must also omit it (e.g. optional bit 32 branch code).
     */
    ECHO
}
