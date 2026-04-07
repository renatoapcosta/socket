┌─────────────────────────────────────────────────────────────┐
│  ADAPTER IN                     │  ADAPTER OUT               │
│  SocketServerAdapter            │  SocketClientAdapter       │
│  ConnectionHandler              │  ConnectionPool            │
│         │                       │         │                  │
│  ────── porta de entrada ──── APPLICATION ──── porta de saída│
│         │                       │         │                  │
│  ProcessTransactionUseCase ◄────┤  MessageGateway            │
│  ProcessReversalUseCase         │  ObservabilityPort         │
│  QueryParametersUseCase         │                            │
│         │                       │                            │
│              DOMAIN                                          │
│  IsoMessage · MessageType · ResponseCode · TransactionResult │
│                                                              │
│  INFRASTRUCTURE                                              │
│  FrameHeader/Reader/Writer · IsoMessageEncoder/Decoder       │
│  SocketFactory · AppConfig · GwcelFieldRegistry              │
└─────────────────────────────────────────────────────────────┘
