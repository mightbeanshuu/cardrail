package dev.cardrail.sw.adapter.vault;

import dev.cardrail.vault.KeyProvider;
import dev.cardrail.vault.KeyStatus;
import dev.cardrail.vault.KeyVersion;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keys decoded once from {@link VaultProperties}. Rotation is a configuration change and a restart:
 * add the new KEK as ACTIVE, demote the previous one to DECRYPT_ONLY, then rewrap.
 *
 * <p>The keys live in the JVM heap next to the data they protect. A deployment that has an HSM or
 * KMS implements {@link KeyProvider} against it instead.
 */
final class ConfiguredKeyProvider implements KeyProvider {

    static final int KEK_BYTES = 32;
    static final int MIN_BLIND_INDEX_KEY_BYTES = 32;

    private final Map<String, KeyVersion> keks;
    private final KeyVersion active;
    private final SecretKey blindIndexKey;

    private ConfiguredKeyProvider(Map<String, KeyVersion> keks, KeyVersion active, SecretKey blindIndexKey) {
        this.keks = keks;
        this.active = active;
        this.blindIndexKey = blindIndexKey;
    }

    /** Error messages name the property and the key id, never the key. */
    static ConfiguredKeyProvider from(VaultProperties properties) {
        if (properties.keks().isEmpty()) {
            throw new IllegalStateException("cardrail.vault.keks is empty: configure at least one KEK "
                    + "(the dev and test profiles ship non-secret development keys)");
        }
        Map<String, KeyVersion> keks = new LinkedHashMap<>();
        for (VaultProperties.Kek kek : properties.keks()) {
            byte[] raw = decode(kek.key(), "cardrail.vault.keks[" + kek.id() + "].key");
            if (raw.length != KEK_BYTES) {
                throw new IllegalStateException("cardrail.vault.keks[" + kek.id() + "].key must be "
                        + KEK_BYTES + " bytes (AES-256), got " + raw.length);
            }
            if (keks.putIfAbsent(kek.id(), new KeyVersion(kek.id(), new SecretKeySpec(raw, "AES"), kek.status())) != null) {
                throw new IllegalStateException("cardrail.vault.keks: duplicate KEK id " + kek.id());
            }
        }
        List<KeyVersion> active = keks.values().stream().filter(k -> k.status() == KeyStatus.ACTIVE).toList();
        if (active.size() != 1) {
            throw new IllegalStateException("cardrail.vault.keks must have exactly one ACTIVE KEK, found "
                    + active.size());
        }
        if (properties.blindIndexKey() == null) {
            throw new IllegalStateException("cardrail.vault.blind-index-key is not set");
        }
        byte[] index = decode(properties.blindIndexKey(), "cardrail.vault.blind-index-key");
        if (index.length < MIN_BLIND_INDEX_KEY_BYTES) {
            throw new IllegalStateException("cardrail.vault.blind-index-key must be at least "
                    + MIN_BLIND_INDEX_KEY_BYTES + " bytes, got " + index.length);
        }
        return new ConfiguredKeyProvider(Map.copyOf(keks), active.getFirst(), new SecretKeySpec(index, "HmacSHA256"));
    }

    private static byte[] decode(String base64, String property) {
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " is not valid base64");
        }
    }

    @Override
    public KeyVersion activeKek() {
        return active;
    }

    @Override
    public Optional<KeyVersion> kek(String id) {
        return Optional.ofNullable(keks.get(id));
    }

    @Override
    public SecretKey blindIndexKey() {
        return blindIndexKey;
    }
}
