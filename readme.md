

Regra de ouro: domínio e casos de uso nunca importam Socket, InputStream, logger ou framework. Todo efeito colateral passa por uma porta (MessageGateway, ObservabilityPort).


Decisões técnicas principais
Decisão	Justificativa

Executors.newVirtualThreadPerTaskExecutor()	1 Virtual Thread por conexão — zero contention de platform thread para 20k req/min
BlockingQueue<PooledConnection> no pool	Borrow com timeout sem busy-wait; thread-safe por design
Header 2 bytes Big-Endian	Spec GwCel seção 1.1 — binário, não ASCII
readFully no FrameReader	TCP fragmentation: garante todos os bytes declarados antes de processar
MDC + SLF4J	Correlação de logs por connectionId/nsu sem acoplar domínio ao logger
record para FrameHeader, SocketOptions, etc.	Imutabilidade garantida pelo compilador, sem boilerplate

Como rodar

```
# Servidor
java -jar target/socker-1.0.0-SNAPSHOT.jar

# Cliente (exemplo)
java -cp target/socker-1.0.0-SNAPSHOT.jar br.com.socker.bootstrap.ClientBootstrap

# Testes
mvn test
````
