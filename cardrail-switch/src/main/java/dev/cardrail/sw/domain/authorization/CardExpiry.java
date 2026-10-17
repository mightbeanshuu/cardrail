package dev.cardrail.sw.domain.authorization;

import java.time.YearMonth;
import java.util.Optional;

/** F14 card expiry. A card is valid through the last day of its expiry month. */
public record CardExpiry(YearMonth month) {

    public static Optional<CardExpiry> parse(String yymm) {
        if (yymm == null || yymm.length() != 4 || !yymm.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        int year = 2000 + Integer.parseInt(yymm.substring(0, 2));
        int month = Integer.parseInt(yymm.substring(2));
        if (month < 1 || month > 12) {
            return Optional.empty();
        }
        return Optional.of(new CardExpiry(YearMonth.of(year, month)));
    }

    public boolean isExpiredAt(YearMonth current) {
        return month.isBefore(current);
    }
}
