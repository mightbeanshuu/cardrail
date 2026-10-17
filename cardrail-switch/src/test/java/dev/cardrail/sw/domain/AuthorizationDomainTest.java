package dev.cardrail.sw.domain;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.CardExpiry;
import dev.cardrail.sw.domain.authorization.DuplicateDecision;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.domain.authorization.TransmissionKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationDomainTest {

    @Test
    void cardIsValidThroughTheLastDayOfItsExpiryMonth() {
        CardExpiry expiry = CardExpiry.parse("2610").orElseThrow();
        assertThat(expiry.isExpiredAt(YearMonth.of(2026, 10))).isFalse();
        assertThat(expiry.isExpiredAt(YearMonth.of(2026, 11))).isTrue();
    }

    @Test
    void malformedExpiriesDoNotParse() {
        assertThat(CardExpiry.parse("2613")).isEmpty();
        assertThat(CardExpiry.parse("2600")).isEmpty();
        assertThat(CardExpiry.parse("26a1")).isEmpty();
        assertThat(CardExpiry.parse("261")).isEmpty();
    }

    @Test
    void requestToStringNeverContainsThePan() {
        AuthorizationRequest request = new AuthorizationRequest("5555555555554444", "3012", 100, 356, "5411", "M1",
                "Shop", "356", "T1", "40001", "000001", "000000000001", "1017103000", "051");
        assertThat(request.toString()).doesNotContain("5555555555554444").contains("555555******4444");
    }

    @Test
    void authCodeIsPresentExactlyWhenApproved() {
        assertThatThrownBy(() -> new AuthorizationResult(null, ResponseCode.APPROVED, null, false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationResult(null, ResponseCode.INSUFFICIENT_FUNDS, "123456", false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameKeyAndSamePayloadReplaysTheOriginalResponse() {
        StoredAuthorization stored = stored("hash-a", AuthorizationStatus.AUTHORIZED, ResponseCode.APPROVED, "654321");

        DuplicateDecision decision = DuplicateDecision.of(stored, "hash-a");

        assertThat(decision).isInstanceOfSatisfying(DuplicateDecision.Replay.class, replay -> {
            assertThat(replay.original().id()).isEqualTo(stored.id());
            assertThat(replay.original().authCode()).isEqualTo("654321");
        });
    }

    @Test
    void sameKeyWithDifferentPayloadIsAMismatchWhateverTheOriginalState() {
        assertThat(DuplicateDecision.of(stored("hash-a", AuthorizationStatus.AUTHORIZED, ResponseCode.APPROVED, "1"),
                "hash-b")).isInstanceOf(DuplicateDecision.Mismatch.class);
        assertThat(DuplicateDecision.of(stored("hash-a", AuthorizationStatus.PENDING, null, null), "hash-b"))
                .isInstanceOf(DuplicateDecision.Mismatch.class);
    }

    @Test
    void retransmissionOfAnUndecidedRequestIsInFlight() {
        assertThat(DuplicateDecision.of(stored("hash-a", AuthorizationStatus.PENDING, null, null), "hash-a"))
                .isInstanceOf(DuplicateDecision.InFlight.class);
    }

    private static StoredAuthorization stored(String hash, AuthorizationStatus status, ResponseCode code,
                                              String authCode) {
        return new StoredAuthorization(UUID.randomUUID(),
                new TransmissionKey("40001", "000001", "1017103000", "T1"), "000000000001", hash, "tok",
                "555555******4444", 100, 356, "5411", "M1", "Shop", status, code, authCode, false, null, null, null,
                null, null, Instant.now());
    }
}
