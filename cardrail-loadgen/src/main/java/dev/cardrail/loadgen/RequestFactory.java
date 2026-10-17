package dev.cardrail.loadgen;

import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.Mti;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Builds {@code 0100} purchase authorizations as an acquirer host would forward them from one
 * terminal. Thread-safe; randomness comes from the caller.
 */
public final class RequestFactory {

    /** Grocery, restaurants, fast food, fuel, department stores, electronics, misc. retail, transit, hotels, pharmacies. */
    public static final List<String> MCCS =
            List.of("5411", "5812", "5814", "5541", "5311", "5732", "5999", "4111", "7011", "5912");

    /** Chip and contactless chip, both with PIN entry capability. */
    private static final List<String> POS_ENTRY_MODES = List.of("051", "071");

    public static final long MIN_AMOUNT_MINOR = 100;
    public static final long MAX_AMOUNT_MINOR = 500_000;

    private static final DateTimeFormatter TRANSMISSION = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("MMdd");

    private final String acquirerId;
    private final String terminalId;
    private final String merchantId;
    private final String cardAcceptorNameLocation;
    private final String countryCode;
    private final String currencyCode;
    private final ZoneId terminalZone;

    /**
     * @param acquirerId   field 32, up to 11 digits
     * @param terminalId   field 41, 8 characters; must differ between connections, since the switch
     *                     treats (acquirer, STAN, field 7, terminal) as the duplicate-transmission key
     * @param countryCode  field 19, ISO 3166 numeric
     * @param currencyCode field 49, ISO 4217 numeric
     * @param terminalZone zone of the terminal's local time and date (fields 12 and 13)
     */
    public RequestFactory(String acquirerId, String terminalId, String merchantId, String countryCode,
                          String currencyCode, ZoneId terminalZone) {
        this.acquirerId = Objects.requireNonNull(acquirerId, "acquirerId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.countryCode = Objects.requireNonNull(countryCode, "countryCode");
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.terminalZone = Objects.requireNonNull(terminalZone, "terminalZone");
        // Field 43 layout: name (25), city (13), country (2).
        this.cardAcceptorNameLocation = String.format("%-25s%-13s%-2s", "CARDRAIL LOADGEN", "TEST CITY", "XX");
        // Validates the per-terminal fields once, so a bad option fails at startup, not on the first request.
        IsoMessage.builder(Mti.AUTHORIZATION_REQUEST)
                .field(32, acquirerId).field(41, terminalId).field(42, merchantId)
                .field(19, countryCode).field(49, currencyCode)
                .build();
    }

    public IsoMessage authorization(Card card, String stan, Instant transmittedAt, RandomGenerator rnd) {
        ZonedDateTime utc = transmittedAt.atZone(ZoneOffset.UTC);
        ZonedDateTime local = transmittedAt.atZone(terminalZone);
        return IsoMessage.builder(Mti.AUTHORIZATION_REQUEST)
                .field(2, card.pan())
                .field(3, "000000")
                .field(4, Long.toString(amount(rnd)))
                .field(7, TRANSMISSION.format(utc))
                .field(11, stan)
                .field(12, LOCAL_TIME.format(local))
                .field(13, LOCAL_DATE.format(local))
                .field(14, card.expiry())
                .field(18, MCCS.get(rnd.nextInt(MCCS.size())))
                .field(19, countryCode)
                .field(22, POS_ENTRY_MODES.get(rnd.nextInt(POS_ENTRY_MODES.size())))
                .field(32, acquirerId)
                .field(37, retrievalReferenceNumber(utc, stan))
                .field(41, terminalId)
                .field(42, merchantId)
                .field(43, cardAcceptorNameLocation)
                .field(49, currencyCode)
                .build();
    }

    /** Log-uniform between {@link #MIN_AMOUNT_MINOR} and {@link #MAX_AMOUNT_MINOR}: many small tickets, few large. */
    static long amount(RandomGenerator rnd) {
        double lo = Math.log(MIN_AMOUNT_MINOR);
        double hi = Math.log(MAX_AMOUNT_MINOR);
        long v = Math.round(Math.exp(lo + rnd.nextDouble() * (hi - lo)));
        return Math.clamp(v, MIN_AMOUNT_MINOR, MAX_AMOUNT_MINOR);
    }

    /** The common YDDDhh + STAN layout: last digit of the year, day of year, hour, then the 6-digit STAN. */
    static String retrievalReferenceNumber(ZonedDateTime utc, String stan) {
        return String.format("%d%03d%02d%s", utc.getYear() % 10, utc.getDayOfYear(), utc.getHour(), stan);
    }
}
