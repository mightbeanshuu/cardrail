package dev.cardrail.clearing.application;

import dev.cardrail.clearing.domain.settlement.Position;

import java.time.LocalDate;
import java.util.Collection;

/** RFC 4180 CSV of a run's positions. Party ids are digits or the network id, so nothing needs quoting. */
public final class SettlementReport {

    public static final String HEADER =
            "settlement_date,party_type,party_id,currency,record_count,gross_minor,interchange_minor,net_minor";

    private static final String CRLF = "\r\n";

    private SettlementReport() {
    }

    public static String fileName(LocalDate settlementDate) {
        return "settlement-" + settlementDate + ".csv";
    }

    public static String render(LocalDate settlementDate, Collection<Position> positions) {
        StringBuilder csv = new StringBuilder(HEADER).append(CRLF);
        positions.stream().sorted(Position.REPORT_ORDER).forEach(p -> csv
                .append(settlementDate).append(',')
                .append(p.partyType()).append(',')
                .append(p.partyId()).append(',')
                .append(p.currency()).append(',')
                .append(p.recordCount()).append(',')
                .append(p.grossMinor()).append(',')
                .append(p.interchangeMinor()).append(',')
                .append(p.netMinor()).append(CRLF));
        return csv.toString();
    }
}
