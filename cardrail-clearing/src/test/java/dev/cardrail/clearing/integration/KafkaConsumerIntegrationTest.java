package dev.cardrail.clearing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.clearing.support.ClearingIntegrationTest;
import dev.cardrail.clearing.support.TestEvents;
import dev.cardrail.clearing.support.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ClearingIntegrationTest
class KafkaConsumerIntegrationTest {

    private static final String KEY = "9999991234567890";
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-18T04:00:00Z");

    @Autowired TestFixtures fixtures;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        fixtures.reset();
        MessageListenerContainer container = listeners.getListenerContainer("clearing-authorization-events");
        ContainerTestUtils.waitForAssignment(container, 2);
    }

    @Test
    void aRedeliveredCaptureCreatesOneRecordAndTheOffsetIsCommittedAfterwards() throws Exception {
        UUID id = UUID.randomUUID();
        String event = json.writeValueAsString(TestEvents.captured(id, 20_000, OCCURRED_AT));
        double duplicatesBefore = duplicates();

        send(TestEvents.CAPTURED_TOPIC, event);
        send(TestEvents.CAPTURED_TOPIC, event);
        UUID sentinel = sendCaptureAndAwait();

        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE authorization_id = ?", id.toString())).isOne();
        assertThat(fixtures.count("SELECT COUNT(*) FROM processed_event WHERE authorization_id = ?", id.toString())).isOne();
        assertThat(duplicates()).isEqualTo(duplicatesBefore + 1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE authorization_id = ?", sentinel.toString())).isOne();

        TopicPartition partition = new TopicPartition(TestEvents.CAPTURED_TOPIC, 0);
        long end = endOffset(partition);
        await().atMost(Duration.ofSeconds(10)).until(() -> committedOffset(partition) == end);
    }

    @Test
    void poisonMessagesGoToTheDeadLetterTopicAndDoNotBlockThePartition() throws Exception {
        UUID missingPan = UUID.randomUUID();
        Map<String, Object> noPan = TestEvents.captured(missingPan, 20_000, OCCURRED_AT);
        noPan.remove("maskedPan");
        String marker = UUID.randomUUID().toString();

        try (Consumer<String, String> dlt = consumer(TestEvents.DEAD_LETTER_TOPIC)) {
            kafka.send(TestEvents.CAPTURED_TOPIC, marker, "{not json").get();
            kafka.send(TestEvents.CAPTURED_TOPIC, marker, json.writeValueAsString(noPan)).get();
            UUID sentinel = sendCaptureAndAwait();

            List<ConsumerRecord<String, String>> deadLetters = new ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                KafkaTestUtils.getRecords(dlt, Duration.ofMillis(500)).forEach(r -> {
                    if (marker.equals(r.key())) {
                        deadLetters.add(r);
                    }
                });
                return deadLetters.size() >= 2;
            });

            assertThat(deadLetters).extracting(ConsumerRecord::value)
                    .containsExactly("{not json", json.writeValueAsString(noPan));
            assertThat(header(deadLetters.get(0), KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TestEvents.CAPTURED_TOPIC);
            assertThat(header(deadLetters.get(0), KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("unreadable JSON");
            assertThat(header(deadLetters.get(1), KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("maskedPan");
            assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE authorization_id = ?", missingPan.toString())).isZero();
            assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE authorization_id = ?", sentinel.toString())).isOne();
        }
    }

    @Test
    void aReversalAfterACaptureIsOffsetEndToEnd() throws Exception {
        UUID id = UUID.randomUUID();
        send(TestEvents.CAPTURED_TOPIC, json.writeValueAsString(TestEvents.captured(id, 20_000, OCCURRED_AT)));
        awaitRecords(id, 1);

        send(TestEvents.REVERSED_TOPIC, json.writeValueAsString(TestEvents.reversed(id, 20_000, OCCURRED_AT.plusSeconds(300))));
        awaitRecords(id, 2);

        assertThat(fixtures.jdbc().sql("SELECT SUM(amount_minor) FROM clearing_record WHERE authorization_id = ?")
                .param(id.toString()).query(Long.class).single()).isZero();
    }

    private UUID sendCaptureAndAwait() throws Exception {
        UUID id = UUID.randomUUID();
        send(TestEvents.CAPTURED_TOPIC, json.writeValueAsString(TestEvents.captured(id, 1_000, OCCURRED_AT)));
        awaitRecords(id, 1);
        return id;
    }

    private void awaitRecords(UUID id, long expected) {
        await().atMost(Duration.ofSeconds(15)).until(() ->
                fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE authorization_id = ?", id.toString()) == expected);
    }

    private void send(String topic, String value) throws Exception {
        kafka.send(topic, KEY, value).get();
    }

    private double duplicates() {
        var counter = meters.find("cardrail.clearing.events").tag("outcome", "DUPLICATE")
                .tag("type", "authorization.captured").counter();
        return counter == null ? 0 : counter.count();
    }

    private Consumer<String, String> consumer(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-test-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private long endOffset(TopicPartition partition) {
        try (Consumer<String, String> consumer = consumer(partition.topic())) {
            return consumer.endOffsets(List.of(partition)).get(partition);
        }
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets("cardrail-clearing")
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
