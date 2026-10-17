package dev.cardrail.apisec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * RFC 3986 percent-encoding as used by OAuth 1.0a (RFC 5849 §3.6): every octet of the UTF-8 form is
 * encoded except the unreserved set {@code ALPHA / DIGIT / "-" / "." / "_" / "~"}, hex digits uppercase.
 * This is byte-for-byte what Mastercard's {@code Util.percentEncode} produces from
 * {@code URLEncoder} plus its three post-substitutions.
 */
final class PercentEncoding {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PercentEncoding() {
    }

    static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 16);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >>> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }

    /**
     * Decodes {@code %XX} escapes and interprets the result as UTF-8. Characters that are not escaped
     * are taken as-is (non-ASCII characters contribute their UTF-8 octets). '+' is a literal plus, not a
     * space: that is how both {@link java.net.URI#getQuery()} and Mastercard's signer treat it.
     *
     * @throws IllegalArgumentException on a truncated or non-hex escape, or octets that are not valid UTF-8
     */
    static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream octets = new ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    throw new IllegalArgumentException("truncated percent escape at index " + i);
                }
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("invalid percent escape at index " + i);
                }
                octets.write((hi << 4) | lo);
                i += 3;
            } else {
                int cp = value.codePointAt(i);
                octets.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(octets.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("percent-decoded octets are not valid UTF-8", e);
        }
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
