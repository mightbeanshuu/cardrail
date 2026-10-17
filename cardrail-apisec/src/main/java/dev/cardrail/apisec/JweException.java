package dev.cardrail.apisec;

import java.util.Objects;

/** A JWE or field-level encryption operation was refused. Messages never contain plaintext or key material. */
public final class JweException extends RuntimeException {

    public enum Reason {
        /** Not five base64url parts, non-canonical base64url, bad header JSON, missing kid, bad IV or tag length. */
        MALFORMED,
        /** {@code alg}/{@code enc} other than the configured pair, or a {@code zip}/{@code crit} header. */
        UNSUPPORTED_ALGORITHM,
        /** No decryption key is configured for the {@code kid}. */
        UNKNOWN_KEY,
        /** Key unwrap or GCM authentication failed. The two are deliberately indistinguishable. */
        DECRYPTION_FAILED,
        /** The JSON document is invalid or does not fit the configured field mapping. */
        INVALID_PAYLOAD
    }

    private final Reason reason;

    JweException(Reason reason, String message) {
        this(reason, message, null);
    }

    JweException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
