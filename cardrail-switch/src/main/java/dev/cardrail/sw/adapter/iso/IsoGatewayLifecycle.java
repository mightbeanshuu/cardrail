package dev.cardrail.sw.adapter.iso;

import dev.cardrail.gateway.GatewayConfig;
import dev.cardrail.gateway.GatewayListener;
import dev.cardrail.gateway.IsoGateway;
import dev.cardrail.gateway.IsoRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Runs the ISO 8583 gateway with the application context. It is in the last lifecycle phase, so it
 * starts accepting only once every other bean (including the web server) is up, and on shutdown it
 * is the first to stop: it drains in-flight requests while the database is still available.
 */
public final class IsoGatewayLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IsoGatewayLifecycle.class);

    private final IsoProperties properties;
    private final IsoRequestHandler handler;
    private final GatewayListener listener;
    private volatile IsoGateway gateway;

    public IsoGatewayLifecycle(IsoProperties properties, IsoRequestHandler handler, GatewayListener listener) {
        this.properties = properties;
        this.handler = handler;
        this.listener = listener;
    }

    @Override
    public synchronized void start() {
        if (gateway != null) {
            return;
        }
        IsoGateway started = new IsoGateway(config(), handler, listener);
        try {
            started.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen for ISO 8583 on " + properties.bindAddress() + ":"
                    + properties.port(), e);
        }
        gateway = started;
        log.info("ISO 8583 listening on {}:{}", properties.bindAddress(), started.port());
    }

    @Override
    public synchronized void stop() {
        IsoGateway running = gateway;
        if (running == null) {
            return;
        }
        gateway = null;
        try {
            if (!running.shutdown(properties.drainTimeout())) {
                log.warn("ISO 8583 gateway did not drain within {}", properties.drainTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return gateway != null;
    }

    /** The bound port; differs from the configured one when that is 0. */
    public int port() {
        IsoGateway running = gateway;
        if (running == null) {
            throw new IllegalStateException("ISO 8583 gateway is not running");
        }
        return running.port();
    }

    private GatewayConfig config() {
        InetAddress address;
        try {
            address = InetAddress.getByName(properties.bindAddress());
        } catch (UnknownHostException e) {
            throw new IllegalStateException("cardrail.iso.bind-address: cannot resolve " + properties.bindAddress(), e);
        }
        return GatewayConfig.defaults()
                .withBindAddress(address)
                .withPort(properties.port())
                .withMaxConnections(properties.maxConnections())
                .withMaxInFlightPerConnection(properties.maxInFlightPerConnection())
                .withRequestTimeout(properties.requestTimeout())
                .withIdleTimeout(properties.idleTimeout())
                .withDrainTimeout(properties.drainTimeout());
    }
}
