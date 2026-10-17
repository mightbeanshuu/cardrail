package dev.cardrail.vault;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** {@link VaultStore} on two concurrent maps. For tests and local development. */
public final class InMemoryVaultStore implements VaultStore {

    private static final HexFormat HEX = HexFormat.of();

    private final ConcurrentMap<String, VaultRecord> byToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> tokenByBlindIndex = new ConcurrentHashMap<>();

    @Override
    public InsertOutcome insertIfAbsent(VaultRecord record) {
        // Claim the token first, then the blind index. In this order a token is published under a
        // blind index only after its record exists, so a concurrent caller that loses the blind
        // index race always finds the winner's record. The reverse order could hand out a token
        // whose record is about to be rolled back because its token collided.
        if (byToken.putIfAbsent(record.token(), record) != null) {
            return new InsertOutcome.TokenTaken();
        }
        String winner = tokenByBlindIndex.putIfAbsent(HEX.formatHex(record.blindIndex()), record.token());
        if (winner != null) {
            byToken.remove(record.token(), record);
            return new InsertOutcome.AlreadyTokenized(byToken.get(winner));
        }
        return new InsertOutcome.Inserted();
    }

    @Override
    public Optional<VaultRecord> findByBlindIndex(byte[] blindIndex) {
        String token = tokenByBlindIndex.get(HEX.formatHex(blindIndex));
        return token == null ? Optional.empty() : Optional.ofNullable(byToken.get(token));
    }

    @Override
    public Optional<VaultRecord> findByToken(String token) {
        return Optional.ofNullable(byToken.get(token));
    }

    @Override
    public List<VaultRecord> findWrappedUnderOtherThan(String kekId, int limit) {
        return byToken.values().stream()
                .filter(r -> !r.dekKeyVersion().equals(kekId))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean rewrap(String token, String expectedKeyVersion, String newKeyVersion, byte[] newWrappedDek) {
        boolean[] updated = {false};
        byToken.computeIfPresent(token, (t, current) -> {
            if (!current.dekKeyVersion().equals(expectedKeyVersion)) {
                return current;
            }
            updated[0] = true;
            return current.withWrappedDek(newKeyVersion, newWrappedDek);
        });
        return updated[0];
    }

    public int size() {
        return tokenByBlindIndex.size();
    }
}
