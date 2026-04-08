package br.com.socker.application.usecase;

import br.com.socker.application.port.out.ConcentratorGateway;
import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.exception.NoActiveConnectionException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DispatchMessageUseCaseImpl}.
 *
 * <p>Uses a hand-written stub for {@link ConcentratorGateway} — avoids Mockito's
 * bytecode instrumentation limitations on Java 25 with {@code --enable-preview}.
 */
class DispatchMessageUseCaseImplTest {

    /** Stub that records every send call and can be configured to throw. */
    static class GatewayStub implements ConcentratorGateway {
        final List<IsoMessage> received = new ArrayList<>();
        RuntimeException throwOnSend = null;

        @Override
        public void send(IsoMessage message) {
            if (throwOnSend != null) throw throwOnSend;
            received.add(message);
        }

        boolean wasSent() { return !received.isEmpty(); }
        IsoMessage lastMessage() { return received.get(received.size() - 1); }
    }

    private GatewayStub gateway;
    private DispatchMessageUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        gateway = new GatewayStub();
        useCase = new DispatchMessageUseCaseImpl(gateway);
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    void dispatch_validProbe_sendsMessage() {
        IsoMessage probe = valid0600();

        useCase.dispatch(probe);

        assertThat(gateway.wasSent()).isTrue();
        assertThat(gateway.lastMessage()).isSameAs(probe);
    }

    // ─── MTI validation ───────────────────────────────────────────────────────

    @Test
    void dispatch_rejects_mti_0200() {
        IsoMessage wrong = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3, "100000").build();

        assertThatThrownBy(() -> useCase.dispatch(wrong))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("not supported");

        assertThat(gateway.wasSent()).isFalse();
    }

    @Test
    void dispatch_rejects_mti_0420() {
        IsoMessage wrong = IsoMessage.builder(MessageType.REVERSAL_REQUEST)
            .field(3, "100000").build();

        assertThatThrownBy(() -> useCase.dispatch(wrong))
            .isInstanceOf(InvalidMessageException.class);

        assertThat(gateway.wasSent()).isFalse();
    }

    // ─── Required field validation ────────────────────────────────────────────

    @Test
    void dispatch_rejects_missing_bit003() {
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            // bit 3 missing
            .field(7, "0327162336").field(11, "132256").field(12, "162338")
            .field(13, "0327").field(42, "644400000000001")
            .field(125, "000132256").field(127, "000248756")
            .build();

        assertThatThrownBy(() -> useCase.dispatch(msg))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit_003");

        assertThat(gateway.wasSent()).isFalse();
    }

    @Test
    void dispatch_rejects_missing_bit007() {
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3, "100000")
            // bit 7 missing
            .field(11, "132256").field(12, "162338").field(13, "0327")
            .field(42, "644400000000001").field(125, "000132256").field(127, "000248756")
            .build();

        assertThatThrownBy(() -> useCase.dispatch(msg))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit_007");
    }

    @Test
    void dispatch_rejects_missing_bit011() {
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3, "100000").field(7, "0327162336")
            // bit 11 missing
            .field(12, "162338").field(13, "0327")
            .field(42, "644400000000001").field(125, "000132256").field(127, "000248756")
            .build();

        assertThatThrownBy(() -> useCase.dispatch(msg))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit_011");
    }

    @Test
    void dispatch_rejects_missing_bit042() {
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3, "100000").field(7, "0327162336").field(11, "132256")
            .field(12, "162338").field(13, "0327")
            // bit 42 missing
            .field(125, "000132256").field(127, "000248756")
            .build();

        assertThatThrownBy(() -> useCase.dispatch(msg))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit_042");
    }

    @Test
    void dispatch_rejects_missing_bit125() {
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3, "100000").field(7, "0327162336").field(11, "132256")
            .field(12, "162338").field(13, "0327").field(42, "644400000000001")
            // bit 125 missing
            .field(127, "000248756")
            .build();

        assertThatThrownBy(() -> useCase.dispatch(msg))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit_125");
    }

    @Test
    void dispatch_rejects_missing_bit127() {
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3, "100000").field(7, "0327162336").field(11, "132256")
            .field(12, "162338").field(13, "0327").field(42, "644400000000001")
            .field(125, "000132256")
            // bit 127 missing
            .build();

        assertThatThrownBy(() -> useCase.dispatch(msg))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit_127");
    }

    @Test
    void dispatch_accepts_optional_bits_absent() {
        // bits 32 and 41 are optional — must succeed when absent
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3,   "100000").field(7,   "0327162336").field(11,  "132256")
            .field(12,  "162338").field(13,  "0327")
            // no bit 32, no bit 41
            .field(42,  "644400000000001").field(125, "000132256").field(127, "000248756")
            .build();

        assertThatCode(() -> useCase.dispatch(msg)).doesNotThrowAnyException();
        assertThat(gateway.wasSent()).isTrue();
    }

    @Test
    void dispatch_accepts_optional_bits_present() {
        useCase.dispatch(valid0600()); // includes bit 32 and 41
        assertThat(gateway.wasSent()).isTrue();
    }

    // ─── Gateway exceptions propagate ────────────────────────────────────────

    @Test
    void dispatch_propagates_noActiveConnection() {
        gateway.throwOnSend = new NoActiveConnectionException();

        assertThatThrownBy(() -> useCase.dispatch(valid0600()))
            .isInstanceOf(NoActiveConnectionException.class);
    }

    @Test
    void dispatch_propagates_queueFull() {
        gateway.throwOnSend = new ConnectionQueueFullException("conn-1");

        assertThatThrownBy(() -> useCase.dispatch(valid0600()))
            .isInstanceOf(ConnectionQueueFullException.class);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private IsoMessage valid0600() {
        return IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3,   "100000")
            .field(7,   "0327162336")
            .field(11,  "132256")
            .field(12,  "162338")
            .field(13,  "0327")
            .field(32,  "00101000000")
            .field(41,  "GT000001")
            .field(42,  "644400000000001")
            .field(125, "000132256")
            .field(127, "000248756")
            .build();
    }
}
