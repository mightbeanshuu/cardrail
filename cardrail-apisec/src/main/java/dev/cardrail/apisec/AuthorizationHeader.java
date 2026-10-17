package dev.cardrail.apisec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Strict parser for {@code Authorization: OAuth name="value", ...} (RFC 5849 §3.5.1).
 *
 * <p>Accepted grammar: the scheme token {@code OAuth} (case-insensitive) and at least one space, then one or
 * more {@code name="value"} pairs separated by a comma with optional surrounding spaces or tabs. Names are
 * {@code [A-Za-z0-9_]+}. Values are visible ASCII without {@code "} or {@code \} and must be valid
 * percent-encoding; there is no quoted-pair escaping because RFC 5849 values are percent-encoded. Duplicate
 * names, parameters outside {@link #ALLOWED}, empty input and trailing garbage are rejected.
 */
final class AuthorizationHeader {

    static final int MAX_LENGTH = 8192;

    static final String CONSUMER_KEY = "oauth_consumer_key";
    static final String NONCE = "oauth_nonce";
    static final String SIGNATURE_METHOD = "oauth_signature_method";
    static final String TIMESTAMP = "oauth_timestamp";
    static final String VERSION = "oauth_version";
    static final String BODY_HASH = "oauth_body_hash";
    static final String SIGNATURE = "oauth_signature";
    static final String REALM = "realm";

    static final Set<String> REQUIRED = Set.of(CONSUMER_KEY, NONCE, SIGNATURE_METHOD, TIMESTAMP, BODY_HASH, SIGNATURE);
    static final Set<String> ALLOWED = Set.of(CONSUMER_KEY, NONCE, SIGNATURE_METHOD, TIMESTAMP, VERSION, BODY_HASH,
            SIGNATURE, REALM);

    /** Parameter values exactly as transmitted (still percent-encoded), in header order. */
    private final Map<String, String> wireValues;

    private AuthorizationHeader(Map<String, String> wireValues) {
        this.wireValues = Collections.unmodifiableMap(wireValues);
    }

    static final class MalformedHeaderException extends Exception {
        MalformedHeaderException(String message) {
            super(message, null, false, false);
        }
    }

    static AuthorizationHeader parse(String header) throws MalformedHeaderException {
        if (header == null || header.isEmpty()) {
            throw new MalformedHeaderException("missing Authorization header");
        }
        if (header.length() > MAX_LENGTH) {
            throw new MalformedHeaderException("Authorization header exceeds " + MAX_LENGTH + " characters");
        }
        if (header.length() < 6 || !header.regionMatches(true, 0, "OAuth", 0, 5) || header.charAt(5) != ' ') {
            throw new MalformedHeaderException("scheme is not OAuth");
        }
        int i = 5;
        while (i < header.length() && header.charAt(i) == ' ') {
            i++;
        }
        Map<String, String> params = new LinkedHashMap<>();
        while (true) {
            int nameStart = i;
            while (i < header.length() && isNameChar(header.charAt(i))) {
                i++;
            }
            if (i == nameStart) {
                throw new MalformedHeaderException("expected parameter name at index " + i);
            }
            String name = header.substring(nameStart, i);
            if (i + 1 >= header.length() || header.charAt(i) != '=' || header.charAt(i + 1) != '"') {
                throw new MalformedHeaderException("expected =\" after " + name);
            }
            i += 2;
            int valueStart = i;
            while (i < header.length() && header.charAt(i) != '"') {
                char c = header.charAt(i);
                if (c < 0x21 || c > 0x7E || c == '\\') {
                    throw new MalformedHeaderException("illegal character in value of " + name);
                }
                i++;
            }
            if (i >= header.length()) {
                throw new MalformedHeaderException("unterminated value for " + name);
            }
            String value = header.substring(valueStart, i);
            i++;
            if (!ALLOWED.contains(name)) {
                throw new MalformedHeaderException("unexpected parameter " + name);
            }
            if (params.putIfAbsent(name, value) != null) {
                throw new MalformedHeaderException("duplicate parameter " + name);
            }
            try {
                PercentEncoding.decode(value);
            } catch (IllegalArgumentException e) {
                throw new MalformedHeaderException("invalid percent-encoding in " + name);
            }
            i = skipWhitespace(header, i);
            if (i == header.length()) {
                break;
            }
            if (header.charAt(i) != ',') {
                throw new MalformedHeaderException("expected , at index " + i);
            }
            i = skipWhitespace(header, i + 1);
        }
        for (String required : REQUIRED) {
            if (!params.containsKey(required)) {
                throw new MalformedHeaderException("missing " + required);
            }
        }
        return new AuthorizationHeader(params);
    }

    String wire(String name) {
        return wireValues.get(name);
    }

    String decoded(String name) {
        String v = wireValues.get(name);
        return v == null ? null : PercentEncoding.decode(v);
    }

    /** OAuth parameters that enter the signature base string: everything except the signature and realm. */
    Map<String, String> signedParameters() {
        Map<String, String> signed = new LinkedHashMap<>(wireValues);
        signed.remove(SIGNATURE);
        signed.remove(REALM);
        return signed;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }
}
