package dev.cardrail.clearing.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Validated
@ConfigurationProperties("cardrail.settlement")
public record SettlementProperties(
        // Each chunk claims its records with one IN list, and Oracle allows at most 1000 items in one (ORA-01795).
        @DefaultValue("500") @Min(1) @Max(1000) int chunkSize,
        @DefaultValue("settlement-reports") @NotNull Path reportDirectory,
        @DefaultValue("CARDRAIL") @Pattern(regexp = "[A-Z0-9_]{1,16}") String networkId) {
}
