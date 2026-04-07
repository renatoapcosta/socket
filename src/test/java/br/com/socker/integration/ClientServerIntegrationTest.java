package br.com.socker.integration;

import br.com.socker.adapter.in.socket.server.SocketServerAdapter;
import br.com.socker.adapter.out.connectionpool.ConnectionPool;
import br.com.socker.adapter.out.connectionpool.ConnectionPoolConfig;
import br.com.socker.adapter.out.logging.StructuredObservabilityAdapter;
import br.com.socker.adapter.out.socket.client.SocketClientAdapter;
import br.com.socker.application.port.in.SendMessageUseCase;
import br.com.socker.application.port.out.ObservabilityPort;
import br.com.socker.application.usecase.ProcessReversalUseCaseImpl;
import br.com.socker.application.usecase.ProcessTransactionUseCaseImpl;
import br.com.socker.application.usecase.QueryParametersUseCaseImpl;
import br.com.socker.application.usecase.SendMessageUseCaseImpl;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.ResponseCode;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.net.SocketFactory;
import br.com.socker.infrastructure.net.SocketOptions;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/**
 * Full integration test: real TCP server + real client over loopback.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientServerIntegrationTest {

    private static SocketServerAdapter server;
    private static ConnectionPool pool;
    private static SendMessageUseCase sendMessage;
    private static int serverPort;

    @BeforeAll
    static void startServerAndClient() throws Exception {
        ObservabilityPort observability = new StructuredObservabilityAdapter(false);

        server = new SocketServerAdapter(
            0, // OS-assigned port
            32,
            5000,
            8192,
            new ProcessTransactionUseCaseImpl(),
            new ProcessReversalUseCaseImpl(),
            new QueryParametersUseCaseImpl(),
            observability
        );
        server.start();
        serverPort = server.getPort();

        // Wait for server to be ready
        Thread.sleep(100);

        SocketOptions options = new SocketOptions("127.0.0.1", serverPort, 2000, 5000, 8192);
        SocketFactory factory = new SocketFactory(options);
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig(2, 10, 3000, 60_000, 500);
        pool = new ConnectionPool(factory, poolConfig, 8192);

        SocketClientAdapter gateway = new SocketClientAdapter(pool);
        sendMessage = new SendMessageUseCaseImpl(gateway);
    }

    @AfterAll
    static void stopAll() {
        if (pool   != null) pool.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void sendTransaction_receivesApprovedResponse() throws Exception {
        IsoMessage request = transaction0200("000001");

        TransactionResult result = sendMessage.send(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasResponse()).isTrue();
        assertThat(result.getResponseMessage().getMessageType())
            .isEqualTo(MessageType.TRANSACTION_RESPONSE);
        assertThat(result.getResponseMessage().getField(39))
            .contains(ResponseCode.APPROVED.getCode());
    }

    @Test
    @Order(2)
    void sendTransaction_mirrorsNsuAndOriginCode() throws Exception {
        IsoMessage request = transaction0200("000042");

        TransactionResult result = sendMessage.send(request);

        IsoMessage response = result.getResponseMessage();
        assertThat(response.getRequiredField(11)).isEqualTo("000042");
        assertThat(response.getRequiredField(42)).isEqualTo("123456789012345");
    }

    @Test
    @Order(3)
    void sendParameterQuery_receivesResponse() throws Exception {
        IsoMessage request = IsoMessage.builder(MessageType.PARAMETER_QUERY_REQUEST)
            .field(3,  "091000")
            .field(7,  "0407130000")
            .field(11, "000010")
            .field(12, "130000")
            .field(13, "0407")
            .field(40, "006")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();

        TransactionResult result = sendMessage.send(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponseMessage().getMessageType())
            .isEqualTo(MessageType.PARAMETER_QUERY_RESPONSE);
        assertThat(result.getResponseMessage().getField(39))
            .contains(ResponseCode.APPROVED.getCode());
    }

    @Test
    @Order(4)
    void concurrentTransactions_allSucceed() throws Exception {
        int concurrency = 20;
        CountDownLatch latch = new CountDownLatch(concurrency);
        List<TransactionResult> results = new CopyOnWriteArrayList<>();

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < concurrency; i++) {
            final int nsu = i + 100;
            exec.submit(() -> {
                try {
                    TransactionResult r = sendMessage.send(transaction0200(String.format("%06d", nsu)));
                    results.add(r);
                } catch (Exception e) {
                    results.add(TransactionResult.protocolError(e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        exec.shutdown();

        long successCount = results.stream().filter(TransactionResult::isSuccess).count();
        assertThat(successCount).isEqualTo(concurrency);
    }

    @Test
    @Order(5)
    void sendReversal_receivesResponse() throws Exception {
        String originalData = "02000000010407123045" + "12345678901" + "00000000000";
        IsoMessage reversal = IsoMessage.builder(MessageType.REVERSAL_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407131500")
            .field(11, "000099")
            .field(12, "131500")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(39, "00")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .field(90, originalData)
            .build();

        TransactionResult result = sendMessage.send(reversal);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponseMessage().getMessageType())
            .isEqualTo(MessageType.REVERSAL_RESPONSE);
    }

    // --- helpers ---

    private IsoMessage transaction0200(String nsu) {
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
