package dev.cardrail.loadgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** The cards a run draws from, uniformly at random. Immutable. */
public final class CardPool {

    /**
     * BINs of the Mastercard test card numbers that payment processors publish for integration testing
     * (5555555555554444, 5105105105105100, 2223003122003222, 5200828282828210), covering both the
     * 51-55 and the 2221-2720 Mastercard ranges. Generated PANs reuse these prefixes, so they look like
     * Mastercard cards to BIN routing while staying in ranges meant for test traffic.
     */
    public static final List<String> MASTERCARD_TEST_BINS = List.of("555555", "510510", "222300", "520082");

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private final List<Card> cards;

    private CardPool(List<Card> cards) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("card pool is empty");
        }
        this.cards = List.copyOf(cards);
    }

    public static CardPool of(List<Card> cards) {
        return new CardPool(cards);
    }

    /**
     * {@code size} distinct 16-digit Luhn-valid PANs on {@link #MASTERCARD_TEST_BINS}, each expiring
     * 1 to 48 months after {@code now}.
     */
    public static CardPool generated(int size, long seed, YearMonth now) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
        RandomGenerator rnd = new SplittableRandom(seed);
        Set<String> pans = new LinkedHashSet<>();
        List<Card> cards = new ArrayList<>(size);
        while (cards.size() < size) {
            String bin = MASTERCARD_TEST_BINS.get(rnd.nextInt(MASTERCARD_TEST_BINS.size()));
            StringBuilder payload = new StringBuilder(bin);
            while (payload.length() < 15) {
                payload.append((char) ('0' + rnd.nextInt(10)));
            }
            String pan = payload.append(Luhn.checkDigit(payload.toString())).toString();
            if (pans.add(pan)) {
                cards.add(new Card(pan, now.plusMonths(1 + rnd.nextInt(48)).format(YYMM)));
            }
        }
        return new CardPool(cards);
    }

    /**
     * Reads one card per line as {@code PAN} or {@code PAN,YYMM}. Blank lines and lines starting with
     * {@code #} are skipped; a card without an expiry gets {@code defaultExpiry}.
     *
     * @throws IllegalArgumentException naming the line (never its content) if a line is not a valid card
     */
    public static CardPool fromFile(Path file, YearMonth defaultExpiry) throws IOException {
        Objects.requireNonNull(defaultExpiry, "defaultExpiry");
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split(",", -1);
            try {
                if (parts.length > 2) {
                    throw new IllegalArgumentException("expected PAN or PAN,YYMM");
                }
                String expiry = parts.length == 2 ? parts[1].strip() : defaultExpiry.format(YYMM);
                cards.add(new Card(parts[0].strip(), expiry));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(file + " line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return new CardPool(cards);
    }

    public int size() {
        return cards.size();
    }

    public Card get(int index) {
        return cards.get(index);
    }

    public Card pick(RandomGenerator rnd) {
        return cards.get(rnd.nextInt(cards.size()));
    }
}
