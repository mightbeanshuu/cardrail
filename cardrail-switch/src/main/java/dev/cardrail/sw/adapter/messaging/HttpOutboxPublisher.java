package dev.cardrail.sw.adapter.messaging;

import dev.cardrail.sw.port.OutboxMessage;
import dev.cardrail.sw.port.OutboxPublisher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Posts outbox events to the clearing service's {@code POST /v1/events}, for deployments without a
 * broker. Each request carries an HMAC-SHA256 signature over {@code timestamp + "." + body}, keyed
 * with a secret shared with clearing, which rejects a bad signature or a stale timestamp. Anything
 * but clearing's JSON acknowledgement throws, so the relay leaves the row unpublished and retries it,
 * as it does for Kafka.
 *
 * <p>Only the event types clearing consumes are sent. The others have no subscriber on this
 * transport, like a topic nobody reads, and are acknowledged without a request.
 */
public class HttpOutboxPublisher implements OutboxPublisher {

    public static final String EVENTS_PATH = "/v1/events";
    public static final String EVENT_ID_HEADER = "Cardrail-Event-Id";
    public static final String EVENT_TYPE_HEADER = "Cardrail-Event-Type";
    public static final String TIMESTAMP_HEADER = "Cardrail-Timestamp";
    public static final String SIGNATURE_HEADER = "Cardrail-Signature";

    private static final int MIN_SECRET_LENGTH = 32;
    private static final int MAX_ERROR_BODY = 200;

    private final HttpClient http;
    private final URI endpoint;
    private final SecretKeySpec key;
    private final Set<String> eventTypes;
    private final Duration timeout;
    private final Clock clock;

    public HttpOutboxPublisher(HttpOutboxProperties properties, Duration timeout, Clock clock) {
        Objects.requireNonNull(properties.baseUrl(), "cardrail.outbox.http.base-url");
        if (properties.secret() == null || properties.secret().length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("cardrail.outbox.http.secret must be at least "
                    + MIN_SECRET_LENGTH + " characters");
        }
        this.endpoint = properties.baseUrl().resolve(EVENTS_PATH);
        this.key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.eventTypes = Set.copyOf(properties.eventTypes());
        this.timeout = timeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public void publish(OutboxMessage message) {
        if (!eventTypes.contains(message.eventType())) {
            return;
        }
        byte[] body = message.payloadJson().getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(EVENT_ID_HEADER, Long.toString(message.id()))
                .header(EVENT_TYPE_HEADER, message.eventType())
                .header(TIMESTAMP_HEADER, timestamp)
                .header(SIGNATURE_HEADER, signature(key, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing outbox message " + message.id(), e);
        } catch (IOException e) {
            throw new IllegalStateException("clearing did not answer outbox message " + message.id(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("clearing answered " + response.statusCode() + " to outbox message "
                    + message.id() + ": " + abbreviate(response.body()));
        }
        // A free Render instance that is still waking up can be answered for by the platform's own
        // page; only a JSON answer is clearing's acknowledgement.
        if (!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
            throw new IllegalStateException("outbox message " + message.id() + " got a " + response.statusCode()
                    + " that is not clearing's acknowledgement");
        }
    }

    /** Lower-case hex HMAC-SHA256 of {@code timestamp + "." + body}. */
    public static String signature(SecretKeySpec key, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is available on every JDK", e);
        }
    }

    private static String abbreviate(String body) {
        return body.length() <= MAX_ERROR_BODY ? body : body.substring(0, MAX_ERROR_BODY) + "...";
    }
}
