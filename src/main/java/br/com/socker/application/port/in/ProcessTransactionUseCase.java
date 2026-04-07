package br.com.socker.application.port.in;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;

/**
 * Port IN — use case for processing a financial transaction (0200/0210).
 *
 * <p>Handles: Recarga de telefone, venda de PIN, TV pré-paga, pagamento de fatura,
 * saque com telefone, TUP Virtual, recarga de serviços.
 *
 * <p>The implementing use case must NOT depend on Socket, ServerSocket, or any transport class.
 */
public interface ProcessTransactionUseCase {

    /**
     * Process an incoming 0200 transaction request and return a 0210 response.
     *
     * @param request the decoded ISO 8583 0200 message
     * @return result containing the 0210 response message
     */
    TransactionResult process(IsoMessage request);
}
