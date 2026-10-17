package dev.cardrail.gateway;

import dev.cardrail.iso8583.IsoMessage;

/**
 * Business logic behind the gateway: turns one decoded request into its response.
 *
 * <p>Called on its own virtual thread for every request except {@code 0800} network management,
 * which the gateway answers itself. Calls overlap, including calls for requests from the same
 * connection, so implementations must be thread-safe. Blocking is fine. When a call overruns the
 * request deadline the gateway answers {@code 91}, interrupts the calling thread and discards
 * whatever the call eventually returns.
 *
 * <p>The returned message must carry field 39, use the response MTI of the request and echo its
 * STAN; {@link dev.cardrail.iso8583.IsoCodec#responseFor} produces such a message. Anything else, a
 * {@code null} return, or a thrown exception is answered with {@code 96}. A thrown exception is
 * logged with its message and stack trace, so it must not carry a PAN.
 */
@FunctionalInterface
public interface IsoRequestHandler {

    IsoMessage handle(IsoMessage request);
}
