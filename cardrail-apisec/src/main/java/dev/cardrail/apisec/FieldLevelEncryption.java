package dev.cardrail.apisec;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.cardrail.apisec.JweException.Reason;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Field-level JWE encryption of JSON payloads, wire-compatible with Mastercard's
 * {@code com.mastercard.developer.encryption.JweEncryption} (client-encryption 1.8.9).
 *
 * <p>A field mapping pairs a plaintext path with the path of the object that carries the encrypted value.
 * With {@code field("$.card", "$.encryptedCard")} and value field name {@code encryptedValue}:
 * <pre>
 *   {"card":{"pan":"5555..."}}  &lt;-&gt;  {"encryptedCard":{"encryptedValue":"eyJhbGciOi..."}}
 * </pre>
 * That is Mastercard's {@code withEncryptionPath("$.card", "$.encryptedCard")} and
 * {@code withDecryptionPath("$.encryptedCard.encryptedValue", "$.card")}. Mastercard's default value field
 * name for JWE is {@code encryptedData}, and its default mapping is {@code $ <-> $}.
 *
 * <p>The plaintext of each JWE is the compact JSON of the selected node. Mastercard's library writes JSON
 * strings, numbers and booleans with {@code toString()} (so the string {@code "abc"} is encrypted as the bare
 * text {@code abc}); on decryption, plaintext that is not valid JSON is therefore taken as a string, as their
 * library does.
 *
 * <p>Decryption refuses to overwrite: if the plaintext path already exists alongside its encrypted form, the
 * payload is rejected rather than merged. A mapping whose encrypted value is absent is skipped, as in
 * Mastercard's library; whether a field <em>must</em> arrive encrypted is the caller's policy.
 */
public final class FieldLevelEncryption {

    public static final String MASTERCARD_DEFAULT_VALUE_FIELD = "encryptedData";
    private static final String CONTENT_TYPE = "application/json";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private record FieldMapping(JsonFieldPath plain, JsonFieldPath encrypted) {
    }

    private final List<FieldMapping> mappings;
    private final String valueField;
    private final RSAPublicKey encryptionKey;
    private final String encryptionKid;
    private final Map<String, RSAPrivateKey> decryptionKeys;
    private final Set<Integer> acceptedIvLengths;

