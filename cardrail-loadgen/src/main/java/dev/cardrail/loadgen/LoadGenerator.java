package dev.cardrail.loadgen;

import dev.cardrail.iso8583.IsoMessage;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load: request {@code i} is due at {@code start + i / rate} and is sent then whether or
 * not earlier requests have been answered. Latency is measured from that due time, not from the
 * moment the request actually left, so a stall on either side shows up in the results instead of
 * silently thinning the sample.
 *
 * <p>Connection {@code c} of {@code n} sends requests {@code c, c + n, c + 2n, ...} from its own
 * virtual thread. A sender that falls behind sends its overdue requests immediately.
 */
public final class LoadGenerator {

    private final LoadGenOptions options;
    private final CardPool cards;

    /** @throws IllegalArgumentException if the acquirer ID, currency or country cannot be encoded */
    public LoadGenerator(LoadGenOptions options, CardPool cards) {
        this.options = Objects.requireNonNull(options, "options");
        this.cards = Objects.requireNonNull(cards, "cards");
        factoryFor(0);
    }

    private RequestFactory factoryFor(int connection) {
        return new RequestFactory(options.acquirerId(), String.format("LG%06d", connection), "CARDRAILLOADGEN",
                options.country(), options.currency(), options.terminalZone());
    }

    public LoadReport run() throws IOException, InterruptedException {
        List<AcquirerClient> clients = new ArrayList<>();
        try {
            for (int c = 0; c < options.connections(); c++) {
                clients.add(AcquirerClient.connect(options.host(), options.port(), options.timeout()));
            }
            return drive(clients);
        } finally {
            clients.forEach(AcquirerClient::close);
        }
    }

    private LoadReport drive(List<AcquirerClient> clients) throws InterruptedException {
        int n = clients.size();
        double intervalNanos = 1e9 / options.rate();
        long start = System.nanoTime();
        long measureFrom = start + options.warmup().toNanos();
        long end = measureFrom + options.duration().toNanos();
        Recorder recorder = new Recorder(measureFrom);

        SplittableRandom root = new SplittableRandom(options.seed());
        List<Future<?>> loops = new ArrayList<>();
        try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < n; c++) {
                int connection = c;
                SplittableRandom rnd = root.split();
                RequestFactory factory = factoryFor(connection);
                loops.add(senders.submit(() -> sendLoop(clients.get(connection), factory, rnd, connection, n,
                        start, intervalNanos, end, recorder)));
            }
        }
        for (Future<?> loop : loops) {
            try {
                loop.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("sender failed", e.getCause());
            }
        }

        // Every request either completes or times out, so this wait is bounded by the timeout.
        long deadline = System.nanoTime() + options.timeout().toNanos() + 1_000_000_000L;
        while (recorder.inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        long unmatched = clients.stream().mapToLong(AcquirerClient::unmatchedResponses).sum();
        Map<String, Long> codes = new TreeMap<>();
        recorder.codes.forEach((code, count) -> codes.put(code, count.sum()));
        return new LoadReport(options.rate(), n, options.warmup(), options.duration(), options.seed(),
                recorder.sent.sum(), recorder.completed.sum(), recorder.timeouts.sum(), recorder.errors.sum(),
                unmatched, recorder.latency, new TreeMap<>(codes), recorder.maxSendLag.get());
    }

    private void sendLoop(AcquirerClient client, RequestFactory factory, SplittableRandom rnd, int connection,
                          int connections, long start, double intervalNanos, long end, Recorder recorder) {
        for (long k = 0; ; k++) {
            long intended = start + Math.round((k * connections + connection) * intervalNanos);
            if (intended >= end) {
                return;
            }
            long wait;
            while ((wait = intended - System.nanoTime()) > 0) {
                LockSupport.parkNanos(wait);
            }
            boolean measured = recorder.isMeasured(intended);
            if (measured) {
                recorder.sent(System.nanoTime() - intended);
            }
            Card card = cards.pick(rnd);
            Instant transmittedAt = Instant.now();
            recorder.inFlight.incrementAndGet();
            CompletableFuture<IsoMessage> response;
            try {
                response = client.send(stan -> factory.authorization(card, stan, transmittedAt, rnd));
            } catch (RuntimeException e) {
                response = CompletableFuture.failedFuture(e);
            }
            response.whenComplete((r, e) -> recorder.complete(intended, measured, r, e));
        }
    }

    private static final class Recorder {
        private final long measureFrom;
        private final LatencyHistogram latency = new LatencyHistogram();
        private final Map<String, LongAdder> codes = new ConcurrentHashMap<>();
        private final LongAdder sent = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder timeouts = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final AtomicLong inFlight = new AtomicLong();
        private final AtomicLong maxSendLag = new AtomicLong();

        Recorder(long measureFrom) {
            this.measureFrom = measureFrom;
        }

        boolean isMeasured(long intended) {
            return intended - measureFrom >= 0;
        }

        void sent(long lagNanos) {
            sent.increment();
            maxSendLag.accumulateAndGet(lagNanos, Math::max);
        }

        void complete(long intended, boolean measured, IsoMessage response, Throwable error) {
            long now = System.nanoTime();
            try {
                if (!measured) {
                    return;
                }
                if (error == null) {
                    latency.record(Math.max(0, now - intended));
                    completed.increment();
                    codes.computeIfAbsent(response.responseCode().orElse("none"), k -> new LongAdder()).increment();
                } else if (unwrap(error) instanceof TimeoutException) {
                    timeouts.increment();
                } else {
                    errors.increment();
                }
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private static Throwable unwrap(Throwable t) {
            return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        }
    }
}
