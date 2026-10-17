package dev.cardrail.sw.adapter.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the console call the API from another origin. Credentials are not allowed: the API has no
 * cookies or sessions, so a cross-origin page gains nothing by sending them.
 */
@Configuration(proxyBeanMethods = false)
class WebCorsConfiguration implements WebMvcConfigurer {

    private final WebProperties properties;

    WebCorsConfiguration(WebProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.allowedOrigins().toArray(String[]::new);
        for (String path : new String[] {"/v1/**", "/dev/**"}) {
            registry.addMapping(path)
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE")
                    .allowedHeaders("Content-Type", CorrelationIdFilter.HEADER)
                    .exposedHeaders(CorrelationIdFilter.HEADER, HttpHeaders.RETRY_AFTER)
                    .maxAge(3600);
        }
    }
}
