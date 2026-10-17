package dev.cardrail.sw.adapter.vault;

import dev.cardrail.vault.KeyStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Objects;

/**
 * Key material and token format for the card vault, under {@code cardrail.vault}.
 *
 * <p>Keys are base64 so they can come from environment variables or a secret store
 * ({@code CARDRAIL_VAULT_KEKS_0_KEY=...}). They must be the same on every instance and across
 * restarts: the blind index key decides which token a PAN maps to, and a KEK that is lost makes the
 * records wrapped under it unreadable.
 *
 * @param tokenBin      first six digits of every token; must lie outside every card scheme's range
 * @param keks          key-encryption keys; exactly one ACTIVE, older ones DECRYPT_ONLY until rewrapped
 * @param blindIndexKey HMAC-SHA256 key for the blind index, at least 32 bytes; never rotated with the KEKs
 */
@ConfigurationProperties("cardrail.vault")
public record VaultProperties(@DefaultValue("999999") String tokenBin, List<Kek> keks, String blindIndexKey) {

    public VaultProperties {
        keks = keks == null ? List.of() : List.copyOf(keks);
    }

    /**
     * @param key base64 of a 256-bit AES key
     */
    public record Kek(String id, String key, KeyStatus status) {

        public Kek {
            Objects.requireNonNull(id, "cardrail.vault.keks[].id");
            Objects.requireNonNull(key, "cardrail.vault.keks[].key");
            Objects.requireNonNull(status, "cardrail.vault.keks[].status");
        }

        @Override
        public String toString() {
            return "Kek[id=" + id + ", status=" + status + "]";
        }
    }

    @Override
    public String toString() {
        return "VaultProperties[tokenBin=" + tokenBin + ", keks=" + keks + "]";
    }
}
