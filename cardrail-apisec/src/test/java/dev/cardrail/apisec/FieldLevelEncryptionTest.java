package dev.cardrail.apisec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.apisec.JweException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static dev.cardrail.apisec.TestKeys.priv;
import static dev.cardrail.apisec.TestKeys.pub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldLevelEncryptionTest {

    static final ObjectMapper JSON = new ObjectMapper();

    final FieldLevelEncryption fle = FieldLevelEncryption.builder()
            .field("$.payment.card", "$.payment.encryptedCard")
            .field("$.customer", "$.encryptedCustomer")
            .encryptionKey(pub(TestKeys.SERVER))
            .decryptionKey(TestKeys.SERVER)
            .build();

    static JsonNode tree(String json) throws Exception {
        return JSON.readTree(json);
    }

    static void assertReason(Runnable r, Reason reason) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(JweException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void roundTripPreservesEverythingIncludingDecimalScale() throws Exception {
        String payload = "{\"payment\":{\"card\":{\"pan\":\"5555444433332222\",\"limit\":10.50},\"amount\":1.10},"
                + "\"customer\":[\"a\",1,true,null],\"untouched\":\"x\"}";
        String encrypted = fle.encrypt(payload);

        JsonNode e = tree(encrypted);
        assertThat(e.at("/payment/card").isMissingNode()).isTrue();
        assertThat(e.at("/payment/encryptedCard/encryptedData").isTextual()).isTrue();
        assertThat(e.at("/encryptedCustomer/encryptedData").isTextual()).isTrue();
        assertThat(encrypted).doesNotContain("5555444433332222");

        String decrypted = fle.decrypt(encrypted);
        assertThat(tree(decrypted)).isEqualTo(tree(payload));
        assertThat(decrypted).contains("\"limit\":10.50").contains("\"amount\":1.10");
    }

    @Test
    void ourJwesUseTheRfcIvLength() throws Exception {
        String jwe = tree(fle.encrypt("{\"customer\":{}}")).at("/encryptedCustomer/encryptedData").textValue();
        assertThat(Base64.getUrlDecoder().decode(jwe.split("\\.")[2])).hasSize(Jwe.RFC_IV_LENGTH);
    }

    @Test
    void absentFieldsAreLeftAlone() throws Exception {
        String payload = "{\"payment\":{\"amount\":1}}";
        assertThat(tree(fle.encrypt(payload))).isEqualTo(tree(payload));
        assertThat(tree(fle.decrypt(payload))).isEqualTo(tree(payload));
    }

    @Test
    void siblingsOfTheEncryptedValueSurviveDecryption() throws Exception {
        String encrypted = fle.encrypt("{\"customer\":{\"id\":7}}");
        String withSibling = encrypted.replace("{\"encryptedData\"", "{\"keyRef\":\"k1\",\"encryptedData\"");
        assertThat(tree(fle.decrypt(withSibling))).isEqualTo(tree("{\"encryptedCustomer\":{\"keyRef\":\"k1\"},\"customer\":{\"id\":7}}"));
    }

    @Test
    void plaintextAlongsideCiphertextIsRejected() {
        String encrypted = fle.encrypt("{\"customer\":{\"id\":7}}");
        String smuggled = encrypted.replaceFirst("^\\{", "{\"customer\":{\"id\":8},");
        assertReason(() -> fle.decrypt(smuggled), Reason.INVALID_PAYLOAD);
    }

    @Test
    void existingEncryptedValueIsNotOverwritten() {
        assertReason(() -> fle.encrypt("{\"customer\":{},\"encryptedCustomer\":{\"encryptedData\":\"x\"}}"),
                Reason.INVALID_PAYLOAD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"s\"", "{\"a\":1,\"a\":2}", "{\"a\":1} {}", "{", "{\"encryptedCustomer\":\"x\"}",
            "{\"encryptedCustomer\":{\"encryptedData\":42}}"})
    void malformedPayloadsAreRejected(String payload) {
        assertReason(() -> fle.decrypt(payload), Reason.INVALID_PAYLOAD);
    }

    @Test
    void nonJsonPlaintextIsReadAsAString() throws Exception {
        String jwe = Jwe.encrypt("hello world".getBytes(StandardCharsets.UTF_8), pub(TestKeys.SERVER),
                KeyFingerprint.sha256Hex(pub(TestKeys.SERVER)), null);
        String out = fle.decrypt("{\"encryptedCustomer\":{\"encryptedData\":\"" + jwe + "\"}}");
        assertThat(tree(out)).isEqualTo(tree("{\"customer\":\"hello world\"}"));
    }

    @Test
    void invalidUtf8PlaintextIsRejected() {
        String jwe = Jwe.encrypt(new byte[]{(byte) 0xC3}, pub(TestKeys.SERVER),
                KeyFingerprint.sha256Hex(pub(TestKeys.SERVER)), null);
        assertReason(() -> fle.decrypt("{\"encryptedCustomer\":{\"encryptedData\":\"" + jwe + "\"}}"),
                Reason.INVALID_PAYLOAD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"card", "$card", "$.", "$.a..b", "$.a[0]", "$..a", "$.a.*", "$['a']"})
    void unsupportedPathsAreRefusedAtConfiguration(String path) {
        assertThatThrownBy(() -> FieldLevelEncryption.builder().field(path, "$.x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossCheckWithLowLevelApi() {
        String kid = KeyFingerprint.sha256Hex(pub(TestKeys.SERVER));
        String jwe = Jwe.encrypt("{}".getBytes(StandardCharsets.UTF_8), pub(TestKeys.SERVER), kid, null);
        assertThat(Jwe.decrypt(jwe, Map.of(kid, priv(TestKeys.SERVER)), Jwe.MASTERCARD_COMPATIBLE_IV_LENGTHS))
                .asString(StandardCharsets.UTF_8).isEqualTo("{}");
        assertReason(() -> Jwe.decrypt(jwe.replaceFirst("^[^.]+",
                        Jwe.base64Url(("{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\",\"kid\":\"" + kid
                                + "\",\"crit\":[\"exp\"]}").getBytes(StandardCharsets.UTF_8))),
                Map.of(kid, priv(TestKeys.SERVER)), Jwe.MASTERCARD_COMPATIBLE_IV_LENGTHS),
                Reason.UNSUPPORTED_ALGORITHM);
    }
}
