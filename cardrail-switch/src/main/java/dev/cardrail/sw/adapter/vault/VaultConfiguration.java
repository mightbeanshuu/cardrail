package dev.cardrail.sw.adapter.vault;

import dev.cardrail.sw.port.AuditTrail;
import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.vault.TokenFormat;
import dev.cardrail.vault.TokenVault;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** The default tokenizer ({@code cardrail.tokenizer=vault}). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cardrail.tokenizer", havingValue = "vault", matchIfMissing = true)
class VaultConfiguration {

    static final Profiles DEV_KEY_PROFILES = Profiles.of("dev | test");

    /**
     * SHA-256 of the development keys published in application.yml. Matching by value rather than by
     * KEK id or profile name catches the keys however they got into the configuration.
     */
    private static final Set<String> DEV_KEY_FINGERPRINTS = Set.of(
            "3b09179f8041084a247c2c62ab8769c86bbedf7d36a235c83d136c63ccd34385",   // dev-kek-v1
            "9fdba2f56acf6931e22c2b7b3e5c3bbc8b07b553859938a57ac1df697d63e6cb");  // dev blind index key

    @Bean
    TokenVault tokenVault(VaultProperties properties, Environment environment, JdbcClient jdbc, AuditTrail audit,
                          PlatformTransactionManager transactions, Clock clock) {
        refuseDevKeysOutsideDev(properties, environment);
        return new TokenVault(new JdbcVaultStore(jdbc), ConfiguredKeyProvider.from(properties),
                new TokenFormat(properties.tokenBin()), new AuditTrailVaultListener(audit, transactions), clock);
    }

    @Bean
    CardTokenizer vaultCardTokenizer(TokenVault vault) {
        return new VaultCardTokenizer(vault);
    }

    static void refuseDevKeysOutsideDev(VaultProperties properties, Environment environment) {
        if (environment.acceptsProfiles(DEV_KEY_PROFILES)) {
            return;
        }
        List<String> devKeys = new ArrayList<>();
        properties.keks().stream()
                .filter(kek -> isDevKey(kek.key()))
                .forEach(kek -> devKeys.add("cardrail.vault.keks[" + kek.id() + "]"));
        if (isDevKey(properties.blindIndexKey())) {
            devKeys.add("cardrail.vault.blind-index-key");
        }
        if (!devKeys.isEmpty()) {
            throw new IllegalStateException("the published development vault keys are configured for "
                    + String.join(", ", devKeys) + " outside the dev and test profiles; supply real keys");
        }
    }

    private static boolean isDevKey(String base64) {
        if (base64 == null) {
            return false;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            return DEV_KEY_FINGERPRINTS.contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is available on every JDK", e);
        }
    }
}
