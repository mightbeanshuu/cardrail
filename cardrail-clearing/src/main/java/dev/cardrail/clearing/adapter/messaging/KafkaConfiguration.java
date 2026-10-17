package dev.cardrail.clearing.adapter.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

    @Bean
    NewTopic deadLetterTopic(EventsProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.deadLetterPartitions())
                .replicas(properties.deadLetterReplicas())
                .build();
    }

    /**
     * Poison messages go to the dead-letter topic at once. Anything else is retried with back-off
     * first, then dead-lettered so one bad record cannot stall its partition forever. Dead-lettered
     * records keep the original topic, partition and offset in headers, so they can be replayed.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafka, EventsProperties properties) {
        // Partition -1 lets the producer pick, since the DLT's partition count need not match the source topic's.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, e) -> new TopicPartition(properties.deadLetterTopic(), -1));
        EventsProperties.Retry retry = properties.retry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
