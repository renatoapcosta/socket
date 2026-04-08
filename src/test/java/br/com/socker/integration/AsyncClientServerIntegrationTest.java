package br.com.socker.integration;

import br.com.socker.adapter.in.socket.server.SocketServerAdapter;
import br.com.socker.adapter.out.connectionpool.AsyncConnectionPool;
import br.com.socker.adapter.out.logging.StructuredObservabilityAdapter;
import br.com.socker.adapter.out.socket.client.AsyncSocketClientAdapter;
import br.com.socker.application.port.out.GatewayException;
import br.com.socker.application.usecase.SendMessageAsyncUseCaseImpl;
import br.com.socker.application.usecase.ProcessReversalUseCaseImpl;
import br.com.socker.application.usecase.ProcessTransactionUseCaseImpl;
import br.com.socker.application.usecase.QueryParametersUseCaseImpl;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.net.SocketFactory;
import br.com.socker.infrastructure.net.SocketOptions;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration test: real TCP server + async multiplexed client over loopback.
 *
 * <p>Uses a single {@link AsyncConnectionPool} with a small number of
 * {@code MultiplexedConnection}s to exercise concurrent in-flight request handling.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AsyncClientServerIntegrationTest {

    private static final int MAX_PAYLOAD      = 8_192;
    private static final int REQUEST_TIMEOUT  = 5_000;
    private static final int BORROW_TIMEOUT   = 3_000;
    private static final int RECONNECT_DELAY  = 500;
    private static final long AWAIT_TIMEOUT   = 7_000L;

    private static SocketServerAdapter server;
    private static AsyncConnectionPool pool;
    private static SendMessageAsyncUseCaseImpl useCase;
    private static int serverPort;

    @BeforeAll
    static void startServerAndClient() throws Exception {
        // Start real server on ephemeral port
        server = new SocketServerAdapter(
            0,
            32,
            10_000,
            MAX_PAYLOAD,
            new ProcessTransactionUseCaseImpl(),
            new ProcessReversalUseCaseImpl(),
            new QueryParametersUseCaseImpl(),
            new StructuredObservabilityAdapter(false)
        );
        server.start();
        serverPort = server.getPort();

        Thread.sleep(100); // let server bind

        // NOTE: readTimeoutMs is validated > 0 by SocketOptions, but AsyncConnectionPool
        // immediately overrides it to 0 (socket.setSoTimeout(0)) after each socket is created.
        // Pass 1 ms here as the minimum valid placeholder.
        SocketOptions options = new SocketOptions(
            "127.0.0.1", serverPort,
            2_000,
            1,          // overridden to SO_TIMEOUT=0 inside AsyncConnectionPool.createConnection()
            MAX_PAYLOAD
        );
        SocketFactory factory = new SocketFactory(options);

        pool = new AsyncConnectionPool(
            factory,
            1,              // minConnections
            3,              // maxConnections
            20,             // maxInFlightPerConnection
            REQUEST_TIMEOUT,
            BORROW_TIMEOUT,
            MAX_PAYLOAD,
            RECONNECT_DELAY
        );

        AsyncSocketClientAdapter gateway = new AsyncSocketClientAdapter(pool);
        useCase = new SendMessageAsyncUseCaseImpl(gateway, AWAIT_TIMEOUT);

        Thread.sleep(200); // let connections pre-warm
    }

    @AfterAll
    static void stopAll() {
        if (pool   != null) pool.close();
        if (server != null) server.close();
    }

    // --- Basic send/receive ---

    @Test
    @Order(1)
    void sendAsync_singleRequest_receivesApprovedResponse() throws Exception {
        IsoMessage request = transaction("A00001");

        CompletableFuture<IsoMessage> future = useCase.sendAsync(request);
        IsoMessage response = future.get(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS);

        assertThat(response.getMessageType()).isEqualTo(MessageType.TRANSACTION_RESPONSE);
        assertThat(response.getField(39)).contains(ResponseCode.APPROVED.getCode());
    }

    @Test
    @Order(2)
    void sendAsync_mirrorsNsuAndOriginCode() throws Exception {
        IsoMessage request = transaction("A00042");

        IsoMessage response = useCase.sendAsync(request).get(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS);

        assertThat(response.getRequiredField(11)).isEqualTo("A00042");
        assertThat(response.getRequiredField(42)).isEqualTo("123456789012345");
    }

    // --- Synchronous facade ---

    @Test
    @Order(3)
    void send_synchronousFacade_returnsSuccessResult() {
        TransactionResult result = useCase.send(transaction("B00001"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasResponse()).isTrue();
        assertThat(result.getResponseMessage().getMessageType())
            .isEqualTo(MessageType.TRANSACTION_RESPONSE);
    }

    // --- Concurrency ---

    @Test
    @Order(4)
    void sendAsync_manyConcurrentRequests_allReceiveCorrectResponse() throws Exception {
        int count = 60; // exceeds maxInFlight per connection — exercises semaphore + pool growth
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>(count);

        for (int i = 1; i <= count; i++) {
            String nsu = String.format("C%05d", i);
            IsoMessage request = transaction(nsu);

            CompletableFuture<Void> f = useCase.sendAsync(request)
                .thenAccept(response -> {
                    // Each response must echo the correct NSU
                    if (nsu.equals(response.getRequiredField(11))
                            && ResponseCode.APPROVED.getCode()
                               .equals(response.getField(39).orElse(""))) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                    latch.countDown();
                })
                .exceptionally(ex -> {
                    failureCount.incrementAndGet();
                    latch.countDown();
                    return null;
                });
            futures.add(f);
        }

        boolean completed = latch.await(15, TimeUnit.SECONDS);
        assertThat(completed).as("All futures should complete within 15s").isTrue();
        assertThat(successCount.get()).isEqualTo(count);
        assertThat(failureCount.get()).isEqualTo(0);
    }

    @Test
    @Order(5)
    void sendAsync_futures_areIndependentlyAddressable() throws Exception {
        // Send two requests concurrently and verify each gets its own response
        CompletableFuture<IsoMessage> f1 = useCase.sendAsync(transaction("D00001"));
        CompletableFuture<IsoMessage> f2 = useCase.sendAsync(transaction("D00002"));

        IsoMessage r1 = f1.get(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS);
        IsoMessage r2 = f2.get(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS);

        assertThat(r1.getRequiredField(11)).isEqualTo("D00001");
        assertThat(r2.getRequiredField(11)).isEqualTo("D00002");
    }

    @Test
    @Order(6)
    void poolRemainsHealthy_afterHighConcurrency() {
        assertThat(pool.activeConnectionCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(7)
    void sendAsync_missingNsu_throwsGatewayException() {
        IsoMessage requestWithoutNsu = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3, "100000")
            .field(4, "000000001000")
            .build();

        assertThatThrownBy(() -> useCase.sendAsync(requestWithoutNsu))
            .isInstanceOf(GatewayException.class)
            .hasMessageContaining("NSU");
    }

    // --- helpers ---

    private IsoMessage transaction(String nsu) {
        return IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, nsu)
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
