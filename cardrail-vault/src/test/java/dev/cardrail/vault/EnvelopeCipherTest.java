package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EnvelopeCipherTest {

    private static final String TOKEN_A = "9999990000000018";
    private static final String TOKEN_B = "9999990000000026";

    private final InMemoryKeyProvider keys = InMemoryKeyProvider.generate();
    private final EnvelopeCipher cipher = new EnvelopeCipher(keys);

    @Test
    void sealsAndOpens() {
        Envelope envelope = cipher.seal(TOKEN_A, TestPans.MC_1.toCharArray());

        assertThat(envelope.kekId()).isEqualTo("kek-v1");
        assertThat(envelope.iv()).hasSize(12);
        assertThat(envelope.wrappedDek()).hasSize(12 + 32 + 16);
        assertThat(envelope.ciphertext()).hasSize(16 + 16);
        assertThat(String.valueOf(cipher.open(TOKEN_A, envelope))).isEqualTo(TestPans.MC_1);
    }

    @Test
    void samePanSealsToDifferentCiphertextEachTime() {
        Envelope first = cipher.seal(TOKEN_A, TestPans.MC_1.toCharArray());
        Envelope second = cipher.seal(TOKEN_A, TestPans.MC_1.toCharArray());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.iv()).isNotEqualTo(second.iv());
    }

    @Test
    void envelopeIsBoundToItsToken() {
        Envelope envelope = cipher.seal(TOKEN_A, TestPans.MC_1.toCharArray());
        assertThatThrownBy(() -> cipher.open(TOKEN_B, envelope))
                .isInstanceOf(VaultIntegrityException.class)
                .hasMessageContaining("wrapped DEK");
    }

    @Test
    void rewrapChangesOnlyTheWrappedDek() {
        Envelope original = cipher.seal(TOKEN_A, TestPans.MC_1.toCharArray());
        keys.rotate();

        Envelope rewrapped = cipher.rewrap(TOKEN_A, original);

        assertThat(rewrapped.kekId()).isEqualTo("kek-v2");
        assertThat(rewrapped.wrappedDek()).isNotEqualTo(original.wrappedDek());
        assertThat(rewrapped.iv()).isSameAs(original.iv());
        assertThat(rewrapped.ciphertext()).isSameAs(original.ciphertext());
        assertThat(String.valueOf(cipher.open(TOKEN_A, rewrapped))).isEqualTo(TestPans.MC_1);
    }

    @Test
    void relabelledKekIdFailsAuthentication() {
        Envelope underV1 = cipher.seal(TOKEN_A, TestPans.MC_1.toCharArray());
        keys.rotate();
        Envelope relabelled = new Envelope("kek-v2", underV1.wrappedDek(), underV1.iv(), underV1.ciphertext());

        assertThatThrownBy(() -> cipher.open(TOKEN_A, relabelled)).isInstanceOf(VaultIntegrityException.class);
    }

    @Test
    void unknownKekIdFailsLoudly() {
        Envelope envelope = cipher.seal(TOKEN_A, TestPans.MC_1.toCharArray());
        Envelope orphan = new Envelope("kek-v9", envelope.wrappedDek(), envelope.iv(), envelope.ciphertext());

        assertThatThrownBy(() -> cipher.open(TOKEN_A, orphan))
                .isInstanceOf(KeyUnavailableException.class)
                .hasMessageContaining("kek-v9");
    }
}
