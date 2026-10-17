package dev.cardrail.sw.adapter.vault;

import dev.cardrail.vault.KeyStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredKeyProviderTest {

    private static final String DEV_KEK = "/yebDAI+0Vw595CGDR5vX5wY8hhwVwcfQR2P0bntk0o=";
    private static final String DEV_INDEX_KEY = "L4uH8gYvEp8uhse9xA2PiO4mf6fD1YBVPo+NgEwv2N8=";

    private final String kekV1 = randomKey(32);
    private final String kekV2 = randomKey(32);
    private final String indexKey = randomKey(32);

    @Test
    void decodesKeksAndPicksTheActiveOne() {
        ConfiguredKeyProvider keys = ConfiguredKeyProvider.from(properties(
                List.of(kek("kek-v1", kekV1, KeyStatus.DECRYPT_ONLY), kek("kek-v2", kekV2, KeyStatus.ACTIVE)),
                indexKey));

        assertThat(keys.activeKek().id()).isEqualTo("kek-v2");
        assertThat(keys.kek("kek-v1")).hasValueSatisfying(k -> {
            assertThat(k.status()).isEqualTo(KeyStatus.DECRYPT_ONLY);
            assertThat(k.key().getEncoded()).isEqualTo(Base64.getDecoder().decode(kekV1));
        });
        assertThat(keys.kek("kek-v3")).isEmpty();
        assertThat(keys.blindIndexKey().getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void requiresExactlyOneActiveKek() {
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(List.of(), indexKey)))
                .hasMessageContaining("cardrail.vault.keks is empty");
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(
                List.of(kek("a", kekV1, KeyStatus.ACTIVE), kek("b", kekV2, KeyStatus.ACTIVE)), indexKey)))
                .hasMessageContaining("exactly one ACTIVE KEK, found 2");
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(
                List.of(kek("a", kekV1, KeyStatus.DECRYPT_ONLY)), indexKey)))
                .hasMessageContaining("exactly one ACTIVE KEK, found 0");
    }

    @Test
    void rejectsMalformedKeysWithoutEchoingThem() {
        String shortKey = randomKey(16);
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(List.of(kek("a", shortKey, KeyStatus.ACTIVE)), indexKey)))
                .hasMessageContaining("cardrail.vault.keks[a].key must be 32 bytes")
                .message().doesNotContain(shortKey);
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(List.of(kek("a", "not base64!", KeyStatus.ACTIVE)), indexKey)))
                .hasMessage("cardrail.vault.keks[a].key is not valid base64");
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(
                List.of(kek("a", kekV1, KeyStatus.ACTIVE), kek("a", kekV2, KeyStatus.DECRYPT_ONLY)), indexKey)))
                .hasMessageContaining("duplicate KEK id a");
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(List.of(kek("a", kekV1, KeyStatus.ACTIVE)), null)))
                .hasMessageContaining("blind-index-key is not set");
        assertThatThrownBy(() -> ConfiguredKeyProvider.from(properties(List.of(kek("a", kekV1, KeyStatus.ACTIVE)), randomKey(16))))
                .hasMessageContaining("blind-index-key must be at least 32 bytes");
    }

    @Test
    void refusesThePublishedDevKeysOutsideTheDevAndTestProfiles() {
        VaultProperties devKeys = properties(List.of(kek("renamed", DEV_KEK, KeyStatus.ACTIVE)), DEV_INDEX_KEY);

        assertThatThrownBy(() -> VaultConfiguration.refuseDevKeysOutsideDev(devKeys, environment("prod")))
                .hasMessageContaining("cardrail.vault.keks[renamed]")
                .hasMessageContaining("cardrail.vault.blind-index-key");
        assertThatThrownBy(() -> VaultConfiguration.refuseDevKeysOutsideDev(
                properties(List.of(kek("a", kekV1, KeyStatus.ACTIVE)), DEV_INDEX_KEY), environment()))
                .hasMessageContaining("cardrail.vault.blind-index-key");
        assertThatCode(() -> VaultConfiguration.refuseDevKeysOutsideDev(devKeys, environment("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> VaultConfiguration.refuseDevKeysOutsideDev(devKeys, environment("test"))).doesNotThrowAnyException();
        assertThatCode(() -> VaultConfiguration.refuseDevKeysOutsideDev(
                properties(List.of(kek("a", kekV1, KeyStatus.ACTIVE)), indexKey), environment("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void toStringNeverShowsKeyMaterial() {
        VaultProperties p = properties(List.of(kek("kek-v1", kekV1, KeyStatus.ACTIVE)), indexKey);
        assertThat(p.toString()).doesNotContain(kekV1).doesNotContain(indexKey).contains("kek-v1");
    }

    private static VaultProperties properties(List<VaultProperties.Kek> keks, String blindIndexKey) {
        return new VaultProperties("999999", keks, blindIndexKey);
    }

    private static VaultProperties.Kek kek(String id, String key, KeyStatus status) {
        return new VaultProperties.Kek(id, key, status);
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static String randomKey(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
