package dev.cardrail.sw.adapter.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * The HTTP outbox transport ({@code cardrail.outbox.publisher=http}), used where there is no broker.
 *
 * @param baseUrl    the clearing service, e.g. {@code https://cardrail-clearing.onrender.com}
 * @param secret     HMAC-SHA256 key shared with clearing, at least 32 characters
 * @param eventTypes the event types clearing consumes; only these are sent
 */
@ConfigurationProperties("cardrail.outbox.http")
public record HttpOutboxProperties(URI baseUrl, String secret, List<String> eventTypes) {

    public HttpOutboxProperties {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }

    @Override
    public String toString() {
        return "HttpOutboxProperties[baseUrl=" + baseUrl + ", eventTypes=" + eventTypes + "]";
    }
}
