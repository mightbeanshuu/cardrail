package dev.cardrail.apisec;

import dev.cardrail.apisec.NonceStore.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryNonceStoreTest {

    static final Instant T = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void secondSightingIsAlreadySeen() {
        InMemoryNonceStore store = new InMemoryNonceStore(10);
        assertThat(store.record("c", "n", T.plusSeconds(60), T)).isEqualTo(Outcome.RECORDED);
        assertThat(store.record("c", "n", T.plusSeconds(60), T.plusSeconds(1))).isEqualTo(Outcome.ALREADY_SEEN);
        assertThat(store.record("other", "n", T.plusSeconds(60), T)).isEqualTo(Outcome.RECORDED);
    }

    @Test
    void entryLivesUntilItsExpiryInstantInclusive() {
        InMemoryNonceStore store = new InMemoryNonceStore(10);
        store.record("c", "n", T.plusSeconds(60), T);
        assertThat(store.record("c", "n", T.plusSeconds(60), T.plusSeconds(60))).isEqualTo(Outcome.ALREADY_SEEN);
        assertThat(store.record("c", "n", T.plusSeconds(120), T.plusSeconds(60).plusNanos(1))).isEqualTo(Outcome.RECORDED);
    }

    @Test
    void capacityIsNeverExceededAndExpiredEntriesMakeRoom() {
        InMemoryNonceStore store = new InMemoryNonceStore(2);
        store.record("c", "a", T.plusSeconds(10), T);
        store.record("c", "b", T.plusSeconds(20), T);
        assertThat(store.record("c", "x", T.plusSeconds(30), T)).isEqualTo(Outcome.CAPACITY_EXHAUSTED);
        assertThat(store.size()).isEqualTo(2);

        assertThat(store.record("c", "x", T.plusSeconds(30), T.plusSeconds(11))).isEqualTo(Outcome.RECORDED);
        assertThat(store.record("c", "b", T.plusSeconds(30), T.plusSeconds(11))).isEqualTo(Outcome.ALREADY_SEEN);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void racingVirtualThreadsRecordOnce() throws Exception {
        InMemoryNonceStore store = new InMemoryNonceStore(100);
        ConcurrentLinkedQueue<Outcome> outcomes = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                exec.submit(() -> {
                    start.await();
                    outcomes.add(store.record("c", "same", T.plusSeconds(60), T));
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(outcomes).hasSize(1000);
        assertThat(outcomes.stream().filter(o -> o == Outcome.RECORDED).count()).isEqualTo(1);
    }
}
