package dev.cardrail.sw.adapter.web;

import dev.cardrail.sw.application.AuthorizationNotFoundException;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.CaptureResult;
import dev.cardrail.sw.application.CaptureService;
import dev.cardrail.sw.application.ReversalResult;
import dev.cardrail.sw.application.ReverseService;
import dev.cardrail.sw.port.AuthorizationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/authorizations")
@Tag(name = "authorizations")
class AuthorizationController {

    private static final String ACTOR = "api";

    private final AuthorizeService authorize;
    private final ReverseService reverse;
    private final CaptureService capture;
    private final AuthorizationRepository authorizations;

    AuthorizationController(AuthorizeService authorize, ReverseService reverse, CaptureService capture,
                            AuthorizationRepository authorizations) {
        this.authorize = authorize;
        this.reverse = reverse;
        this.capture = capture;
        this.authorizations = authorizations;
    }

    @PostMapping
    @Operation(summary = "Authorize a card payment",
            description = "Always HTTP 200 when the switch reached a decision, approved or not; see responseCode.")
    AuthorizationResponseBody authorize(@Valid @RequestBody AuthorizationRequestBody body) {
        return AuthorizationResponseBody.of(authorize.authorize(body.toDomain()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Look up an authorization")
    AuthorizationView get(@PathVariable UUID id) {
        return authorizations.findById(id)
                .map(AuthorizationView::of)
                .orElseThrow(() -> new AuthorizationNotFoundException(id.toString()));
    }

    @PostMapping("/{id}/reversal")
    @Operation(summary = "Reverse an authorization and release its hold (idempotent)")
    ReversalResult reverse(@PathVariable UUID id) {
        return reverse.reverse(id, ACTOR);
    }

    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture all or part of an authorization; the remainder is released")
    CaptureResult capture(@PathVariable UUID id, @Valid @RequestBody(required = false) CaptureRequestBody body) {
        return capture.capture(id, body == null ? null : body.amountMinor(), ACTOR);
    }
}
