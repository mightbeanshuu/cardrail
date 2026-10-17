package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.ledger.JournalKind;

import java.util.Optional;
import java.util.UUID;

/**
 * Cardholder accounts and authorization holds, kept in the double-entry ledger. Every balance change
 * happens under a row lock on the account and is recorded as a balanced journal in the same
 * transaction. Methods join the caller's transaction when there is one.
 */
public interface IssuerLedger {

    void openAccount(String token, int currency, long openingBalanceMinor);

    void setStatus(String token, AccountStatus status);

    Optional<AccountBalance> balance(String token);

    /** Moves the amount from available to held, keyed by authorization id. */
    HoldPlacement placeHold(UUID authorizationId, String token, long amountMinor, int currency);

    /**
     * Called when the switch stops waiting for the issuer. Guarantees no hold will exist for this
     * authorization afterwards: either it prevents a late hold from being placed, or it releases one
     * that already was.
     *
     * @return true if a hold had been placed and was released
     */
    boolean voidHold(UUID authorizationId);

    /** @return the amount released, 0 if there was no active hold */
    long releaseHold(UUID authorizationId, JournalKind kind);

    /** Settles part or all of the hold to the settlement account and releases the rest. */
    void captureHold(UUID authorizationId, long captureAmountMinor, String settlementAccountRef);
}
