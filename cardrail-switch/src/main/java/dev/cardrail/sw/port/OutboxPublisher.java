package dev.cardrail.sw.port;

public interface OutboxPublisher {

    /** Returns only once the broker has acknowledged the message; throws if it did not. */
    void publish(OutboxMessage message);
}
