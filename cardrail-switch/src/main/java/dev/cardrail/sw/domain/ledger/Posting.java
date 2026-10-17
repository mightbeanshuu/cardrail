package dev.cardrail.sw.domain.ledger;

/** One journal line. {@code amountMinor} is signed and never zero. */
public record Posting(long ledgerAccountId, long amountMinor) {

    public Posting {
        if (amountMinor == 0) {
            throw new IllegalArgumentException("a posting must move a non-zero amount");
        }
    }
}
