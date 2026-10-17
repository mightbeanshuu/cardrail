package dev.cardrail.sw.adapter.vault;

import dev.cardrail.sw.domain.audit.ChainVerification;
import dev.cardrail.sw.domain.card.Luhn;
import dev.cardrail.sw.port.AuditTrail;
import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.InvalidCardNumberException;
import dev.cardrail.sw.port.TokenizedCard;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.TestFixtures;
import dev.cardrail.vault.DetokenizeReason;
import dev.cardrail.vault.KeyStatus;
import dev.cardrail.vault.Pan;
import dev.cardrail.vault.TokenFormat;
import dev.cardrail.vault.TokenVault;
import dev.cardrail.vault.UnknownTokenException;
import dev.cardrail.vault.VaultRecord;
import dev.cardrail.vault.VaultStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CardrailIntegrationTest
class VaultTokenizationIntegrationTest {

    private static final String PAN = "5555555555554444";
    private static final AtomicLong UNIQUE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired TestFixtures fixtures;
    @Autowired CardTokenizer tokenizer;
    @Autowired TokenVault vault;
    @Autowired VaultProperties properties;
    @Autowired AuditTrail audit;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void theSwitchTokenizesThroughTheVaultAndStoresNoPan() {
        TokenizedCard card = tokenizer.tokenize(PAN);

        assertThat(card.token()).hasSize(16).startsWith("999999").isNotEqualTo(PAN);
        assertThat(Luhn.isValid(card.token())).isTrue();
        assertThat(card.maskedPan()).isEqualTo("555555******4444");
        assertThat(card.bin()).isEqualTo("555555");
        assertThat(card.last4()).isEqualTo("4444");

        VaultRecord row = store().findByToken(card.token()).orElseThrow();
        assertThat(row.maskedPan()).isEqualTo("555555******4444");
        assertThat(row.dekKeyVersion()).isEqualTo("dev-kek-v1");
        assertThat(new String(row.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain(PAN);
        assertThat(jdbc.sql("SELECT count(*) FROM vault_record WHERE masked_pan = '555555******4444'")
                .query(Long.class).single()).isOne();
    }

    @Test
    void samePanGivesTheSameTokenEveryTimeAndAfterARestart() {
        String pan = freshPan();
        String token = tokenizer.tokenize(pan).token();

        assertThat(tokenizer.tokenize(pan).token()).isEqualTo(token);
        assertThat(tokenizer.tokenize(freshPan()).token()).isNotEqualTo(token);

        // A restart is a new vault over the same table, with keys decoded again from configuration.
        TokenVault restarted = new TokenVault(store(), ConfiguredKeyProvider.from(properties), new TokenFormat(),
                new AuditTrailVaultListener(audit, transactions), clock);
        assertThat(new VaultCardTokenizer(restarted).tokenize(pan).token()).isEqualTo(token);
        Pan back = restarted.detokenize(token, DetokenizeReason.CLEARING);
        assertThat(back.exposeFullPanUnsafe()).isEqualTo(pan.toCharArray());
        back.destroy();
    }

    @Test
    void concurrentFirstSightingsOfOnePanAgreeOnOneToken() throws Exception {
        String pan = freshPan();
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        Set<String> tokens = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    tokens.add(tokenizer.tokenize(pan).token());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(tokens).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM vault_record WHERE token = :token")
                .param("token", tokens.iterator().next()).query(Long.class).single()).isOne();
    }

    @Test
    void storeTellsTheTwoConflictsApart() {
        JdbcVaultStore store = store();
        String token = tokenizer.tokenize(PAN).token();
        VaultRecord winner = store.findByToken(token).orElseThrow();

        VaultRecord samePanOtherToken = copy(winner, nextFreeToken(), winner.blindIndex());
        assertThat(store.insertIfAbsent(samePanOtherToken))
                .isInstanceOfSatisfying(VaultStore.InsertOutcome.AlreadyTokenized.class,
                        lost -> assertThat(lost.existing().token()).isEqualTo(token));

        VaultRecord otherPanSameToken = copy(winner, token, randomBytes(32));
        assertThat(store.insertIfAbsent(otherPanSameToken)).isInstanceOf(VaultStore.InsertOutcome.TokenTaken.class);

        assertThat(jdbc.sql("SELECT count(*) FROM vault_record").query(Long.class).single()).isOne();
    }

    @Test
    void rewrapIsCompareAndSetOnTheKeyVersion() {
        JdbcVaultStore store = store();
        String token = tokenizer.tokenize(PAN).token();

        assertThat(store.rewrap(token, "kek-elsewhere", "kek-v9", randomBytes(60))).isFalse();
        assertThat(store.findByToken(token).orElseThrow().dekKeyVersion()).isEqualTo("dev-kek-v1");
        assertThat(store.findWrappedUnderOtherThan("dev-kek-v1", 10)).isEmpty();
        assertThat(store.findWrappedUnderOtherThan("kek-v9", 10)).extracting(VaultRecord::token).containsExactly(token);
    }

    @Test
    void rotationRewrapsEveryRecordWithoutChangingTokens() {
        String token = tokenizer.tokenize(PAN).token();
        String otherToken = tokenizer.tokenize(freshPan()).token();
        String newKek = Base64.getEncoder().encodeToString(randomBytes(32));
        VaultProperties.Kek devKek = properties.keks().getFirst();

        TokenVault rotated = vaultWith(List.of(
                new VaultProperties.Kek(devKek.id(), devKek.key(), KeyStatus.DECRYPT_ONLY),
                new VaultProperties.Kek("kek-v2", newKek, KeyStatus.ACTIVE)));
        assertThat(rotated.rewrapAll()).isEqualTo(2);
        assertThat(rotated.rewrapAll()).isZero();
        assertThat(store().findByToken(token).orElseThrow().dekKeyVersion()).isEqualTo("kek-v2");
        assertThat(rotated.detokenize(token, DetokenizeReason.DISPUTE).masked()).isEqualTo("555555******4444");
        assertThat(new VaultCardTokenizer(rotated).tokenize(PAN).token()).isEqualTo(token);

        // Rotate back so the rest of the suite, which only knows the dev KEK, can still read every record.
        TokenVault back = vaultWith(List.of(devKek, new VaultProperties.Kek("kek-v2", newKek, KeyStatus.DECRYPT_ONLY)));
        assertThat(back.rewrapAll()).isEqualTo(2);
        assertThat(vault.detokenize(otherToken, DetokenizeReason.DISPUTE).masked()).startsWith("520082");
    }

    @Test
    void everyDetokenizeIsInTheAuditChainWithoutThePan() {
        String token = tokenizer.tokenize(PAN).token();

        Pan pan = vault.detokenize(token, DetokenizeReason.CLEARING);
        pan.destroy();
        assertThatThrownBy(() -> vault.detokenize(PAN, DetokenizeReason.AUTHORIZATION))
                .isInstanceOf(UnknownTokenException.class);
        assertThatThrownBy(() -> vault.detokenize("9999990000000006", DetokenizeReason.REVERSAL))
                .isInstanceOf(UnknownTokenException.class);

        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT actor, action, entity, entity_id, details::text AS details
                          FROM audit_log WHERE action = 'PAN_DETOKENIZE' ORDER BY seq
                        """)
                .query().listOfRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("entity_id"))
                .containsExactly(token, AuditTrailVaultListener.MALFORMED, "9999990000000006");
        assertThat(rows.get(0).get("details").toString())
                .contains("\"outcome\": \"GRANTED\"").contains("\"reason\": \"CLEARING\"").contains("dev-kek-v1");
        assertThat(rows.get(1).get("details").toString()).contains("MALFORMED_TOKEN");
        assertThat(rows.get(2).get("details").toString()).contains("UNKNOWN_TOKEN");
        assertThat(rows.toString()).doesNotContain(PAN);
        assertThat(audit.verify()).isInstanceOf(ChainVerification.Intact.class);
    }

    @Test
    void aTokenPresentedAsACardIsAnInvalidCardNumber() {
        String token = tokenizer.tokenize(PAN).token();

        assertThatThrownBy(() -> tokenizer.tokenize(token)).isInstanceOf(InvalidCardNumberException.class);
        assertThatThrownBy(() -> tokenizer.tokenize("5555555555554445"))
                .isInstanceOf(InvalidCardNumberException.class)
                .message().doesNotContain("5555555555554445");
    }

    @Test
    void theConfiguredKeysAreTheDevKeysTheGuardRecognises() {
        assertThatThrownBy(() -> VaultConfiguration.refuseDevKeysOutsideDev(properties, new MockEnvironment()))
                .hasMessageContaining("cardrail.vault.keks[dev-kek-v1]")
                .hasMessageContaining("cardrail.vault.blind-index-key");
    }

    private TokenVault vaultWith(List<VaultProperties.Kek> keks) {
        VaultProperties p = new VaultProperties(properties.tokenBin(), keks, properties.blindIndexKey());
        return new TokenVault(store(), ConfiguredKeyProvider.from(p), new TokenFormat(),
                new AuditTrailVaultListener(audit, transactions), clock);
    }

    private JdbcVaultStore store() {
        return new JdbcVaultStore(jdbc);
    }

    private String nextFreeToken() {
        TokenFormat format = new TokenFormat();
        String candidate;
        do {
            candidate = format.next();
        } while (store().findByToken(candidate).isPresent());
        return candidate;
    }

    private VaultRecord copy(VaultRecord r, String token, byte[] blindIndex) {
        return new VaultRecord(token, blindIndex, r.wrappedDek(), r.dekKeyVersion(), r.iv(), r.ciphertext(),
                r.maskedPan(), clock.instant());
    }

    /** A Luhn-valid Mastercard-range PAN not used by any other test in this run. */
    private static String freshPan() {
        String payload = "520082" + "%09d".formatted(UNIQUE.incrementAndGet() % 1_000_000_000L);
        return payload + Luhn.checkDigit(payload);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
