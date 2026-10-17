package dev.cardrail.gateway;

import dev.cardrail.iso8583.FrameReader;
import dev.cardrail.iso8583.FrameWriter;
import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.IsoParseException;
import dev.cardrail.iso8583.Mti;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * One accepted socket. {@link #run()} is the read loop, on the connection's own virtual thread; each
 * request is processed on a virtual thread of its own, so responses go out in completion order.
 *
 * <p>A permit of {@link #inFlight} is held from dispatch until the handler has returned and the
 * response is written. The read loop takes the permit before dispatching, so when all are held it
 * stops reading and the peer is held back by TCP flow control instead of having requests dropped.
 */
final class GatewayConnection {

    private static final System.Logger LOG = System.getLogger(GatewayConnection.class.getName());

    private static final String APPROVED = "00";
    private static final String INVALID_TRANSACTION = "12";
    private static final String FORMAT_ERROR = "30";
    private static final String ISSUER_UNAVAILABLE = "91";
    private static final String SYSTEM_MALFUNCTION = "96";
    private static final String ECHO_TEST = "301";

    private final Socket socket;
    private final SocketAddress remote;
    private final GatewayContext ctx;
    private final GatewayConfig config;
    private final IsoCodec codec;
    private final CountingInputStream in;
    private final FrameReader reader;
    private final FrameWriter writer;
    private final Semaphore inFlight;
    private final Consumer<GatewayConnection> onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastActivityNanos = System.nanoTime();

    GatewayConnection(Socket socket, GatewayContext ctx, Consumer<GatewayConnection> onClosed) throws IOException {
        this.socket = socket;
        this.remote = socket.getRemoteSocketAddress();
        this.ctx = ctx;
        this.config = ctx.config();
        this.codec = ctx.codec();
        this.in = new CountingInputStream(new BufferedInputStream(socket.getInputStream()));
        this.reader = new FrameReader(in, config.maxFrameLength());
        this.writer = new FrameWriter(socket.getOutputStream());
        this.inFlight = new Semaphore(config.maxInFlightPerConnection());
        this.onClosed = onClosed;
    }

    SocketAddress remote() {
        return remote;
    }

    void run() {
        CloseReason reason;
        try {
            reason = readLoop();
        } catch (ProtocolException | EOFException e) {
            LOG.log(WARNING, "closing connection from {0}: {1}", remote, e.getMessage());
            reason = CloseReason.PROTOCOL_ERROR;
        } catch (IOException e) {
            if (ctx.stopping().getAsBoolean() || closed.get()) {
                reason = CloseReason.SHUTDOWN;
            } else {
                LOG.log(DEBUG, "read from {0} failed: {1}", remote, e.toString());
                reason = CloseReason.IO_ERROR;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = CloseReason.SHUTDOWN;
        }
        if (reason == CloseReason.PEER_CLOSED || reason == CloseReason.SHUTDOWN) {
            awaitOutstandingResponses();
        }
        close(reason);
    }

    private CloseReason readLoop() throws IOException, InterruptedException {
        long idleNanos = config.idleTimeout().toNanos();
        while (true) {
            long consumedBefore = in.count();
            Optional<byte[]> frame;
            try {
                frame = reader.read();
            } catch (SocketTimeoutException e) {
                if (in.count() != consumedBefore) {
                    throw new ProtocolException("peer stalled inside a frame for longer than the idle timeout");
                }
                if (ctx.stopping().getAsBoolean()) {
                    return CloseReason.SHUTDOWN;
                }
                boolean nothingOutstanding = inFlight.availablePermits() == config.maxInFlightPerConnection();
                if (nothingOutstanding && System.nanoTime() - lastActivityNanos >= idleNanos) {
                    return CloseReason.IDLE_TIMEOUT;
                }
                continue;
            }
            if (frame.isEmpty()) {
                return ctx.stopping().getAsBoolean() ? CloseReason.SHUTDOWN : CloseReason.PEER_CLOSED;
            }
            long receivedAt = System.nanoTime();
            lastActivityNanos = receivedAt;

            Inbound inbound = classify(frame.get());
            if (inbound == null) {
                continue;
            }
            inFlight.acquire();
            if (ctx.stopping().getAsBoolean()) {
                inFlight.release();
                return CloseReason.SHUTDOWN;
            }
            try {
                ctx.requestExecutor().execute(() -> process(inbound, receivedAt));
            } catch (RejectedExecutionException e) {
                inFlight.release();
                return CloseReason.SHUTDOWN;
            }
        }
    }

    /**
     * @return the work for this frame, or {@code null} to discard it
     * @throws ProtocolException if the frame cannot be answered, which ends the connection
     */
    private Inbound classify(byte[] frame) throws ProtocolException {
        IsoMessage message;
        try {
            message = codec.decode(frame);
        } catch (IsoParseException e) {
            Optional<IsoMessage> partial = MessageSalvage.salvage(frame, codec.spec());
            if (partial.isPresent() && partial.get().has(11)) {
                LOG.log(DEBUG, "format error from {0} in {1} at offset {2}; answering 30",
                        remote, where(e), e.offset());
                return new Inbound(partial.get(), true);
            }
            // Only positions are logged: the frame may contain a PAN.
            throw new ProtocolException("undecodable " + frame.length + "-byte frame (" + where(e) + " at offset "
                    + e.offset() + "), MTI or STAN not recoverable");
        }
        Mti.Function function = message.mti().function();
        if (function != Mti.Function.REQUEST && function != Mti.Function.ADVICE) {
            LOG.log(WARNING, "discarding MTI {0} from {1}: not a request or advice", message.mti(), remote);
            return null;
        }
        return new Inbound(message, false);
    }

    private static String where(IsoParseException e) {
        return e.field() == IsoParseException.NO_FIELD ? "header" : "field " + e.field();
    }

    private void process(Inbound inbound, long receivedAt) {
        try {
            IsoMessage request = inbound.request();
            if (inbound.formatError()) {
                write(request, IsoCodec.responseFor(request, FORMAT_ERROR, null), receivedAt);
            } else if (isNetworkManagementRequest(request.mti())) {
                String code = request.networkManagementCode().filter(ECHO_TEST::equals).isPresent()
                        ? APPROVED : INVALID_TRANSACTION;
                write(request, IsoCodec.responseFor(request, code, null), receivedAt);
            } else {
                callHandler(request, receivedAt);
            }
        } finally {
            inFlight.release();
        }
    }

    private static boolean isNetworkManagementRequest(Mti mti) {
        return mti.messageClass() == Mti.MessageClass.NETWORK_MANAGEMENT && mti.isRequest();
    }

    private void callHandler(IsoMessage request, long receivedAt) {
        CompletableFuture<IsoMessage> result = new CompletableFuture<>();
        Thread worker = ctx.handlerThreads().newThread(() -> {
            try {
                result.complete(ctx.handler().handle(request));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        worker.start();

        long remaining = config.requestTimeout().toNanos() - (System.nanoTime() - receivedAt);
        IsoMessage response;
        try {
            response = acceptable(request, result.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            worker.interrupt();
            LOG.log(DEBUG, "handler exceeded {0} for MTI {1} STAN {2}; answering 91",
                    config.requestTimeout(), request.mti(), request.stan().orElse("-"));
            response = IsoCodec.responseFor(request, ISSUER_UNAVAILABLE, null);
        } catch (ExecutionException e) {
            LOG.log(WARNING, "handler failed for MTI " + request.mti() + " STAN " + request.stan().orElse("-")
                    + "; answering 96", e.getCause());
            response = IsoCodec.responseFor(request, SYSTEM_MALFUNCTION, null);
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return;
        }
        write(request, response, receivedAt);
        try {
            // Keep the in-flight permit until the handler really returns, so a handler that ignores
            // interruption still counts against the connection's limit after its 91 has been sent.
            worker.join();
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    /** The handler's response if it can be sent as the answer to {@code request}, else a 96. */
    private IsoMessage acceptable(IsoMessage request, IsoMessage response) {
        String problem;
        if (response == null) {
            problem = "returned null";
        } else if (!response.mti().equals(request.mti().responseMti())) {
            problem = "returned MTI " + response.mti() + " for request MTI " + request.mti();
        } else if (!response.has(39)) {
            problem = "returned a response without field 39";
        } else if (!response.stan().equals(request.stan())) {
            problem = "returned a response whose STAN does not match the request";
        } else if (!response.spec().equals(codec.spec())) {
            problem = "returned a message built with a different MessageSpec";
        } else {
            return response;
        }
        LOG.log(WARNING, "handler {0} (MTI {1}); answering 96", problem, request.mti());
        return IsoCodec.responseFor(request, SYSTEM_MALFUNCTION, null);
    }

    private void write(IsoMessage request, IsoMessage response, long receivedAt) {
        try {
            writer.write(codec.encode(response));
        } catch (IOException e) {
            LOG.log(DEBUG, "write to {0} failed: {1}", remote, e.toString());
            close(CloseReason.IO_ERROR);
            return;
        }
        long now = System.nanoTime();
        lastActivityNanos = now;
        ctx.listener().requestCompleted(request.mti(), response.responseCode().orElseThrow(), now - receivedAt);
    }

    private void awaitOutstandingResponses() {
        try {
            if (!inFlight.tryAcquire(config.maxInFlightPerConnection(), config.drainTimeout().toNanos(),
                    TimeUnit.NANOSECONDS)) {
                LOG.log(WARNING, "closing {0} with requests still outstanding after {1}", remote, config.drainTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops reading new requests; the read loop then drains outstanding responses and closes. */
    void stopReading() {
        try {
            socket.shutdownInput();
        } catch (IOException e) {
            close(CloseReason.SHUTDOWN);
        }
    }

    void close(CloseReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(DEBUG, "closing {0}: {1}", remote, e.toString());
        }
        onClosed.accept(this);
        ctx.listener().connectionClosed(remote, reason);
    }

    private record Inbound(IsoMessage request, boolean formatError) {
        Inbound {
            Objects.requireNonNull(request, "request");
        }
    }

    /** Lets the read loop tell a timeout between frames (safe to retry) from one inside a frame. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
