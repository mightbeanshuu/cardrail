package dev.cardrail.apisec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Client side of {@link OAuth1Verifier}: produces the same {@code Authorization} header as Mastercard's
 * {@code OAuth.getAuthorizationHeader}. Parameter values go into the header and the base string verbatim
 * (Mastercard does not percent-encode them; only {@code oauth_signature} is encoded), header format
 * {@code OAuth name="value",name="value"}.
 */
public final class OAuth1Signer {

    private static final int NONCE_BYTES = 16;

    private final String consumerKey;
    private final RSAPrivateKey signingKey;
    private final OAuthSignatureMethod method;
    private final Clock clock;
    private final SecureRandom random;

    public OAuth1Signer(String consumerKey, RSAPrivateKey signingKey) {
        this(consumerKey, signingKey, OAuthSignatureMethod.RSA_SHA256, Clock.systemUTC(), new SecureRandom());
    }

    public OAuth1Signer(String consumerKey, RSAPrivateKey signingKey, OAuthSignatureMethod method, Clock clock,
                        SecureRandom random) {
        this.consumerKey = requireHeaderSafe(consumerKey);
        this.signingKey = Objects.requireNonNull(signingKey, "signingKey");
        this.method = Objects.requireNonNull(method, "method");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * @param body request body bytes, or {@code null} for none (hashed as the empty string, as Mastercard does)
     * @return the value for the {@code Authorization} header
     */
    public String authorizationHeader(String httpMethod, URI uri, byte[] body) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return authorizationHeader(httpMethod, uri, body, HexFormat.of().formatHex(nonce),
                clock.instant().getEpochSecond());
    }

    /** Deterministic variant for interop tests; production callers must not choose their own nonce. */
    String authorizationHeader(String httpMethod, URI uri, byte[] body, String nonce, long timestamp) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(AuthorizationHeader.CONSUMER_KEY, consumerKey);
        params.put(AuthorizationHeader.NONCE, requireHeaderSafe(nonce));
        params.put(AuthorizationHeader.SIGNATURE_METHOD, method.oauthName());
        params.put(AuthorizationHeader.TIMESTAMP, Long.toString(timestamp));
        params.put(AuthorizationHeader.VERSION, "1.0");
        params.put(AuthorizationHeader.BODY_HASH,
                Base64.getEncoder().encodeToString(OAuth1Verifier.sha256(body == null ? new byte[0] : body)));

        String baseString = SignatureBaseString.of(httpMethod, uri, params);
        params.put(AuthorizationHeader.SIGNATURE, PercentEncoding.encode(sign(baseString)));

        StringJoiner header = new StringJoiner(",", "OAuth ", "");
        params.forEach((k, v) -> header.add(k + "=\"" + v + '"'));
        return header.toString();
    }

    private String sign(String baseString) {
        try {
            Signature signer = method.newSignature();
            signer.initSign(signingKey);
            signer.update(baseString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing with " + method.oauthName() + " failed", e);
        }
    }

    /**
     * Values are placed in the header unencoded (Mastercard-compatible), so they must not be able to break
     * out of the quoted string or be mistaken for percent-escapes.
     */
    private static String requireHeaderSafe(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be empty");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E || c == '"' || c == '\\' || c == '%') {
                throw new IllegalArgumentException("value must be visible ASCII without \" \\ or %");
            }
        }
        return value;
    }
}
