package br.com.socker.infrastructure.concentrator;

import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.exception.NoActiveConnectionException;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit and integration tests for {@link ConcentratorConnectionRegistry}.
 *
 * <p>Uses real loopback sockets to create {@link ConcentratorConnection} instances
 * without mocking infrastructure.
 */
class ConcentratorConnectionRegistryTest {

    private ConcentratorConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConcentratorConnectionRegistry();
    }

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    void getActive_initiallyEmpty() {
        assertThat(registry.getActive()).isEmpty();
    }

    @Test
    void send_withNoActiveConnection_throwsNoActiveConnectionException() {
        IsoMessage msg = probe0600();

        assertThatThrownBy(() -> registry.send(msg))
            .isInstanceOf(NoActiveConnectionException.class);
    }

    // ─── register ─────────────────────────────────────────────────────────────

    @Test
    void register_makesConnectionActive() throws Exception {
        try (SocketPair pair = SocketPair.open()) {
            ConcentratorConnection connection =
                new ConcentratorConnection("conn-1", pair.server, 100);

            registry.register(connection);

            assertThat(registry.getActive()).isPresent();
            assertThat(registry.getActive().get().getConnectionId()).isEqualTo("conn-1");

            connection.close();
        }
    }

    @Test
    void register_replacesOldConnection() throws Exception {
        try (SocketPair pair1 = SocketPair.open();
             SocketPair pair2 = SocketPair.open()) {

            ConcentratorConnection conn1 = new ConcentratorConnection("conn-1", pair1.server, 100);
            ConcentratorConnection conn2 = new ConcentratorConnection("conn-2", pair2.server, 100);

            registry.register(conn1);
            registry.register(conn2);

            // conn2 should now be active
            assertThat(registry.getActive().get().getConnectionId()).isEqualTo("conn-2");

            // conn1 should be closed
            Thread.sleep(100); // let the close propagate
            assertThat(conn1.isAlive()).isFalse();

            conn2.close();
        }
    }

    // ─── deregister ───────────────────────────────────────────────────────────

    @Test
    void deregister_removesActiveConnection() throws Exception {
        try (SocketPair pair = SocketPair.open()) {
            ConcentratorConnection connection =
                new ConcentratorConnection("conn-1", pair.server, 100);

            registry.register(connection);
            registry.deregister("conn-1");

            assertThat(registry.getActive()).isEmpty();
        }
    }

    @Test
    void deregister_withWrongId_isNoOp() throws Exception {
        try (SocketPair pair = SocketPair.open()) {
            ConcentratorConnection connection =
                new ConcentratorConnection("conn-1", pair.server, 100);

            registry.register(connection);
            registry.deregister("wrong-id"); // should not remove conn-1

            assertThat(registry.getActive()).isPresent();
            assertThat(registry.getActive().get().getConnectionId()).isEqualTo("conn-1");

            connection.close();
        }
    }

    @Test
    void deregister_withNoActiveConnection_isNoOp() {
        // Should not throw
        assertThatCode(() -> registry.deregister("any-id")).doesNotThrowAnyException();
    }

    // ─── send ─────────────────────────────────────────────────────────────────

    @Test
    void send_withActiveConnection_enqueuesSuccessfully() throws Exception {
        try (SocketPair pair = SocketPair.open()) {
            ConcentratorConnection connection =
                new ConcentratorConnection("conn-1", pair.server, 100);

            registry.register(connection);

            // Should not throw
            assertThatCode(() -> registry.send(probe0600())).doesNotThrowAnyException();

            connection.close();
        }
    }

    @Test
    void send_afterDeregister_throwsNoActiveConnectionException() throws Exception {
        try (SocketPair pair = SocketPair.open()) {
            ConcentratorConnection connection =
                new ConcentratorConnection("conn-1", pair.server, 100);

            registry.register(connection);
            registry.deregister("conn-1");

            assertThatThrownBy(() -> registry.send(probe0600()))
                .isInstanceOf(NoActiveConnectionException.class);
        }
    }

    @Test
    void send_withFullQueue_throwsConnectionQueueFullException() throws Exception {
        try (SocketPair pair = SocketPair.open()) {
            // Capacity = 1
            ConcentratorConnection connection =
                new ConcentratorConnection("conn-1", pair.server, 1);

            registry.register(connection);

            // Fill the queue
            // The writer may drain it immediately; use a tiny capacity to make it fail faster
            // Fill beyond capacity
            AtomicInteger enqueued = new AtomicInteger(0);
            assertThatThrownBy(() -> {
                for (int i = 0; i < 1000; i++) {
                    registry.send(probe0600());
                    enqueued.incrementAndGet();
                }
            }).isInstanceOf(ConnectionQueueFullException.class);

            connection.close();
        }
    }

    // ─── Reconnect scenario ───────────────────────────────────────────────────

    @Test
    void reconnect_newConnectionBecomesActive() throws Exception {
        try (SocketPair pair1 = SocketPair.open();
             SocketPair pair2 = SocketPair.open()) {

            ConcentratorConnection conn1 = new ConcentratorConnection("conn-A", pair1.server, 100);
            registry.register(conn1);

            assertThat(registry.getActive().get().getConnectionId()).isEqualTo("conn-A");

            // Simulate reconnect
            ConcentratorConnection conn2 = new ConcentratorConnection("conn-B", pair2.server, 100);
            registry.register(conn2);

            assertThat(registry.getActive().get().getConnectionId()).isEqualTo("conn-B");

            // Old connection should be closed; new one accepts sends
            assertThatCode(() -> registry.send(probe0600())).doesNotThrowAnyException();

            conn2.close();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private IsoMessage probe0600() {
        return IsoMessage.builder(MessageType.PROBE_REQUEST)
            .field(3, "100000").field(7, "0327162336").field(11, "132256")
            .field(12, "162338").field(13, "0327").field(42, "644400000000001")
            .field(125, "000132256").field(127, "000248756")
            .build();
    }

    /**
     * Helper — opens a loopback server/client socket pair for testing.
     * The {@code server} side represents the inbound Concentrador connection.
     */
    static class SocketPair implements AutoCloseable {
        final Socket server; // accepted side — used as ConcentratorConnection socket
        final Socket client; // initiating side — simulates the Concentrador

        private SocketPair(Socket server, Socket client) {
            this.server = server;
            this.client = client;
        }

        static SocketPair open() throws IOException {
            ServerSocket ss = new ServerSocket(0);
            Socket client   = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server   = ss.accept();
            ss.close();
            return new SocketPair(server, client);
        }

        @Override
        public void close() throws IOException {
            try { server.close(); } catch (IOException ignored) {}
            try { client.close(); } catch (IOException ignored) {}
        }
    }
}
