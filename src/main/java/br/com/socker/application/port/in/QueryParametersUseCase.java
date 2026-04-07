package br.com.socker.application.port.in;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;

/**
 * Port IN — use case for processing parameter queries (9100/9110) and invoice queries (9300/9310).
 *
 * <p>The parameter query synchronizes the parameter table (TP) between the Concentrador and GwCel.
 * The invoice query retrieves open invoices for payment.
 */
public interface QueryParametersUseCase {

    /**
     * Process a 9100 parameter query request and return a 9110 response.
     *
     * @param request the decoded ISO 8583 9100 message
     * @return result containing the 9110 response
     */
    TransactionResult processParameterQuery(IsoMessage request);

    /**
     * Process a 9300 invoice query request and return a 9310 response.
     *
     * @param request the decoded ISO 8583 9300 message
     * @return result containing the 9310 response
     */
    TransactionResult processInvoiceQuery(IsoMessage request);
}
