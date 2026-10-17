package dev.cardrail.sw.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Off in tests, which run the jobs explicitly so their effects are deterministic. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "cardrail.scheduling.enabled", havingValue = "true")
class SchedulingConfiguration {
}
