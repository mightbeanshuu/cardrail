package dev.cardrail.sw.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardAllowlistTest {

    @Test
    void emptyAllowsEveryNumber() {
        assertThat(new CardAllowlist(List.of()).permits("5412751234123452")).isTrue();
        assertThat(new CardAllowlist(null).permits("4111111111111111")).isTrue();
    }

    @Test
    void aNumberMustStartWithOneOfThePrefixes() {
        CardAllowlist allowlist = new CardAllowlist(List.of("5555555555554444", "222240"));

        assertThat(allowlist.permits("5555555555554444")).isTrue();
        assertThat(allowlist.permits("2222400070000005")).isTrue();
        assertThat(allowlist.permits("5555555555554445")).isFalse();
        assertThat(allowlist.permits("5412751234123452")).isFalse();
    }

    @Test
    void prefixesAreDigits() {
        assertThatThrownBy(() -> new CardAllowlist(List.of("5555 5555")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardrail.cards.allowed-prefixes");
    }
}
