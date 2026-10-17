package dev.cardrail.apisec;

import dev.cardrail.apisec.VerificationResult.Reason;
import dev.cardrail.apisec.VerificationResult.Rejected;
import dev.cardrail.apisec.VerificationResult.Verified;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.cardrail.apisec.TestKeys.priv;
import static dev.cardrail.apisec.TestKeys.pub;
import static org.assertj.core.api.Assertions.assertThat;

class OAuth1VerifierTest {

    static final String CONSUMER = "consumer-a!01";
    static final String OTHER = "consumer-b!02";
    static final URI URI_ = URI.create("https://api.cardrail.dev/v1/authorizations?x=1");
    static final byte[] BODY = "{\"amount\":1}".getBytes(StandardCharsets.UTF_8);
    static final long T0 = 1_790_000_000L;

    final MutableClock clock = new MutableClock(Instant.ofEpochSecond(T0));
    final InMemoryConsumerKeyRegistry registry = new InMemoryConsumerKeyRegistry()
            .register(CONSUMER, pub(TestKeys.CLIENT))
            .register(OTHER, pub(TestKeys.OTHER_CLIENT));
    final OAuth1Signer signer = new OAuth1Signer(CONSUMER, priv(TestKeys.CLIENT));

    OAuth1Verifier verifier(NonceStore store) {
        return OAuth1Verifier.builder()
                .consumerKeys(registry)
                .nonceStore(store)
                .clock(clock)
                .maxClockSkew(Duration.ofSeconds(300))
                .build();
    }

    final OAuth1Verifier verifier = verifier(new InMemoryNonceStore(1000));

    String sign(String nonce, long timestamp) {
        return signer.authorizationHeader("POST", URI_, BODY, nonce, timestamp);
    }

    Reason reason(String header) {
        VerificationResult r = verifier.verify("POST", URI_, BODY, header);
        assertThat(r).isInstanceOf(Rejected.class);
        return ((Rejected) r).reason();
    }

    @Test
    void ownHeaderVerifies() {
        assertThat(verifier.verify("POST", URI_, BODY, sign("n1", T0))).isEqualTo(new Verified(CONSUMER));
    }

    @Test
    void whitespaceAroundCommasAndLowerCaseSchemeAreAccepted() {
        String header = sign("n1", T0).replaceFirst("^OAuth ", "oauth   ").replace("\",", "\" ,\t ");
        assertThat(verifier.verify("POST", URI_, BODY, header)).isInstanceOf(Verified.class);
    }

