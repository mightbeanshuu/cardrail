package dev.cardrail.apisec;

import java.util.Objects;

/** Outcome of {@link OAuth1Verifier#verify}. */
public sealed interface VerificationResult {

    /** The request was signed by the holder of the private key registered for {@code consumerKey}. */
    record Verified(String consumerKey) implements VerificationResult {
        public Verified {
            Objects.requireNonNull(consumerKey, "consumerKey");
        }
    }

    /**
     * The request must not be processed. {@code detail} is for server-side logs only; return a generic 401 to
     * the caller so the verifier is not an oracle.
     */
    record Rejected(Reason reason, String detail) implements VerificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }

    enum Reason {
        /** Header absent, not the OAuth scheme, syntactically invalid, or missing a required parameter. */
        MALFORMED_HEADER,
        /** The request URI cannot be normalised into a signature base string unambiguously. */
        MALFORMED_REQUEST,
        /** {@code oauth_signature_method} is not one this verifier was configured to accept. */
        UNSUPPORTED_METHOD,
        /** {@code oauth_timestamp} is outside the allowed clock skew. */
        STALE_TIMESTAMP,
        /** No public key is registered for {@code oauth_consumer_key}. */
        UNKNOWN_CONSUMER,
        /** {@code oauth_body_hash} does not match SHA-256 of the received body. */
        BODY_HASH_MISMATCH,
        /** The signature does not verify over the recomputed signature base string. */
        BAD_SIGNATURE,
        /** A correctly signed request whose nonce was already accepted within the timestamp window. */
        REPLAYED_NONCE,
        /** The nonce store is full of unexpired entries; rejecting is the only choice that keeps replay protection. */
        NONCE_STORE_FULL
    }

    default boolean isVerified() {
        return this instanceof Verified;
    }
}
