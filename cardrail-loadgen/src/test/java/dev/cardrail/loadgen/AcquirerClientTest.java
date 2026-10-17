package dev.cardrail.loadgen;

import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AcquirerClientTest {

    private static final Card CARD = new Card("5555555555554444", "2912");
    private static final RequestFactory FACTORY =
            new RequestFactory("123456", "LG000001", "CARDRAILLOADGEN", "356", "356", ZoneOffset.UTC);

    private final SplittableRandom rnd = new SplittableRandom(3);

    private IsoMessage request(String stan) {
        return FACTORY.authorization(CARD, stan, Instant.now(), rnd);
    }

    @Test
    void correlatesResponsesThatArriveOutOfOrder() throws Exception {
        int n = 20;
        try (FakeIsoServer server = new FakeIsoServer(req -> {
            int stan = Integer.parseInt(req.stan().orElseThrow());
            Thread.sleep((n + 1 - stan) * 15L);
            return Optional.of(IsoCodec.responseFor(req, "00", null));
        }, true);
             AcquirerClient client = AcquirerClient.connect("127.0.0.1", server.port(), Duration.ofSeconds(5))) {
            List<IsoMessage> sent = new ArrayList<>();
            List<CompletableFuture<IsoMessage>> futures = new ArrayList<>();
            List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < n; i++) {
                IsoMessage[] built = new IsoMessage[1];
                CompletableFuture<IsoMessage> f = client.send(stan -> built[0] = request(stan));
                sent.add(built[0]);
                futures.add(f);
                f.thenAccept(r -> completionOrder.add(r.stan().orElseThrow()));
            }
            for (int i = 0; i < n; i++) {
                IsoMessage resp = futures.get(i).get(5, TimeUnit.SECONDS);
                assertThat(resp.stan()).isEqualTo(sent.get(i).stan());
                assertThat(resp.amountMinor()).isEqualTo(sent.get(i).amountMinor());
                assertThat(resp.retrievalReferenceNumber()).isEqualTo(sent.get(i).retrievalReferenceNumber());
            }
            assertThat(completionOrder.getFirst()).isEqualTo(String.format("%06d", n));
            assertThat(client.outstanding()).isZero();
            assertThat(client.unmatchedResponses()).isZero();
        }
    }

    @Test
    void allocatedStansWrapAndSkipThoseStillOutstanding() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeIsoServer server = new FakeIsoServer(req -> {
            if (req.stan().orElseThrow().equals("000001")) {
                release.await();
            }
            return Optional.of(IsoCodec.responseFor(req, "00", null));
        }, true);
             AcquirerClient client = AcquirerClient.connect("127.0.0.1", server.port(), Duration.ofSeconds(5),
                     new StanSequence(999_998))) {
            CompletableFuture<IsoMessage> held = client.send(request("000001"));
            List<String> stans = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                stans.add(client.send(this::request).get(5, TimeUnit.SECONDS).stan().orElseThrow());
            }
            assertThat(stans).containsExactly("999998", "999999", "000002");
            release.countDown();
            assertThat(held.get(5, TimeUnit.SECONDS).stan()).contains("000001");
        }
    }

    @Test
    void aStanCannotBeOutstandingTwice() throws Exception {
        try (FakeIsoServer server = new FakeIsoServer(req -> Optional.empty(), true);
             AcquirerClient client = AcquirerClient.connect("127.0.0.1", server.port(), Duration.ofSeconds(5))) {
            client.send(request("000007"));
            assertThatThrownBy(() -> client.send(request("000007")).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void unansweredRequestTimesOutAndALateAnswerIsCountedUnmatched() throws Exception {
        try (FakeIsoServer server = new FakeIsoServer(req -> {
            Thread.sleep(400);
            return Optional.of(IsoCodec.responseFor(req, "00", null));
        }, true);
             AcquirerClient client = AcquirerClient.connect("127.0.0.1", server.port(), Duration.ofMillis(100))) {
            long t0 = System.nanoTime();
            CompletableFuture<IsoMessage> f = client.send(this::request);
            assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isBetween(100L, 1_000L);
            assertThat(client.outstanding()).isZero();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.unmatchedResponses() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(client.unmatchedResponses()).isEqualTo(1);
        }
    }

    @Test
    void responseWithTheRightStanButAnotherTransmissionTimeIsNotAMatch() throws Exception {
        try (FakeIsoServer server = new FakeIsoServer(
                req -> Optional.of(IsoCodec.responseFor(req, "00", null).withField(7, "0101000000")), true);
             AcquirerClient client = AcquirerClient.connect("127.0.0.1", server.port(), Duration.ofMillis(300))) {
            CompletableFuture<IsoMessage> f = client.send(this::request);
            assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
            assertThat(client.unmatchedResponses()).isEqualTo(1);
        }
    }

    @Test
    void lostConnectionFailsOutstandingRequests() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (FakeIsoServer server = new FakeIsoServer(req -> {
            received.countDown();
            return Optional.empty();
        }, true);
             AcquirerClient client = AcquirerClient.connect("127.0.0.1", server.port(), Duration.ofSeconds(10))) {
            CompletableFuture<IsoMessage> f = client.send(this::request);
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            server.dropConnections();
            assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            assertThatThrownBy(() -> client.send(this::request).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IOException.class);
            assertThat(client.isOpen()).isFalse();
        }
    }
}
