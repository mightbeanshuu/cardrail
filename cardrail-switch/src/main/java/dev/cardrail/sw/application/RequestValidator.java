package dev.cardrail.sw.application;

import dev.cardrail.sw.application.risk.RiskProperties;
import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.CardExpiry;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Step (a) of the pipeline. Format errors (30) concern the message itself, including the fields that
 * identify it, so they are answered without being stored. Content errors (13, 12, 54) come from a
 * well-formed message and are stored like any other decline.
 */
@Component
public class RequestValidator {

    private static final long MAX_AMOUNT_MINOR = 999_999_999_999L;
    private static final Pattern N3 = Pattern.compile("\\d{3}");
    private static final Pattern N4 = Pattern.compile("\\d{4}");
    private static final Pattern N6 = Pattern.compile("\\d{6}");
    private static final Pattern N10 = Pattern.compile("\\d{10}");
    private static final Pattern ACQUIRER_ID = Pattern.compile("\\d{1,11}");
    private static final Pattern RRN = Pattern.compile("[A-Za-z0-9]{12}");

    private final Set<Integer> supportedCurrencies;

    public RequestValidator(RiskProperties risk) {
        this.supportedCurrencies = Set.copyOf(risk.currencies().keySet());
    }

    public record Rejection(ResponseCode code, String reason, boolean storable) {
    }

    public Optional<Rejection> validate(AuthorizationRequest r, Instant now) {
        Optional<String> formatError = formatError(r);
        if (formatError.isPresent()) {
            return Optional.of(new Rejection(ResponseCode.FORMAT_ERROR, formatError.get(), false));
        }
        if (r.amountMinor() <= 0 || r.amountMinor() > MAX_AMOUNT_MINOR) {
            return stored(ResponseCode.INVALID_AMOUNT, "amount-out-of-range");
        }
        if (!supportedCurrencies.contains(r.currency())) {
            return stored(ResponseCode.INVALID_TRANSACTION, "currency-not-supported");
        }
        CardExpiry expiry = CardExpiry.parse(r.expiry()).orElseThrow();
        if (expiry.isExpiredAt(YearMonth.from(now.atZone(ZoneOffset.UTC)))) {
            return stored(ResponseCode.EXPIRED_CARD, "card-expired");
        }
        return Optional.empty();
    }

    private static Optional<String> formatError(AuthorizationRequest r) {
        if (!N6.matcher(r.stan()).matches()) {
            return Optional.of("stan");
        }
        if (!isTransmissionDateTime(r.transmissionDateTime())) {
            return Optional.of("transmission-datetime");
        }
        if (!ACQUIRER_ID.matcher(r.acquirerId()).matches()) {
            return Optional.of("acquirer-id");
        }
        if (!lengthBetween(r.terminalId(), 1, 8)) {
            return Optional.of("terminal-id");
        }
        if (!RRN.matcher(r.rrn()).matches()) {
            return Optional.of("rrn");
        }
        if (CardExpiry.parse(r.expiry()).isEmpty()) {
            return Optional.of("expiry");
        }
        if (!N4.matcher(r.mcc()).matches()) {
            return Optional.of("mcc");
        }
        if (!N3.matcher(r.merchantCountry()).matches() || !N3.matcher(r.posEntryMode()).matches()) {
            return Optional.of("merchant-country-or-pos-entry-mode");
        }
        if (!lengthBetween(r.merchantId(), 1, 15) || !lengthBetween(r.merchantName(), 1, 40)) {
            return Optional.of("merchant");
        }
        if (r.currency() < 1 || r.currency() > 999) {
            return Optional.of("currency");
        }
        return Optional.empty();
    }

    /** F7 is MMDDhhmmss without a year, so only the calendar fields themselves can be checked. */
    private static boolean isTransmissionDateTime(String value) {
        if (!N10.matcher(value).matches()) {
            return false;
        }
        try {
            MonthDay.of(Integer.parseInt(value.substring(0, 2)), Integer.parseInt(value.substring(2, 4)));
        } catch (DateTimeException e) {
            return false;
        }
        return Integer.parseInt(value.substring(4, 6)) < 24
                && Integer.parseInt(value.substring(6, 8)) < 60
                && Integer.parseInt(value.substring(8, 10)) < 60;
    }

    private static boolean lengthBetween(String value, int min, int max) {
        return value.length() >= min && value.length() <= max;
    }

    private static Optional<Rejection> stored(ResponseCode code, String reason) {
        return Optional.of(new Rejection(code, reason, true));
    }
}
