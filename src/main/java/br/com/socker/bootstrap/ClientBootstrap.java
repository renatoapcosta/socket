package br.com.socker.bootstrap;

import br.com.socker.adapter.out.connectionpool.ConnectionPool;
import br.com.socker.adapter.out.connectionpool.ConnectionPoolConfig;
import br.com.socker.adapter.out.socket.client.SocketClientAdapter;
import br.com.socker.application.port.in.SendMessageUseCase;
import br.com.socker.application.usecase.SendMessageUseCaseImpl;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import br.com.socker.domain.model.TransactionResult;
import br.com.socker.infrastructure.config.AppConfig;
import br.com.socker.infrastructure.net.SocketFactory;
import br.com.socker.infrastructure.net.SocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Example entry point demonstrating how to wire and use the Socket Client.
 *
 * <p>Sends a sample 0200 (transaction request) to GwCel and logs the 0210 response.
 *
 * <p>To run: {@code java -cp socker.jar br.com.socker.bootstrap.ClientBootstrap}
 */
public class ClientBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ClientBootstrap.class);

    private static final DateTimeFormatter TX_DATETIME = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter TX_TIME     = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter TX_DATE     = DateTimeFormatter.ofPattern("MMdd");

    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();

        // 1. Build socket options
        SocketOptions options = new SocketOptions(
            config.clientHost(),
            config.clientPort(),
            config.clientConnectTimeoutMs(),
            config.clientReadTimeoutMs(),
            config.clientMaxPayloadBytes()
        );

        // 2. Build socket factory
        SocketFactory socketFactory = new SocketFactory(options);

        // 3. Build connection pool
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig(
            config.poolMinSize(),
            config.poolMaxSize(),
            config.poolBorrowTimeoutMs(),
            config.poolIdleTimeoutMs(),
            config.poolReconnectDelayMs()
        );

        try (ConnectionPool pool = new ConnectionPool(socketFactory, poolConfig, config.clientMaxPayloadBytes())) {

            // 4. Wire adapter and use case
            SocketClientAdapter gateway = new SocketClientAdapter(pool);
            SendMessageUseCase sendMessage = new SendMessageUseCaseImpl(gateway);

            // 5. Build a sample 0200 request
            LocalDateTime now = LocalDateTime.now();
            IsoMessage request = IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
                .field(3,  "100000")                      // processing code: recharge
                .field(4,  "000000001000")                // amount: R$10,00 = 1000 centavos
                .field(7,  now.format(TX_DATETIME))       // transmission datetime
                .field(11, "000001")                      // NSU
                .field(12, now.format(TX_TIME))           // local time
                .field(13, now.format(TX_DATE))           // local date
                .field(32, "12345678901")                 // branch code (LL-VAR content)
                .field(40, "006")                         // interface version
                .field(41, "TERM0001")                    // terminal ID
                .field(42, "123456789012345")             // origin code
                .field(49, "986")                         // currency: BRL
                .build();

            // 6. Send and receive
            TransactionResult result = sendMessage.send(request);

            if (result.isSuccess() && result.hasResponse()) {
                IsoMessage response = result.getResponseMessage();
                log.info("Response received: MTI={} responseCode={}",
                    response.getMessageType().getMti(),
                    response.getField(39).orElse("?"));
            } else {
                log.error("Transaction failed: {}", result.getErrorMessage().orElse("unknown"));
            }
        }
    }
}
