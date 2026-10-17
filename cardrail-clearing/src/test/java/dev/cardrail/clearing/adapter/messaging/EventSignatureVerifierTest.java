package dev.cardrail.clearing.adapter.messaging;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSignatureVerifierTest {

    // The same vector is in cardrail-switch's HttpOutboxPublisherTest: both sides must agree on it.
    private static final String SECRET = "cardrail-signature-test-vector-0123456789";
    private static final String TIMESTAMP = "1792310400";
    private static final byte[] BODY =
            "{\"eventType\":\"authorization.captured\",\"authorizationId\":\"7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b\"}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "70c60be37f4cc49e795e36ee2fec1b10ebc903ce596585fffd2f33a5db779828";
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(1_792_310_400L);

    @Test
    void acceptsTheSwitchsSignatureOfTheSharedTestVector() {
        assertThatCode(() -> verifierAt(SIGNED_AT.plusSeconds(3)).verify(TIMESTAMP, SIGNATURE, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAChangedBodyOrAWrongSignature() {
        EventSignatureVerifier verifier = verifierAt(SIGNED_AT);
        byte[] changed = new String(BODY, StandardCharsets.UTF_8).replace("captured", "reversed")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, SIGNATURE, changed))
                .isInstanceOf(InvalidSignatureException.class).hasMessage("signature does not match");
        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, "0".repeat(64), BODY))
                .isInstanceOf(InvalidSignatureException.class).hasMessage("signature does not match");
        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, SIGNATURE.toUpperCase(), BODY))
                .isInstanceOf(InvalidSignatureException.class).hasMessage("signature does not match");
        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, "not-hex", BODY))
                .isInstanceOf(InvalidSignatureException.class).hasMessage("signature does not match");
    }

    @Test
    void theTimestampIsSignedSoMovingItBreaksTheSignature() {
        String later = Long.toString(SIGNED_AT.getEpochSecond() + 60);

        assertThatThrownBy(() -> verifierAt(SIGNED_AT.plusSeconds(60)).verify(later, SIGNATURE, BODY))
                .isInstanceOf(InvalidSignatureException.class).hasMessage("signature does not match");
    }

    @Test
    void rejectsATimestampOutsideTheToleranceEitherWay() {
        assertThatCode(() -> verifierAt(SIGNED_AT.plus(Duration.ofMinutes(5))).verify(TIMESTAMP, SIGNATURE, BODY))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> verifierAt(SIGNED_AT.plus(Duration.ofMinutes(5)).plusSeconds(1))
                .verify(TIMESTAMP, SIGNATURE, BODY))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("more than 5 minutes");
        assertThatThrownBy(() -> verifierAt(SIGNED_AT.minus(Duration.ofMinutes(6))).verify(TIMESTAMP, SIGNATURE, BODY))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("more than 5 minutes");
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        EventSignatureVerifier verifier = verifierAt(SIGNED_AT);

        assertThatThrownBy(() -> verifier.verify(null, SIGNATURE, BODY)).isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(TIMESTAMP, null, BODY)).isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("2026-10-18T09:00:00Z", SIGNATURE, BODY))
                .isInstanceOf(InvalidSignatureException.class).hasMessage("timestamp must be epoch seconds");
    }

    @Test
    void refusesAShortSecret() {
        assertThatThrownBy(() -> new EventSignatureVerifier("short", Duration.ofMinutes(5), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardrail.events.http.secret");
    }

    private static EventSignatureVerifier verifierAt(Instant now) {
        return new EventSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));
    }
}
