package dev.cardrail.sw.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One shared application context for every integration test and the Cucumber suite: real Postgres
 * (cardrail_test), real Redis (database 15), embedded Kafka, and the Prometheus registry (which
 * {@code @SpringBootTest} leaves out unless asked). Keeping the configuration identical
 * everywhere is what lets Spring cache and reuse the context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(TestSupportConfiguration.class)
public @interface CardrailIntegrationTest {
}
