package dev.cardrail.sw.adapter.web;

import jakarta.validation.constraints.Positive;

/** @param amountMinor omit to capture the full authorized amount */
record CaptureRequestBody(@Positive Long amountMinor) {
}
