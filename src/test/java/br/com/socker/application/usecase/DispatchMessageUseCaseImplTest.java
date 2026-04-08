package br.com.socker.application.usecase;

import br.com.socker.application.port.out.SessionGateway;
import br.com.socker.domain.exception.InvalidMessageException;
import br.com.socker.domain.exception.SessionNotFoundException;
import br.com.socker.domain.exception.SessionQueueFullException;
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
 * <p>Uses a hand-written stub for {@link SessionGateway} — avoids Mockito's
 * bytecode instrumentation limitations on Java 25 with {@code --enable-preview}.
 */
class DispatchMessageUseCaseImplTest {

    /** Stub that records every enqueue call and can be configured to throw. */
    static class GatewayStub implements SessionGateway {
        final List<IsoMessage> received = new ArrayList<>();
        final List<String> sessionIds   = new ArrayList<>();
        RuntimeException throwOnEnqueue = null;

        @Override
        public void enqueue(String sessionId, IsoMessage message) {
            if (throwOnEnqueue != null) throw throwOnEnqueue;
            sessionIds.add(sessionId);
            received.add(message);
        }

        boolean wasEnqueued() { return !received.isEmpty(); }
        IsoMessage lastMessage() { return received.get(received.size() - 1); }
        String lastSessionId() { return sessionIds.get(sessionIds.size() - 1); }
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
    void dispatch_validProbe_enqueuesMessage() {
        IsoMessage probe = valid0600();

        useCase.dispatch("session-1", probe);

        assertThat(gateway.wasEnqueued()).isTrue();
        assertThat(gateway.lastMessage()).isSameAs(probe);
    }

    @Test
    void dispatch_passesCorrectSessionId() {
        useCase.dispatch("my-session-abc", valid0600());

        assertThat(gateway.lastSessionId()).isEqualTo("my-session-abc");
    }

    // ─── MTI validation ───────────────────────────────────────────────────────

    @Test
    void dispatch_rejects_mti_0200() {
        IsoMessage wrong = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3, "100000").build();

        assertThatThrownBy(() -> useCase.dispatch("s1", wrong))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("not supported");

        assertThat(gateway.wasEnqueued()).isFalse();
    }

    @Test
    void dispatch_rejects_mti_0420() {
        IsoMessage wrong = IsoMessage.builder(MessageType.REVERSAL_REQUEST)
            .field(3, "100000").build();

        assertThatThrownBy(() -> useCase.dispatch("s1", wrong))
            .isInstanceOf(InvalidMessageException.class);

        assertThat(gateway.wasEnqueued()).isFalse();
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

        assertThatThrownBy(() -> useCase.dispatch("s1", msg))
            .isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("bit_003");

        assertThat(gateway.wasEnqueued()).isFalse();
    }

    @Test
    void dispatch_rejects_missing_bit007() {
        IsoMessage msg = IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3, "100000")
            // bit 7 missing
            .field(11, "132256").field(12, "162338").field(13, "0327")
            .field(42, "644400000000001").field(125, "000132256").field(127, "000248756")
            .build();

        assertThatThrownBy(() -> useCase.dispatch("s1", msg))
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

        assertThatThrownBy(() -> useCase.dispatch("s1", msg))
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

        assertThatThrownBy(() -> useCase.dispatch("s1", msg))
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

        assertThatThrownBy(() -> useCase.dispatch("s1", msg))
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

        assertThatThrownBy(() -> useCase.dispatch("s1", msg))
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

        assertThatCode(() -> useCase.dispatch("s1", msg)).doesNotThrowAnyException();
        assertThat(gateway.wasEnqueued()).isTrue();
    }

    @Test
    void dispatch_accepts_optional_bits_present() {
        useCase.dispatch("s1", valid0600()); // includes bit 32 and 41
        assertThat(gateway.wasEnqueued()).isTrue();
    }

    // ─── Gateway exceptions propagate ────────────────────────────────────────

    @Test
    void dispatch_propagates_sessionNotFound() {
        gateway.throwOnEnqueue = new SessionNotFoundException("s1");

        assertThatThrownBy(() -> useCase.dispatch("s1", valid0600()))
            .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void dispatch_propagates_queueFull() {
        gateway.throwOnEnqueue = new SessionQueueFullException("s1");

        assertThatThrownBy(() -> useCase.dispatch("s1", valid0600()))
            .isInstanceOf(SessionQueueFullException.class);
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
