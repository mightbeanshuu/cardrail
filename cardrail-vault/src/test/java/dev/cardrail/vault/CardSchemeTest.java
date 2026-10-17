package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CardSchemeTest {

    @ParameterizedTest
    @CsvSource({
            "5555555555554444, MASTERCARD",
            "5105105105105100, MASTERCARD",
            "2223003122003222, MASTERCARD",
            "222100, MASTERCARD",
            "272099, MASTERCARD",
            "222099, UNKNOWN",
            "272100, UNKNOWN",
            "500000, UNKNOWN",
            "510000, MASTERCARD",
            "559999, MASTERCARD",
            "560000, UNKNOWN",
            "4111111111111111, VISA",
            "378282246310005, AMEX",
            "340000, AMEX",
            "350000, UNKNOWN",
            "608000, RUPAY",
            "652150, RUPAY",
            "817200, RUPAY",
            "820000, RUPAY",
            "508500, RUPAY",
            "507000, UNKNOWN",
            "999999, UNKNOWN",
            "555555******4444, MASTERCARD",
            "22, UNKNOWN",
    })
    void detectsSchemeFromLeadingDigits(String digits, CardScheme expected) {
        assertThat(CardScheme.detect(digits)).isEqualTo(expected);
    }
}
