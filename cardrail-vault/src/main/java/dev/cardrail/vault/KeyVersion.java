package dev.cardrail.vault;

import java.util.Objects;
import javax.crypto.SecretKey;

/** A versioned key-encryption key. {@code toString()} omits the key. */
public record KeyVersion(String id, SecretKey key, KeyStatus status) {

    public KeyVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
    }

    KeyVersion withStatus(KeyStatus newStatus) {
        return new KeyVersion(id, key, newStatus);
    }

    @Override
    public String toString() {
        return "KeyVersion[id=" + id + ", status=" + status + "]";
    }
}
