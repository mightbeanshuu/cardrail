package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryVaultStoreTest {

    private final InMemoryVaultStore store = new InMemoryVaultStore();

    @Test
    void secondRecordForSameBlindIndexReturnsTheFirst() {
        VaultRecord first = record("9999991234567897", new byte[]{1});
        VaultRecord second = record("9999991111111116", new byte[]{1});

        assertThat(store.insertIfAbsent(first)).isInstanceOf(VaultStore.InsertOutcome.Inserted.class);
        assertThat(store.insertIfAbsent(second)).isEqualTo(new VaultStore.InsertOutcome.AlreadyTokenized(first));
        assertThat(store.findByToken(second.token())).isEmpty();
        assertThat(store.findByBlindIndex(new byte[]{1})).contains(first);
    }

    @Test
    void takenTokenIsReportedAndDoesNotDisturbTheOwner() {
        VaultRecord owner = record("9999991234567897", new byte[]{1});
        VaultRecord clash = record("9999991234567897", new byte[]{2});
        store.insertIfAbsent(owner);

        assertThat(store.insertIfAbsent(clash)).isInstanceOf(VaultStore.InsertOutcome.TokenTaken.class);
        assertThat(store.findByToken(owner.token())).contains(owner);
        assertThat(store.findByBlindIndex(new byte[]{2})).isEmpty();
    }

    @Test
    void rewrapIsCompareAndSet() {
        VaultRecord record = record("9999991234567897", new byte[]{1});
        store.insertIfAbsent(record);

        assertThat(store.rewrap(record.token(), "kek-v1", "kek-v2", new byte[]{9})).isTrue();
        assertThat(store.rewrap(record.token(), "kek-v1", "kek-v3", new byte[]{8})).isFalse();
        assertThat(store.findByToken(record.token()).orElseThrow().dekKeyVersion()).isEqualTo("kek-v2");
        assertThat(store.findWrappedUnderOtherThan("kek-v2", 10)).isEmpty();
    }

    private static VaultRecord record(String token, byte[] blindIndex) {
        return new VaultRecord(token, blindIndex, new byte[0], "kek-v1", new byte[0], new byte[0],
                "555555******4444", Instant.EPOCH);
    }
}
