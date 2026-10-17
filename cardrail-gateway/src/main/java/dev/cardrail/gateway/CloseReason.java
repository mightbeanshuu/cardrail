package dev.cardrail.gateway;

/** Why the gateway closed a connection. */
public enum CloseReason {
    /** The peer closed its side; responses to requests already read were still sent. */
    PEER_CLOSED,
    /** No request arrived and none was outstanding for {@link GatewayConfig#idleTimeout()}. */
    IDLE_TIMEOUT,
    /** A frame could not be read or decoded and no response could be built for it. */
    PROTOCOL_ERROR,
    /** Reading from or writing to the socket failed. */
    IO_ERROR,
    /** The gateway is shutting down. */
    SHUTDOWN
}
