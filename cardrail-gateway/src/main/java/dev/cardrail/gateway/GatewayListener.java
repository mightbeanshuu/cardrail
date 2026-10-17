package dev.cardrail.gateway;

import dev.cardrail.iso8583.Mti;

import java.net.SocketAddress;

/**
 * Observation hooks, so metrics can be bound without the gateway depending on a metrics library.
 *
 * <p>Methods are called on gateway threads, concurrently, and must not block. An exception thrown
 * from a hook is logged and otherwise ignored.
 */
public interface GatewayListener {

    GatewayListener NOOP = new GatewayListener() { };

    default void connectionOpened(SocketAddress remote) {
    }

    /** A connection was accepted and immediately closed because {@link GatewayConfig#maxConnections()} was reached. */
    default void connectionRejected(SocketAddress remote) {
    }

    default void connectionClosed(SocketAddress remote, CloseReason reason) {
    }

    /**
     * A response was written.
     *
     * @param requestMti   MTI of the request being answered
     * @param responseCode field 39 of the response as sent
     * @param nanos        time from the request frame being read to the response being written
     */
    default void requestCompleted(Mti requestMti, String responseCode, long nanos) {
    }
}
