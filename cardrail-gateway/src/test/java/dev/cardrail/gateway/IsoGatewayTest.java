package dev.cardrail.gateway;

import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.Mti;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class IsoGatewayTest {

    private static final GatewayConfig TEST_CONFIG = GatewayConfig.defaults().withPort(0);

    private static final IsoRequestHandler APPROVE =
            req -> IsoCodec.responseFor(req, "00", "A" + req.stan().orElseThrow().substring(1));

    private final List<IsoGateway> gateways = new ArrayList<>();

    @AfterEach
    void stopGateways() throws InterruptedException {
        for (IsoGateway g : gateways) {
            g.shutdown(Duration.ofSeconds(5));
        }
    }

    private IsoGateway start(GatewayConfig config, IsoRequestHandler handler, GatewayListener listener)
            throws IOException {
        IsoGateway g = new IsoGateway(config, handler, listener);
        g.start();
        gateways.add(g);
        return g;
    }

    private IsoGateway start(GatewayConfig config, IsoRequestHandler handler) throws IOException {
        return start(config, handler, GatewayListener.NOOP);
    }

    @Test
    void echoTestIsAnsweredWithoutCallingTheHandler() throws Exception {
        AtomicInteger handlerCalls = new AtomicInteger();
        IsoGateway g = start(TEST_CONFIG, req -> {
            handlerCalls.incrementAndGet();
            return APPROVE.handle(req);
        });
        try (TestClient c = new TestClient(g.port())) {
            c.send(Messages.echo(42));
            IsoMessage resp = c.receive();

            assertThat(resp.mti()).isEqualTo(Mti.NETWORK_MANAGEMENT_RESPONSE);
            assertThat(resp.responseCode()).contains("00");
            assertThat(resp.stan()).contains("000042");
            assertThat(resp.networkManagementCode()).contains("301");
            assertThat(resp.has(38)).isFalse();
        }
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void networkManagementOtherThanEchoIsAnsweredInvalidTransaction() throws Exception {
        IsoGateway g = start(TEST_CONFIG, APPROVE);
        try (TestClient c = new TestClient(g.port())) {
            c.send(Messages.echo(1).withField(70, "001"));
            assertThat(c.receive().responseCode()).contains("12");
        }
    }

    @Test
    void authorizationIsAnsweredByTheHandler() throws Exception {
        List<String> completed = new CopyOnWriteArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);
        GatewayListener listener = new GatewayListener() {
            @Override
            public void requestCompleted(Mti requestMti, String responseCode, long nanos) {
                assertThat(nanos).isPositive();
                completed.add(requestMti + "/" + responseCode);
            }

            @Override
            public void connectionClosed(SocketAddress remote, CloseReason reason) {
                assertThat(reason).isEqualTo(CloseReason.PEER_CLOSED);
                closed.countDown();
            }
        };
        IsoGateway g = start(TEST_CONFIG, APPROVE, listener);
        IsoMessage request = Messages.authorization(123, 2599);
        try (TestClient c = new TestClient(g.port())) {
            c.send(request);
            IsoMessage resp = c.receive();

            assertThat(resp.mti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
            assertThat(resp.responseCode()).contains("00");
            assertThat(resp.authorizationId()).contains("A00123");
            assertThat(resp.stan()).isEqualTo(request.stan());
            assertThat(resp.retrievalReferenceNumber()).isEqualTo(request.retrievalReferenceNumber());
            assertThat(resp.amountMinor()).isEqualTo(request.amountMinor());
        }
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(completed).containsExactly("0100/00");
    }

    @Test
    void pipelinedRequestsAreAnsweredInCompletionOrder() throws Exception {
        int n = 10;
        IsoGateway g = start(TEST_CONFIG, req -> {
            int stan = Integer.parseInt(req.stan().orElseThrow());
            sleep((n + 1 - stan) * 40L);
            return APPROVE.handle(req);
        });
        try (TestClient c = new TestClient(g.port())) {
            for (int stan = 1; stan <= n; stan++) {
                c.send(Messages.authorization(stan, stan * 100L));
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                IsoMessage resp = c.receive();
                int stan = Integer.parseInt(resp.stan().orElseThrow());
                assertThat(resp.amountMinor().getAsLong()).isEqualTo(stan * 100L);
                assertThat(resp.responseCode()).contains("00");
                order.add(stan);
            }
            assertThat(order).containsExactly(10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        }
    }

    @Test
    void inFlightRequestsPerConnectionNeverExceedTheLimit() throws Exception {
        int limit = 4;
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        IsoGateway g = start(TEST_CONFIG.withMaxInFlightPerConnection(limit), req -> {
            int now = current.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            entered.incrementAndGet();
            try {
                gate.await();
                sleep(1);
                return APPROVE.handle(req);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            } finally {
                current.decrementAndGet();
            }
        });
        int total = 400;
        try (TestClient c = new TestClient(g.port())) {
            Thread sender = Thread.ofVirtual().start(() -> {
                try {
                    for (int stan = 1; stan <= total; stan++) {
                        c.send(Messages.authorization(stan, stan));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            // With the handler held, exactly `limit` requests get in and the rest wait unread.
            Thread.sleep(300);
            assertThat(entered).hasValue(limit);
            gate.countDown();

            Map<String, IsoMessage> byStan = new HashMap<>();
            for (int i = 0; i < total; i++) {
                IsoMessage resp = c.receive();
                byStan.put(resp.stan().orElseThrow(), resp);
            }
            sender.join();
            assertThat(byStan).hasSize(total);
            assertThat(byStan.values()).allSatisfy(r -> assertThat(r.responseCode()).contains("00"));
        }
        assertThat(peak.get()).isEqualTo(limit);
    }

    @Test
    void malformedMessageWithRecoverableStanIsAnsweredFormatError() throws Exception {
        AtomicInteger handlerCalls = new AtomicInteger();
        IsoGateway g = start(TEST_CONFIG, req -> {
            handlerCalls.incrementAndGet();
            return APPROVE.handle(req);
        });
        IsoMessage request = Messages.authorization(7, 100);
        byte[] bytes = new IsoCodec().encode(request);
        // Field 49 is the last field; make it non-numeric.
        System.arraycopy("AB1".getBytes(StandardCharsets.US_ASCII), 0, bytes, bytes.length - 3, 3);

        try (TestClient c = new TestClient(g.port())) {
            c.sendRaw(bytes);
            IsoMessage resp = c.receive();

            assertThat(resp.mti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
            assertThat(resp.responseCode()).contains("30");
            assertThat(resp.stan()).contains("000007");
            assertThat(resp.retrievalReferenceNumber()).isEqualTo(request.retrievalReferenceNumber());
            assertThat(resp.has(49)).isFalse();

            c.send(Messages.echo(8));
            assertThat(c.receive().stan()).contains("000008");
        }
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void unrecoverableFramesCloseTheConnection() throws Exception {
        List<CloseReason> reasons = new CopyOnWriteArrayList<>();
        IsoGateway g = start(TEST_CONFIG, APPROVE, new GatewayListener() {
            @Override
            public void connectionClosed(SocketAddress remote, CloseReason reason) {
                reasons.add(reason);
            }
        });

        try (TestClient c = new TestClient(g.port())) {
            c.sendRaw("ZZZZ0000000000000000".getBytes(StandardCharsets.US_ASCII));
            assertThat(c.closedByPeer(Duration.ofSeconds(5))).isTrue();
        }

        byte[] badAmount = new IsoCodec().encode(Messages.authorization(9, 100));
        // MTI, primary bitmap, field 2 (LL prefix + 16 digits), field 3: field 4 starts here. It precedes
        // the STAN, so once it is damaged the STAN cannot be located.
        int amountAt = 4 + 16 + 2 + 16 + 6;
        assertThat(new String(badAmount, amountAt, 12, StandardCharsets.US_ASCII)).isEqualTo("000000000100");
        badAmount[amountAt] = 'X';
        try (TestClient c = new TestClient(g.port())) {
            c.sendRaw(badAmount);
            assertThat(c.closedByPeer(Duration.ofSeconds(5))).isTrue();
        }

        try (TestClient c = new TestClient(g.port())) {
            c.sendRawFrame(new byte[] {(byte) 0xFF, (byte) 0xFF, '0', '1'});
            assertThat(c.closedByPeer(Duration.ofSeconds(5))).isTrue();
        }

        awaitSize(reasons, 3);
        assertThat(reasons).containsOnly(CloseReason.PROTOCOL_ERROR);
    }

    @Test
    void handlerExceptionIsAnsweredSystemMalfunction() throws Exception {
        IsoGateway g = start(TEST_CONFIG, req -> {
            throw new IllegalStateException("ledger unavailable");
        });
        try (TestClient c = new TestClient(g.port())) {
            c.send(Messages.authorization(5, 100));
            IsoMessage resp = c.receive();
            assertThat(resp.mti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
            assertThat(resp.responseCode()).contains("96");
            assertThat(resp.stan()).contains("000005");
        }
    }

    @Test
    void handlerResponseThatCannotBeMatchedIsReplacedWithSystemMalfunction() throws Exception {
        IsoGateway g = start(TEST_CONFIG, req -> APPROVE.handle(req).withField(11, "999999"));
        try (TestClient c = new TestClient(g.port())) {
            c.send(Messages.authorization(5, 100));
            IsoMessage resp = c.receive();
            assertThat(resp.stan()).contains("000005");
            assertThat(resp.responseCode()).contains("96");
        }
    }

    @Test
    void slowHandlerIsAnsweredIssuerUnavailableAndItsLateResultDiscarded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        IsoGateway g = start(TEST_CONFIG.withRequestTimeout(Duration.ofMillis(150)), req -> {
            // Ignores interruption, like a handler stuck in a non-interruptible call.
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            }
            returned.countDown();
            return APPROVE.handle(req);
        });
        try (TestClient c = new TestClient(g.port())) {
            long t0 = System.nanoTime();
            c.send(Messages.authorization(1, 100));
            IsoMessage resp = c.receive();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertThat(resp.responseCode()).contains("91");
            assertThat(resp.stan()).contains("000001");
            assertThat(elapsedMillis).isBetween(150L, 2_000L);

            release.countDown();
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            c.send(Messages.echo(2));
            IsoMessage next = c.receive();
            assertThat(next.mti()).isEqualTo(Mti.NETWORK_MANAGEMENT_RESPONSE);
            assertThat(next.stan()).contains("000002");
        }
        assertThat(interrupted).isTrue();
    }

    @Test
    void idleConnectionIsClosed() throws Exception {
        CountDownLatch idleClosed = new CountDownLatch(1);
        IsoGateway g = start(TEST_CONFIG.withIdleTimeout(Duration.ofMillis(300)), APPROVE, new GatewayListener() {
            @Override
            public void connectionClosed(SocketAddress remote, CloseReason reason) {
                if (reason == CloseReason.IDLE_TIMEOUT) {
                    idleClosed.countDown();
                }
            }
        });
        try (TestClient c = new TestClient(g.port())) {
            c.send(Messages.echo(1));
            c.receive();
            assertThat(c.closedByPeer(Duration.ofSeconds(5))).isTrue();
        }
        assertThat(idleClosed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void connectionWaitingForAResponseIsNotIdle() throws Exception {
        IsoGateway g = start(TEST_CONFIG.withIdleTimeout(Duration.ofMillis(200)), req -> {
            sleep(800);
            return APPROVE.handle(req);
        });
        try (TestClient c = new TestClient(g.port())) {
            c.send(Messages.authorization(1, 100));
            assertThat(c.receive().responseCode()).contains("00");
            assertThat(c.closedByPeer(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void connectionsBeyondTheLimitAreRejected() throws Exception {
        AtomicInteger rejected = new AtomicInteger();
        IsoGateway g = start(TEST_CONFIG.withMaxConnections(2), APPROVE, new GatewayListener() {
            @Override
            public void connectionRejected(SocketAddress remote) {
                rejected.incrementAndGet();
            }
        });
        try (TestClient a = new TestClient(g.port()); TestClient b = new TestClient(g.port())) {
            a.send(Messages.echo(1));
            b.send(Messages.echo(2));
            a.receive();
            b.receive();
            try (TestClient c = new TestClient(g.port())) {
                assertThat(c.closedByPeer(Duration.ofSeconds(5))).isTrue();
            }
            assertThat(g.activeConnections()).isEqualTo(2);
        }
        assertThat(rejected).hasValue(1);
    }

    @Test
    void gracefulShutdownAnswersRequestsAlreadyReadAndStopsAccepting() throws Exception {
        int n = 10;
        CountDownLatch entered = new CountDownLatch(n);
        IsoGateway g = start(TEST_CONFIG, req -> {
            entered.countDown();
            sleep(300);
            return APPROVE.handle(req);
        });
        int port = g.port();
        try (TestClient c = new TestClient(port); TestClient idle = new TestClient(port)) {
            idle.send(Messages.echo(99));
            idle.receive();
            for (int stan = 1; stan <= n; stan++) {
                c.send(Messages.authorization(stan, 100));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            Future<Boolean> drained = exec.submit(() -> g.shutdown(Duration.ofSeconds(5)));

            List<String> stans = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                IsoMessage resp = c.receive();
                assertThat(resp.responseCode()).contains("00");
                stans.add(resp.stan().orElseThrow());
            }
            assertThat(stans).hasSize(n).doesNotHaveDuplicates();
            assertThat(drained.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(c.closedByPeer(Duration.ofSeconds(2))).isTrue();
            assertThat(idle.closedByPeer(Duration.ofSeconds(2))).isTrue();
            exec.shutdown();
        }
        assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), port).close())
                .isInstanceOf(ConnectException.class);
    }

    @Test
    void fiftyConnectionsOfTwoHundredPipelinedRequestsAreAllAnsweredCorrectly() throws Exception {
        int connections = 50;
        int perConnection = 200;
        LongAdder completed = new LongAdder();
        IsoGateway g = start(TEST_CONFIG, req -> {
            if (ThreadLocalRandom.current().nextInt(4) == 0) {
                sleep(ThreadLocalRandom.current().nextInt(3));
            }
            long amount = req.amountMinor().orElseThrow();
            return IsoCodec.responseFor(req, amount % 7 == 0 ? "51" : "00", null);
        }, new GatewayListener() {
            @Override
            public void requestCompleted(Mti requestMti, String responseCode, long nanos) {
                completed.increment();
            }
        });

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int conn = 0; conn < connections; conn++) {
                results.add(exec.submit(() -> runClient(g.port(), perConnection)));
            }
            for (Future<Integer> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(perConnection);
            }
        }
        assertThat(completed.sum()).isEqualTo((long) connections * perConnection);
    }

    private static int runClient(int port, int count) throws Exception {
        Map<String, Long> sent = new ConcurrentHashMap<>();
        try (TestClient c = new TestClient(port)) {
            Thread sender = Thread.ofVirtual().start(() -> {
                try {
                    for (int stan = 1; stan <= count; stan++) {
                        long amount = ThreadLocalRandom.current().nextLong(1, 1_000_000);
                        sent.put(String.format("%06d", stan), amount);
                        c.send(Messages.authorization(stan, amount));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            int matched = 0;
            for (int i = 0; i < count; i++) {
                IsoMessage resp = c.receive();
                String stan = resp.stan().orElseThrow();
                Long amount = sent.remove(stan);
                assertThat(amount).as("response for unknown or already answered STAN %s", stan).isNotNull();
                assertThat(resp.amountMinor().getAsLong()).isEqualTo(amount);
                assertThat(resp.responseCode()).contains(amount % 7 == 0 ? "51" : "00");
                matched++;
            }
            sender.join();
            return matched;
        }
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (list.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(list).hasSize(size);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
