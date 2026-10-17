package dev.cardrail.vault;

import java.util.List;
import java.util.Optional;

/**
 * Persistence SPI. A JDBC implementation maps {@link VaultRecord} to one table with unique
 * constraints on {@code token} and {@code blind_index}, and implements
 * {@link #insertIfAbsent(VaultRecord)} as a single INSERT whose constraint violation tells the
 * two conflict cases apart.
 */
public interface VaultStore {

    /** Result of {@link #insertIfAbsent(VaultRecord)}. */
    sealed interface InsertOutcome {

        /** The record was stored. */
        record Inserted() implements InsertOutcome {
        }

        /** A record with the same blind index already exists; that record is the PAN's token. */
        record AlreadyTokenized(VaultRecord existing) implements InsertOutcome {
        }

        /** The token is already in use for a different PAN; retry with a fresh token. */
        record TokenTaken() implements InsertOutcome {
        }
    }

    /**
     * Atomically stores {@code record} unless its blind index or token is already present. Under
     * concurrent calls with the same blind index exactly one caller gets {@code Inserted}; all
     * others get {@code AlreadyTokenized} with the winner's record.
     */
    InsertOutcome insertIfAbsent(VaultRecord record);

    Optional<VaultRecord> findByBlindIndex(byte[] blindIndex);

    Optional<VaultRecord> findByToken(String token);

    /** Up to {@code limit} records whose DEK is wrapped under a KEK other than {@code kekId}. */
    List<VaultRecord> findWrappedUnderOtherThan(String kekId, int limit);

    /**
     * Replaces the wrapped DEK and its key version, only if the record is still wrapped under
     * {@code expectedKeyVersion} (compare-and-set, so concurrent rewrap jobs cannot clobber each
     * other). Returns whether the update happened.
     */
    boolean rewrap(String token, String expectedKeyVersion, String newKeyVersion, byte[] newWrappedDek);
}
