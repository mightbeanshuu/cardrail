package dev.cardrail.clearing.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * JDBC 4.2 java.time mappings that both pgjdbc and ojdbc11 implement: TIMESTAMP WITH TIME ZONE as
 * OffsetDateTime (neither driver maps it to Instant) and DATE as LocalDate.
 */
final class SqlTypes {

    private SqlTypes() {
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}
