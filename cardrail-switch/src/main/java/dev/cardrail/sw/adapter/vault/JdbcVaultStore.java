package dev.cardrail.sw.adapter.vault;

import dev.cardrail.vault.VaultRecord;
import dev.cardrail.vault.VaultStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** {@link VaultStore} on the {@code vault_record} table (V7). */
final class JdbcVaultStore implements VaultStore {

    private static final String COLUMNS =
            "token, blind_index, wrapped_dek, dek_key_version, iv, ciphertext, masked_pan, created_at";

    private static final RowMapper<VaultRecord> MAPPER = (rs, n) -> new VaultRecord(
            rs.getString("token"),
            rs.getBytes("blind_index"),
            rs.getBytes("wrapped_dek"),
            rs.getString("dek_key_version"),
            rs.getBytes("iv"),
            rs.getBytes("ciphertext"),
            rs.getString("masked_pan"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    JdbcVaultStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One INSERT, arbitrated by the two unique constraints. {@code ON CONFLICT DO NOTHING} without a
     * target covers both, so a conflict never raises an error and never aborts a caller's transaction.
     * A conflicting row that is still uncommitted makes the INSERT wait for it; once it has committed,
     * the re-select below (a new statement, so a new snapshot under READ COMMITTED) sees it. If a row
     * with this blind index exists, the PAN lost the race and that row is its token; otherwise the
     * conflict can only have been on the token.
     */
    @Override
    public InsertOutcome insertIfAbsent(VaultRecord record) {
        int inserted = jdbc.sql("""
                        INSERT INTO vault_record (token, blind_index, wrapped_dek, dek_key_version, iv, ciphertext,
                                                  masked_pan, created_at)
                        VALUES (:token, :blindIndex, :wrappedDek, :dekKeyVersion, :iv, :ciphertext, :maskedPan,
                                :createdAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("token", record.token())
                .param("blindIndex", record.blindIndex())
                .param("wrappedDek", record.wrappedDek())
                .param("dekKeyVersion", record.dekKeyVersion())
                .param("iv", record.iv())
                .param("ciphertext", record.ciphertext())
                .param("maskedPan", record.maskedPan())
                .param("createdAt", record.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        if (inserted == 1) {
            return new InsertOutcome.Inserted();
        }
        return findByBlindIndex(record.blindIndex())
                .<InsertOutcome>map(InsertOutcome.AlreadyTokenized::new)
                .orElseGet(InsertOutcome.TokenTaken::new);
    }

    @Override
    public Optional<VaultRecord> findByBlindIndex(byte[] blindIndex) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vault_record WHERE blind_index = :blindIndex")
                .param("blindIndex", blindIndex)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<VaultRecord> findByToken(String token) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vault_record WHERE token = :token")
                .param("token", token)
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<VaultRecord> findWrappedUnderOtherThan(String kekId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM vault_record
                        WHERE dek_key_version <> :kekId
                        ORDER BY token
                        LIMIT :limit
                        """)
                .param("kekId", kekId)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    @Override
    public boolean rewrap(String token, String expectedKeyVersion, String newKeyVersion, byte[] newWrappedDek) {
        return jdbc.sql("""
                        UPDATE vault_record SET wrapped_dek = :wrappedDek, dek_key_version = :newKeyVersion
                         WHERE token = :token AND dek_key_version = :expectedKeyVersion
                        """)
                .param("wrappedDek", newWrappedDek)
                .param("newKeyVersion", newKeyVersion)
                .param("token", token)
                .param("expectedKeyVersion", expectedKeyVersion)
                .update() == 1;
    }
}
