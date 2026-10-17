package dev.cardrail.loadgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardPoolTest {

    @Test
    void publishedMastercardTestNumbersPassLuhnAndCoverTheTestBins() {
        for (String pan : new String[] {"5555555555554444", "5105105105105100", "2223003122003222", "5200828282828210"}) {
            assertThat(Luhn.isValid(pan)).as(pan).isTrue();
            assertThat(CardPool.MASTERCARD_TEST_BINS).contains(pan.substring(0, 6));
        }
        assertThat(Luhn.isValid("5555555555554445")).isFalse();
        assertThat(Luhn.isValid("79927398713")).isTrue();
        assertThat(Luhn.checkDigit("7992739871")).isEqualTo(3);
    }

    @Test
    void generatedCardsAreDistinctValidMastercardTestNumbersWithFutureExpiry() {
        YearMonth now = YearMonth.of(2026, 10);
        CardPool pool = CardPool.generated(2000, 42, now);
        Set<String> pans = new HashSet<>();
        for (int i = 0; i < pool.size(); i++) {
            Card c = pool.get(i);
            assertThat(c.pan()).hasSize(16);
            assertThat(Luhn.isValid(c.pan())).isTrue();
            assertThat(CardPool.MASTERCARD_TEST_BINS).contains(c.pan().substring(0, 6));
            YearMonth expiry = YearMonth.of(2000 + Integer.parseInt(c.expiry().substring(0, 2)),
                    Integer.parseInt(c.expiry().substring(2)));
            assertThat(expiry).isAfter(now).isBeforeOrEqualTo(now.plusMonths(48));
            pans.add(c.pan());
        }
        assertThat(pans).hasSize(2000);
        assertThat(CardPool.generated(10, 42, now).get(3)).isEqualTo(CardPool.generated(10, 42, now).get(3));
    }

    @Test
    void readsCardsFromFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pans.txt");
        Files.writeString(file, "# test cards\n5555555555554444,2912\n\n  5105105105105100  \n");
        CardPool pool = CardPool.fromFile(file, YearMonth.of(2029, 1));
        assertThat(pool.size()).isEqualTo(2);
        assertThat(pool.get(0)).isEqualTo(new Card("5555555555554444", "2912"));
        assertThat(pool.get(1)).isEqualTo(new Card("5105105105105100", "2901"));
    }

    @Test
    void invalidFileLineIsReportedByNumberWithoutItsContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pans.txt");
        Files.writeString(file, "5555555555554444\n5555555555554445\n");
        assertThatThrownBy(() -> CardPool.fromFile(file, YearMonth.of(2029, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2")
                .message().doesNotContain("5555555555554445");
    }

    @Test
    void cardToStringMasksThePan() {
        assertThat(new Card("5555555555554444", "2912").toString())
                .doesNotContain("5555555555554444")
                .contains("555555")
                .contains("4444");
    }
}
