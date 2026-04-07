package br.com.socker.domain.model;

/**
 * ISO 8583 Message Type Indicator (MTI) values used by GwCel interface.
 *
 * <p>The MTI is a 4-digit code at the start of every ISO 8583 message.
 * Each value identifies the purpose and direction of the message.
 */
public enum MessageType {

    // Financial Transaction (Recarga / Pagamento / Saque)
    TRANSACTION_REQUEST("0200"),
    TRANSACTION_RESPONSE("0210"),
    TRANSACTION_CONFIRMATION("0202"),

    // Reversal (Desfazimento)
    REVERSAL_REQUEST("0420"),
    REVERSAL_RESPONSE("0430"),

    // Probe / Status query (Sonda)
    PROBE_REQUEST("0600"),
    PROBE_RESPONSE("0610"),

    // Parameter query (Consulta de Parâmetros)
    PARAMETER_QUERY_REQUEST("9100"),
    PARAMETER_QUERY_RESPONSE("9110"),

    // Invoice query (Consulta de Faturas)
    INVOICE_QUERY_REQUEST("9300"),
    INVOICE_QUERY_RESPONSE("9310");

    private final String mti;

    MessageType(String mti) {
        this.mti = mti;
    }

    public String getMti() {
        return mti;
    }

    public static MessageType fromMti(String mti) {
        for (MessageType type : values()) {
            if (type.mti.equals(mti)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MTI: " + mti);
    }

    public boolean isRequest() {
        return switch (this) {
            case TRANSACTION_REQUEST, REVERSAL_REQUEST, PROBE_REQUEST,
                 PARAMETER_QUERY_REQUEST, INVOICE_QUERY_REQUEST,
                 TRANSACTION_CONFIRMATION -> true;
            default -> false;
        };
    }

    public boolean isResponse() {
        return !isRequest();
    }

    @Override
    public String toString() {
        return mti;
    }
}
