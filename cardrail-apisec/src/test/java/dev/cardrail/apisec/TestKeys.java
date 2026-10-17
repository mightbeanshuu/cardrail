package dev.cardrail.apisec;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/** RSA-2048 key pairs generated once per test JVM; no key material is committed. */
final class TestKeys {

    static final KeyPair CLIENT = generate();
    static final KeyPair OTHER_CLIENT = generate();
    static final KeyPair SERVER = generate();
    static final KeyPair ROTATED_SERVER = generate();

    private TestKeys() {
    }

    static RSAPublicKey pub(KeyPair pair) {
        return (RSAPublicKey) pair.getPublic();
    }

    static RSAPrivateKey priv(KeyPair pair) {
        return (RSAPrivateKey) pair.getPrivate();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
