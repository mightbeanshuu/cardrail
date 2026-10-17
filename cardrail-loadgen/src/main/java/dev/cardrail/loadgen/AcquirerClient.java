package dev.cardrail.loadgen;

import dev.cardrail.iso8583.FrameReader;
import dev.cardrail.iso8583.FrameWriter;
import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.IsoParseException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * One acquirer-side connection to an ISO 8583 server that may answer pipelined requests out of order.
 *
 * <p>Responses are matched to requests by STAN (field 11), which must be unique among the requests
 * outstanding on this connection, and additionally checked against the request's field 7 so that a
 * late response to a timed-out request cannot be mistaken for the answer to a later request that
 * reused its STAN. A response that matches nothing is counted in {@link #unmatchedResponses()} and
 * dropped.
 *
 * <p>Thread-safe: any number of threads may send. Futures are completed on the connection's reader
 * thread or the timeout thread, so callbacks attached to them should be short.
 */
public final class AcquirerClient implements AutoCloseable {

    private static final IsoCodec CODEC = new IsoCodec();

    private final Socket socket;
    private final FrameReader reader;
    private final FrameWriter writer;
    private final Duration requestTimeout;
    private final StanSequence stans;
    // Guards choosing and registering a STAN, so two senders cannot claim the same one.
    private final ReentrantLock allocation = new ReentrantLock();
    private final Map<String, Pending> outstanding = new ConcurrentHashMap<>();
    private final LongAdder unmatched = new LongAdder();
    private final LongAdder undecodable = new LongAdder();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread readerThread;

    private record Pending(Optional<String> transmissionDateTime, CompletableFuture<IsoMessage> future) {
    }

    private AcquirerClient(Socket socket, Duration requestTimeout, StanSequence stans) throws IOException {
        this.socket = socket;
        this.stans = stans;
        this.requestTimeout = requestTimeout;
        this.reader = new FrameReader(new BufferedInputStream(socket.getInputStream()));
        this.writer = new FrameWriter(socket.getOutputStream());
        this.readerThread = Thread.ofVirtual().name("acquirer-reader-" + socket.getLocalPort()).start(this::readLoop);
    }

    public static AcquirerClient connect(String host, int port, Duration requestTimeout) throws IOException {
        return connect(host, port, requestTimeout, new StanSequence());
    }

    static AcquirerClient connect(String host, int port, Duration requestTimeout, StanSequence stans)
            throws IOException {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), (int) Math.min(Integer.MAX_VALUE, requestTimeout.toMillis()));
            return new AcquirerClient(socket, requestTimeout, stans);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Sends a request whose STAN the caller chose.
     *
     * @return completes with the response, or exceptionally with {@link java.util.concurrent.TimeoutException}
     *         after the request timeout, {@link IllegalStateException} if the STAN is already outstanding,
     *         or {@link IOException} if the connection fails
     */
    public CompletableFuture<IsoMessage> send(IsoMessage request) {
        String stan = request.stan().orElseThrow(() -> new IllegalArgumentException("request has no STAN (field 11)"));
        Pending pending = new Pending(request.transmissionDateTime(), new CompletableFuture<>());
        allocation.lock();
        try {
            if (outstanding.putIfAbsent(stan, pending) != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("STAN " + stan + " is already outstanding"));
            }
        } finally {
            allocation.unlock();
        }
        return transmit(stan, request, pending);
    }

    /**
     * Allocates the next STAN not outstanding on this connection (wrapping after 999999), builds the
     * request with it and sends it.
     *
     * @return as {@link #send(IsoMessage)}; fails with {@link IllegalStateException} if every STAN is outstanding
     */
    public CompletableFuture<IsoMessage> send(Function<String, IsoMessage> requestForStan) {
        String stan;
        IsoMessage request;
        Pending pending;
        allocation.lock();
        try {
            stan = null;
            for (int i = 0; i < StanSequence.MAX; i++) {
                String candidate = stans.next();
                if (!outstanding.containsKey(candidate)) {
                    stan = candidate;
                    break;
                }
            }
            if (stan == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("all STANs are outstanding"));
            }
            request = requestForStan.apply(stan);
            if (!request.stan().equals(Optional.of(stan))) {
                throw new IllegalArgumentException("request built for STAN " + stan + " carries a different field 11");
            }
            pending = new Pending(request.transmissionDateTime(), new CompletableFuture<>());
            outstanding.put(stan, pending);
        } finally {
            allocation.unlock();
        }
        return transmit(stan, request, pending);
    }

    private CompletableFuture<IsoMessage> transmit(String stan, IsoMessage request, Pending pending) {
        CompletableFuture<IsoMessage> future = pending.future();
        future.orTimeout(requestTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .whenComplete((r, e) -> outstanding.remove(stan, pending));
        if (closed.get()) {
            future.completeExceptionally(new IOException("connection closed"));
            return future;
        }
        try {
            writer.write(CODEC.encode(request));
        } catch (IOException e) {
            future.completeExceptionally(e);
            close();
        }
        return future;
    }

    private void readLoop() {
        IOException failure = null;
        try {
            while (true) {
                Optional<byte[]> frame = reader.read();
                if (frame.isEmpty()) {
                    break;
                }
                IsoMessage response;
                try {
                    response = CODEC.decode(frame.get());
                } catch (IsoParseException e) {
                    undecodable.increment();
                    continue;
                }
                Pending pending = response.stan().map(outstanding::get).orElse(null);
                if (pending == null || !matches(pending, response)) {
                    unmatched.increment();
                    continue;
                }
                pending.future().complete(response);
            }
        } catch (IOException e) {
            failure = e;
        }
        closed.set(true);
        closeSocket();
        IOException cause = failure != null ? failure : new IOException("connection closed by server");
        outstanding.values().forEach(p -> p.future().completeExceptionally(cause));
    }

    private static boolean matches(Pending pending, IsoMessage response) {
        return pending.transmissionDateTime().isEmpty()
                || pending.transmissionDateTime().equals(response.transmissionDateTime());
    }

    public int outstanding() {
        return outstanding.size();
    }

    /** Responses whose STAN (and field 7) matched no outstanding request, typically late answers to timed-out requests. */
    public long unmatchedResponses() {
        return unmatched.sum();
    }

    public long undecodableResponses() {
        return undecodable.sum();
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /** Closes the socket; outstanding requests fail with {@link IOException}. */
    @Override
    public void close() {
        closed.set(true);
        closeSocket();
        try {
            readerThread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do: the connection is being discarded either way.
        }
    }
}
