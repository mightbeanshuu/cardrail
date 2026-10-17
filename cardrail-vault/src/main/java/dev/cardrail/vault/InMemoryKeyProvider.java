package dev.cardrail.vault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Keys generated in process memory. For tests and local development only: the keys die with the
 * JVM and sit in the same heap as the data they protect.
 */
public final class InMemoryKeyProvider implements KeyProvider {

    private final SecureRandom random = new SecureRandom();
    private final Map<String, KeyVersion> keks = new LinkedHashMap<>();
    private final SecretKey blindIndexKey;
    private KeyVersion active;
    private int generation;

    private InMemoryKeyProvider() {
        this.blindIndexKey = generate("HmacSHA256");
        this.active = addKek(KeyStatus.ACTIVE);
    }

    /** A provider with one ACTIVE KEK ({@code kek-v1}) and a fresh blind index key. */
    public static InMemoryKeyProvider generate() {
        return new InMemoryKeyProvider();
    }

    /** Adds a new ACTIVE KEK and demotes the previous one to DECRYPT_ONLY. Returns the new KEK. */
    public synchronized KeyVersion rotate() {
        keks.put(active.id(), active.withStatus(KeyStatus.DECRYPT_ONLY));
        active = addKek(KeyStatus.ACTIVE);
        return active;
    }

    /**
     * Marks a KEK RETIRED. Call only after {@link TokenVault#rewrapAll()} has moved every record
     * off it; records left behind will fail to detokenize.
     */
    public synchronized void retire(String id) {
        KeyVersion kek = keks.get(id);
        if (kek == null) {
            throw new IllegalArgumentException("unknown KEK " + id);
        }
        if (kek.status() == KeyStatus.ACTIVE) {
            throw new IllegalStateException("cannot retire the active KEK " + id + "; rotate first");
        }
        keks.put(id, kek.withStatus(KeyStatus.RETIRED));
    }

    @Override
    public synchronized KeyVersion activeKek() {
        return active;
    }

    @Override
    public synchronized Optional<KeyVersion> kek(String id) {
        return Optional.ofNullable(keks.get(id));
    }

    @Override
    public SecretKey blindIndexKey() {
        return blindIndexKey;
    }

    /** All KEKs in creation order. */
    public synchronized List<KeyVersion> keks() {
        return List.copyOf(keks.values());
    }

    private KeyVersion addKek(KeyStatus status) {
        generation++;
        KeyVersion kek = new KeyVersion("kek-v" + generation, generate("AES"), status);
        keks.put(kek.id(), kek);
        return kek;
    }

    private SecretKey generate(String algorithm) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(algorithm);
            generator.init(256, random);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " key generation unavailable", e);
        }
    }
}
