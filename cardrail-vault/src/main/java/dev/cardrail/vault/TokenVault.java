package dev.cardrail.vault;

import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Exchanges PANs for format-preserving tokens and back.
 *
 * <p>Tokenization is idempotent: the blind index finds an existing record for the PAN, so the same
 * PAN always yields the same token, including under concurrent calls (the store's put-if-absent
 * decides the single winner). Detokenization is audited, whatever its outcome.
 */
public final class TokenVault {

    static final int MAX_TOKEN_ATTEMPTS = 8;
    private static final int REWRAP_BATCH = 500;

    private final VaultStore store;
    private final KeyProvider keys;
    private final TokenFormat tokens;
    private final VaultAuditListener audit;
    private final Clock clock;
    private final EnvelopeCipher cipher;
    private final BlindIndex blindIndex;

    public TokenVault(VaultStore store, KeyProvider keys, TokenFormat tokens, VaultAuditListener audit, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cipher = new EnvelopeCipher(keys);
        this.blindIndex = new BlindIndex(keys.blindIndexKey());
    }

    /**
     * Returns the PAN's token, issuing one if the PAN has not been seen before.
     *
     * @throws InvalidCardNumberException if the PAN lies in the token BIN (it is a token, not a card)
     */
    public TokenizedCard tokenize(Pan pan) {
        if (tokens.startsWithTokenBin(pan.bin())) {
            throw new InvalidCardNumberException("card number is in the vault's token BIN; refusing to tokenize a token");
        }
        char[] digits = pan.exposeFullPanUnsafe();
        try {
            byte[] index = blindIndex.compute(digits);
            Optional<VaultRecord> existing = store.findByBlindIndex(index);
            if (existing.isPresent()) {
                return TokenizedCard.from(existing.get());
            }
            for (int attempt = 0; attempt < MAX_TOKEN_ATTEMPTS; attempt++) {
                String token = tokens.next();
                // AAD binds the ciphertext to the token, so each candidate token needs its own seal.
                Envelope envelope = cipher.seal(token, digits);
                VaultRecord record = new VaultRecord(token, index, envelope.wrappedDek(), envelope.kekId(),
                        envelope.iv(), envelope.ciphertext(), pan.masked(), clock.instant());
                switch (store.insertIfAbsent(record)) {
                    case VaultStore.InsertOutcome.Inserted inserted -> {
                        return TokenizedCard.from(record);
                    }
                    case VaultStore.InsertOutcome.AlreadyTokenized lost -> {
                        return TokenizedCard.from(lost.existing());
                    }
                    case VaultStore.InsertOutcome.TokenTaken taken -> {
                        // collision on the random digits: draw again
                    }
                }
            }
            throw new IllegalStateException("no free token after " + MAX_TOKEN_ATTEMPTS
                    + " attempts; token BIN " + tokens.tokenBin() + " is close to exhausted");
        } finally {
            Arrays.fill(digits, '\0');
        }
    }

    /**
     * Returns the PAN behind {@code token}. The caller should {@link Pan#destroy()} it when done.
     *
     * @throws UnknownTokenException    if the input is not a token of this vault, or has no record
     * @throws VaultIntegrityException  if the stored record fails authentication
     * @throws KeyUnavailableException  if the record's KEK is unknown or retired
     */
    public Pan detokenize(String token, DetokenizeReason reason) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(reason, "reason");
        if (!tokens.isToken(token)) {
            report(null, reason, null, DetokenizeEvent.Outcome.MALFORMED_TOKEN);
            throw new UnknownTokenException("input is not a well-formed token of this vault");
        }
        VaultRecord record = store.findByToken(token).orElse(null);
        if (record == null) {
            report(token, reason, null, DetokenizeEvent.Outcome.UNKNOWN_TOKEN);
            throw new UnknownTokenException("no vault record for token " + token);
        }
        char[] digits;
        try {
            digits = cipher.open(token, record.envelope());
        } catch (VaultIntegrityException e) {
            report(token, reason, record.dekKeyVersion(), DetokenizeEvent.Outcome.INTEGRITY_FAILURE);
            throw e;
        } catch (KeyUnavailableException e) {
            report(token, reason, record.dekKeyVersion(), DetokenizeEvent.Outcome.KEY_UNAVAILABLE);
            throw e;
        }
        try {
            report(token, reason, record.dekKeyVersion(), DetokenizeEvent.Outcome.GRANTED);
            return Pan.of(digits);
        } finally {
            Arrays.fill(digits, '\0');
        }
    }

    /**
     * Rewraps up to {@code limit} DEKs that are not under the active KEK. PAN ciphertext is not
     * touched. Returns how many records this call moved.
     *
     * @throws KeyUnavailableException if a record's KEK is unknown or already retired
     */
    public int rewrapBatch(int limit) {
        String active = keys.activeKek().id();
        int moved = 0;
        for (VaultRecord record : store.findWrappedUnderOtherThan(active, limit)) {
            Envelope rewrapped = cipher.rewrap(record.token(), record.envelope());
            if (store.rewrap(record.token(), record.dekKeyVersion(), rewrapped.kekId(), rewrapped.wrappedDek())) {
                moved++;
            }
        }
        return moved;
    }

    /** Runs {@link #rewrapBatch(int)} until no record is left under an old KEK. Returns the total moved. */
    public int rewrapAll() {
        int total = 0;
        int moved;
        while ((moved = rewrapBatch(REWRAP_BATCH)) > 0) {
            total += moved;
        }
        return total;
    }

    private void report(String token, DetokenizeReason reason, String kekId, DetokenizeEvent.Outcome outcome) {
        audit.onDetokenize(new DetokenizeEvent(token, reason, clock.instant(), kekId, outcome));
    }
}
