package dev.cardrail.clearing.support;

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
 * One application context shared by every integration test: the real database, embedded Kafka
 * with the switch's two topics and the dead-letter topic, and MockMvc. Identical configuration
 * everywhere is what lets Spring cache the context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@ActiveProfiles(resolver = ClearingDbProfileResolver.class)
@EmbeddedKafka(partitions = 1,
        topics = {TestEvents.CAPTURED_TOPIC, TestEvents.REVERSED_TOPIC, TestEvents.DEAD_LETTER_TOPIC},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(TestSupportConfiguration.class)
public @interface ClearingIntegrationTest {
}
