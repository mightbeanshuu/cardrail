package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class InMemoryKeyProviderTest {

    @Test
    void rotationActivatesNewKekAndDemotesPrevious() {
        InMemoryKeyProvider keys = InMemoryKeyProvider.generate();
        assertThat(keys.activeKek().id()).isEqualTo("kek-v1");

        KeyVersion v2 = keys.rotate();

        assertThat(v2.id()).isEqualTo("kek-v2");
        assertThat(keys.activeKek()).isEqualTo(v2);
        assertThat(keys.kek("kek-v1")).get().extracting(KeyVersion::status).isEqualTo(KeyStatus.DECRYPT_ONLY);
        assertThat(keys.kek("kek-v1").get().key()).isNotEqualTo(v2.key());
        assertThat(v2.key().getEncoded()).hasSize(32);
    }

    @Test
    void activeKekCannotBeRetired() {
        InMemoryKeyProvider keys = InMemoryKeyProvider.generate();
        assertThatIllegalStateException().isThrownBy(() -> keys.retire("kek-v1"));
    }

    @Test
    void blindIndexKeyIsSeparateFromKeksAndSurvivesRotation() {
        InMemoryKeyProvider keys = InMemoryKeyProvider.generate();
        var indexKey = keys.blindIndexKey();
        keys.rotate();
        assertThat(keys.blindIndexKey()).isSameAs(indexKey);
        assertThat(indexKey.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(keys.keks()).noneMatch(k -> k.key().equals(indexKey));
    }

    @Test
    void keyVersionToStringOmitsKeyMaterial() {
        KeyVersion kek = InMemoryKeyProvider.generate().activeKek();
        assertThat(kek.toString())
                .isEqualTo("KeyVersion[id=kek-v1, status=ACTIVE]")
                .doesNotContain(HexFormat.of().formatHex(kek.key().getEncoded()));
    }
}
