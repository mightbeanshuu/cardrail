package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.TokenizedCard;
import dev.cardrail.sw.support.RequestBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuplicateResolverTest {

    private static final TokenizedCard CARD = new TokenizedCard("tok", "555555******4444", "555555", "4444");

    private final AuthorizationRepository authorizations = mock(AuthorizationRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DuplicateResolver resolver = new DuplicateResolver(authorizations,
            new DuplicateProperties("k".repeat(32), Duration.ofMillis(200), Duration.ofMillis(10)),
            new SwitchMetrics(registry), Clock.systemUTC());
    private final AuthorizationRequest request = RequestBuilder.purchase("5555555555554444", 100).build();

    @Test
    void replaysTheOriginalResponse() {
        StoredAuthorization original = stored("same", AuthorizationStatus.AUTHORIZED, ResponseCode.APPROVED, "123456");
        when(authorizations.findByKey(request.transmissionKey())).thenReturn(Optional.of(original));

        AuthorizationResult result = resolver.resolve(request, "same", CARD);

        assertThat(result).isEqualTo(original.toResult());
        assertThat(registry.counter("cardrail.authorization.duplicates", "outcome", "replay").count()).isOne();
    }

    @Test
    void differentContentUnderTheSameKeyIs94WithoutAnId() {
        when(authorizations.findByKey(request.transmissionKey()))
                .thenReturn(Optional.of(stored("other", AuthorizationStatus.AUTHORIZED, ResponseCode.APPROVED, "1")));

        AuthorizationResult result = resolver.resolve(request, "same", CARD);

        assertThat(result.responseCode()).isEqualTo(ResponseCode.DUPLICATE_TRANSMISSION);
        assertThat(result.id()).isNull();
    }

    @Test
    void waitsForAnInFlightOriginalAndThenReplaysIt() {
        StoredAuthorization pending = stored("same", AuthorizationStatus.PENDING, null, null);
        StoredAuthorization decided = new StoredAuthorization(pending.id(), pending.key(), pending.rrn(), "same",
                "tok", pending.maskedPan(), 100, 356, "5411", "M", "Shop", AuthorizationStatus.DECLINED,
                ResponseCode.INSUFFICIENT_FUNDS, null, false, "issuer-declined", null, null, null, null, Instant.now());
        when(authorizations.findByKey(request.transmissionKey()))
                .thenReturn(Optional.of(pending), Optional.of(pending), Optional.of(decided));

        assertThat(resolver.resolve(request, "same", CARD).responseCode()).isEqualTo(ResponseCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void givesUpOnAnOriginalThatStaysInFlight() {
        when(authorizations.findByKey(request.transmissionKey()))
                .thenReturn(Optional.of(stored("same", AuthorizationStatus.PENDING, null, null)));

        assertThat(resolver.resolve(request, "same", CARD).responseCode()).isEqualTo(ResponseCode.DUPLICATE_TRANSMISSION);
        assertThat(registry.counter("cardrail.authorization.duplicates", "outcome", "in_flight").count()).isOne();
    }

    private StoredAuthorization stored(String hash, AuthorizationStatus status, ResponseCode code, String authCode) {
        return new StoredAuthorization(UUID.randomUUID(), request.transmissionKey(), request.rrn(), hash, "tok",
                "555555******4444", 100, 356, "5411", "M", "Shop", status, code, authCode, false, null, null, null,
                null, null, Instant.now());
    }
}
