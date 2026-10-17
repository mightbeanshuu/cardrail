package dev.cardrail.sw.adapter.issuer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Dev profile only: toggles the simulated issuer's latency and failure rate at runtime. */
@Profile("dev")
@RestController
@RequestMapping("/dev/issuer/faults")
@Tag(name = "dev: simulated issuer")
class SimulatedIssuerAdminController {

    private final SimulatedIssuerFaults faults;

    SimulatedIssuerAdminController(SimulatedIssuerFaults faults) {
        this.faults = faults;
    }

    record FaultSettings(@NotNull @Min(0) Long latencyMs,
                         @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double failureRate) {
    }

    @GetMapping
    @Operation(summary = "Current fault settings")
    FaultSettings get() {
        return view(faults.current());
    }

    @PutMapping
    @Operation(summary = "Set latency and failure rate")
    FaultSettings set(@Valid @RequestBody FaultSettings settings) {
        faults.set(new SimulatedIssuerFaults.Settings(Duration.ofMillis(settings.latencyMs()), settings.failureRate()));
        return view(faults.current());
    }

    @DeleteMapping
    @Operation(summary = "Restore the configured settings")
    FaultSettings reset() {
        faults.reset();
        return view(faults.current());
    }

    private static FaultSettings view(SimulatedIssuerFaults.Settings s) {
        return new FaultSettings(s.latency().toMillis(), s.failureRate());
    }
}
