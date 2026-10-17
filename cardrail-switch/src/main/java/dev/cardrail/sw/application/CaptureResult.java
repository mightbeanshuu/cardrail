package dev.cardrail.sw.application;

import java.time.Instant;
import java.util.UUID;

/** @param releasedMinor the uncaptured remainder of the hold, returned to the cardholder */
public record CaptureResult(UUID authorizationId, long capturedMinor, long releasedMinor, Instant capturedAt) {
}
