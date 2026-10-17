package dev.cardrail.sw.port;

/**
 * Replaces a PAN with a token before anything is stored or logged.
 *
 * <p>Implementations must be deterministic (same PAN, same token): the token keys the issuer account,
 * the velocity counters and the outbox partitioning.
 */
public interface CardTokenizer {

    /** @throws InvalidCardNumberException if the PAN fails length or Luhn checks */
    TokenizedCard tokenize(String pan);
}
