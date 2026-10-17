package dev.cardrail.apisec;

import java.security.interfaces.RSAPublicKey;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory {@link ConsumerKeyRegistry}. Refuses RSA keys shorter than 2048 bits. */
public final class InMemoryConsumerKeyRegistry implements ConsumerKeyRegistry {

    static final int MIN_MODULUS_BITS = 2048;

    private final ConcurrentHashMap<String, RSAPublicKey> keys = new ConcurrentHashMap<>();

    public InMemoryConsumerKeyRegistry register(String consumerKey, RSAPublicKey publicKey) {
        Objects.requireNonNull(consumerKey, "consumerKey");
        Objects.requireNonNull(publicKey, "publicKey");
        if (publicKey.getModulus().bitLength() < MIN_MODULUS_BITS) {
            throw new IllegalArgumentException("RSA key must be at least " + MIN_MODULUS_BITS + " bits");
        }
        keys.put(consumerKey, publicKey);
        return this;
    }

    public void revoke(String consumerKey) {
        keys.remove(consumerKey);
    }

    @Override
    public Optional<RSAPublicKey> publicKeyFor(String consumerKey) {
        return Optional.ofNullable(keys.get(consumerKey));
    }
}
