package dev.cardrail.apisec;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureBaseStringTest {

    static final Map<String, String> OAUTH = new LinkedHashMap<>();

    static {
        OAUTH.put("oauth_consumer_key", "ck!1");
        OAUTH.put("oauth_nonce", "n0nce");
        OAUTH.put("oauth_signature_method", "RSA-SHA256");
        OAUTH.put("oauth_timestamp", "1790000000");
        OAUTH.put("oauth_version", "1.0");
        OAUTH.put("oauth_body_hash", "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
    }

    @Test
    void knownAnswer() {
        String sbs = SignatureBaseString.of("get", URI.create("HTTPS://Api.Example.com:443/a%2Fb?z=1&a=2&a=1&q=%C3%A9"), OAUTH);

        assertThat(sbs).isEqualTo("GET&https%3A%2F%2Fapi.example.com%2Fa%252Fb&"
                + "a%3D1%26a%3D2%26oauth_body_hash%3D47DEQpj8HBSa%2B%2FTImW%2B5JCeuQeRkm5NMpJWZG3hSuFU%3D"
                + "%26oauth_consumer_key%3Dck%211%26oauth_nonce%3Dn0nce%26oauth_signature_method%3DRSA-SHA256"
                + "%26oauth_timestamp%3D1790000000%26oauth_version%3D1.0%26q%3D%25C3%25A9%26z%3D1");
    }

    @Test
    void queryWithoutEscapesIsTakenVerbatimAndWithEscapesIsReencoded() {
        assertThat(SignatureBaseString.queryParameters("a=b!c").get("a")).containsExactly("b!c");
        assertThat(SignatureBaseString.queryParameters("a=b!c&d=%20").get("a")).containsExactly("b%21c");
        assertThat(SignatureBaseString.queryParameters("p=a+b").get("p")).containsExactly("a+b");
        assertThat(SignatureBaseString.queryParameters("p=a+b&s=%20").get("p")).containsExactly("a%2Bb");
    }

    @Test
    void nonDefaultPortIsKept() {
        assertThat(SignatureBaseString.baseUri(URI.create("https://h:8443"))).isEqualTo("https://h:8443/");
        assertThat(SignatureBaseString.baseUri(URI.create("http://h:80/x?y#z"))).isEqualTo("http://h/x");
    }

    @Test
    void malformedQueryEscapesAreRejected() {
        assertThatThrownBy(() -> SignatureBaseString.queryParameters("a=%C3")).isInstanceOf(IllegalArgumentException.class);
    }
}
