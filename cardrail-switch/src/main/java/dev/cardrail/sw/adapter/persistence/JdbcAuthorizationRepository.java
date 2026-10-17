package dev.cardrail.sw.adapter.persistence;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.domain.authorization.TransmissionKey;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.NewAuthorization;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.cardrail.sw.adapter.persistence.SqlTypes.instant;
import static dev.cardrail.sw.adapter.persistence.SqlTypes.timestamp;

@Repository
class JdbcAuthorizationRepository implements AuthorizationRepository {

    private static final String COLUMNS = """
            id, acquirer_id, stan, transmission_datetime, terminal_id, rrn, payload_hash, token, masked_pan,
            amount_minor, currency, mcc, merchant_id, merchant_name, status, response_code, auth_code,
            stand_in, decline_reason, captured_amount_minor, captured_at, reversed_at, expired_at, created_at
            """;

    private static final RowMapper<StoredAuthorization> MAPPER = (rs, n) -> {
        String responseCode = rs.getString("response_code");
        return new StoredAuthorization(
                rs.getObject("id", UUID.class),
                new TransmissionKey(rs.getString("acquirer_id"), rs.getString("stan"),
                        rs.getString("transmission_datetime"), rs.getString("terminal_id")),
                rs.getString("rrn"),
                rs.getString("payload_hash"),
                rs.getString("token"),
                rs.getString("masked_pan"),
                rs.getLong("amount_minor"),
                rs.getInt("currency"),
                rs.getString("mcc"),
                rs.getString("merchant_id"),
                rs.getString("merchant_name"),
                AuthorizationStatus.valueOf(rs.getString("status")),
                responseCode == null ? null : ResponseCode.fromCode(responseCode),
                rs.getString("auth_code"),
                rs.getBoolean("stand_in"),
                rs.getString("decline_reason"),
                rs.getObject("captured_amount_minor", Long.class),
                instant(rs, "captured_at"),
                instant(rs, "reversed_at"),
                instant(rs, "expired_at"),
                instant(rs, "created_at"));
    };

    private final JdbcClient jdbc;

    JdbcAuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredAuthorization> insertIfAbsent(NewAuthorization a) {
        AuthorizationRequest r = a.request();
        return jdbc.sql("""
                        INSERT INTO card_authorization (
                            id, acquirer_id, stan, transmission_datetime, terminal_id, rrn, payload_hash, token,
                            masked_pan, amount_minor, currency, mcc, merchant_id, merchant_name, merchant_country,
                            pos_entry_mode, status, response_code, decline_reason, created_at, updated_at)
                        VALUES (
                            :id, :acquirerId, :stan, :transmissionDateTime, :terminalId, :rrn, :payloadHash, :token,
                            :maskedPan, :amountMinor, :currency, :mcc, :merchantId, :merchantName, :merchantCountry,
                            :posEntryMode, :status, :responseCode, :declineReason, :createdAt, :createdAt)
                        ON CONFLICT ON CONSTRAINT card_authorization_transmission_key DO NOTHING
                        RETURNING
                        """ + COLUMNS)
                .param("id", a.id())
                .param("acquirerId", r.acquirerId())
                .param("stan", r.stan())
                .param("transmissionDateTime", r.transmissionDateTime())
                .param("terminalId", r.terminalId())
                .param("rrn", r.rrn())
                .param("payloadHash", a.payloadHash())
                .param("token", a.token())
                .param("maskedPan", a.maskedPan())
                .param("amountMinor", r.amountMinor())
                .param("currency", r.currency())
                .param("mcc", r.mcc())
                .param("merchantId", r.merchantId())
                .param("merchantName", r.merchantName())
                .param("merchantCountry", r.merchantCountry())
                .param("posEntryMode", r.posEntryMode())
                .param("status", a.status().name())
                .param("responseCode", a.responseCode() == null ? null : a.responseCode().code())
                .param("declineReason", a.declineReason())
                .param("createdAt", timestamp(a.createdAt()))
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<StoredAuthorization> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM card_authorization WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<StoredAuthorization> findByKey(TransmissionKey key) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM card_authorization
                        WHERE acquirer_id = :acquirerId AND stan = :stan
                          AND transmission_datetime = :transmissionDateTime AND terminal_id = :terminalId
                        """)
                .param("acquirerId", key.acquirerId())
                .param("stan", key.stan())
                .param("transmissionDateTime", key.transmissionDateTime())
                .param("terminalId", key.terminalId())
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<StoredAuthorization> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM card_authorization WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    @Override
    public StoredAuthorization recordDecision(UUID id, AuthorizationStatus status, ResponseCode responseCode,
                                              String authCode, boolean standIn, String declineReason,
                                              Instant decidedAt) {
        return updateOne(id, "record a decision for", jdbc.sql("""
                        UPDATE card_authorization
                           SET status = :status, response_code = :responseCode, auth_code = :authCode,
                               stand_in = :standIn, decline_reason = :declineReason, updated_at = :at
                         WHERE id = :id AND status = 'PENDING'
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("status", status.name())
                .param("responseCode", responseCode.code())
                .param("authCode", authCode)
                .param("standIn", standIn)
                .param("declineReason", declineReason)
                .param("at", timestamp(decidedAt)));
    }

    @Override
    public StoredAuthorization markReversed(UUID id, Instant reversedAt) {
        return updateOne(id, "reverse", jdbc.sql("""
                        UPDATE card_authorization SET status = 'REVERSED', reversed_at = :at, updated_at = :at
                         WHERE id = :id AND status = 'AUTHORIZED'
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("at", timestamp(reversedAt)));
    }

    @Override
    public StoredAuthorization markCaptured(UUID id, long capturedAmountMinor, Instant capturedAt) {
        return updateOne(id, "capture", jdbc.sql("""
                        UPDATE card_authorization
                           SET status = 'CAPTURED', captured_amount_minor = :amount, captured_at = :at, updated_at = :at
                         WHERE id = :id AND status = 'AUTHORIZED'
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("amount", capturedAmountMinor)
                .param("at", timestamp(capturedAt)));
    }

    @Override
    public StoredAuthorization markExpired(UUID id, Instant expiredAt) {
        return updateOne(id, "expire", jdbc.sql("""
                        UPDATE card_authorization SET status = 'EXPIRED', expired_at = :at, updated_at = :at
                         WHERE id = :id AND status = 'AUTHORIZED'
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("at", timestamp(expiredAt)));
    }

    @Override
    public List<StoredAuthorization> lockExpirable(Instant createdBefore, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM card_authorization
                        WHERE status = 'AUTHORIZED' AND created_at < :cutoff
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("cutoff", timestamp(createdBefore))
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    @Override
    public List<StoredAuthorization> lockAbandoned(Instant createdBefore, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM card_authorization
                        WHERE status = 'PENDING' AND created_at < :cutoff
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("cutoff", timestamp(createdBefore))
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    @Override
    public RecentActivity recentActivity(String token, int currency, Instant since, UUID excluding) {
        return jdbc.sql("""
                        SELECT count(*) AS attempts, COALESCE(sum(amount_minor), 0) AS amount
                          FROM card_authorization
                         WHERE token = :token AND currency = :currency AND created_at >= :since AND id <> :excluding
                        """)
                .param("token", token)
                .param("currency", currency)
                .param("since", timestamp(since))
                .param("excluding", excluding)
                .query((rs, n) -> new RecentActivity(rs.getLong("attempts"), rs.getLong("amount")))
                .single();
    }

    private static StoredAuthorization updateOne(UUID id, String action, JdbcClient.StatementSpec statement) {
        return statement.query(MAPPER)
                .optional()
                .orElseThrow(() -> new IllegalStateException("could not " + action + " authorization " + id
                        + ": it is not in the expected state"));
    }
}
