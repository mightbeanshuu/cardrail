package dev.cardrail.apisec;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Optional;

/**
 * {@code oauth_signature_method} values understood by this module. Mastercard's signer defaults to
 * {@link #RSA_SHA256}; {@code oauth1-signer} 1.6.0 can also emit {@link #RSA_PSS}.
 */
public enum OAuthSignatureMethod {

    /** RSASSA-PKCS1-v1_5 with SHA-256. Deterministic: same key and base string give the same signature. */
    RSA_SHA256("RSA-SHA256"),

    /** RSASSA-PSS with SHA-256, MGF1-SHA-256 and a 32-byte salt, the parameters Mastercard's signer uses. */
    RSA_PSS("RSA-PSS");

    private static final PSSParameterSpec PSS_SHA256 =
            new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, PSSParameterSpec.TRAILER_FIELD_BC);

    private final String oauthName;

    OAuthSignatureMethod(String oauthName) {
        this.oauthName = oauthName;
    }

    /** The exact token carried in {@code oauth_signature_method}. */
    public String oauthName() {
        return oauthName;
    }

    public static Optional<OAuthSignatureMethod> fromOAuthName(String name) {
        for (OAuthSignatureMethod m : values()) {
            if (m.oauthName.equals(name)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    Signature newSignature() throws GeneralSecurityException {
        return switch (this) {
            case RSA_SHA256 -> Signature.getInstance("SHA256withRSA");
            case RSA_PSS -> {
                Signature s = Signature.getInstance("RSASSA-PSS");
                s.setParameter(PSS_SHA256);
                yield s;
            }
        };
    }
}
