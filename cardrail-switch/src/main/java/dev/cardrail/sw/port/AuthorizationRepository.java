package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.domain.authorization.TransmissionKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationRepository {

    /**
     * Inserts the row unless its transmission key is already taken. Concurrent inserts of the same key
     * are arbitrated by the unique constraint: exactly one gets the stored row back, the others get empty.
     */
    Optional<StoredAuthorization> insertIfAbsent(NewAuthorization authorization);

    Optional<StoredAuthorization> findById(UUID id);

    Optional<StoredAuthorization> findByKey(TransmissionKey key);

    /** Row-locks the authorization for the rest of the current transaction. */
    Optional<StoredAuthorization> lockById(UUID id);

    /** Moves a PENDING authorization to its decided state. */
    StoredAuthorization recordDecision(UUID id, AuthorizationStatus status, ResponseCode responseCode, String authCode,
                        boolean standIn, String declineReason, Instant decidedAt);

    StoredAuthorization markReversed(UUID id, Instant reversedAt);

    StoredAuthorization markCaptured(UUID id, long capturedAmountMinor, Instant capturedAt);

    StoredAuthorization markExpired(UUID id, Instant expiredAt);

    /**
     * Locks up to {@code limit} AUTHORIZED rows created before the cutoff, skipping rows another
     * transaction already holds, so concurrent sweepers split the work instead of repeating it.
     */
    List<StoredAuthorization> lockExpirable(Instant createdBefore, int limit);

    /** Same as {@link #lockExpirable} for PENDING rows: decisions abandoned by a crashed or failed request. */
    List<StoredAuthorization> lockAbandoned(Instant createdBefore, int limit);

    /** One page of matches, newest first, and the total number of matches. */
    AuthorizationPage search(AuthorizationSearch search);

    /** Attempts on this card in this currency since {@code since}, not counting {@code excluding}. */
    RecentActivity recentActivity(String token, int currency, Instant since, UUID excluding);

    record RecentActivity(long count, long amountMinor) {
    }
}
