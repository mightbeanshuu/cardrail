package dev.cardrail.apisec;

import dev.cardrail.apisec.VerificationResult.Reason;
import dev.cardrail.apisec.VerificationResult.Rejected;
import dev.cardrail.apisec.VerificationResult.Verified;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side verification of Mastercard-style OAuth 1.0a requests: RSA signature over the RFC 5849 signature
 * base string, plus {@code oauth_body_hash} = base64(SHA-256(body)) binding the entity body.
 *
 * <p>Checks run cheapest first; the nonce is recorded only after the signature verifies, so unauthenticated
 * traffic cannot fill the nonce store or burn a legitimate client's nonces.
 *
 * <p>The {@code requestUri} passed to {@link #verify} must be the URI the client addressed (scheme, host,
 * non-default port, raw path and raw query). Behind a TLS-terminating proxy, rebuild it from the configured
 * public base URL plus the raw request-target, not from the internal hop.
 */
public final class OAuth1Verifier {

    static final int MAX_NONCE_LENGTH = 128;
    private static final int SHA256_LENGTH = 32;

    private final ConsumerKeyRegistry consumerKeys;
    private final NonceStore nonces;
    private final Clock clock;
    private final Duration maxClockSkew;
    private final Set<OAuthSignatureMethod> allowedMethods;

    private OAuth1Verifier(Builder b) {
        this.consumerKeys = Objects.requireNonNull(b.consumerKeys, "consumerKeys");
        this.nonces = Objects.requireNonNull(b.nonces, "nonceStore");
        this.clock = b.clock;
        this.maxClockSkew = b.maxClockSkew;
        this.allowedMethods = Set.copyOf(b.allowedMethods);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param method              HTTP method as received
     * @param requestUri          absolute URI the client addressed
     * @param body                raw entity body bytes; {@code null} or empty for a request without a body
     * @param authorizationHeader value of the {@code Authorization} header, or {@code null} if absent
     */
    public VerificationResult verify(String method, URI requestUri, byte[] body, String authorizationHeader) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestUri, "requestUri");

        AuthorizationHeader header;
        try {
            header = AuthorizationHeader.parse(authorizationHeader);
        } catch (AuthorizationHeader.MalformedHeaderException e) {
            return reject(Reason.MALFORMED_HEADER, e.getMessage());
        }

        String version = header.decoded(AuthorizationHeader.VERSION);
        if (version != null && !version.equals("1.0")) {
            return reject(Reason.MALFORMED_HEADER, "oauth_version must be 1.0");
        }

        String methodName = header.decoded(AuthorizationHeader.SIGNATURE_METHOD);
        Optional<OAuthSignatureMethod> signatureMethod = OAuthSignatureMethod.fromOAuthName(methodName)
                .filter(allowedMethods::contains);
        if (signatureMethod.isEmpty()) {
            return reject(Reason.UNSUPPORTED_METHOD, "signature method " + methodName + " not accepted");
        }

        String timestampText = header.decoded(AuthorizationHeader.TIMESTAMP);
        if (!isDigits(timestampText, 12)) {
            return reject(Reason.MALFORMED_HEADER, "oauth_timestamp must be 1-12 decimal digits");
        }
        long timestamp = Long.parseLong(timestampText);
        Instant now = clock.instant();
        if (Math.abs(now.getEpochSecond() - timestamp) > maxClockSkew.toSeconds()) {
            return reject(Reason.STALE_TIMESTAMP, "timestamp " + timestamp + " outside +/-" + maxClockSkew);
        }

        String nonce = header.decoded(AuthorizationHeader.NONCE);
        if (nonce.isEmpty() || nonce.length() > MAX_NONCE_LENGTH) {
            return reject(Reason.MALFORMED_HEADER, "oauth_nonce must be 1-" + MAX_NONCE_LENGTH + " characters");
        }

        String consumerKey = header.decoded(AuthorizationHeader.CONSUMER_KEY);
        Optional<RSAPublicKey> publicKey = consumerKeys.publicKeyFor(consumerKey);
        if (publicKey.isEmpty()) {
            return reject(Reason.UNKNOWN_CONSUMER, "no key registered for consumer");
        }

