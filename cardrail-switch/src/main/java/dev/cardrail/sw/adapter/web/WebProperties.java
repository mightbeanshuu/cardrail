package dev.cardrail.sw.adapter.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param allowedOrigins browser origins allowed to call the API cross-origin (the console)
 * @param rateLimit      per-client limit on requests that change state; off unless enabled
 */
@ConfigurationProperties("cardrail.web")
record WebProperties(List<String> allowedOrigins, @DefaultValue RateLimit rateLimit) {

    WebProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    /**
     * A token bucket per client address.
     *
     * @param burst             requests a client can make at once
     * @param requestsPerMinute rate at which its bucket refills
     * @param maxClients        buckets kept before idle ones are dropped, so spoofed addresses cannot exhaust memory
     */
    record RateLimit(@DefaultValue("false") boolean enabled,
                     @DefaultValue("20") int burst,
                     @DefaultValue("60") int requestsPerMinute,
                     @DefaultValue("10000") int maxClients) {

        RateLimit {
            if (burst < 1 || requestsPerMinute < 1 || maxClients < 1) {
                throw new IllegalArgumentException("cardrail.web.rate-limit values must be positive");
            }
        }
    }
}
