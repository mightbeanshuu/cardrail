package dev.cardrail.sw.domain.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;

/**
 * SHA-256 over the previous hash and a canonical encoding of the record. Every field is written as
 * {@code <length>:<value>;}, so no choice of field contents can make two different records encode
 * to the same bytes (which plain delimiter joining would allow).
 */
public final class AuditHasher {

    public static final String GENESIS_HASH = "0".repeat(64);

    private AuditHasher() {
    }

    public static String hash(long seq, Instant occurredAt, String actor, String action, String entity,
                              String entityId, SortedMap<String, String> details, String prevHash) {
        StringBuilder canonical = new StringBuilder(256);
        field(canonical, prevHash);
        field(canonical, Long.toString(seq));
        field(canonical, occurredAt.toString());
        field(canonical, actor);
        field(canonical, action);
        field(canonical, entity);
        field(canonical, entityId);
        field(canonical, Integer.toString(details.size()));
        for (Map.Entry<String, String> e : details.entrySet()) {
            field(canonical, e.getKey());
            field(canonical, e.getValue());
        }
        return HexFormat.of().formatHex(sha256().digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void field(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value).append(';');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }
}
