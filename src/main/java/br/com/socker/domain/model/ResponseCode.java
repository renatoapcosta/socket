package br.com.socker.domain.model;

/**
 * ISO 8583 Field 39 — Response Code.
 *
 * <p>2-character code indicating the result of a transaction.
 * Includes codes originated by GwCel, branch systems, and authorization systems.
 */
public enum ResponseCode {

    // Common approval codes
    APPROVED("00", "Transação Aprovada", true),
    APPROVED_MORE_INVOICES("02", "Transação Aprovada - há mais faturas em aberto", true),
    APPROVED_NO_INVOICES("22", "Não há faturas em aberto", true),

    // Rejection codes
    INVALID_ESTABLISHMENT("03", "Código de Estabelecimento inválido", false),
    PROCESSING_ERROR("06", "Erro no Processamento", false),
    TRANSACTION_IN_PROGRESS("09", "Transação em processamento", false),
    INVOICE_ALREADY_PAID("19", "Pagamento da fatura em aberto já foi efetuado", false),
    PENDING_CONFIRMATION("77", "Pendente de Confirmação", false),
    TRANSACTION_NOT_FOUND("80", "Transação Inexistente", false),
    TRANSACTION_UNDONE("86", "Transação Desfeita", false),

    // N-series: Branch rejection codes
    RECHARGE_NOT_ALLOWED("N0", "Recarga não permitida", false),
    BRANCH_PROCESSING_ERROR("N1", "Erro no tratamento na Filial", false),
    INVALID_PHONE_CODE("N2", "Código Telefone/Inválido", false),
    VALUE_NOT_ALLOWED("N3", "Valor não Permitido", false),
    PIN_NOT_AVAILABLE("N4", "PIN não Disponível", false),
    ESTABLISHMENT_BLOCKED("N5", "Estab. bloqueado pela Filial", false),
    INVALID_ORIGINAL_DATA("N7", "Dados originais inválidos (UpSell)", false),
    RESERVED_GWCEL("N8", "Reservado GwCel", false),

    // UpSell
    UPSELL_ACCEPTED("DU", "Desistência devido a UpSell", false),

    // Withdrawal specific
    INSUFFICIENT_BALANCE("C8", "Saldo insuficiente", false),
    INVALID_PASSWORD("C9", "Senha inválida", false),

    // G-series: GwCel system codes
    UNCATALOGUED_ERROR("G0", "Erro não catalogado", false),
    NO_BRANCH_COMMUNICATION("G1", "Sem comunicação com Filial", false),
    INVALID_INTERFACE("G2", "Interface inválida", false),
    COMMUNICATION_CONGESTION("G3", "Congestionamento comunicação c/ Filial", false),

    // S-series: GwCel system codes
    INVALID_PARAMETER_VERSION("S0", "Versão de Parâmetros Inválida", false),
    MESSAGE_FORMAT_ERROR("S2", "Erro de formato na mensagem", false),
    INVALID_BRANCH_CODE("S3", "Código de Filial inválido", false),
    ESTABLISHMENT_NOT_REGISTERED("S4", "Cód. De Estabelecimento não cadastrado", false),
    INVALID_FRAGMENT("S5", "Fragmento inválido", false),
    INVALID_PROCESSING_CODE("S6", "Código de processamento inválido", false),
    UNHANDLED_ISO_CODE("S7", "Código ISO8583 não tratado", false),
    INCOHERENT_DATA("S8", "Dados incoerentes", false),
    INVALID_TERMINAL("S9", "Terminal inválido", false),
    BRANCH_NO_PARAMETERS("SA", "Filial / Regional sem Parâmetros", false),

    // A-series: Authorizer codes
    AUTHORIZER_UNAVAILABLE("A0", "Autorizador de Estab. Indisponível", false),
    ESTABLISHMENT_BLOCKED_AUTHORIZER("A1", "Estab. bloqueado pelo Autorizador", false),
    INVALID_ESTABLISHMENT_AUTHORIZER("A2", "Cód. de Estab. inválido para Autorizador", false),
    BLOCKED_BY_AUTHORIZER("BZ", "Operação Bloqueada pelo Autorizador", false);

    private final String code;
    private final String description;
    private final boolean approved;

    ResponseCode(String code, String description, boolean approved) {
        this.code = code;
        this.description = description;
        this.approved = approved;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isApproved() {
        return approved;
    }

    public static ResponseCode fromCode(String code) {
        for (ResponseCode rc : values()) {
            if (rc.code.equals(code)) {
                return rc;
            }
        }
        throw new IllegalArgumentException("Unknown response code: " + code);
    }

    public static boolean isKnownCode(String code) {
        for (ResponseCode rc : values()) {
            if (rc.code.equals(code)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return code;
    }
}
