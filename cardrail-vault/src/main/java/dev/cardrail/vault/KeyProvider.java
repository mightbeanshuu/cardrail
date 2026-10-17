package dev.cardrail.vault;

import java.util.Optional;
import javax.crypto.SecretKey;

/**
 * Source of key material for the vault. This is the seam for an HSM or cloud KMS: production
 * implementations keep KEKs inside the HSM and expose wrap/unwrap there; {@link InMemoryKeyProvider}
 * exists for tests and local development only.
 */
public interface KeyProvider {

    /** The KEK that wraps new DEKs. Its status is {@link KeyStatus#ACTIVE}. */
    KeyVersion activeKek();

    /** Any known KEK by id, whatever its status. */
    Optional<KeyVersion> kek(String id);

    /**
     * HMAC-SHA256 key for the blind index. Deliberately separate from every KEK: rotating KEKs must
     * not change blind index values, or existing PANs would no longer be found.
     */
    SecretKey blindIndexKey();
}
