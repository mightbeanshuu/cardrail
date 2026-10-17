package dev.cardrail.sw.domain.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced set of postings. Value moves from accounts with negative postings to accounts with
 * positive ones, and the postings always sum to zero. The database enforces the same invariant at
 * commit; checking here as well turns a programming error into an immediate, readable failure.
 *
 * @param authorizationId the authorization that caused this journal, or null (opening balances)
 */
public record Journal(UUID id, JournalKind kind, UUID authorizationId, List<Posting> postings) {

    public Journal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a journal needs at least two postings");
        }
        long sum = 0;
        for (Posting p : postings) {
            sum = Math.addExact(sum, p.amountMinor());
        }
        if (sum != 0) {
            throw new IllegalArgumentException("journal postings sum to " + sum + ", not zero");
        }
    }

    public static Journal transfer(JournalKind kind, UUID authorizationId, long fromAccount, long toAccount,
                                   long amountMinor) {
        return builder(kind, authorizationId).move(fromAccount, toAccount, amountMinor).build();
    }

    public static Builder builder(JournalKind kind, UUID authorizationId) {
        return new Builder(kind, authorizationId);
    }

    public static final class Builder {
        private final JournalKind kind;
        private final UUID authorizationId;
        private final List<Posting> postings = new ArrayList<>();

        private Builder(JournalKind kind, UUID authorizationId) {
            this.kind = kind;
            this.authorizationId = authorizationId;
        }

        /** Adds a debit/credit pair; zero amounts are skipped so callers need no special case. */
        public Builder move(long fromAccount, long toAccount, long amountMinor) {
            if (amountMinor < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }
            if (amountMinor > 0) {
                postings.add(new Posting(fromAccount, -amountMinor));
                postings.add(new Posting(toAccount, amountMinor));
            }
            return this;
        }

        public Journal build() {
            return new Journal(UUID.randomUUID(), kind, authorizationId, postings);
        }
    }
}
