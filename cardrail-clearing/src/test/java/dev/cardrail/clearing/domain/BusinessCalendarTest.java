package dev.cardrail.clearing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessCalendarTest {

    private static final BusinessCalendar IST_23 = new BusinessCalendar(ZoneId.of("Asia/Kolkata"), LocalTime.of(23, 0));

    // 23:00 IST is 17:30 UTC.
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-10-18T17:29:59.999999999Z, 2026-10-18",
            "2026-10-18T17:30:00Z,           2026-10-19",
            "2026-10-18T17:30:00.000000001Z, 2026-10-19",
            "2026-10-18T18:29:59Z,           2026-10-19",
            "2026-10-18T18:30:00Z,           2026-10-19",
            "2026-10-17T18:30:00Z,           2026-10-18",
            "2026-10-18T09:15:30Z,           2026-10-18"
    })
    void eventsAtOrAfterTheCutOffBelongToTheNextBusinessDate(Instant occurredAt, LocalDate expected) {
        assertThat(IST_23.businessDateOf(occurredAt)).isEqualTo(expected);
    }

    @Test
    void theCutOffIsJudgedInTheSettlementZoneNotUtc() {
        Instant elevenPmUtc = Instant.parse("2026-10-18T23:00:00Z");
        assertThat(IST_23.businessDateOf(elevenPmUtc)).as("04:30 IST on the 19th").isEqualTo(LocalDate.of(2026, 10, 19));

        BusinessCalendar utc = new BusinessCalendar(ZoneId.of("UTC"), LocalTime.of(23, 0));
        assertThat(utc.businessDateOf(elevenPmUtc)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(utc.businessDateOf(Instant.parse("2026-10-18T22:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void aMidnightCutOffIsTheCalendarDate() {
        BusinessCalendar midnight = new BusinessCalendar(ZoneId.of("Asia/Kolkata"), LocalTime.MIDNIGHT);
        assertThat(midnight.businessDateOf(Instant.parse("2026-10-18T18:29:59Z"))).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(midnight.businessDateOf(Instant.parse("2026-10-18T18:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 19));
    }
}
