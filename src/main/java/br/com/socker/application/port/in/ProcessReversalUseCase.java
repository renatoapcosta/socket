package br.com.socker.application.port.in;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.TransactionResult;

/**
 * Port IN — use case for processing a reversal request (0420/0430).
 *
 * <p>Reversals are sent when a 0200 request timed out and the transaction state
 * is uncertain. The use case must inform GwCel that the original transaction
 * should not be committed.
 */
public interface ProcessReversalUseCase {

    /**
     * Process a 0420 reversal request and return a 0430 response.
     *
     * @param request the decoded ISO 8583 0420 message
     * @return result containing the 0430 response
     */
    TransactionResult process(IsoMessage request);
}
