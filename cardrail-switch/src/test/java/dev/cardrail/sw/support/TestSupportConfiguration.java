package dev.cardrail.sw.support;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfiguration {

    /** Every test run starts from an empty schema built by the real migrations. */
    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }

    @Bean
    TestFixtures testFixtures() {
        return new TestFixtures();
    }
}
