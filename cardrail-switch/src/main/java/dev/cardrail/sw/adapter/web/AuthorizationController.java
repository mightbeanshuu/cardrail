package dev.cardrail.sw.adapter.web;

import dev.cardrail.sw.application.AuthorizationNotFoundException;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.CaptureResult;
import dev.cardrail.sw.application.CaptureService;
import dev.cardrail.sw.application.ReversalResult;
import dev.cardrail.sw.application.ReverseService;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.port.AuthorizationPage;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.AuthorizationSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
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

    record SearchResponse(List<AuthorizationView> items, int page, int size, long total) {
    }

    @GetMapping
    @Operation(summary = "Search authorizations, newest first",
            description = "Filters combine with AND. from is inclusive, to exclusive (ISO-8601 instants).")
    SearchResponse search(@RequestParam(required = false) AuthorizationStatus status,
                          @RequestParam(required = false) String responseCode,
                          @RequestParam(required = false) String merchantId,
                          @RequestParam(required = false) String last4,
                          @RequestParam(required = false) Boolean standIn,
                          @RequestParam(required = false) Instant from,
                          @RequestParam(required = false) Instant to,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "" + AuthorizationSearch.DEFAULT_SIZE) int size) {
        AuthorizationSearch search;
        try {
            search = new AuthorizationSearch(status, responseCode == null ? null : ResponseCode.fromCode(responseCode),
                    merchantId, last4, standIn, from, to, page, size);
        } catch (IllegalArgumentException e) {
            throw new InvalidSearchException(e.getMessage());
        }
        AuthorizationPage result = authorizations.search(search);
        return new SearchResponse(result.items().stream().map(AuthorizationView::of).toList(), page, size,
                result.total());
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
