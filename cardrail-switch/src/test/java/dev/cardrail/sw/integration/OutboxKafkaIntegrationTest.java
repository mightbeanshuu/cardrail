package dev.cardrail.sw.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.sw.adapter.messaging.KafkaOutboxPublisher;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.OutboxRelay;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@CardrailIntegrationTest
class OutboxKafkaIntegrationTest {

    private static final String PAN = "5555555555554444";
    private static final String APPROVED_TOPIC = "cardrail.authorization.approved";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;
    @Autowired OutboxRelay relay;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        fixtures.reset();
        fixtures.openAccount(PAN, 50_000);
    }

    @Test
    void approvedAuthorizationProducesExactlyOneRecordKeyedByToken() throws Exception {
        AuthorizationResult approved = authorize.authorize(RequestBuilder.purchase(PAN, 20_000).build());

        assertThat(relay.publishPending()).isOne();
        assertThat(relay.publishPending()).as("published rows are not sent again").isZero();

        try (Consumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(APPROVED_TOPIC));
            List<ConsumerRecord<String, String>> records = StreamSupport.stream(
                            KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 1).records(APPROVED_TOPIC)
                                    .spliterator(),
                            false)
                    .filter(r -> r.value().contains(approved.id().toString()))
                    .toList();

            assertThat(records).hasSize(1);
            ConsumerRecord<String, String> record = records.getFirst();
            assertThat(record.key()).isEqualTo(fixtures.token(PAN));
            assertThat(json.readTree(record.value()).get("responseCode").asText()).isEqualTo("00");
            assertThat(record.value()).doesNotContain(PAN);
            assertThat(new String(record.headers().lastHeader(KafkaOutboxPublisher.EVENT_TYPE_HEADER).value(),
                    StandardCharsets.UTF_8)).isEqualTo("authorization.approved");
        }
        assertThat(fixtures.jdbc().sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class).single()).isZero();
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("outbox-test-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
