package dev.cardrail.sw.application;

import dev.cardrail.sw.support.RequestBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFingerprintTest {

    private final PayloadFingerprint fingerprint =
            new PayloadFingerprint(new DuplicateProperties("k".repeat(32), Duration.ofSeconds(1), Duration.ofMillis(10)));

    @Test
    void identicalRequestsHaveIdenticalFingerprints() {
        RequestBuilder request = RequestBuilder.purchase("5555555555554444", 100).stan("000777");
        assertThat(fingerprint.of(request.build())).isEqualTo(fingerprint.of(request.build())).hasSize(64);
    }

    @Test
    void anyFieldChangeChangesTheFingerprint() {
        RequestBuilder request = RequestBuilder.purchase("5555555555554444", 100).stan("000777");
        String base = fingerprint.of(request.build());
        assertThat(fingerprint.of(request.amountMinor(101).build())).isNotEqualTo(base);
    }

    @Test
    void fingerprintIsKeyed() {
        RequestBuilder request = RequestBuilder.purchase("5555555555554444", 100);
        PayloadFingerprint otherKey =
                new PayloadFingerprint(new DuplicateProperties("j".repeat(32), Duration.ofSeconds(1), Duration.ofMillis(10)));
        assertThat(otherKey.of(request.build())).isNotEqualTo(fingerprint.of(request.build()));
    }
}
