socker/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/br/com/socker/
    │   │   ├── domain/
    │   │   │   ├── model/         IsoMessage, MessageType, ProcessingCode, ResponseCode, TransactionResult
    │   │   │   └── exception/     DomainException, InvalidMessageException, TransactionException
    │   │   ├── application/
    │   │   │   ├── port/in/       ProcessTransactionUseCase, ProcessReversalUseCase,
    │   │   │   │                  QueryParametersUseCase, SendMessageUseCase
    │   │   │   ├── port/out/      MessageGateway, GatewayException, ObservabilityPort
    │   │   │   └── usecase/       ProcessTransactionUseCaseImpl, ProcessReversalUseCaseImpl,
    │   │   │                      QueryParametersUseCaseImpl, SendMessageUseCaseImpl
    │   │   ├── adapter/
    │   │   │   ├── in/socket/server/    SocketServerAdapter, ConnectionHandler
    │   │   │   └── out/
    │   │   │       ├── socket/client/   SocketClientAdapter
    │   │   │       ├── connectionpool/  ConnectionPool, PooledConnection, ConnectionPoolConfig
    │   │   │       └── logging/         StructuredObservabilityAdapter
    │   │   ├── infrastructure/
    │   │   │   ├── protocol/      Frame, FrameHeader, FrameReader, FrameWriter,
    │   │   │   │                  IsoMessageEncoder, IsoMessageDecoder,
    │   │   │   │                  FieldDefinition, GwcelFieldRegistry, ProtocolException
    │   │   │   ├── net/           SocketFactory, SocketOptions
    │   │   │   └── config/        AppConfig
    │   │   └── bootstrap/         ServerBootstrap, ClientBootstrap
    │   └── resources/
    │       ├── application.properties
    │       └── logback.xml
    └── test/  (43 testes)
        ├── infrastructure/protocol/   FrameHeaderTest, FrameReaderWriterTest, IsoMessageCodecTest
        ├── application/usecase/       ProcessTransactionUseCaseTest
        ├── adapter/out/connectionpool/ ConnectionPoolTest
        └── integration/               ClientServerIntegrationTest
