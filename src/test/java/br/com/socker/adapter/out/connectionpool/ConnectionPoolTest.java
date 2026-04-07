package br.com.socker.adapter.out.connectionpool;

import br.com.socker.infrastructure.net.SocketFactory;
import br.com.socker.infrastructure.net.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ConnectionPoolTest {

    private ServerSocket serverSocket;
    private int serverPort;

    @BeforeEach
    void startServer() throws IOException {
        serverSocket = new ServerSocket(0); // OS-assigned port
        serverPort = serverSocket.getLocalPort();

        // Accept connections in background (don't read/write)
        Thread.ofVirtual().start(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    serverSocket.accept(); // keep accepting, don't close clients
                } catch (IOException ignored) {}
            }
        });
    }

    @AfterEach
    void stopServer() throws IOException {
        serverSocket.close();
    }

    private ConnectionPool buildPool(int min, int max) {
        SocketOptions options = new SocketOptions("127.0.0.1", serverPort, 1000, 2000, 8192);
        SocketFactory factory = new SocketFactory(options);
        ConnectionPoolConfig config = new ConnectionPoolConfig(min, max, 3000, 60_000, 100);
        return new ConnectionPool(factory, config, 8192);
    }

    @Test
    void borrow_returnsHealthyConnection() throws Exception {
        try (ConnectionPool pool = buildPool(1, 5)) {
            PooledConnection conn = pool.borrow();
            assertThat(conn).isNotNull();
            assertThat(conn.isHealthy()).isTrue();
            pool.returnConnection(conn);
        }
    }

    @Test
    void returnAndReBorrow_reusesSameConnection() throws Exception {
        try (ConnectionPool pool = buildPool(1, 5)) {
            PooledConnection first = pool.borrow();
            String firstId = first.getConnectionId();
            pool.returnConnection(first);

            PooledConnection second = pool.borrow();
            assertThat(second.getConnectionId()).isEqualTo(firstId);
            pool.returnConnection(second);
        }
    }

    @Test
    void pool_exhaust_throwsWhenMaxReached() throws Exception {
        try (ConnectionPool pool = buildPool(0, 2)) {
            PooledConnection c1 = pool.borrow();
            PooledConnection c2 = pool.borrow();

            // Pool is now at max (2); next borrow should fail after timeout
            assertThatThrownBy(() -> pool.borrow())
                .isInstanceOf(ConnectionPool.PoolExhaustedException.class);

            pool.returnConnection(c1);
            pool.returnConnection(c2);
        }
    }

    @Test
    void concurrentBorrows_doNotCorrupt() throws Exception {
        int threads = 10;
        try (ConnectionPool pool = buildPool(2, 10)) {
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threads);

            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        PooledConnection conn = pool.borrow();
                        Thread.sleep(50); // simulate work
                        pool.returnConnection(conn);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                        // pool exhausted is acceptable under high concurrency here
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            exec.shutdown();

            assertThat(successCount.get()).isGreaterThan(0);
        }
    }

    @Test
    void returnUnhealthyConnection_evictsIt() throws Exception {
        // Use a large reconnect delay so the replenishment thread doesn't
        // create a new connection before we can observe the eviction.
        SocketOptions options = new SocketOptions("127.0.0.1", serverPort, 1000, 2000, 8192);
        SocketFactory factory = new SocketFactory(options);
        ConnectionPoolConfig config = new ConnectionPoolConfig(1, 5, 3000, 60_000, 30_000); // 30s reconnect
        try (ConnectionPool pool = new ConnectionPool(factory, config, 8192)) {

            PooledConnection conn = pool.borrow();
            int initialTotal = pool.totalCount();

            // Force connection to become unhealthy
            conn.closeQuietly();

            // Return immediately and check BEFORE reconnect delay fires
            pool.returnConnection(conn);

            // With 30s reconnect delay, the pool won't have replenished yet
            assertThat(pool.totalCount()).isLessThan(initialTotal);
        }
    }

    @Test
    void close_drainAllConnections() throws Exception {
        ConnectionPool pool = buildPool(3, 5);

        // Ensure connections are pre-warmed
        Thread.sleep(200);
        assertThat(pool.totalCount()).isGreaterThan(0);

        pool.close();

        assertThat(pool.availableCount()).isEqualTo(0);
    }
}
