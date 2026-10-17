package dev.cardrail.apisec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mastercard.developer.encryption.JweConfig;
import com.mastercard.developer.encryption.JweConfigBuilder;
import com.mastercard.developer.encryption.JweEncryption;
import dev.cardrail.apisec.JweException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static dev.cardrail.apisec.TestKeys.priv;
import static dev.cardrail.apisec.TestKeys.pub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payloads encrypted by Mastercard's {@code com.mastercard.developer:client-encryption:1.8.9}
 * {@code JweEncryption} must decrypt here and vice versa; tampering and algorithm substitution must fail.
 */
class MastercardJweInteropTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String PAYLOAD = """
            {"amount":{"value":125.50,"currency":"EUR"},\
            "card":{"pan":"5555444433332222","expiry":"2812","holder":"Zoë Ångström 東京 🚀"},\
            "merchantId":"M-0042"}""";

    static final String KID = KeyFingerprint.sha256Hex(pub(TestKeys.SERVER));

    static JweConfig mastercardCardConfig(Integer ivSize) throws Exception {
        JweConfigBuilder b = JweConfigBuilder.aJweEncryptionConfig()
                .withEncryptionKey(pub(TestKeys.SERVER))
                .withDecryptionKey(priv(TestKeys.SERVER))
                .withEncryptionPath("$.card", "$.encryptedCard")
                .withDecryptionPath("$.encryptedCard.encryptedValue", "$.card")
                .withEncryptedValueFieldName("encryptedValue");
        if (ivSize != null) {
            b.withEncryptionIVSize(ivSize);
        }
        return b.build();
    }

    static FieldLevelEncryption.Builder oursForCard() {
        return FieldLevelEncryption.builder()
                .field("$.card", "$.encryptedCard")
                .encryptedValueFieldName("encryptedValue");
    }

    static final FieldLevelEncryption OURS = oursForCard()
            .encryptionKey(pub(TestKeys.SERVER))
            .decryptionKey(TestKeys.SERVER)
            .build();

    static JsonNode tree(String json) throws Exception {
        return JSON.readTree(json);
    }

    static String jweAt(String json, String containerField) throws Exception {
        return tree(json).get(containerField).get("encryptedValue").textValue();
    }

    @Test
    @DisplayName("Mastercard encrypts $.card (default 16-byte IV) -> we decrypt")
    void mastercardEncryptsWeDecrypt() throws Exception {
        String encrypted = JweEncryption.encryptPayload(PAYLOAD, mastercardCardConfig(null));

        assertThat(tree(encrypted).has("card")).isFalse();
        assertThat(tree(encrypted).at("/encryptedCard/encryptedValue").isTextual()).isTrue();
        assertThat(tree(OURS.decrypt(encrypted))).isEqualTo(tree(PAYLOAD));
    }

    @Test
    @DisplayName("Mastercard encrypts with a 12-byte IV -> we decrypt")
    void mastercardTwelveByteIv() throws Exception {
        String encrypted = JweEncryption.encryptPayload(PAYLOAD, mastercardCardConfig(12));
        assertThat(tree(OURS.decrypt(encrypted))).isEqualTo(tree(PAYLOAD));
    }

    @Test
    @DisplayName("Mastercard's default IV is 16 bytes, which strict RFC 7518 mode rejects")
    void mastercardDefaultIvIsSixteenBytes() throws Exception {
        String jwe = jweAt(JweEncryption.encryptPayload(PAYLOAD, mastercardCardConfig(null)), "encryptedCard");
        assertThat(Base64.getUrlDecoder().decode(jwe.split("\\.")[2])).hasSize(16);

        FieldLevelEncryption strict = oursForCard().decryptionKey(TestKeys.SERVER).acceptedIvLengths(12).build();
        String encrypted = JweEncryption.encryptPayload(PAYLOAD, mastercardCardConfig(null));
        assertThatThrownBy(() -> strict.decrypt(encrypted))
                .isInstanceOfSatisfying(JweException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MALFORMED));
    }

    @Test
    @DisplayName("Mastercard's protected header: alg, enc, cty and kid = SHA-256 of the SPKI, hex")
    void mastercardHeaderAndKid() throws Exception {
        JweConfig config = mastercardCardConfig(null);
        String jwe = jweAt(JweEncryption.encryptPayload(PAYLOAD, config), "encryptedCard");
        JsonNode header = Jwe.parseHeader(jwe.split("\\.")[0]);

        assertThat(header.get("alg").textValue()).isEqualTo("RSA-OAEP-256");
        assertThat(header.get("enc").textValue()).isEqualTo("A256GCM");
        assertThat(header.get("cty").textValue()).isEqualTo("application/json");
        assertThat(header.get("kid").textValue())
                .isEqualTo(KID)
                .isEqualTo(config.getEncryptionKeyFingerprint())
                .hasSize(64);
    }

    @Test
    @DisplayName("we encrypt $.card -> Mastercard decrypts")
    void weEncryptMastercardDecrypts() throws Exception {
        String encrypted = OURS.encrypt(PAYLOAD);

        assertThat(tree(encrypted).has("card")).isFalse();
        String decrypted = JweEncryption.decryptPayload(encrypted, mastercardCardConfig(null));
        assertThat(tree(decrypted)).isEqualTo(tree(PAYLOAD));
    }

    @Test
    @DisplayName("whole-payload default mapping ($ <-> {encryptedData}) both directions")
    void wholePayloadDefaultMapping() throws Exception {
        JweConfig mcDefault = JweConfigBuilder.aJweEncryptionConfig()
                .withEncryptionKey(pub(TestKeys.SERVER))
                .withDecryptionKey(priv(TestKeys.SERVER))
                .build();
        FieldLevelEncryption ours = FieldLevelEncryption.builder()
                .field("$", "$")
                .encryptionKey(pub(TestKeys.SERVER))
                .decryptionKey(TestKeys.SERVER)
                .build();

        String fromMastercard = JweEncryption.encryptPayload(PAYLOAD, mcDefault);
        assertThat(tree(fromMastercard).fieldNames()).toIterable().containsExactly("encryptedData");
        assertThat(tree(ours.decrypt(fromMastercard))).isEqualTo(tree(PAYLOAD));

        String fromUs = ours.encrypt(PAYLOAD);
        assertThat(tree(fromUs).fieldNames()).toIterable().containsExactly("encryptedData");
        assertThat(tree(JweEncryption.decryptPayload(fromUs, mcDefault))).isEqualTo(tree(PAYLOAD));
    }

    @Test
    @DisplayName("primitive field: Mastercard encrypts the string \"5555...\" as bare digits, so it decrypts as a number")
    void primitiveStringFieldTypeIsLostByMastercardEncoding() throws Exception {
        String payload = "{\"pan\":\"5555444433332222\",\"name\":\"Zoë\"}";
        JweConfig mc = JweConfigBuilder.aJweEncryptionConfig()
                .withEncryptionKey(pub(TestKeys.SERVER))
                .withDecryptionKey(priv(TestKeys.SERVER))
                .withEncryptionPath("$.pan", "$.encryptedPan")
                .withEncryptionPath("$.name", "$.encryptedName")
                .withDecryptionPath("$.encryptedPan.encryptedData", "$.pan")
                .withDecryptionPath("$.encryptedName.encryptedData", "$.name")
                .build();
        FieldLevelEncryption ours = FieldLevelEncryption.builder()
                .field("$.pan", "$.encryptedPan")
                .field("$.name", "$.encryptedName")
                .encryptionKey(pub(TestKeys.SERVER))
                .decryptionKey(TestKeys.SERVER)
                .build();

        String fromMastercard = JweEncryption.encryptPayload(payload, mc);
        String panJwe = tree(fromMastercard).at("/encryptedPan/encryptedData").textValue();
        byte[] panPlaintext = Jwe.decrypt(panJwe, Map.of(KID, priv(TestKeys.SERVER)), Jwe.MASTERCARD_COMPATIBLE_IV_LENGTHS);
        assertThat(new String(panPlaintext, StandardCharsets.UTF_8)).isEqualTo("5555444433332222");

        JsonNode viaOurs = tree(ours.decrypt(fromMastercard));
        JsonNode viaTheirs = tree(JweEncryption.decryptPayload(fromMastercard, mc));
        assertThat(viaOurs.get("pan").isNumber()).isTrue();
        assertThat(viaTheirs.get("pan").isNumber()).isTrue();
        assertThat(viaOurs.get("name").textValue()).isEqualTo("Zoë");
        assertThat(viaTheirs.get("name").textValue()).isEqualTo("Zoë");

        JsonNode oursToTheirs = tree(JweEncryption.decryptPayload(ours.encrypt(payload), mc));
        assertThat(oursToTheirs).as("we encrypt JSON text, so the string type survives").isEqualTo(tree(payload));
    }

    @Test
    @DisplayName("JCA default OAEP params (MGF1-SHA1) cannot unwrap a Mastercard RSA-OAEP-256 key")
    void jcaDefaultMgf1IsSha1() throws Exception {
        String jwe = jweAt(JweEncryption.encryptPayload(PAYLOAD, mastercardCardConfig(null)), "encryptedCard");
        byte[] encryptedKey = Base64.getUrlDecoder().decode(jwe.split("\\.")[1]);

        Cipher jcaDefault = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        jcaDefault.init(Cipher.DECRYPT_MODE, priv(TestKeys.SERVER));
        OAEPParameterSpec defaults = jcaDefault.getParameters().getParameterSpec(OAEPParameterSpec.class);
        assertThat(((MGF1ParameterSpec) defaults.getMGFParameters()).getDigestAlgorithm()).isEqualTo("SHA-1");
        assertThatThrownBy(() -> jcaDefault.doFinal(encryptedKey)).isInstanceOf(BadPaddingException.class);

        Cipher explicit = Cipher.getInstance("RSA/ECB/OAEPPadding");
        explicit.init(Cipher.DECRYPT_MODE, priv(TestKeys.SERVER),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        assertThat(explicit.doFinal(encryptedKey)).hasSize(32);
    }

    @Nested
    @DisplayName("tampering with a Mastercard-produced JWE")
    class Tampering {

        final String encrypted;
        final String jwe;

        Tampering() throws Exception {
            encrypted = JweEncryption.encryptPayload(PAYLOAD, mastercardCardConfig(null));
            jwe = jweAt(encrypted, "encryptedCard");
        }

        String replaceJwe(String newJwe) {
            ObjectNode root;
            try {
                root = (ObjectNode) tree(encrypted);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            ((ObjectNode) root.get("encryptedCard")).put("encryptedValue", newJwe);
            return root.toString();
        }

        String withPart(int index, UnaryOperator<byte[]> change) {
            String[] parts = jwe.split("\\.");
            parts[index] = Jwe.base64Url(change.apply(Base64.getUrlDecoder().decode(parts[index])));
            return String.join(".", parts);
        }

        String withHeader(UnaryOperator<ObjectNode> change) {
            String[] parts = jwe.split("\\.");
            ObjectNode header = (ObjectNode) Jwe.parseHeader(parts[0]);
            parts[0] = Jwe.base64Url(change.apply(header).toString().getBytes(StandardCharsets.UTF_8));
            return String.join(".", parts);
        }

        static byte[] flipFirstBit(byte[] b) {
            byte[] c = b.clone();
            c[0] ^= 0x01;
            return c;
        }

        Reason reasonFor(String tamperedJwe) {
            String payload = replaceJwe(tamperedJwe);
            try {
                OURS.decrypt(payload);
            } catch (JweException e) {
                return e.reason();
            }
            throw new AssertionError("tampered JWE was accepted");
        }

        @Test
        void untouchedDecrypts() throws Exception {
            assertThat(tree(OURS.decrypt(replaceJwe(jwe)))).isEqualTo(tree(PAYLOAD));
        }

        @Test
        void ciphertextBitFlipped() {
            assertThat(reasonFor(withPart(3, Tampering::flipFirstBit))).isEqualTo(Reason.DECRYPTION_FAILED);
        }

        @Test
        void tagBitFlipped() {
            assertThat(reasonFor(withPart(4, Tampering::flipFirstBit))).isEqualTo(Reason.DECRYPTION_FAILED);
        }

        @Test
        void ivBitFlipped() {
            assertThat(reasonFor(withPart(2, Tampering::flipFirstBit))).isEqualTo(Reason.DECRYPTION_FAILED);
        }

        @Test
        void encryptedKeyBitFlippedFailsLikeABadTag() {
            assertThat(reasonFor(withPart(1, Tampering::flipFirstBit))).isEqualTo(Reason.DECRYPTION_FAILED);
        }

        @Test
        void tagTruncated() {
            assertThat(reasonFor(withPart(4, b -> java.util.Arrays.copyOf(b, 12)))).isEqualTo(Reason.MALFORMED);
        }

        @Test
        void protectedHeaderChangedWithSameAlgorithmsBreaksTheAad() {
            assertThat(reasonFor(withHeader(h -> h.put("cty", "text/plain")))).isEqualTo(Reason.DECRYPTION_FAILED);
        }

        @Test
        void algDowngradedToRsaOaepSha1() {
            assertThat(reasonFor(withHeader(h -> h.put("alg", "RSA-OAEP")))).isEqualTo(Reason.UNSUPPORTED_ALGORITHM);
        }

        @Test
        void algSetToDir() {
            assertThat(reasonFor(withHeader(h -> h.put("alg", "dir")))).isEqualTo(Reason.UNSUPPORTED_ALGORITHM);
        }

        @Test
        void encSwitchedToCbcHmac() {
            assertThat(reasonFor(withHeader(h -> h.put("enc", "A128CBC-HS256"))))
                    .isEqualTo(Reason.UNSUPPORTED_ALGORITHM);
        }

        @Test
        void encSwitchedToA128Gcm() {
            assertThat(reasonFor(withHeader(h -> h.put("enc", "A128GCM")))).isEqualTo(Reason.UNSUPPORTED_ALGORITHM);
        }

        @Test
        void zipHeaderAdded() {
            assertThat(reasonFor(withHeader(h -> h.put("zip", "DEF")))).isEqualTo(Reason.UNSUPPORTED_ALGORITHM);
        }

        @Test
        void kidUnknown() {
            assertThat(reasonFor(withHeader(h -> h.put("kid", "0".repeat(64))))).isEqualTo(Reason.UNKNOWN_KEY);
        }

        @Test
        void kidRemoved() {
            assertThat(reasonFor(withHeader(h -> {
                h.remove("kid");
                return h;
            }))).isEqualTo(Reason.MALFORMED);
        }

        @Test
        void duplicateAlgInHeaderJson() {
            String[] parts = jwe.split("\\.");
            String json = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            parts[0] = Jwe.base64Url(json.replace("{", "{\"alg\":\"dir\",").getBytes(StandardCharsets.UTF_8));
            assertThat(reasonFor(String.join(".", parts))).isEqualTo(Reason.MALFORMED);
        }

        @Test
        void sixParts() {
            assertThat(reasonFor(jwe + ".AAAA")).isEqualTo(Reason.MALFORMED);
        }

        @Test
        void paddedBase64() {
            String[] parts = jwe.split("\\.");
            parts[4] = parts[4] + "==";
            assertThat(reasonFor(String.join(".", parts))).isEqualTo(Reason.MALFORMED);
        }
    }

    @Test
    @DisplayName("key rotation: JWEs for the old and new key both decrypt, selected by kid")
    void keyRotationByKid() throws Exception {
        FieldLevelEncryption rotating = oursForCard()
                .decryptionKey(TestKeys.SERVER)
                .decryptionKey(TestKeys.ROTATED_SERVER)
                .build();
        JweConfig toRotated = JweConfigBuilder.aJweEncryptionConfig()
                .withEncryptionKey(pub(TestKeys.ROTATED_SERVER))
                .withEncryptionPath("$.card", "$.encryptedCard")
                .withEncryptedValueFieldName("encryptedValue")
                .build();

        assertThat(tree(rotating.decrypt(JweEncryption.encryptPayload(PAYLOAD, mastercardCardConfig(null)))))
                .isEqualTo(tree(PAYLOAD));
        assertThat(tree(rotating.decrypt(JweEncryption.encryptPayload(PAYLOAD, toRotated))))
                .isEqualTo(tree(PAYLOAD));
    }

    @Test
    @DisplayName("JWE for another key under our kid fails as DECRYPTION_FAILED, not a distinct unwrap error")
    void wrongKeyUnderKnownKid() {
        String jwe = Jwe.encrypt("{}".getBytes(StandardCharsets.UTF_8), pub(TestKeys.ROTATED_SERVER), KID, null);
        assertThatThrownBy(() -> Jwe.decrypt(jwe, Map.of(KID, priv(TestKeys.SERVER)), Set.of(12)))
                .isInstanceOfSatisfying(JweException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.DECRYPTION_FAILED));
    }
}
