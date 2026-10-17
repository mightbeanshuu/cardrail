package dev.cardrail.apisec;

import com.mastercard.developer.oauth.OAuth;
import com.mastercard.developer.oauth.SignatureMethod;
import dev.cardrail.apisec.VerificationResult.Reason;
import dev.cardrail.apisec.VerificationResult.Rejected;
import dev.cardrail.apisec.VerificationResult.Verified;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static dev.cardrail.apisec.TestKeys.priv;
import static dev.cardrail.apisec.TestKeys.pub;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests signed by Mastercard's own {@code com.mastercard.developer:oauth1-signer:1.6.0} must verify here,
 * every tampering must be rejected with the right reason, and {@link OAuth1Signer} must produce byte-identical
 * signatures to Mastercard's for the same nonce and timestamp.
 */
class MastercardOAuthInteropTest {

    /** Real Mastercard consumer keys contain '!', which neither side percent-encodes in the header. */
    static final String CONSUMER = "cardrail-acquirer-0001!4f6b2c1e9a7d4b3c8e5f0a1b2c3d4e5f";
    static final String OTHER_CONSUMER = "cardrail-acquirer-0002!0a0b0c0d0e0f";

    static final String JSON_BODY = "{\"amount\":{\"value\":\"125.00\",\"currency\":\"EUR\"},\"merchantId\":\"M-0042\"}";
    static final String UNICODE_BODY = "{\"merchant\":\"Café Zürich ☕ 東京\",\"note\":\"naïve 🚀\"}";

    final InMemoryConsumerKeyRegistry registry = new InMemoryConsumerKeyRegistry()
            .register(CONSUMER, pub(TestKeys.CLIENT))
            .register(OTHER_CONSUMER, pub(TestKeys.OTHER_CLIENT));

    OAuth1Verifier verifier(Clock clock) {
        return OAuth1Verifier.builder()
                .consumerKeys(registry)
                .nonceStore(new InMemoryNonceStore(10_000))
                .clock(clock)
                .maxClockSkew(Duration.ofMinutes(5))
                .build();
    }

    final OAuth1Verifier verifier = verifier(Clock.systemUTC());

    static String mastercardSign(String method, String uri, String body) {
        return OAuth.getAuthorizationHeader(URI.create(uri), method, body, StandardCharsets.UTF_8, CONSUMER,
                priv(TestKeys.CLIENT));
    }

    static byte[] bytes(String body) {
        return body == null ? null : body.getBytes(StandardCharsets.UTF_8);
    }

    static Reason reasonOf(VerificationResult r) {
        assertThat(r).isInstanceOf(Rejected.class);
        return ((Rejected) r).reason();
    }

