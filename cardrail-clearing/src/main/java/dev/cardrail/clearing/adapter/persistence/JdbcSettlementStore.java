package dev.cardrail.clearing.adapter.persistence;

import dev.cardrail.clearing.domain.ClearingStatus;
import dev.cardrail.clearing.domain.settlement.CurrencyTotals;
import dev.cardrail.clearing.domain.settlement.PartyType;
import dev.cardrail.clearing.domain.settlement.Position;
import dev.cardrail.clearing.port.SettlementStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static dev.cardrail.clearing.adapter.persistence.SqlTypes.timestamp;

@Repository
class JdbcSettlementStore implements SettlementStore {

    private static final RowMapper<Position> POSITION = (rs, n) -> new Position(
            PartyType.valueOf(rs.getString("party_type")),
            rs.getString("party_id"),
            rs.getInt("currency"),
            rs.getLong("record_count"),
            rs.getLong("gross_minor"),
            rs.getLong("interchange_minor"),
            rs.getLong("net_minor"));

    private final JdbcClient jdbc;

    JdbcSettlementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int claimForSettlement(LocalDate settlementDate, Collection<Long> recordIds) {
        if (recordIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE clearing_record
                           SET settlement_date = :settlementDate
                         WHERE id IN (:ids) AND settlement_date IS NULL AND status = :pending
                        """)
                .param("settlementDate", settlementDate)
                .param("ids", List.copyOf(recordIds))
                .param("pending", ClearingStatus.PENDING_SETTLEMENT.name())
                .update();
    }

    @Override
    public void addPartials(LocalDate settlementDate, List<Position> partials) {
        for (Position p : partials) {
            jdbc.sql("""
                            INSERT INTO settlement_partial (
                                settlement_date, party_type, party_id, currency, record_count, gross_minor,
                                interchange_minor, net_minor)
                            VALUES (:settlementDate, :partyType, :partyId, :currency, :recordCount, :gross,
                                    :interchange, :net)
                            """)
                    .param("settlementDate", settlementDate)
                    .param("partyType", p.partyType().name())
                    .param("partyId", p.partyId())
                    .param("currency", p.currency())
                    .param("recordCount", p.recordCount())
                    .param("gross", p.grossMinor())
                    .param("interchange", p.interchangeMinor())
                    .param("net", p.netMinor())
                    .update();
        }
    }

    @Override
    public List<Position> sumPartials(LocalDate settlementDate) {
        return sorted(jdbc.sql("""
                        SELECT party_type, party_id, currency,
                               SUM(record_count) AS record_count, SUM(gross_minor) AS gross_minor,
                               SUM(interchange_minor) AS interchange_minor, SUM(net_minor) AS net_minor
                          FROM settlement_partial
                         WHERE settlement_date = :settlementDate
                         GROUP BY party_type, party_id, currency
                        """)
                .param("settlementDate", settlementDate)
                .query(POSITION)
                .list());
    }

    @Override
    public List<CurrencyTotals> claimedRecordTotals(LocalDate settlementDate) {
        return jdbc.sql("""
                        SELECT currency, COUNT(*) AS record_count, SUM(amount_minor) AS gross_minor,
                               SUM(interchange_fee_minor) AS interchange_minor
                          FROM clearing_record
                         WHERE settlement_date = :settlementDate
                         GROUP BY currency
                        """)
                .param("settlementDate", settlementDate)
                .query((rs, n) -> new CurrencyTotals(rs.getInt("currency"), rs.getLong("record_count"),
                        rs.getLong("gross_minor"), rs.getLong("interchange_minor")))
                .list();
    }

    @Override
    public void insertPositions(LocalDate settlementDate, List<Position> positions, Instant at) {
        for (Position p : positions) {
            jdbc.sql("""
                            INSERT INTO settlement_position (
                                settlement_date, party_type, party_id, currency, record_count, gross_minor,
                                interchange_minor, net_minor, created_at)
                            VALUES (:settlementDate, :partyType, :partyId, :currency, :recordCount, :gross,
                                    :interchange, :net, :at)
                            """)
                    .param("settlementDate", settlementDate)
                    .param("partyType", p.partyType().name())
                    .param("partyId", p.partyId())
                    .param("currency", p.currency())
                    .param("recordCount", p.recordCount())
                    .param("gross", p.grossMinor())
                    .param("interchange", p.interchangeMinor())
                    .param("net", p.netMinor())
                    .param("at", timestamp(at))
                    .update();
        }
    }

    @Override
    public int markSettled(LocalDate settlementDate, Instant at) {
        return jdbc.sql("""
                        UPDATE clearing_record
                           SET status = :settled, settled_at = :at
                         WHERE settlement_date = :settlementDate AND status = :pending
                        """)
                .param("settled", ClearingStatus.SETTLED.name())
                .param("at", timestamp(at))
                .param("settlementDate", settlementDate)
                .param("pending", ClearingStatus.PENDING_SETTLEMENT.name())
                .update();
    }

    @Override
    public List<Position> positions(LocalDate settlementDate) {
        return sorted(jdbc.sql("""
                        SELECT party_type, party_id, currency, record_count, gross_minor, interchange_minor, net_minor
                          FROM settlement_position
                         WHERE settlement_date = :settlementDate
                        """)
                .param("settlementDate", settlementDate)
                .query(POSITION)
                .list());
    }

    // Sorted in Java: ORDER BY on text follows the database collation, which differs between vendors.
    private static List<Position> sorted(List<Position> positions) {
        List<Position> copy = new ArrayList<>(positions);
        copy.sort(Position.REPORT_ORDER);
        return copy;
    }
}