        byte[] claimedBodyHash;
        byte[] signature;
        try {
            claimedBodyHash = Base64.getDecoder().decode(header.decoded(AuthorizationHeader.BODY_HASH));
            signature = Base64.getDecoder().decode(header.decoded(AuthorizationHeader.SIGNATURE));
        } catch (IllegalArgumentException e) {
            return reject(Reason.MALFORMED_HEADER, "oauth_body_hash or oauth_signature is not base64");
        }
        byte[] actualBodyHash = sha256(body == null ? new byte[0] : body);
        if (claimedBodyHash.length != SHA256_LENGTH || !MessageDigest.isEqual(claimedBodyHash, actualBodyHash)) {
            return reject(Reason.BODY_HASH_MISMATCH, "oauth_body_hash does not match the received body");
        }

        String baseString;
        try {
            baseString = SignatureBaseString.of(method, requestUri, header.signedParameters());
        } catch (IllegalArgumentException e) {
            return reject(Reason.MALFORMED_REQUEST, e.getMessage());
        }

        if (!signatureValid(signatureMethod.get(), publicKey.get(), baseString, signature)) {
            return reject(Reason.BAD_SIGNATURE, "signature does not verify");
        }

        // The acceptance check above truncates `now` to whole seconds, so a timestamp stays acceptable until
        // timestamp + skew + 1s; the entry must outlive that or the last second of the window is replayable.
        Instant expiresAt = Instant.ofEpochSecond(timestamp).plus(maxClockSkew).plusSeconds(1);
        return switch (nonces.record(consumerKey, nonce, expiresAt, now)) {
            case RECORDED -> new Verified(consumerKey);
            case ALREADY_SEEN -> reject(Reason.REPLAYED_NONCE, "nonce already used");
            case CAPACITY_EXHAUSTED -> reject(Reason.NONCE_STORE_FULL, "nonce store full");
        };
    }

    private static boolean signatureValid(OAuthSignatureMethod method, RSAPublicKey key, String baseString,
                                          byte[] signature) {
        try {
            Signature verifier = method.newSignature();
            verifier.initVerify(key);
            verifier.update(baseString.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every Java SE runtime", e);
        }
    }

    private static boolean isDigits(String s, int maxLength) {
        if (s.isEmpty() || s.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static Rejected reject(Reason reason, String detail) {
        return new Rejected(reason, detail);
    }

    public static final class Builder {
        private ConsumerKeyRegistry consumerKeys;
        private NonceStore nonces;
        private Clock clock = Clock.systemUTC();
        private Duration maxClockSkew = Duration.ofMinutes(5);
        private Set<OAuthSignatureMethod> allowedMethods = EnumSet.of(OAuthSignatureMethod.RSA_SHA256);

        private Builder() {
        }

        public Builder consumerKeys(ConsumerKeyRegistry registry) {
            this.consumerKeys = registry;
            return this;
        }

        public Builder nonceStore(NonceStore store) {
            this.nonces = store;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** Maximum allowed |now - oauth_timestamp|, whole seconds. Default 5 minutes. */
        public Builder maxClockSkew(Duration skew) {
            if (skew.isNegative() || skew.isZero() || skew.toNanosPart() != 0) {
                throw new IllegalArgumentException("skew must be a positive whole number of seconds");
            }
            this.maxClockSkew = skew;
            return this;
        }

        /** Default: {@code RSA-SHA256} only, the method Mastercard's signer uses unless told otherwise. */
        public Builder allowedSignatureMethods(Set<OAuthSignatureMethod> methods) {
            if (methods.isEmpty()) {
                throw new IllegalArgumentException("at least one signature method must be allowed");
            }
            this.allowedMethods = EnumSet.copyOf(methods);
            return this;
        }

        public OAuth1Verifier build() {
            return new OAuth1Verifier(this);
        }
    }
}