    /** Requests covering the canonicalisation edge cases: method, URI, body. */
    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of("POST", "https://api.cardrail.dev/v1/authorizations", JSON_BODY),
                Arguments.of("GET", "https://api.cardrail.dev/v1/authorizations/123", null),
                Arguments.of("GET", "https://api.cardrail.dev/v1/authorizations/123", ""),
                Arguments.of("POST", "https://api.cardrail.dev/v1/merchants", UNICODE_BODY),
                Arguments.of("GET", "https://api.cardrail.dev/v1/search?b=2&a=1&a=0&q=caf%C3%A9%20au%20lait"
                        + "&sym=%21%2A%27%28%29&plus=a+b&empty=&flag", null),
                Arguments.of("GET", "https://api.cardrail.dev/v1/search?x=3&x=1&x=2&x=10", null),
                Arguments.of("GET", "https://api.cardrail.dev/v1/search?filter=a:b/c?d@e!f$g'h(i)j*k,l;m", null),
                Arguments.of("GET", "https://api.cardrail.dev/v1/search?a=1&&b=2&", null),
                Arguments.of("GET", "https://api.cardrail.dev/v1/merchants?name=Zoë&city=東京", null),
                Arguments.of("DELETE", "https://api.cardrail.dev/v1/tokens/tok%2Fabc%20def", null),
                Arguments.of("PUT", "https://API.CardRail.dev:443/v1/limits?currency=EUR", JSON_BODY),
                Arguments.of("POST", "http://localhost:8080/v1/authorizations?dryRun=true", JSON_BODY),
                Arguments.of("POST", "https://api.cardrail.dev", "x")
        );
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("requests")
    @DisplayName("Mastercard-signed request verifies")
    void mastercardSignedRequestVerifies(String method, String uri, String body) {
        String header = mastercardSign(method, uri, body);

        VerificationResult result = verifier.verify(method, URI.create(uri), bytes(body), header);

        assertThat(result).isEqualTo(new Verified(CONSUMER));
    }

    @Test
    @DisplayName("default port and host case are normalised: :443 + upper case verifies against the bare host")
    void defaultPortAndHostCaseAreNormalised() {
        String header = mastercardSign("GET", "https://API.CardRail.DEV:443/v1/ping", null);

        assertThat(verifier.verify("GET", URI.create("https://api.cardrail.dev/v1/ping"), null, header))
                .isEqualTo(new Verified(CONSUMER));
    }

    @Test
    @DisplayName("GET with no body: null and empty body are the same (hash of the empty string)")
    void emptyBodyHashesAsEmptyString() {
        String header = mastercardSign("GET", "https://api.cardrail.dev/v1/ping", null);
        assertThat(new AuthorizationHeaderView(header).bodyHash())
                .isEqualTo("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
        assertThat(verifier.verify("GET", URI.create("https://api.cardrail.dev/v1/ping"), new byte[0], header))
                .isInstanceOf(Verified.class);
    }

    @Nested
    @DisplayName("tampering")
    class Tampering {

        static final String URI_TEXT = "https://api.cardrail.dev/v1/authorizations?acquirer=000123&mode=live";
        final String header = mastercardSign("POST", URI_TEXT, JSON_BODY);

        Reason verify(String method, String uri, String body, String authorization) {
            return reasonOf(verifier.verify(method, URI.create(uri), bytes(body), authorization));
        }

        @Test
        void bodyChanged() {
            String changed = JSON_BODY.replace("125.00", "925.00");
            assertThat(verify("POST", URI_TEXT, changed, header)).isEqualTo(Reason.BODY_HASH_MISMATCH);
        }

        @Test
        void bodyChangedAndBodyHashRecomputed() {
            String changed = JSON_BODY.replace("125.00", "925.00");
            String forgedHash = Base64.getEncoder().encodeToString(OAuth1Verifier.sha256(bytes(changed)));
            String forged = new AuthorizationHeaderView(header).with("oauth_body_hash", forgedHash);
            assertThat(verify("POST", URI_TEXT, changed, forged)).isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void bodyRemoved() {
            assertThat(verify("POST", URI_TEXT, null, header)).isEqualTo(Reason.BODY_HASH_MISMATCH);
        }

        @Test
        void pathChanged() {
            assertThat(verify("POST", URI_TEXT.replace("/v1/authorizations", "/v1/refunds"), JSON_BODY, header))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void hostChanged() {
            assertThat(verify("POST", URI_TEXT.replace("api.cardrail.dev", "api.attacker.dev"), JSON_BODY, header))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void schemeChanged() {
            assertThat(verify("POST", URI_TEXT.replace("https:", "http:"), JSON_BODY, header))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void portAdded() {
            assertThat(verify("POST", URI_TEXT.replace("cardrail.dev/", "cardrail.dev:8443/"), JSON_BODY, header))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void queryValueChanged() {
            assertThat(verify("POST", URI_TEXT.replace("mode=live", "mode=test"), JSON_BODY, header))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void queryParamAdded() {
            assertThat(verify("POST", URI_TEXT + "&mode=test", JSON_BODY, header)).isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void queryParamRemoved() {
            assertThat(verify("POST", URI_TEXT.replace("&mode=live", ""), JSON_BODY, header))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void methodChanged() {
            assertThat(verify("PUT", URI_TEXT, JSON_BODY, header)).isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void timestampEditedInHeader() {
            AuthorizationHeaderView view = new AuthorizationHeaderView(header);
            String edited = view.with("oauth_timestamp", Long.toString(Long.parseLong(view.timestamp()) - 1));
            assertThat(verify("POST", URI_TEXT, JSON_BODY, edited)).isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void nonceEditedInHeader() {
            String edited = new AuthorizationHeaderView(header).with("oauth_nonce", "AAAAAAAAAAAAAAAA");
            assertThat(verify("POST", URI_TEXT, JSON_BODY, edited)).isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void consumerKeySwappedToAnotherRegisteredConsumer() {
            String edited = new AuthorizationHeaderView(header).with("oauth_consumer_key", OTHER_CONSUMER);
            assertThat(verify("POST", URI_TEXT, JSON_BODY, edited)).isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void signatureFromAnotherRequestTransplanted() {
            String other = mastercardSign("POST", URI_TEXT.replace("live", "test"), JSON_BODY);
            String transplanted = new AuthorizationHeaderView(header)
                    .with("oauth_signature", new AuthorizationHeaderView(other).wire("oauth_signature"));
            assertThat(verify("POST", URI_TEXT, JSON_BODY, transplanted)).isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void signatureMethodDowngradedInHeader() {
            String edited = new AuthorizationHeaderView(header).with("oauth_signature_method", "HMAC-SHA1");
            assertThat(verify("POST", URI_TEXT, JSON_BODY, edited)).isEqualTo(Reason.UNSUPPORTED_METHOD);
        }
    }

    @Nested
    @DisplayName("freshness and replay")
    class FreshnessAndReplay {

        static final String URI_TEXT = "https://api.cardrail.dev/v1/authorizations";

        @Test
        void replayOfAcceptedHeaderIsRejected() {
            String header = mastercardSign("POST", URI_TEXT, JSON_BODY);
            assertThat(verifier.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header))
                    .isInstanceOf(Verified.class);
            assertThat(reasonOf(verifier.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header)))
                    .isEqualTo(Reason.REPLAYED_NONCE);
        }

        @Test
        void rejectedAttemptDoesNotConsumeTheNonce() {
            String header = mastercardSign("POST", URI_TEXT, JSON_BODY);
            assertThat(reasonOf(verifier.verify("POST", URI.create(URI_TEXT), bytes("{}"), header)))
                    .isEqualTo(Reason.BODY_HASH_MISMATCH);
            assertThat(verifier.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header))
                    .isInstanceOf(Verified.class);
        }

        @Test
        void serverClockSixMinutesAheadMeansStale() {
            String header = mastercardSign("POST", URI_TEXT, JSON_BODY);
            OAuth1Verifier late = verifier(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6)));
            assertThat(reasonOf(late.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header)))
                    .isEqualTo(Reason.STALE_TIMESTAMP);
        }

        @Test
        void serverClockSixMinutesBehindMeansStale() {
            String header = mastercardSign("POST", URI_TEXT, JSON_BODY);
            OAuth1Verifier early = verifier(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-6)));
            assertThat(reasonOf(early.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header)))
                    .isEqualTo(Reason.STALE_TIMESTAMP);
        }

        @Test
        void serverClockFourMinutesAheadIsWithinWindow() {
            String header = mastercardSign("POST", URI_TEXT, JSON_BODY);
            OAuth1Verifier skewed = verifier(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(4)));
            assertThat(skewed.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header))
                    .isInstanceOf(Verified.class);
        }
    }

    @Nested
    @DisplayName("consumers and signature methods")
    class ConsumersAndMethods {

        static final String URI_TEXT = "https://api.cardrail.dev/v1/authorizations";

        @Test
        void unknownConsumer() {
            String header = OAuth.getAuthorizationHeader(URI.create(URI_TEXT), "POST", JSON_BODY,
                    StandardCharsets.UTF_8, "never-registered!00", priv(TestKeys.CLIENT));
            assertThat(reasonOf(verifier.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header)))
                    .isEqualTo(Reason.UNKNOWN_CONSUMER);
        }

        @Test
        void signedWithKeyOtherThanTheRegisteredOne() {
            String header = OAuth.getAuthorizationHeader(URI.create(URI_TEXT), "POST", JSON_BODY,
                    StandardCharsets.UTF_8, CONSUMER, priv(TestKeys.OTHER_CLIENT));
            assertThat(reasonOf(verifier.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header)))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }

        @Test
        void rsaPssIsRejectedUnlessEnabled() {
            String header = OAuth.getAuthorizationHeader(URI.create(URI_TEXT), "POST", JSON_BODY,
                    StandardCharsets.UTF_8, CONSUMER, priv(TestKeys.CLIENT), SignatureMethod.RSA_PSS_SHA256);
            assertThat(new AuthorizationHeaderView(header).wire("oauth_signature_method")).isEqualTo("RSA-PSS");
            assertThat(reasonOf(verifier.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header)))
                    .isEqualTo(Reason.UNSUPPORTED_METHOD);
        }

        @Test
        void rsaPssVerifiesWhenEnabled() {
            String header = OAuth.getAuthorizationHeader(URI.create(URI_TEXT), "POST", JSON_BODY,
                    StandardCharsets.UTF_8, CONSUMER, priv(TestKeys.CLIENT), SignatureMethod.RSA_PSS_SHA256);
            OAuth1Verifier pss = OAuth1Verifier.builder()
                    .consumerKeys(registry)
                    .nonceStore(new InMemoryNonceStore(16))
                    .allowedSignatureMethods(EnumSet.of(OAuthSignatureMethod.RSA_SHA256, OAuthSignatureMethod.RSA_PSS))
                    .build();
            assertThat(pss.verify("POST", URI.create(URI_TEXT), bytes(JSON_BODY), header))
                    .isEqualTo(new Verified(CONSUMER));
            assertThat(reasonOf(pss.verify("PATCH", URI.create(URI_TEXT), bytes(JSON_BODY), header)))
                    .isEqualTo(Reason.BAD_SIGNATURE);
        }
    }

    /** Queries this verifier refuses to normalise rather than guess at. */
    @Nested
    @DisplayName("malformed queries fail closed")
    class MalformedQueries {

        @Test
        void invalidUtf8InQueryIsRejectedNotReplaced() {
            String sent = "https://api.cardrail.dev/v1/search?a=%FF";
            String header = mastercardSign("GET", sent, null);

            assertThat(reasonOf(verifier.verify("GET", URI.create(sent), null, header)))
                    .isEqualTo(Reason.MALFORMED_REQUEST);
        }
    }

    @Nested
    @DisplayName("OAuth1Signer against Mastercard's signer")
    class SignerInterop {

        /**
         * Mastercard's API does not accept a nonce or timestamp, so this signs with Mastercard first, reads
         * its nonce and timestamp from the header, and signs the same request with ours. RSA-SHA256
         * (PKCS#1 v1.5) is deterministic, so identical signatures prove identical signature base strings.
         */
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("dev.cardrail.apisec.MastercardOAuthInteropTest#requests")
        void sameNonceAndTimestampGiveIdenticalHeaderParameters(String method, String uri, String body) {
            AuthorizationHeaderView theirs = new AuthorizationHeaderView(mastercardSign(method, uri, body));

            OAuth1Signer signer = new OAuth1Signer(CONSUMER, priv(TestKeys.CLIENT));
            AuthorizationHeaderView ours = new AuthorizationHeaderView(signer.authorizationHeader(method,
                    URI.create(uri), bytes(body), theirs.wire("oauth_nonce"), Long.parseLong(theirs.timestamp())));

            for (String name : List.of("oauth_consumer_key", "oauth_nonce", "oauth_signature_method",
                    "oauth_timestamp", "oauth_version", "oauth_body_hash", "oauth_signature")) {
                assertThat(ours.wire(name)).as(name).isEqualTo(theirs.wire(name));
            }
        }

        @Test
        void headerFormatMatchesMastercardsLayout() {
            String header = new OAuth1Signer(CONSUMER, priv(TestKeys.CLIENT))
                    .authorizationHeader("GET", URI.create("https://api.cardrail.dev/v1/ping"), null);
            assertThat(header).matches("OAuth (oauth_[a-z_]+=\"[^\"]+\",){6}oauth_signature=\"[^\"]+\"");
        }

        @Test
        void ourRandomNonceHeaderVerifies() {
            OAuth1Signer signer = new OAuth1Signer(CONSUMER, priv(TestKeys.CLIENT), OAuthSignatureMethod.RSA_SHA256,
                    Clock.systemUTC(), new SecureRandom());
            URI uri = URI.create("https://api.cardrail.dev/v1/search?q=caf%C3%A9&x=2&x=1");
            String header = signer.authorizationHeader("POST", uri, bytes(UNICODE_BODY));
            assertThat(verifier.verify("POST", uri, bytes(UNICODE_BODY), header)).isEqualTo(new Verified(CONSUMER));
        }
    }

    /** Test-side view over a header string, via the production parser. */
    record AuthorizationHeaderView(String header) {

        AuthorizationHeader parsed() {
            try {
                return AuthorizationHeader.parse(header);
            } catch (AuthorizationHeader.MalformedHeaderException e) {
                throw new AssertionError("Mastercard header rejected by parser: " + e.getMessage(), e);
            }
        }

        String wire(String name) {
            return parsed().wire(name);
        }

        String timestamp() {
            return wire("oauth_timestamp");
        }

        String bodyHash() {
            return wire("oauth_body_hash");
        }

        String with(String name, String newWireValue) {
            String old = name + "=\"" + wire(name) + "\"";
            assertThat(header).contains(old);
            return header.replace(old, name + "=\"" + newWireValue + "\"");
        }
    }
}
