package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class TokenVaultTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryKeyProvider keys = InMemoryKeyProvider.generate();
    private final InMemoryVaultStore store = new InMemoryVaultStore();
    private final List<DetokenizeEvent> events = new CopyOnWriteArrayList<>();
    private final TokenVault vault = vaultOver(store);

    private TokenVault vaultOver(VaultStore backing) {
        return new TokenVault(backing, keys, new TokenFormat(), events::add, CLOCK);
    }

    @Test
    void tokenizeReturnsCardDetailsWithoutThePan() {
        TokenizedCard card = vault.tokenize(Pan.of(TestPans.MC_1));

        assertThat(card.maskedPan()).isEqualTo("555555******4444");
        assertThat(card.bin()).isEqualTo("555555");
        assertThat(card.last4()).isEqualTo("4444");
        assertThat(card.scheme()).isEqualTo(CardScheme.MASTERCARD);
        assertThat(card.token()).startsWith("999999").hasSize(16).isNotEqualTo(TestPans.MC_1);
        assertThat(Luhn.isValid(card.token())).isTrue();
        assertThat(card.toString()).doesNotContain(TestPans.MC_1);
    }

    @Test
    void storedRecordContainsNoPlaintextPan() {
        TokenizedCard card = vault.tokenize(Pan.of(TestPans.MC_1));
        VaultRecord record = store.findByToken(card.token()).orElseThrow();

        String ascii = new String(record.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(ascii).doesNotContain(TestPans.MC_1);
        assertThat(record.toString()).doesNotContain(TestPans.MC_1);
        assertThat(record.createdAt()).isEqualTo(CLOCK.instant());
        assertThat(record.dekKeyVersion()).isEqualTo("kek-v1");
    }

    @Property(tries = 200)
    void tokenizeIsIdempotentAndRoundTrips(@ForAll("pans") String digits) {
        TokenizedCard first = vault.tokenize(Pan.of(digits));
        TokenizedCard second = vault.tokenize(Pan.of(digits));

        assertThat(second).isEqualTo(first);
        assertThat(vault.detokenize(first.token(), DetokenizeReason.AUTHORIZATION)).isEqualTo(Pan.of(digits));
    }

    @Test
    void differentPansGetDifferentTokens() {
        Set<String> tokens = Set.of(
                vault.tokenize(Pan.of(TestPans.MC_1)).token(),
                vault.tokenize(Pan.of(TestPans.MC_2)).token(),
                vault.tokenize(Pan.of(TestPans.MC_2_SERIES)).token(),
                vault.tokenize(Pan.of(TestPans.VISA)).token());
        assertThat(tokens).hasSize(4);
        assertThat(store.size()).isEqualTo(4);
    }

    @Test
    void concurrentTokenizeOfOnePanYieldsOneToken() throws Exception {
        int threads = 64;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return vault.tokenize(Pan.of(TestPans.MC_1)).token();
                }));
            }
            ready.await();
            go.countDown();
            Set<String> tokens = ConcurrentHashMap.newKeySet();
            for (Future<String> result : results) {
                tokens.add(result.get());
            }
            assertThat(tokens).hasSize(1);
        }
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.findWrappedUnderOtherThan("none", 100)).hasSize(1);
    }

    @Test
    void tokenCollisionIsRetriedWithFreshDigits() {
        // Scripted digits: the first two tokens drawn are identical, the third differs.
        int[] script = {1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        RandomGenerator scripted = new RandomGenerator() {
            private int next;

            @Override
            public int nextInt(int bound) {
                return script[next++];
            }

            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }
        };
        TokenVault collisionProne = new TokenVault(store, keys, new TokenFormat("999999", scripted), events::add, CLOCK);

        String first = collisionProne.tokenize(Pan.of(TestPans.MC_1)).token();
        String second = collisionProne.tokenize(Pan.of(TestPans.VISA)).token();

        assertThat(first).startsWith("999999123456789");
        assertThat(second).startsWith("999999987654321");
        assertThat(collisionProne.detokenize(second, DetokenizeReason.AUTHORIZATION)).isEqualTo(Pan.of(TestPans.VISA));
    }

    @Test
    void refusesToTokenizeAToken() {
        String token = vault.tokenize(Pan.of(TestPans.MC_1)).token();
        assertThatThrownBy(() -> vault.tokenize(Pan.of(token)))
                .isInstanceOf(InvalidCardNumberException.class)
                .hasMessageNotContaining(token);
    }

    // --- tampering: copy a record into a fresh store with one field altered, then detokenize ---

    @Test
    void bitFlipInCiphertextIsDetected() {
        assertTampered(r -> new VaultRecord(r.token(), r.blindIndex(), r.wrappedDek(), r.dekKeyVersion(), r.iv(),
                flipLastBit(r.ciphertext()), r.maskedPan(), r.createdAt()));
    }

    @Test
    void bitFlipInIvIsDetected() {
        assertTampered(r -> new VaultRecord(r.token(), r.blindIndex(), r.wrappedDek(), r.dekKeyVersion(),
                flipLastBit(r.iv()), r.ciphertext(), r.maskedPan(), r.createdAt()));
    }

    @Test
    void bitFlipInWrappedDekIsDetected() {
        assertTampered(r -> r.withWrappedDek(r.dekKeyVersion(), flipLastBit(r.wrappedDek())));
    }

    @Test
    void bitFlipInWrapNonceIsDetected() {
        assertTampered(r -> {
            byte[] wrapped = r.wrappedDek().clone();
            wrapped[0] ^= 1;
            return r.withWrappedDek(r.dekKeyVersion(), wrapped);
        });
    }

    @Test
    void truncatedCiphertextIsDetected() {
        assertTampered(r -> new VaultRecord(r.token(), r.blindIndex(), r.wrappedDek(), r.dekKeyVersion(), r.iv(),
                Arrays.copyOf(r.ciphertext(), r.ciphertext().length - 1), r.maskedPan(), r.createdAt()));
    }

    @Test
    void relabelledKeyVersionIsDetected() {
        VaultRecord underV1 = record(vault.tokenize(Pan.of(TestPans.MC_1)));
        keys.rotate();
        assertIntegrityFailure(underV1.withWrappedDek("kek-v2", underV1.wrappedDek()));
    }

    @Test
    void panCiphertextSwappedOntoAnotherTokenFails() {
        VaultRecord victim = record(vault.tokenize(Pan.of(TestPans.VISA)));
        VaultRecord donor = record(vault.tokenize(Pan.of(TestPans.MC_1)));
        VaultRecord swapped = new VaultRecord(victim.token(), victim.blindIndex(), victim.wrappedDek(),
                victim.dekKeyVersion(), donor.iv(), donor.ciphertext(), victim.maskedPan(), victim.createdAt());

        assertIntegrityFailure(swapped);
    }

    @Test
    void wholeEnvelopeSwappedOntoAnotherTokenFails() {
        VaultRecord victim = record(vault.tokenize(Pan.of(TestPans.VISA)));
        VaultRecord donor = record(vault.tokenize(Pan.of(TestPans.MC_1)));
        VaultRecord swapped = new VaultRecord(victim.token(), victim.blindIndex(), donor.wrappedDek(),
                donor.dekKeyVersion(), donor.iv(), donor.ciphertext(), victim.maskedPan(), victim.createdAt());

        assertIntegrityFailure(swapped);
    }

    // --- rotation ---

    @Test
    void rotationRewrapsDeksWithoutTouchingCiphertext() {
        List<String> tokens = List.of(
                vault.tokenize(Pan.of(TestPans.MC_1)).token(),
                vault.tokenize(Pan.of(TestPans.MC_2)).token(),
                vault.tokenize(Pan.of(TestPans.VISA)).token());
        List<VaultRecord> before = tokens.stream().map(this::record).toList();

        keys.rotate();
        String afterRotation = vault.tokenize(Pan.of(TestPans.MC_2_SERIES)).token();
        assertThat(record(afterRotation).dekKeyVersion()).isEqualTo("kek-v2");
        assertThat(vault.detokenize(tokens.get(0), DetokenizeReason.CLEARING)).isEqualTo(Pan.of(TestPans.MC_1));

        assertThat(vault.rewrapAll()).isEqualTo(3);
        assertThat(vault.rewrapAll()).isZero();

        for (VaultRecord old : before) {
            VaultRecord now = record(old.token());
            assertThat(now.dekKeyVersion()).isEqualTo("kek-v2");
            assertThat(now.wrappedDek()).isNotEqualTo(old.wrappedDek());
            assertThat(now.iv()).isEqualTo(old.iv());
            assertThat(now.ciphertext()).isEqualTo(old.ciphertext());
            assertThat(now.blindIndex()).isEqualTo(old.blindIndex());
        }

        keys.retire("kek-v1");
        assertThat(vault.detokenize(tokens.get(2), DetokenizeReason.AUTHORIZATION)).isEqualTo(Pan.of(TestPans.VISA));
        assertThat(vault.tokenize(Pan.of(TestPans.MC_1)).token()).isEqualTo(tokens.get(0));
    }

    @Test
    void detokenizeWorksAcrossSeveralRotations() {
        String token = vault.tokenize(Pan.of(TestPans.MC_1)).token();
        for (int i = 0; i < 3; i++) {
            keys.rotate();
            vault.rewrapAll();
        }
        assertThat(record(token).dekKeyVersion()).isEqualTo("kek-v4");
        assertThat(vault.detokenize(token, DetokenizeReason.DISPUTE)).isEqualTo(Pan.of(TestPans.MC_1));
    }

    @Test
    void retiredKekWithRemainingRecordsFailsLoudly() {
        String token = vault.tokenize(Pan.of(TestPans.MC_1)).token();
        keys.rotate();
        keys.retire("kek-v1");

        assertThatThrownBy(() -> vault.detokenize(token, DetokenizeReason.AUTHORIZATION))
                .isInstanceOf(KeyUnavailableException.class)
                .hasMessageContaining("kek-v1")
                .hasMessageContaining("RETIRED");
        assertThatThrownBy(vault::rewrapAll).isInstanceOf(KeyUnavailableException.class);
        assertThat(events).last().extracting(DetokenizeEvent::outcome).isEqualTo(DetokenizeEvent.Outcome.KEY_UNAVAILABLE);
    }

    // --- audit ---

    @Test
    void detokenizeIsAuditedWithoutThePan() {
        String token = vault.tokenize(Pan.of(TestPans.MC_1)).token();

        vault.detokenize(token, DetokenizeReason.AUTHORIZATION);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.token()).isEqualTo(token);
            assertThat(event.reason()).isEqualTo(DetokenizeReason.AUTHORIZATION);
            assertThat(event.at()).isEqualTo(CLOCK.instant());
            assertThat(event.kekId()).isEqualTo("kek-v1");
            assertThat(event.outcome()).isEqualTo(DetokenizeEvent.Outcome.GRANTED);
            assertThat(event.toString()).doesNotContain(TestPans.MC_1).doesNotContain("4444");
        });
        assertThat(Arrays.stream(DetokenizeEvent.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("token", "reason", "at", "kekId", "outcome");
    }

    @Test
    void failedDetokenizeAttemptsAreAuditedToo() {
        String unknown = new TokenFormat().next();

        assertThatThrownBy(() -> vault.detokenize(unknown, DetokenizeReason.CLEARING))
                .isInstanceOf(UnknownTokenException.class);
        assertThatThrownBy(() -> vault.detokenize(TestPans.MC_1, DetokenizeReason.CLEARING))
                .isInstanceOf(UnknownTokenException.class)
                .hasMessageNotContaining(TestPans.MC_1);

        assertThat(events).extracting(DetokenizeEvent::outcome)
                .containsExactly(DetokenizeEvent.Outcome.UNKNOWN_TOKEN, DetokenizeEvent.Outcome.MALFORMED_TOKEN);
        assertThat(events.get(1).token()).isNull();
        assertThat(events.toString()).doesNotContain(TestPans.MC_1);
    }

    @Test
    void failingAuditListenerWithholdsThePan() {
        String token = vault.tokenize(Pan.of(TestPans.MC_1)).token();
        TokenVault strict = new TokenVault(store, keys, new TokenFormat(), event -> {
            throw new IllegalStateException("audit sink down");
        }, CLOCK);

        assertThatThrownBy(() -> strict.detokenize(token, DetokenizeReason.AUTHORIZATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit sink down");
    }

    private void assertTampered(UnaryOperator<VaultRecord> tamper) {
        VaultRecord original = record(vault.tokenize(Pan.of(TestPans.MC_1)));
        assertIntegrityFailure(tamper.apply(original));
    }

    private void assertIntegrityFailure(VaultRecord tampered) {
        InMemoryVaultStore attacked = new InMemoryVaultStore();
        assertThat(attacked.insertIfAbsent(tampered)).isInstanceOf(VaultStore.InsertOutcome.Inserted.class);
        events.clear();

        assertThatThrownBy(() -> vaultOver(attacked).detokenize(tampered.token(), DetokenizeReason.AUTHORIZATION))
                .isInstanceOf(VaultIntegrityException.class)
                .hasNoSuppressedExceptions();
        assertThat(events).singleElement().extracting(DetokenizeEvent::outcome)
                .isEqualTo(DetokenizeEvent.Outcome.INTEGRITY_FAILURE);
    }

    private VaultRecord record(TokenizedCard card) {
        return record(card.token());
    }

    private VaultRecord record(String token) {
        return store.findByToken(token).orElseThrow();
    }

    private static byte[] flipLastBit(byte[] bytes) {
        byte[] copy = bytes.clone();
        copy[copy.length - 1] ^= 1;
        return copy;
    }

    @Provide
    Arbitrary<String> pans() {
        return TestPans.validPans();
    }
}
