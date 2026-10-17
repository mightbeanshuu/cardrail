package dev.cardrail.clearing.adapter.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code POST /v1/events}, the switch's HTTP outbox transport for deployments without a broker.
 *
 * @param secret    HMAC-SHA256 key shared with the switch, at least 32 characters
 * @param tolerance how far a request's timestamp may be from this service's clock, either way
 */
@ConfigurationProperties("cardrail.events.http")
public record HttpEventsProperties(
        @DefaultValue("false") boolean enabled,
        String secret,
        @DefaultValue("5m") Duration tolerance) {

    @Override
    public String toString() {
        return "HttpEventsProperties[enabled=" + enabled + ", tolerance=" + tolerance + "]";
    }
}
