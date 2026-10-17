package dev.cardrail.clearing.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

/** Builds {@code POST /v1/events} requests the way the switch's HTTP outbox transport sends them. */
public final class SignedEvents {

    private SignedEvents() {
    }

    public static MockHttpServletRequestBuilder request(String secret, String eventType, long eventId, String body,
                                                     Instant signedAt) {
        String timestamp = Long.toString(signedAt.getEpochSecond());
        return MockMvcRequestBuilders.post("/v1/events")
                .contentType("application/json")
                .header("Cardrail-Event-Id", Long.toString(eventId))
                .header("Cardrail-Event-Type", eventType)
                .header("Cardrail-Timestamp", timestamp)
                .header("Cardrail-Signature", sign(secret, timestamp + "." + body))
                .content(body);
    }

    public static String sign(String secret, String signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
