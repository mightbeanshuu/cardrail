# cardrail-gateway

ISO 8583 TCP server for cardrail. It reads length-prefixed frames (see `cardrail-iso8583`), hands
decoded requests to an `IsoRequestHandler` and writes the responses back on the same connection.
Java 21, no runtime dependencies besides `cardrail-iso8583`.

```java
IsoRequestHandler handler = request -> IsoCodec.responseFor(request, "00", "123456");
IsoGateway gateway = new IsoGateway(GatewayConfig.defaults().withPort(8583), handler, GatewayListener.NOOP);
gateway.start();                                // binds, then accepts on a virtual thread
...
boolean drained = gateway.shutdown(Duration.ofSeconds(10));
```

## Behaviour

- **Threads.** One virtual thread reads each connection; every request runs on a virtual thread of
  its own, so requests on one connection are pipelined and responses go out in completion order.
  The acquirer matches them by STAN. All writes for a connection go through one `FrameWriter`.
- **Backpressure.** At most `maxInFlightPerConnection` requests per connection are processed at
  once. When the limit is reached the gateway stops reading that socket, so the client is slowed
  by TCP flow control; nothing is dropped. A slot is freed only when the handler has actually
  returned, even if the request was already answered `91`.
- **Network management.** `0800` with field 70 = `301` (echo) is answered `0810`/`00` without calling
  the handler; other network management requests (e.g. F70 `001` sign-on) get `12`.
- **Errors.**

  | Situation | Result |
  |---|---|
  | Message rejected by the codec, but MTI and STAN readable | response with F39 `30`, echoing the fields read before the defect |
  | MTI or STAN not recoverable, empty or oversized frame, EOF inside a frame | connection closed; the log names the field and offset, never payload bytes |
  | Handler throws, returns `null`, or returns a response with the wrong MTI, no F39 or another STAN | F39 `96` |
  | Handler exceeds `requestTimeout` (measured from frame receipt) | F39 `91`; the handler thread is interrupted and its eventual result discarded |
  | Received MTI is not a request or advice (e.g. a stray `0110`) | logged and discarded |

- **Connections.** Beyond `maxConnections` a new connection is closed on accept. A connection with
  nothing received and nothing outstanding for `idleTimeout` is closed; a slow response does not
  count as idle. When the peer closes its side, responses still being processed are written
  before the socket is closed.
- **Shutdown.** `shutdown(timeout)` stops accepting, shuts down input on every connection, waits for
  requests already read to be answered, then closes. At the deadline it closes whatever is left
  and interrupts the remaining handler threads; the return value says whether it drained in time.

## Configuration

`GatewayConfig.defaults()`: loopback, port 8583, backlog 256, 1024 connections, 64 in flight per
connection, 5 s request deadline, 5 min idle timeout, 10 s drain timeout, and a frame limit equal
to the largest message the default `MessageSpec` can encode. Each value has a `with*` method.
The default bind address is loopback, so listening on a network interface is an explicit choice.

## Metrics hooks

`GatewayListener` has `connectionOpened`, `connectionRejected`, `connectionClosed(remote, CloseReason)`
and `requestCompleted(requestMti, responseCode, nanos)`, with `nanos` from frame read to response
written. Hooks are called on gateway threads and must not block; an exception from a hook is logged
and ignored. This keeps the gateway free of a metrics dependency (the switch binds Micrometer).

Logging uses `System.Logger`, so it ends up wherever the host application routes JDK platform logging.

## Tests

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -q -pl cardrail-gateway -am verify
```

`IsoGatewayTest` covers echo without the handler, 0100 through the handler, out-of-order pipelined
responses, the in-flight limit, format error `30`, unrecoverable frames, `96` on handler failure or
an unmatchable response, `91` with the late result discarded, idle timeout, the connection limit,
graceful shutdown draining in-flight requests, and 50 connections x 200 pipelined requests.
`MessageSalvagePropertyTest` (jqwik) checks that recovering fields from a rejected message never
throws on arbitrary bytes and always recovers the STAN when the damage comes after it.
