package br.com.socker.bootstrap;

import br.com.socker.adapter.out.connectionpool.AsyncConnectionPool;
import br.com.socker.adapter.out.socket.client.AsyncSocketClientAdapter;
import br.com.socker.application.port.out.GatewayException;
import br.com.socker.application.usecase.SendMessageAsyncUseCaseImpl;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.config.AsyncClientConfig;
import br.com.socker.infrastructure.net.SocketFactory;
import br.com.socker.infrastructure.net.SocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point demonstrating the async multiplexed socket client.
 *
 * <p>Sends 50 concurrent 0200 requests over a small pool of {@code MultiplexedConnection}s
 * and logs the results. A single TCP connection handles all in-flight requests simultaneously
 * via Virtual Thread-based {@code ReadLoop} and {@code WriterThread}.
 *
 * <p>To run: {@code java -cp socker.jar br.com.socker.bootstrap.AsyncClientBootstrap}
 *
 * <p><b>Wiring:</b>
 * <pre>
 *   AsyncClientConfig
 *     → SocketFactory
 *       → AsyncConnectionPool (holds N MultiplexedConnections)
 *         → AsyncSocketClientAdapter (implements AsyncMessageGateway)
 *           → SendMessageAsyncUseCaseImpl (sync + async facade)
 * </pre>
 */
public class AsyncClientBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AsyncClientBootstrap.class);

    private static final DateTimeFormatter TX_DATETIME = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter TX_TIME     = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter TX_DATE     = DateTimeFormatter.ofPattern("MMdd");

    public static void main(String[] args) throws Exception {
        AsyncClientConfig config = new AsyncClientConfig();

        // 1. Socket factory — uses client.host / client.port from application.properties
        // NOTE: SocketOptions validates readTimeoutMs > 0, but AsyncConnectionPool
        // immediately overrides it to SO_TIMEOUT=0 after each socket is created.
        // We use 1 ms here as the minimum valid placeholder value.
        SocketOptions options = new SocketOptions(
            config.clientHost(),
            config.clientPort(),
            config.clientConnectTimeoutMs(),
            1, // overridden to SO_TIMEOUT=0 inside AsyncConnectionPool.createConnection()
            config.clientMaxPayloadBytes()
        );
        SocketFactory socketFactory = new SocketFactory(options);

        // 2. Async connection pool
        try (AsyncConnectionPool pool = new AsyncConnectionPool(
                socketFactory,
                config.asyncPoolMinConnections(),
                config.asyncPoolMaxConnections(),
                config.asyncMaxInFlightPerConnection(),
                config.asyncRequestTimeoutMs(),
                config.asyncBorrowTimeoutMs(),
                config.clientMaxPayloadBytes(),
                config.asyncReconnectDelayMs())) {

            // 3. Wire adapter and use case
            AsyncSocketClientAdapter gateway = new AsyncSocketClientAdapter(pool);
            SendMessageAsyncUseCaseImpl useCase =
                new SendMessageAsyncUseCaseImpl(gateway, config.asyncAwaitTimeoutMs());

            log.info("AsyncClientBootstrap: pool active={}", pool.activeConnectionCount());

            // 4. Demo: 50 concurrent requests, all async
            int total = 50;
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failureCount = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(total);
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);

            LocalDateTime now = LocalDateTime.now();

            for (int i = 1; i <= total; i++) {
                final String nsu = String.format("%06d", i);
                final IsoMessage request = buildRequest(nsu, now);

                CompletableFuture<Void> f;
                try {
                    f = useCase.sendAsync(request)
                        .thenApply(TransactionResult::success)
                        .exceptionally(ex -> TransactionResult.protocolError(ex.getMessage()))
                        .thenAccept(result -> {
                            if (result.isSuccess()) {
                                successCount.incrementAndGet();
                                log.debug("NSU={} → MTI={} RC={}",
                                    nsu,
                                    result.getResponseMessage().getMessageType().getMti(),
                                    result.getResponseMessage().getField(39).orElse("?"));
                            } else {
                                failureCount.incrementAndGet();
                                log.warn("NSU={} → FAILED: {}",
                                    nsu, result.getErrorMessage().orElse("unknown"));
                            }
                            latch.countDown();
                        });
                } catch (GatewayException e) {
                    log.error("NSU={} → dispatch error: {}", nsu, e.getMessage());
                    failureCount.incrementAndGet();
                    latch.countDown();
                    f = CompletableFuture.completedFuture(null);
                }
                futures.add(f);
            }

            // 5. Wait for all futures
            latch.await();
            log.info("AsyncClientBootstrap complete: success={} failure={} total={}",
                successCount.get(), failureCount.get(), total);
            log.info("Pool connections still alive: {}", pool.activeConnectionCount());
        }
    }

    private static IsoMessage buildRequest(String nsu, LocalDateTime now) {
        return IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  now.format(TX_DATETIME))
            .field(11, nsu)
            .field(12, now.format(TX_TIME))
            .field(13, now.format(TX_DATE))
            .field(32, "12345678901")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();
    }
}
