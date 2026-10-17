package dev.cardrail.apisec;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * OAuth 1.0a signature base string (RFC 5849 §3.4.1), normalised the way Mastercard's
 * {@code com.mastercard.developer.oauth.OAuth} (oauth1-signer 1.6.0) does it, so that signatures
 * produced by that library verify here. Where Mastercard's normalisation departs from RFC 5849 this
 * class follows Mastercard, except in the cases where following it would leave request content
 * unauthenticated or ambiguous; those fail closed (see {@link #queryParameters(String)}).
 */
final class SignatureBaseString {

    private SignatureBaseString() {
    }

    /**
     * @param method      HTTP method; upper-cased with {@link Locale#ROOT}
     * @param uri         absolute request URI as the client addressed it
     * @param oauthParams OAuth protocol parameters, excluding {@code oauth_signature} and {@code realm}, with
     *                    values exactly as they appear on the wire in the Authorization header
     * @throws IllegalArgumentException if the URI is not absolute or its query cannot be normalised
     */
    static String of(String method, URI uri, Map<String, String> oauthParams) {
        return method.toUpperCase(Locale.ROOT)
                + '&' + PercentEncoding.encode(baseUri(uri))
                + '&' + PercentEncoding.encode(parameterString(uri.getRawQuery(), oauthParams));
    }

    /**
     * RFC 5849 §3.4.1.2: lower-case scheme and authority, drop the default port, keep the raw path,
     * drop query and fragment. Mastercard takes the decoded authority and strips the port with
     * {@code lastIndexOf(':')}; replicated so both sides agree on the same bytes.
     */
    static String baseUri(URI uri) {
        if (!uri.isAbsolute() || uri.getAuthority() == null) {
            throw new IllegalArgumentException("request URI must be absolute with an authority");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String authority = uri.getAuthority().toLowerCase(Locale.ROOT);
        if (("http".equals(scheme) && uri.getPort() == 80) || ("https".equals(scheme) && uri.getPort() == 443)) {
            authority = authority.substring(0, authority.lastIndexOf(':'));
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return scheme + "://" + authority + path;
    }

    static String parameterString(String rawQuery, Map<String, String> oauthParams) {
        TreeMap<String, List<String>> all = queryParameters(rawQuery);
        oauthParams.forEach((k, v) -> all.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
        StringJoiner joined = new StringJoiner("&");
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            List<String> values = e.getValue();
            Collections.sort(values);
            for (String v : values) {
                joined.add(e.getKey() + '=' + v);
            }
        }
        return joined.toString();
    }

    /**
     * Query parameters as Mastercard's {@code extractQueryParams} normalises them: if the query contains
     * any percent escape, every name and value is decoded and re-encoded per RFC 3986; otherwise names and
     * values are used verbatim. Sorting is Java {@code String} order on the resulting names, then values.
     *
     * <p>The raw query is split on {@code &} and {@code =} before anything is decoded, so an escaped
     * {@code &} or {@code =} stays data, and every value of a repeated name is signed.
     * Malformed escapes or non-UTF-8 octets, which {@link URI#getQuery()} would silently replace with U+FFFD
     * (making distinct queries collide), are rejected.
     */
    static TreeMap<String, List<String>> queryParameters(String rawQuery) {
        TreeMap<String, List<String>> params = new TreeMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        boolean reencode = !PercentEncoding.decode(rawQuery).equals(rawQuery);
        // String.split drops trailing empty segments; Mastercard splits the same way.
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq > 0 ? pair.substring(0, eq) : pair;
            String value = eq > 0 ? pair.substring(eq + 1) : "";
            if (reencode) {
                name = PercentEncoding.encode(PercentEncoding.decode(name));
                value = PercentEncoding.encode(PercentEncoding.decode(value));
            }
            params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return params;
    }
}