    private FieldLevelEncryption(Builder b) {
        if (b.mappings.isEmpty()) {
            throw new IllegalStateException("at least one field mapping is required");
        }
        if (b.encryptionKey == null && b.decryptionKeys.isEmpty()) {
            throw new IllegalStateException("configure an encryption key, a decryption key, or both");
        }
        this.mappings = List.copyOf(b.mappings);
        this.valueField = b.valueField;
        this.encryptionKey = b.encryptionKey;
        this.encryptionKid = b.encryptionKid;
        this.decryptionKeys = Map.copyOf(b.decryptionKeys);
        this.acceptedIvLengths = Set.copyOf(b.acceptedIvLengths);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Encrypts every mapped field present in {@code json}; absent fields are skipped. */
    public String encrypt(String json) {
        if (encryptionKey == null) {
            throw new IllegalStateException("no encryption key configured");
        }
        ObjectNode root = parseObject(json);
        for (FieldMapping m : mappings) {
            JsonNode plain;
            if (m.plain().isRoot()) {
                plain = root;
                root = MAPPER.createObjectNode();
            } else {
                ObjectNode parent = m.plain().findParent(root);
                plain = parent == null ? null : parent.remove(m.plain().leaf());
            }
            if (plain == null) {
                continue;
            }
            ObjectNode container = m.encrypted().findOrCreateObject(root);
            if (container.has(valueField)) {
                throw new JweException(Reason.INVALID_PAYLOAD, m.encrypted() + "." + valueField + " already present");
            }
            byte[] plaintext;
            try {
                plaintext = MAPPER.writeValueAsBytes(plain);
            } catch (JacksonException e) {
                throw new IllegalStateException("serialising a parsed tree cannot fail", e);
            }
            container.put(valueField, Jwe.encrypt(plaintext, encryptionKey, encryptionKid, CONTENT_TYPE));
        }
        return write(root);
    }

    /** Decrypts every mapped field present in {@code json}. */
    public String decrypt(String json) {
        if (decryptionKeys.isEmpty()) {
            throw new IllegalStateException("no decryption key configured");
        }
        ObjectNode root = parseObject(json);
        for (FieldMapping m : mappings) {
            JsonNode containerNode = m.encrypted().find(root);
            if (containerNode == null) {
                continue;
            }
            if (!containerNode.isObject()) {
                throw new JweException(Reason.INVALID_PAYLOAD, "expected an object at " + m.encrypted());
            }
            ObjectNode container = (ObjectNode) containerNode;
            JsonNode jwe = container.get(valueField);
            if (jwe == null) {
                continue;
            }
            if (!jwe.isTextual()) {
                throw new JweException(Reason.INVALID_PAYLOAD, m.encrypted() + "." + valueField + " must be a string");
            }
            JsonNode plain = toJson(Jwe.decrypt(jwe.textValue(), decryptionKeys, acceptedIvLengths));

            container.remove(valueField);
            if (container.isEmpty() && !m.encrypted().isRoot()) {
                m.encrypted().findParent(root).remove(m.encrypted().leaf());
            }
            place(root, m.plain(), plain);
        }
        return write(root);
    }

    private static void place(ObjectNode root, JsonFieldPath path, JsonNode value) {
        if (path.isRoot()) {
            if (!value.isObject()) {
                throw new JweException(Reason.INVALID_PAYLOAD, "decrypted value for $ must be a JSON object");
            }
            for (Map.Entry<String, JsonNode> e : value.properties()) {
                if (root.has(e.getKey())) {
                    throw new JweException(Reason.INVALID_PAYLOAD, "decrypted member " + e.getKey() + " already present");
                }
                root.set(e.getKey(), e.getValue());
            }
            return;
        }
        ObjectNode parent = path.findOrCreateParent(root);
        if (parent.has(path.leaf())) {
            throw new JweException(Reason.INVALID_PAYLOAD, path + " present in both plaintext and encrypted form");
        }
        parent.set(path.leaf(), value);
    }

    private static JsonNode toJson(byte[] plaintext) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new JweException(Reason.INVALID_PAYLOAD, "decrypted value is not UTF-8");
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            return node == null || node.isMissingNode() ? TextNode.valueOf(text) : node;
        } catch (JacksonException e) {
            return TextNode.valueOf(text);
        }
    }

    private static ObjectNode parseObject(String json) {
        Objects.requireNonNull(json, "json");
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new JweException(Reason.INVALID_PAYLOAD, "payload is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new JweException(Reason.INVALID_PAYLOAD, "payload must be a JSON object");
        }
        return (ObjectNode) node;
    }

    private static String write(ObjectNode root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JacksonException e) {
            throw new IllegalStateException("serialising a parsed tree cannot fail", e);
        }
    }

    public static final class Builder {
        private final List<FieldMapping> mappings = new ArrayList<>();
        private final Set<String> plainPaths = new HashSet<>();
        private String valueField = MASTERCARD_DEFAULT_VALUE_FIELD;
        private RSAPublicKey encryptionKey;
        private String encryptionKid;
        private final Map<String, RSAPrivateKey> decryptionKeys = new HashMap<>();
        private Set<Integer> acceptedIvLengths = Jwe.MASTERCARD_COMPATIBLE_IV_LENGTHS;

        private Builder() {
        }

        /**
         * @param plainPath          where the cleartext value lives, e.g. {@code $.card}
         * @param encryptedObjectPath object that carries the JWE in its value field, e.g. {@code $.encryptedCard}
         */
        public Builder field(String plainPath, String encryptedObjectPath) {
            if (!plainPaths.add(plainPath)) {
                throw new IllegalArgumentException("duplicate mapping for " + plainPath);
            }
            mappings.add(new FieldMapping(JsonFieldPath.parse(plainPath), JsonFieldPath.parse(encryptedObjectPath)));
            return this;
        }

        public Builder encryptedValueFieldName(String name) {
            if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("value field name must match [A-Za-z0-9_-]+");
            }
            this.valueField = name;
            return this;
        }

        /** Encrypt for {@code key}, with {@code kid} = {@link KeyFingerprint#sha256Hex} as Mastercard computes it. */
        public Builder encryptionKey(RSAPublicKey key) {
            return encryptionKey(key, KeyFingerprint.sha256Hex(key));
        }

        public Builder encryptionKey(RSAPublicKey key, String kid) {
            this.encryptionKey = Objects.requireNonNull(key, "key");
            this.encryptionKid = Objects.requireNonNull(kid, "kid");
            return this;
        }

        /** Accept JWEs whose {@code kid} is the SHA-256 fingerprint of {@code pair}'s public key. */
        public Builder decryptionKey(KeyPair pair) {
            return decryptionKey(KeyFingerprint.sha256Hex(pair.getPublic()), (RSAPrivateKey) pair.getPrivate());
        }

        /** Repeatable, for key rotation: each key is selected by its {@code kid}. */
        public Builder decryptionKey(String kid, RSAPrivateKey key) {
            Objects.requireNonNull(kid, "kid");
            Objects.requireNonNull(key, "key");
            if (decryptionKeys.putIfAbsent(kid, key) != null) {
                throw new IllegalArgumentException("duplicate decryption key for kid " + kid);
            }
            return this;
        }

        /** Default {@link Jwe#MASTERCARD_COMPATIBLE_IV_LENGTHS}; pass {@code 12} alone for strict RFC 7518. */
        public Builder acceptedIvLengths(int... lengths) {
            Set<Integer> s = new HashSet<>();
            for (int l : lengths) {
                if (l != 12 && l != 16) {
                    throw new IllegalArgumentException("supported IV lengths are 12 and 16 bytes");
                }
                s.add(l);
            }
            if (s.isEmpty()) {
                throw new IllegalArgumentException("at least one IV length is required");
            }
            this.acceptedIvLengths = s;
            return this;
        }

        public FieldLevelEncryption build() {
            return new FieldLevelEncryption(this);
        }
    }
}
