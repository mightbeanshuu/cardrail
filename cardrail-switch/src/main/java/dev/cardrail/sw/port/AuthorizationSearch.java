package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Filters for listing authorizations, newest first. Null filters match everything.
 *
 * @param from inclusive lower bound on the creation time
 * @param to   exclusive upper bound on the creation time
 * @param page 0-based
 */
public record AuthorizationSearch(
        AuthorizationStatus status,
        ResponseCode responseCode,
        String merchantId,
        String last4,
        Boolean standIn,
        Instant from,
        Instant to,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    private static final Pattern LAST4 = Pattern.compile("\\d{4}");

    public AuthorizationSearch {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        if (last4 != null && !LAST4.matcher(last4).matches()) {
            throw new IllegalArgumentException("last4 must be exactly four digits");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
