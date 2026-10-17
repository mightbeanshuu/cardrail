package dev.cardrail.sw.adapter.dev;

import dev.cardrail.sw.domain.card.Luhn;
import dev.cardrail.sw.domain.card.PanMasking;
import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.InvalidCardNumberException;
import dev.cardrail.sw.port.TokenizedCard;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Keyless alternative to the vault ({@code cardrail.tokenizer=dev}): a deterministic, keyed token in
 * the reserved BIN 999999, 19 digits and Luhn-valid. It stores nothing, so tokens cannot be turned
 * back into PANs. Not for production use; the default tokenizer is the vault.
 */
public class DevCardTokenizer implements CardTokenizer {

    private static final String TOKEN_BIN = "999999";
    private static final long TWELVE_DIGITS = 1_000_000_000_000L;

    private final SecretKeySpec key;

    public DevCardTokenizer(String key) {
        if (key == null || key.length() < 32) {
            throw new IllegalArgumentException("cardrail.dev.tokenizer.key must be at least 32 characters");
        }
        this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public TokenizedCard tokenize(String pan) {
        if (pan.length() < 13 || pan.length() > 19 || !Luhn.isValid(pan)) {
            throw new InvalidCardNumberException("card number failed length or check-digit validation");
        }
        long digits = Long.remainderUnsigned(ByteBuffer.wrap(hmac(pan)).getLong(), TWELVE_DIGITS);
        String payload = TOKEN_BIN + "%012d".formatted(digits);
        String token = payload + Luhn.checkDigit(payload);
        return new TokenizedCard(token, PanMasking.mask(pan), pan.substring(0, 6), pan.substring(pan.length() - 4));
    }

    private byte[] hmac(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is available on every JDK", e);
        }
    }
}
