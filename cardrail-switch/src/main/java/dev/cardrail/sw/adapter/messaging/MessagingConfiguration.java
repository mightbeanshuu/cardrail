package dev.cardrail.sw.adapter.messaging;

import dev.cardrail.sw.application.AuthorizationEvents;
import dev.cardrail.sw.application.OutboxProperties;
import dev.cardrail.sw.port.OutboxPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    private static final List<String> EVENT_TYPES = List.of(
            AuthorizationEvents.APPROVED, AuthorizationEvents.DECLINED, AuthorizationEvents.STIP_ADVICE,
            AuthorizationEvents.REVERSED, AuthorizationEvents.CAPTURED, AuthorizationEvents.EXPIRED);

    @Bean
    @ConditionalOnProperty(name = "cardrail.outbox.publisher", havingValue = "log", matchIfMissing = true)
    OutboxPublisher logOutboxPublisher() {
        return new LogOutboxPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "cardrail.outbox.publisher", havingValue = "kafka")
    OutboxPublisher kafkaOutboxPublisher(KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        return new KafkaOutboxPublisher(kafka, properties.topicPrefix(), properties.sendTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "cardrail.outbox.publisher", havingValue = "http")
    OutboxPublisher httpOutboxPublisher(HttpOutboxProperties http, OutboxProperties properties, Clock clock) {
        return new HttpOutboxPublisher(http, properties.sendTimeout(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "cardrail.outbox.publisher", havingValue = "kafka")
    KafkaAdmin.NewTopics outboxTopics(OutboxProperties properties) {
        return new KafkaAdmin.NewTopics(EVENT_TYPES.stream()
                .map(type -> TopicBuilder.name(KafkaOutboxPublisher.topic(properties.topicPrefix(), type))
                        .partitions(properties.topicPartitions())
                        .replicas(properties.topicReplicas())
                        .build())
                .toArray(NewTopic[]::new));
    }
}