    @Test
    void realmIsAllowedAndNotSigned() {
        String header = sign("n1", T0).replaceFirst("^OAuth ", "OAuth realm=\"cardrail\",");
        assertThat(verifier.verify("POST", URI_, BODY, header)).isInstanceOf(Verified.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "Bearer abc",
            "OAuth",
            "OAuth ",
            "OAuthoauth_nonce=\"x\"",
            "OAuth oauth_nonce=x",
            "OAuth oauth_nonce=\"x",
            "OAuth oauth_nonce=\"x\",",
            "OAuth oauth_nonce=\"x\";oauth_timestamp=\"1\"",
            "OAuth oauth_nonce=\"a b\"",
            "OAuth oauth_nonce=\"a\\\"b\""
    })
    void syntacticallyBrokenHeadersAreMalformed(String header) {
        assertThat(reason(header)).isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void absentHeaderIsMalformed() {
        assertThat(reason(null)).isEqualTo(Reason.MALFORMED_HEADER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"oauth_consumer_key", "oauth_nonce", "oauth_signature_method", "oauth_timestamp",
            "oauth_body_hash", "oauth_signature"})
    void everyRequiredParameterIsRequired(String name) {
        String header = sign("n1", T0).replaceAll(",?" + name + "=\"[^\"]*\"", "").replace("OAuth ,", "OAuth ");
        assertThat(reason(header)).isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void duplicateParameterIsMalformed() {
        assertThat(reason(sign("n1", T0) + ",oauth_nonce=\"n2\"")).isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void unsupportedParameterIsMalformed() {
        assertThat(reason(sign("n1", T0) + ",oauth_token=\"t\"")).isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void invalidPercentEscapeIsMalformed() {
        assertThat(reason(sign("n1", T0).replace("oauth_nonce=\"n1\"", "oauth_nonce=\"n%G1\"")))
                .isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void versionOtherThanOnePointZeroIsMalformed() {
        assertThat(reason(sign("n1", T0).replace("oauth_version=\"1.0\"", "oauth_version=\"2.0\"")))
                .isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void nonNumericTimestampIsMalformed() {
        assertThat(reason(sign("n1", T0).replace("oauth_timestamp=\"" + T0 + "\"", "oauth_timestamp=\"17e8\"")))
                .isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void oversizedNonceIsMalformed() {
        assertThat(reason(sign("n".repeat(129), T0))).isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void signatureThatIsNotBase64IsMalformed() {
        String header = sign("n1", T0).replaceAll("oauth_signature=\"[^\"]*\"", "oauth_signature=\"!!!\"");
        assertThat(reason(header)).isEqualTo(Reason.MALFORMED_HEADER);
    }

    @Test
    void relativeRequestUriIsMalformedRequest() {
        VerificationResult r = verifier.verify("POST", URI.create("/v1/authorizations?x=1"), BODY, sign("n1", T0));
        assertThat(r).isEqualTo(new Rejected(Reason.MALFORMED_REQUEST, "request URI must be absolute with an authority"));
    }

    @Test
    void windowEdgesAreInclusiveAndTheNonceOutlivesThem() {
        String header = sign("edge", T0);

        clock.set(Instant.ofEpochSecond(T0 + 300));
        assertThat(verifier.verify("POST", URI_, BODY, header)).isInstanceOf(Verified.class);

        clock.set(Instant.ofEpochSecond(T0 + 300).plusMillis(999));
        assertThat(reason(header)).as("still inside the window, so still a replay").isEqualTo(Reason.REPLAYED_NONCE);

        clock.set(Instant.ofEpochSecond(T0 + 301));
        assertThat(reason(header)).isEqualTo(Reason.STALE_TIMESTAMP);
    }

    @Test
    void futureTimestampInsideSkewIsAccepted() {
        assertThat(verifier.verify("POST", URI_, BODY, sign("future", T0 + 300))).isInstanceOf(Verified.class);
        assertThat(reason(sign("future2", T0 + 301))).isEqualTo(Reason.STALE_TIMESTAMP);
    }

    @Test
    void noncesAreScopedPerConsumer() {
        OAuth1Signer other = new OAuth1Signer(OTHER, priv(TestKeys.OTHER_CLIENT));
        assertThat(verifier.verify("POST", URI_, BODY, sign("shared", T0))).isInstanceOf(Verified.class);
        assertThat(verifier.verify("POST", URI_, BODY, other.authorizationHeader("POST", URI_, BODY, "shared", T0)))
                .isEqualTo(new Verified(OTHER));
    }

    @Test
    void concurrentReplaysAdmitExactlyOne() throws Exception {
        String header = sign("race", T0);
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<VerificationResult>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return verifier.verify("POST", URI_, BODY, header);
                }));
            }
            start.countDown();
            long verified = 0;
            for (Future<VerificationResult> f : results) {
                VerificationResult r = f.get();
                if (r.isVerified()) {
                    verified++;
                } else {
                    assertThat(((Rejected) r).reason()).isEqualTo(Reason.REPLAYED_NONCE);
                }
            }
            assertThat(verified).isEqualTo(1);
        }
    }

    @Test
    void fullNonceStoreFailsClosedUntilEntriesExpire() {
        OAuth1Verifier small = verifier(new InMemoryNonceStore(2));
        assertThat(small.verify("POST", URI_, BODY, sign("a", T0))).isInstanceOf(Verified.class);
        assertThat(small.verify("POST", URI_, BODY, sign("b", T0))).isInstanceOf(Verified.class);
        VerificationResult third = small.verify("POST", URI_, BODY, sign("c", T0));
        assertThat(((Rejected) third).reason()).isEqualTo(Reason.NONCE_STORE_FULL);

        clock.set(Instant.ofEpochSecond(T0 + 302));
        assertThat(small.verify("POST", URI_, BODY, sign("c", T0 + 302))).isInstanceOf(Verified.class);
    }

    @Test
    void registryRefusesShortKeys() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        java.security.interfaces.RSAPublicKey weak = (java.security.interfaces.RSAPublicKey) g.generateKeyPair().getPublic();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new InMemoryConsumerKeyRegistry().register("k", weak))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signerRefusesValuesThatWouldBreakTheHeader() {
        for (String bad : List.of("a\"b", "a b", "a%20b", "a\\b", "")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OAuth1Signer(bad, priv(TestKeys.CLIENT)))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
