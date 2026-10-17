package dev.cardrail.sw.adapter.iso;

import dev.cardrail.gateway.CloseReason;
import dev.cardrail.gateway.GatewayListener;
import dev.cardrail.iso8583.Mti;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway hooks as Micrometer meters. The hooks run on gateway I/O threads, and meter lookups are
 * concurrent-map reads, so nothing here blocks.
 *
 * <ul>
 *   <li>{@code cardrail.iso.connections} (gauge): connections open now</li>
 *   <li>{@code cardrail.iso.connections.closed} (counter, tag {@code reason})</li>
 *   <li>{@code cardrail.iso.connections.rejected} (counter): closed on accept at the connection limit</li>
 *   <li>{@code cardrail.iso.requests} (timer, tags {@code mti}, {@code response_code}): frame read to
 *       response written, so it includes queueing behind the per-connection in-flight limit</li>
 * </ul>
 */
final class MicrometerGatewayListener implements GatewayListener {

    private final MeterRegistry registry;
    private final AtomicInteger open = new AtomicInteger();
    private final Counter rejected;

    MicrometerGatewayListener(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("cardrail.iso.connections", open, AtomicInteger::get)
                .description("Open ISO 8583 connections")
                .register(registry);
        this.rejected = Counter.builder("cardrail.iso.connections.rejected")
                .description("Connections closed on accept because the connection limit was reached")
                .register(registry);
    }

    @Override
    public void connectionOpened(SocketAddress remote) {
        open.incrementAndGet();
    }

    @Override
    public void connectionRejected(SocketAddress remote) {
        rejected.increment();
    }

    @Override
    public void connectionClosed(SocketAddress remote, CloseReason reason) {
        open.decrementAndGet();
        Counter.builder("cardrail.iso.connections.closed")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }

    @Override
    public void requestCompleted(Mti requestMti, String responseCode, long nanos) {
        Timer.builder("cardrail.iso.requests")
                .description("ISO 8583 requests, from frame read to response written")
                .tag("mti", requestMti.value())
                .tag("response_code", responseCode)
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
