package dev.cardrail.sw.application.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * @param currencies limits per ISO 4217 numeric currency; a currency not listed here is not accepted
 */
@ConfigurationProperties("cardrail.risk")
public record RiskProperties(Map<Integer, CurrencyLimits> currencies, Velocity velocity) {

    public RiskProperties {
        Objects.requireNonNull(currencies, "cardrail.risk.currencies");
        Objects.requireNonNull(velocity, "cardrail.risk.velocity");
        currencies = Map.copyOf(currencies);
    }

    public record CurrencyLimits(long maxTransactionMinor, long velocityMaxAmountMinor) {
    }

    /**
     * @param degradedRatio fraction of the normal limits applied while the shared counter is down
     */
    public record Velocity(Duration window, int maxCount, double degradedRatio) {
    }
}
