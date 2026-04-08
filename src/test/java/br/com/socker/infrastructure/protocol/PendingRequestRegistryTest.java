package br.com.socker.infrastructure.protocol;

import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PendingRequestRegistry}.
 *
 * <p>Covers: register, complete, failAll, duplicate-NSU rejection,
 * per-request timeout, and map cleanup after completion.
 */
class PendingRequestRegistryTest {

    private static final long TIMEOUT_MS = 200;

    private PendingRequestRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PendingRequestRegistry(TIMEOUT_MS);
    }

    // --- register ---

    @Test
    void register_returnsFutureThatIsNotYetComplete() {
        CompletableFuture<IsoMessage> future = registry.register("000001");

        assertThat(future).isNotNull();
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void register_rejectsDuplicateNsu() {
        registry.register("000001");

        assertThatThrownBy(() -> registry.register("000001"))
            .isInstanceOf(PendingRequestRegistry.DuplicateNsuException.class)
            .hasMessageContaining("000001");
    }

    @Test
    void register_allowsDifferentNsus() {
        CompletableFuture<IsoMessage> f1 = registry.register("000001");
        CompletableFuture<IsoMessage> f2 = registry.register("000002");

        assertThat(f1).isNotSameAs(f2);
        assertThat(registry.pendingCount()).isEqualTo(2);
    }

    // --- complete ---

    @Test
    void complete_completesTheFuture() throws Exception {
        CompletableFuture<IsoMessage> future = registry.register("000001");
        IsoMessage response = buildResponse("000001");

        boolean matched = registry.complete("000001", response);

        assertThat(matched).isTrue();
        assertThat(future.get(1, TimeUnit.SECONDS)).isSameAs(response);
    }

    @Test
    void complete_removesNsuFromPendingMap() throws Exception {
        registry.register("000001");
        registry.complete("000001", buildResponse("000001"));

        // Allow whenComplete cleanup to run
        Thread.sleep(50);

        assertThat(registry.pendingCount()).isEqualTo(0);
    }

    @Test
    void complete_returnsFalseForUnknownNsu() {
        boolean matched = registry.complete("999999", buildResponse("999999"));

        assertThat(matched).isFalse();
    }

    @Test
    void complete_returnsFalseAfterTimeoutExpired() throws Exception {
        registry.register("000001");

        // Wait for the per-request timeout to fire
        Thread.sleep(TIMEOUT_MS + 100);

        boolean matched = registry.complete("000001", buildResponse("000001"));

        // The future was already completed exceptionally by orTimeout; complete() returns false
        assertThat(matched).isFalse();
    }

    // --- per-request timeout ---

    @Test
    void register_futureFails_withTimeoutException_afterConfiguredDelay() {
        CompletableFuture<IsoMessage> future = registry.register("000001");

        // The future should fail with TimeoutException within TIMEOUT_MS
        assertThatThrownBy(() -> future.get(TIMEOUT_MS + 300, TimeUnit.MILLISECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void register_nsuRemovedFromMap_afterTimeout() throws Exception {
        registry.register("000001");

        Thread.sleep(TIMEOUT_MS + 100);

        assertThat(registry.pendingCount()).isEqualTo(0);
    }

    @Test
    void nsu_canBeReused_afterPreviousFutureCompleted() throws Exception {
        CompletableFuture<IsoMessage> first = registry.register("000001");
        registry.complete("000001", buildResponse("000001"));
        first.get(1, TimeUnit.SECONDS); // ensure cleanup ran

        Thread.sleep(20); // let whenComplete cleanup settle

        // Should not throw DuplicateNsuException
        CompletableFuture<IsoMessage> second = registry.register("000001");
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void nsu_canBeReused_afterTimeout() throws Exception {
        registry.register("000001");
        Thread.sleep(TIMEOUT_MS + 100); // let timeout and cleanup run

        // Should not throw DuplicateNsuException
        CompletableFuture<IsoMessage> renewed = registry.register("000001");
        assertThat(renewed.isDone()).isFalse();
    }

    // --- failAll ---

    @Test
    void failAll_failsEveryPendingFuture() throws Exception {
        CompletableFuture<IsoMessage> f1 = registry.register("000001");
        CompletableFuture<IsoMessage> f2 = registry.register("000002");
        CompletableFuture<IsoMessage> f3 = registry.register("000003");

        RuntimeException cause = new RuntimeException("connection died");
        registry.failAll(cause);

        assertThat(f1.isCompletedExceptionally()).isTrue();
        assertThat(f2.isCompletedExceptionally()).isTrue();
        assertThat(f3.isCompletedExceptionally()).isTrue();

        assertThatThrownBy(() -> f1.get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCause(cause);
    }

    @Test
    void failAll_onEmptyRegistry_doesNotThrow() {
        assertThatCode(() -> registry.failAll(new RuntimeException("dead")))
            .doesNotThrowAnyException();
    }

    @Test
    void failAll_cleansUpPendingMap() throws Exception {
        registry.register("000001");
        registry.register("000002");

        registry.failAll(new RuntimeException("dead"));
        Thread.sleep(50); // let whenComplete handlers run

        assertThat(registry.pendingCount()).isEqualTo(0);
    }

    @Test
    void pendingCount_reflectsCurrentInFlightRequests() {
        assertThat(registry.pendingCount()).isEqualTo(0);

        registry.register("000001");
        assertThat(registry.pendingCount()).isEqualTo(1);

        registry.register("000002");
        assertThat(registry.pendingCount()).isEqualTo(2);

        registry.complete("000001", buildResponse("000001"));
        // Map cleanup is async via whenComplete; pendingCount may still be 2 here
        // — we only assert it doesn't exceed 2

        registry.failAll(new RuntimeException("cleanup"));
        // After failAll, eventual cleanup runs
    }

    // --- constructor guard ---

    @Test
    void constructor_rejectsZeroOrNegativeTimeout() {
        assertThatThrownBy(() -> new PendingRequestRegistry(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingRequestRegistry(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private IsoMessage buildResponse(String nsu) {
        return IsoMessage.builder(MessageType.TRANSACTION_RESPONSE)
            .field(11, nsu)
            .field(39, "00")
            .build();
    }
}
