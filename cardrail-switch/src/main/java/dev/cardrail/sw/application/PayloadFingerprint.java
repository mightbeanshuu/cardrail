package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 over every field of the request. Two transmissions with the same key and the same
 * fingerprint are the same message sent twice.
 */
@Component
public class PayloadFingerprint {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PayloadFingerprint(DuplicateProperties properties) {
        this.key = new SecretKeySpec(properties.payloadHashKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String of(AuthorizationRequest r) {
        StringBuilder canonical = new StringBuilder(160);
        for (String field : new String[] {
                r.pan(), r.expiry(), Long.toString(r.amountMinor()), Integer.toString(r.currency()), r.mcc(),
                r.merchantId(), r.merchantName(), r.merchantCountry(), r.terminalId(), r.acquirerId(), r.stan(),
                r.rrn(), r.transmissionDateTime(), r.posEntryMode()}) {
            canonical.append(field.length()).append(':').append(field).append(';');
        }
        return HexFormat.of().formatHex(mac().doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private Mac mac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is available on every JDK", e);
        }
    }
}
