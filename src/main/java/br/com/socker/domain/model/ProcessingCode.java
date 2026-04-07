package br.com.socker.domain.model;

/**
 * ISO 8583 Field 3 — Processing Code.
 *
 * <p>6-digit code that identifies the transaction type.
 * Used in 0200, 0202, 0420, 9100, 9300, 0600 messages.
 */
public enum ProcessingCode {

    RECHARGE("100000", "Recarga de telefone"),
    RECHARGE_DEBIT("100010", "Recarga Débito"),
    RECHARGE_CREDIT("100020", "Recarga Crédito"),
    PHONE_WITHDRAWAL("600000", "Saque com telefone"),
    INVOICE_PAYMENT("900200", "Pagamento de Faturas"),
    INVOICE_QUERY("900100", "Consulta de Faturas"),
    PARAMETER_QUERY("091000", "Consulta de Parâmetros");

    private final String code;
    private final String description;

    ProcessingCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ProcessingCode fromCode(String code) {
        for (ProcessingCode pc : values()) {
            if (pc.code.equals(code)) {
                return pc;
            }
        }
        throw new IllegalArgumentException("Unknown processing code: " + code);
    }

    @Override
    public String toString() {
        return code;
    }
}
