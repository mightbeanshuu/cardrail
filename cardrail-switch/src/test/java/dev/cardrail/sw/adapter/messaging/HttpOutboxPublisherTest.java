package dev.cardrail.sw.adapter.messaging;

import com.sun.net.httpserver.HttpServer;
import dev.cardrail.sw.port.OutboxMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOutboxPublisherTest {

    // The same vector is in cardrail-clearing's EventSignatureVerifierTest: both sides must agree on it.
    static final String VECTOR_SECRET = "cardrail-signature-test-vector-0123456789";
    static final String VECTOR_TIMESTAMP = "1792310400";
    static final String VECTOR_BODY =
            "{\"eventType\":\"authorization.captured\",\"authorizationId\":\"7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b\"}";
    static final String VECTOR_SIGNATURE = "70c60be37f4cc49e795e36ee2fec1b10ebc903ce596585fffd2f33a5db779828";

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_792_310_400L), ZoneOffset.UTC);

    private record Received(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> contentType = new AtomicReference<>("application/json");
    private final AtomicReference<String> answer = new AtomicReference<>("{\"outcome\":\"RECORDED\"}");
    private HttpServer clearing;

    @BeforeEach
    void startClearing() throws IOException {
        clearing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        clearing.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()), new String(body, StandardCharsets.UTF_8)));
            byte[] answer = this.answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType.get());
            exchange.sendResponseHeaders(status.get(), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        clearing.start();
    }

    @AfterEach
    void stopClearing() {
        clearing.stop(0);
    }

    @Test
    void signatureMatchesTheSharedTestVector() {
        SecretKeySpec key = new SecretKeySpec(VECTOR_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        assertThat(HttpOutboxPublisher.signature(key, VECTOR_TIMESTAMP, VECTOR_BODY.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(VECTOR_SIGNATURE);
    }

    @Test
    void postsTheEventWithTypeIdTimestampAndSignature() {
        publisher(baseUrl()).publish(new OutboxMessage(42, "authorization.captured", "tok", VECTOR_BODY, 0));

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/events");
            assertThat(request.body()).isEqualTo(VECTOR_BODY);
            assertThat(request.header("Content-Type")).isEqualTo("application/json");
            assertThat(request.header("Accept")).isEqualTo("application/json");
            assertThat(request.header("Cardrail-Event-Id")).isEqualTo("42");
            assertThat(request.header("Cardrail-Event-Type")).isEqualTo("authorization.captured");
            assertThat(request.header("Cardrail-Timestamp")).isEqualTo(VECTOR_TIMESTAMP);
            assertThat(request.header("Cardrail-Signature")).isEqualTo(VECTOR_SIGNATURE);
        });
    }

    @Test
    void anAnswerOtherThan2xxThrowsSoTheRowIsRetried() {
        status.set(401);

        assertThatThrownBy(() -> publisher(baseUrl())
                .publish(new OutboxMessage(7, "authorization.reversed", "tok", "{}", 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clearing answered 401 to outbox message 7");
    }

    @Test
    void a200ThatIsNotClearingsJsonIsNotTakenAsDelivery() {
        contentType.set("text/html; charset=utf-8");
        answer.set("<html>Service waking up</html>");

        assertThatThrownBy(() -> publisher(baseUrl())
                .publish(new OutboxMessage(10, "authorization.captured", "tok", "{}", 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not clearing's acknowledgement");
    }

    @Test
    void anUnreachableClearingServiceThrows() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> publisher(URI.create("http://127.0.0.1:" + closedPort))
                .publish(new OutboxMessage(8, "authorization.captured", "tok", "{}", 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clearing did not answer outbox message 8");
    }

    @Test
    void eventTypesClearingDoesNotConsumeAreNotSent() {
        publisher(baseUrl()).publish(new OutboxMessage(9, "authorization.approved", "tok", "{}", 0));

        assertThat(received).isEmpty();
    }

    @Test
    void refusesAShortSecret() {
        assertThatThrownBy(() -> new HttpOutboxPublisher(
                new HttpOutboxProperties(baseUrl(), "too-short", List.of("authorization.captured")),
                Duration.ofSeconds(1), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardrail.outbox.http.secret");
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + clearing.getAddress().getPort());
    }

    private static HttpOutboxPublisher publisher(URI baseUrl) {
        return new HttpOutboxPublisher(new HttpOutboxProperties(baseUrl, VECTOR_SECRET,
                List.of("authorization.captured", "authorization.reversed")), Duration.ofSeconds(2), CLOCK);
    }
}
