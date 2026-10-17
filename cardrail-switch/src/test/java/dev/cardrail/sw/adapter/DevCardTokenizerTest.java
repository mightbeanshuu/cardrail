package dev.cardrail.sw.adapter;

import dev.cardrail.sw.adapter.dev.DevCardTokenizer;
import dev.cardrail.sw.domain.card.Luhn;
import dev.cardrail.sw.port.InvalidCardNumberException;
import dev.cardrail.sw.port.TokenizedCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevCardTokenizerTest {

    private final DevCardTokenizer tokenizer = new DevCardTokenizer("test-key-".repeat(4));

    @Test
    void tokenIsDeterministicLuhnValidAndInTheReservedBin() {
        TokenizedCard card = tokenizer.tokenize("5555555555554444");

        assertThat(tokenizer.tokenize("5555555555554444")).isEqualTo(card);
        assertThat(card.token()).hasSize(19).startsWith("999999").doesNotContain("5555555555554444");
        assertThat(Luhn.isValid(card.token())).isTrue();
        assertThat(card.maskedPan()).isEqualTo("555555******4444");
        assertThat(card.bin()).isEqualTo("555555");
        assertThat(card.last4()).isEqualTo("4444");
    }

    @Test
    void differentPansAndDifferentKeysGiveDifferentTokens() {
        assertThat(tokenizer.tokenize("5105105105105100").token())
                .isNotEqualTo(tokenizer.tokenize("5555555555554444").token());
        assertThat(new DevCardTokenizer("other-key".repeat(4)).tokenize("5555555555554444").token())
                .isNotEqualTo(tokenizer.tokenize("5555555555554444").token());
    }

    @Test
    void rejectsBadLengthAndCheckDigitWithoutEchoingThePan() {
        assertThatThrownBy(() -> tokenizer.tokenize("5555555555554445"))
                .isInstanceOf(InvalidCardNumberException.class)
                .message().doesNotContain("5555555555554445");
        assertThatThrownBy(() -> tokenizer.tokenize("42")).isInstanceOf(InvalidCardNumberException.class);
        assertThatThrownBy(() -> tokenizer.tokenize("55555555555544A4")).isInstanceOf(InvalidCardNumberException.class);
    }
}
