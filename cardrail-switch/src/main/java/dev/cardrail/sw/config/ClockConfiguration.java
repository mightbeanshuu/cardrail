package dev.cardrail.sw.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    // PostgreSQL timestamps hold microseconds while Linux clocks report nanoseconds. Ticking at the
    // database's precision keeps an Instant equal to itself after a round trip, so a replayed
    // reversal or capture returns exactly what the first call returned.
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
