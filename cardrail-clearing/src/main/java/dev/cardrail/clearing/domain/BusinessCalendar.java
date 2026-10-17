package dev.cardrail.clearing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Assigns each event to a clearing business date. The day runs from the cut-off on the previous
 * calendar day (inclusive) to the cut-off on this one (exclusive), in the settlement time zone: with
 * a 23:00 IST cut-off, 22:59:59.999 IST on 18 October is business date 18 October and 23:00:00 IST
 * is already 19 October. A midnight cut-off means business date equals calendar date.
 */
public record BusinessCalendar(ZoneId zone, LocalTime cutOff) {

    public BusinessCalendar {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(cutOff, "cutOff");
    }

    public LocalDate businessDateOf(Instant instant) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, zone);
        if (cutOff.equals(LocalTime.MIDNIGHT) || local.toLocalTime().isBefore(cutOff)) {
            return local.toLocalDate();
        }
        return local.toLocalDate().plusDays(1);
    }
}
