package dev.cardrail.clearing.adapter.persistence;

import dev.cardrail.clearing.domain.ClearingRecord;
import dev.cardrail.clearing.domain.ClearingStatus;
import dev.cardrail.clearing.domain.RecordType;
import dev.cardrail.clearing.port.ClearingRecords;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.cardrail.clearing.adapter.persistence.SqlTypes.date;
import static dev.cardrail.clearing.adapter.persistence.SqlTypes.instant;
import static dev.cardrail.clearing.adapter.persistence.SqlTypes.timestamp;

@Repository
class JdbcClearingRecords implements ClearingRecords {

    private static final String COLUMNS = """
            id, authorization_id, record_type, token, masked_pan, issuer_bin, acquirer_id, merchant_id, mcc,
            currency, amount_minor, interchange_category, interchange_fee_minor, business_date, occurred_at,
            status, settlement_date, settled_at, created_at
            """;

    private static final RowMapper<ClearingRecord> MAPPER = (rs, n) -> new ClearingRecord(
            rs.getLong("id"),
            UUID.fromString(rs.getString("authorization_id")),
            RecordType.valueOf(rs.getString("record_type")),
            rs.getString("token"),
            rs.getString("masked_pan"),
            rs.getString("issuer_bin"),
            rs.getString("acquirer_id"),
            rs.getString("merchant_id"),
            rs.getString("mcc"),
            rs.getInt("currency"),
            rs.getLong("amount_minor"),
            rs.getString("interchange_category"),
            rs.getLong("interchange_fee_minor"),
            date(rs, "business_date"),
            instant(rs, "occurred_at"),
            ClearingStatus.valueOf(rs.getString("status")),
            date(rs, "settlement_date"),
            instant(rs, "settled_at"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    JdbcClearingRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ClearingRecord r) {
        jdbc.sql("""
                        INSERT INTO clearing_record (
                            authorization_id, record_type, token, masked_pan, issuer_bin, acquirer_id, merchant_id, mcc,
                            currency, amount_minor, interchange_category, interchange_fee_minor, business_date,
                            occurred_at, status, created_at)
                        VALUES (
                            :authorizationId, :recordType, :token, :maskedPan, :issuerBin, :acquirerId, :merchantId, :mcc,
                            :currency, :amountMinor, :interchangeCategory, :interchangeFeeMinor, :businessDate,
                            :occurredAt, :status, :createdAt)
                        """)
                .param("authorizationId", r.authorizationId().toString())
                .param("recordType", r.type().name())
                .param("token", r.token())
                .param("maskedPan", r.maskedPan())
                .param("issuerBin", r.issuerBin())
                .param("acquirerId", r.acquirerId())
                .param("merchantId", r.merchantId())
                .param("mcc", r.mcc())
                .param("currency", r.currency())
                .param("amountMinor", r.amountMinor())
                .param("interchangeCategory", r.interchangeCategory())
                .param("interchangeFeeMinor", r.interchangeFeeMinor())
                .param("businessDate", r.businessDate())
                .param("occurredAt", timestamp(r.occurredAt()))
                .param("status", r.status().name())
                .param("createdAt", timestamp(r.createdAt()))
                .update();
    }

    @Override
    public Optional<ClearingRecord> findCapture(UUID authorizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM clearing_record WHERE authorization_id = :id AND record_type = :type")
                .param("id", authorizationId.toString())
                .param("type", RecordType.CAPTURE.name())
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<ClearingRecord> findByBusinessDate(LocalDate businessDate, int page, int size) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM clearing_record
                        WHERE business_date = :businessDate
                        ORDER BY id
                        OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                        """)
                .param("businessDate", businessDate)
                .param("offset", (long) page * size)
                .param("size", size)
                .query(MAPPER)
                .list();
    }

    @Override
    public long countByBusinessDate(LocalDate businessDate) {
        return jdbc.sql("SELECT COUNT(*) FROM clearing_record WHERE business_date = :businessDate")
                .param("businessDate", businessDate)
                .query(Long.class)
                .single();
    }
}
