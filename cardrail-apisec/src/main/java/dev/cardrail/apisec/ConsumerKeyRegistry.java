package dev.cardrail.apisec;

import java.security.interfaces.RSAPublicKey;
import java.util.Optional;

/** Lookup of the RSA public key a client registered for its OAuth consumer key. */
public interface ConsumerKeyRegistry {

    Optional<RSAPublicKey> publicKeyFor(String consumerKey);
}
