package dev.cardrail.sw.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/** On in the public demo ({@code cardrail.web.rate-limit.enabled=true}); see {@link RateLimitInterceptor}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cardrail.web.rate-limit.enabled", havingValue = "true")
class RateLimitConfiguration implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    RateLimitConfiguration(WebProperties properties, Clock clock, ObjectMapper json) {
        this.interceptor = new RateLimitInterceptor(new ClientRateLimiter(properties.rateLimit(), clock), json);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
