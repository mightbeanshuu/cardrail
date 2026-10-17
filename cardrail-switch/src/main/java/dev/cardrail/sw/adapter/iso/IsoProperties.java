package dev.cardrail.sw.adapter.iso;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * The ISO 8583 TCP listener, under {@code cardrail.iso}. See cardrail-gateway's {@code GatewayConfig}
 * for what each limit does. {@code cardrail.iso.enabled=false} leaves the listener out entirely.
 *
 * @param port        0 picks a free port (tests)
 * @param bindAddress host name or address to listen on; loopback unless deliberately exposed
 */
@ConfigurationProperties("cardrail.iso")
public record IsoProperties(
        int port,
        String bindAddress,
        int maxConnections,
        int maxInFlightPerConnection,
        Duration requestTimeout,
        Duration idleTimeout,
        Duration drainTimeout) {

    public IsoProperties {
        Objects.requireNonNull(bindAddress, "cardrail.iso.bind-address");
        Objects.requireNonNull(requestTimeout, "cardrail.iso.request-timeout");
        Objects.requireNonNull(idleTimeout, "cardrail.iso.idle-timeout");
        Objects.requireNonNull(drainTimeout, "cardrail.iso.drain-timeout");
    }
}
