package dev.cardrail.sw.application;

import dev.cardrail.sw.port.OutboxMessage;
import dev.cardrail.sw.port.OutboxPublisher;
import dev.cardrail.sw.port.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final List<Long> sent = new ArrayList<>();

    @Test
    void failedMessageIsRetriedLaterAndHoldsBackLaterMessagesWithTheSameKey() {
        when(outbox.lockUnpublished(100, 10)).thenReturn(List.of(
                new OutboxMessage(1, "authorization.approved", "card-a", "{}", 0),
                new OutboxMessage(2, "authorization.approved", "card-b", "{}", 0),
                new OutboxMessage(3, "authorization.reversed", "card-a", "{}", 0)));
        OutboxPublisher publisher = message -> {
            if (message.id() == 1) {
                throw new IllegalStateException("broker unavailable");
            }
            sent.add(message.id());
        };

        int published = relay(publisher).publishPending();

        assertThat(published).isOne();
        assertThat(sent).containsExactly(2L);
        verify(outbox).markFailed(eq(1L), anyString());
        verify(outbox).markPublished(eq(2L), any());
        verify(outbox, never()).markPublished(eq(3L), any());
        verify(outbox, never()).markFailed(eq(3L), anyString());
    }

    private OutboxRelay relay(OutboxPublisher publisher) {
        return new OutboxRelay(outbox, publisher,
                new OutboxProperties("kafka", 100, 10, "cardrail.", Duration.ofSeconds(1), 1, (short) 1),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                new SwitchMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
    }
}
