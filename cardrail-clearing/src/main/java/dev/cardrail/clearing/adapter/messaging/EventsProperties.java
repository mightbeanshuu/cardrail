package dev.cardrail.clearing.adapter.messaging;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("cardrail.events")
public record EventsProperties(
        @DefaultValue("cardrail.") @NotBlank String topicPrefix,
        @DefaultValue("cardrail.clearing.dlt") @NotBlank String deadLetterTopic,
        @DefaultValue("3") @Min(1) int deadLetterPartitions,
        @DefaultValue("1") @Min(1) short deadLetterReplicas,
        @DefaultValue Retry retry) {

    /** Back-off for failures that may pass, such as the database being briefly unavailable. */
    public record Retry(
            @DefaultValue("4") @Min(0) int maxRetries,
            @DefaultValue("500ms") Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("10s") Duration maxInterval) {
    }
}
