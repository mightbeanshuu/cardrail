package dev.cardrail.clearing.adapter.messaging;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Checks the signature the switch puts on each event it posts: lower-case hex HMAC-SHA256, keyed
 * with the shared secret, over {@code timestamp + "." + body}, the timestamp being epoch seconds.
 * The timestamp must be within the tolerance of this service's clock, so a recorded request cannot
 * be replayed later; a replay within the window is harmless, since each event is processed once.
 */
public class EventSignatureVerifier {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final Pattern EPOCH_SECONDS = Pattern.compile("\\d{1,12}");
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final SecretKeySpec key;
    private final Duration tolerance;
    private final Clock clock;

    public EventSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("cardrail.events.http.secret must be at least "
                    + MIN_SECRET_LENGTH + " characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /** Messages say what is wrong, never what the signature should have been. */
    public void verify(String timestamp, String signature, byte[] body) {
        if (timestamp == null || signature == null) {
            throw new InvalidSignatureException("timestamp and signature headers are required");
        }
        if (!EPOCH_SECONDS.matcher(timestamp).matches()) {
            throw new InvalidSignatureException("timestamp must be epoch seconds");
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(Long.parseLong(timestamp)), clock.instant()).abs();
        if (skew.compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("timestamp is more than " + tolerance.toMinutes()
                    + " minutes from this service's clock");
        }
        if (!HEX_SHA256.matcher(signature).matches()
                || !MessageDigest.isEqual(HexFormat.of().parseHex(signature), mac(timestamp, body))) {
            throw new InvalidSignatureException("signature does not match");
        }
    }

    private byte[] mac(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is available on every JDK", e);
        }
    }
}
