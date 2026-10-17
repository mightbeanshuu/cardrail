package dev.cardrail.gateway;

import dev.cardrail.iso8583.FieldSpec;
import dev.cardrail.iso8583.FrameReader;
import dev.cardrail.iso8583.MessageSpec;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;

/**
 * Gateway settings. Start from {@link #defaults()} and override with the {@code with*} methods.
 *
 * @param bindAddress              local address to listen on; the default is loopback, so exposing the
 *                                 port on a network interface is an explicit decision
 * @param port                     TCP port; 0 picks an ephemeral port (see {@link IsoGateway#port()})
 * @param backlog                  listen backlog passed to the OS
 * @param maxConnections           connections accepted beyond this are closed immediately
 * @param maxInFlightPerConnection requests from one connection being processed at once; when reached the
 *                                 gateway stops reading that socket until one completes
 * @param maxFrameLength           largest frame accepted; a longer header closes the connection before
 *                                 the payload is read
 * @param requestTimeout           deadline for the handler, measured from the frame being read; past it
 *                                 the request is answered {@code 91}
 * @param idleTimeout              a connection with no request received and none outstanding for this
 *                                 long is closed
 * @param drainTimeout             how long a closing connection waits for its outstanding responses
 */
public record GatewayConfig(
        InetAddress bindAddress,
        int port,
        int backlog,
        int maxConnections,
        int maxInFlightPerConnection,
        int maxFrameLength,
        Duration requestTimeout,
        Duration idleTimeout,
        Duration drainTimeout) {

    public GatewayConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port must be 0-65535, got " + port);
        }
        requirePositive("backlog", backlog);
        requirePositive("maxConnections", maxConnections);
        requirePositive("maxInFlightPerConnection", maxInFlightPerConnection);
        if (maxFrameLength < 1 || maxFrameLength > FrameReader.MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be 1-" + FrameReader.MAX_HEADER_LENGTH);
        }
        requirePositive("requestTimeout", requestTimeout);
        requirePositive("idleTimeout", idleTimeout);
        requirePositive("drainTimeout", drainTimeout);
    }

    /**
     * Loopback, port 8583, backlog 256, 1024 connections, 64 in flight per connection, 5 s request deadline,
     * 5 min idle timeout, 10 s drain, and a frame limit equal to the largest message the default
     * {@link MessageSpec} can encode.
     */
    public static GatewayConfig defaults() {
        return new GatewayConfig(InetAddress.getLoopbackAddress(), 8583, 256, 1024, 64,
                largestEncodableMessage(MessageSpec.defaultSpec()),
                Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofSeconds(10));
    }

    /** MTI, both bitmaps if needed, and every field at its maximum length with its length prefix. */
    static int largestEncodableMessage(MessageSpec spec) {
        int length = 4 + 16;
        boolean secondary = false;
        for (FieldSpec f : spec.fields()) {
            length += f.lengthType().prefixDigits() + f.length();
            secondary |= f.number() > 64;
        }
        if (secondary) {
            length += 16;
        }
        return Math.min(length, FrameReader.MAX_HEADER_LENGTH);
    }

    public GatewayConfig withBindAddress(InetAddress v) {
        return new GatewayConfig(v, port, backlog, maxConnections, maxInFlightPerConnection, maxFrameLength,
                requestTimeout, idleTimeout, drainTimeout);
    }

    public GatewayConfig withPort(int v) {
        return new GatewayConfig(bindAddress, v, backlog, maxConnections, maxInFlightPerConnection, maxFrameLength,
                requestTimeout, idleTimeout, drainTimeout);
    }

    public GatewayConfig withBacklog(int v) {
        return new GatewayConfig(bindAddress, port, v, maxConnections, maxInFlightPerConnection, maxFrameLength,
                requestTimeout, idleTimeout, drainTimeout);
    }

    public GatewayConfig withMaxConnections(int v) {
        return new GatewayConfig(bindAddress, port, backlog, v, maxInFlightPerConnection, maxFrameLength,
                requestTimeout, idleTimeout, drainTimeout);
    }

    public GatewayConfig withMaxInFlightPerConnection(int v) {
        return new GatewayConfig(bindAddress, port, backlog, maxConnections, v, maxFrameLength,
                requestTimeout, idleTimeout, drainTimeout);
    }

    public GatewayConfig withMaxFrameLength(int v) {
        return new GatewayConfig(bindAddress, port, backlog, maxConnections, maxInFlightPerConnection, v,
                requestTimeout, idleTimeout, drainTimeout);
    }

    public GatewayConfig withRequestTimeout(Duration v) {
        return new GatewayConfig(bindAddress, port, backlog, maxConnections, maxInFlightPerConnection, maxFrameLength,
                v, idleTimeout, drainTimeout);
    }

    public GatewayConfig withIdleTimeout(Duration v) {
        return new GatewayConfig(bindAddress, port, backlog, maxConnections, maxInFlightPerConnection, maxFrameLength,
                requestTimeout, v, drainTimeout);
    }

    public GatewayConfig withDrainTimeout(Duration v) {
        return new GatewayConfig(bindAddress, port, backlog, maxConnections, maxInFlightPerConnection, maxFrameLength,
                requestTimeout, idleTimeout, v);
    }

    private static void requirePositive(String name, int v) {
        if (v < 1) {
            throw new IllegalArgumentException(name + " must be positive, got " + v);
        }
    }

    private static void requirePositive(String name, Duration v) {
        Objects.requireNonNull(v, name);
        if (v.isNegative() || v.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, got " + v);
        }
    }
}
