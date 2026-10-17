package dev.cardrail.sw.adapter.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** @param allowedOrigins browser origins allowed to call the API cross-origin (the console's dev server) */
@ConfigurationProperties("cardrail.web")
record WebProperties(List<String> allowedOrigins) {

    WebProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
