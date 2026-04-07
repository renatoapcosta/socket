package br.com.socker.application.usecase;

import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProcessTransactionUseCaseTest {

    private final ProcessTransactionUseCaseImpl useCase = new ProcessTransactionUseCaseImpl();

    @Test
    void process_approvesValidTransaction() {
        IsoMessage request = validRequest();

        TransactionResult result = useCase.process(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasResponse()).isTrue();
        assertThat(result.getResponseMessage().getMessageType())
            .isEqualTo(MessageType.TRANSACTION_RESPONSE);
        assertThat(result.getResponseMessage().getField(39))
            .contains(ResponseCode.APPROVED.getCode());
    }

    @Test
    void process_mirrorsNsuInResponse() {
        IsoMessage request = validRequest();

        TransactionResult result = useCase.process(request);

        assertThat(result.getResponseMessage().getRequiredField(11)).isEqualTo("000001");
    }

    @Test
    void process_mirrorsBranchCodeInResponse() {
        IsoMessage request = validRequest();

        TransactionResult result = useCase.process(request);

        assertThat(result.getResponseMessage().getRequiredField(32)).isEqualTo("12345678901");
    }

    @Test
    void process_rejectsWrongMti() {
        IsoMessage wrongType = IsoMessage.builder(MessageType.REVERSAL_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, "000001")
            .field(12, "123045")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();

        assertThatThrownBy(() -> useCase.process(wrongType))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("Expected 0200");
    }

    @Test
    void process_rejectsMissingNsu() {
        IsoMessage missingNsu = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            // bit 11 (NSU) intentionally missing
            .field(12, "123045")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();

        assertThatThrownBy(() -> useCase.process(missingNsu))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit 11");
    }

    @Test
    void process_populatesLocalTimeInResponse() {
        IsoMessage request = validRequest();

        TransactionResult result = useCase.process(request);

        // Local time should be a 6-digit string in hhmmss format
        String localTime = result.getResponseMessage().getRequiredField(12);
        assertThat(localTime).hasSize(6).containsOnlyDigits();
    }

    // --- helper ---

    private IsoMessage validRequest() {
        return IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, "000001")
            .field(12, "123045")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();
    }
}
