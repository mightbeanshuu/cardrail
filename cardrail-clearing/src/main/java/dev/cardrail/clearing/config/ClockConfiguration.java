package dev.cardrail.clearing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    // PostgreSQL timestamps and Oracle's default TIMESTAMP(6) hold microseconds, while Linux clocks
    // report nanoseconds. Ticking at that precision keeps a timestamp equal to itself after a round trip.
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
