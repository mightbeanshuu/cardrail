package dev.cardrail.sw.adapter.issuer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
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
import java.time.Instant;

/**
 * Dev and demo profiles: toggles the simulated issuer's latency and failure rate at runtime, so a
 * visitor of the public demo can watch stand-in processing take over.
 */
@Profile("dev | demo")
@RestController
@RequestMapping("/dev/issuer/faults")
@Tag(name = "dev: simulated issuer")
class SimulatedIssuerAdminController {

    private final SimulatedIssuerFaults faults;

    SimulatedIssuerAdminController(SimulatedIssuerFaults faults) {
        this.faults = faults;
    }

    /** 10 s is well past the issuer time limit; more would only hold request threads for nothing. */
    record FaultSettings(@NotNull @Min(0) @Max(10_000) Long latencyMs,
                         @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double failureRate) {
    }

    /** @param resetsAt when the configured settings come back by themselves; absent if they do not */
    record FaultView(long latencyMs, double failureRate, Instant resetsAt) {
    }

    @GetMapping
    @Operation(summary = "Current fault settings")
    FaultView get() {
        return view();
    }

    @PutMapping
    @Operation(summary = "Set latency and failure rate")
    FaultView set(@Valid @RequestBody FaultSettings settings) {
        faults.set(new SimulatedIssuerFaults.Settings(Duration.ofMillis(settings.latencyMs()), settings.failureRate()));
        return view();
    }

    @DeleteMapping
    @Operation(summary = "Restore the configured settings")
    FaultView reset() {
        faults.reset();
        return view();
    }

    private FaultView view() {
        SimulatedIssuerFaults.Settings s = faults.current();
        return new FaultView(s.latency().toMillis(), s.failureRate(), faults.resetsAt().orElse(null));
    }
}
