package dev.cardrail.clearing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A rate in basis points plus a fixed fee per transaction, in the transaction currency's minor units. */
public record InterchangeRate(String category, int rateBps, long fixedFeeMinor) {

    private static final BigDecimal BPS_PER_UNIT = BigDecimal.valueOf(10_000);

    public InterchangeRate {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category is required");
        }
        if (rateBps < 0 || rateBps > 10_000) {
            throw new IllegalArgumentException("rateBps must be within 0..10000, was " + rateBps);
        }
        if (fixedFeeMinor < 0) {
            throw new IllegalArgumentException("fixedFeeMinor must not be negative, was " + fixedFeeMinor);
        }
    }

    /** round-half-even(amount * bps / 10000) + fixed fee. */
    public long feeFor(long amountMinor) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("interchange is computed on a positive amount, was " + amountMinor);
        }
        long proportional = BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(rateBps))
                .divide(BPS_PER_UNIT, 0, RoundingMode.HALF_EVEN)
                .longValueExact();
        return Math.addExact(proportional, fixedFeeMinor);
    }
}
