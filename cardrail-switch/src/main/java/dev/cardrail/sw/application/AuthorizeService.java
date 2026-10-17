package dev.cardrail.sw.application;

import dev.cardrail.sw.application.issuer.IssuerClient;
import dev.cardrail.sw.application.issuer.IssuerOutcome;
import dev.cardrail.sw.application.risk.RiskContext;
import dev.cardrail.sw.application.risk.RiskEngine;
import dev.cardrail.sw.application.standin.StandInProcessor;
import dev.cardrail.sw.domain.authorization.AuthCodes;
import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.card.PanMasking;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.standin.StandInDecision;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.InvalidCardNumberException;
import dev.cardrail.sw.port.IssuerRequest;
import dev.cardrail.sw.port.NewAuthorization;
import dev.cardrail.sw.port.TokenizedCard;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * The authorization pipeline: validate, tokenize, claim the transmission key, risk, issuer (or stand
 * in), record. No database transaction or connection is held while waiting on the issuer; the claim
 * and the final record are separate short transactions, with PENDING in between.
 */
@Service
public class AuthorizeService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizeService.class);
    private static final Pattern DIGITS = Pattern.compile("\\d{12,19}");

    private final RequestValidator validator;
    private final CardTokenizer tokenizer;
    private final PayloadFingerprint fingerprint;
    private final AuthorizationRepository authorizations;
    private final DuplicateResolver duplicates;
    private final RiskEngine risk;
    private final IssuerClient issuer;
    private final StandInProcessor standIn;
    private final AuthorizationRecorder recorder;
    private final SwitchMetrics metrics;
    private final Clock clock;
    private final RandomGenerator random = new SecureRandom();

    public AuthorizeService(RequestValidator validator, CardTokenizer tokenizer, PayloadFingerprint fingerprint,
                            AuthorizationRepository authorizations, DuplicateResolver duplicates, RiskEngine risk,
                            IssuerClient issuer, StandInProcessor standIn, AuthorizationRecorder recorder,
                            SwitchMetrics metrics, Clock clock) {
        this.validator = validator;
        this.tokenizer = tokenizer;
        this.fingerprint = fingerprint;
        this.authorizations = authorizations;
        this.duplicates = duplicates;
        this.risk = risk;
        this.issuer = issuer;
        this.standIn = standIn;
        this.recorder = recorder;
        this.metrics = metrics;
        this.clock = clock;
    }

    public AuthorizationResult authorize(AuthorizationRequest request) {
        Timer.Sample sample = metrics.startAuthorization();
        try (MDC.MDCCloseable rrn = MDC.putCloseable("rrn", request.rrn());
             MDC.MDCCloseable stan = MDC.putCloseable("stan", request.stan())) {
            AuthorizationResult result = process(request);
            metrics.authorizationCompleted(sample, result);
            log.info("authorization {} answered {} (stand-in: {})", result.id(), result.responseCode().code(),
                    result.standIn());
            return result;
        } finally {
            MDC.remove("authorizationId");
        }
    }

    private AuthorizationResult process(AuthorizationRequest request) {
        Instant now = clock.instant();
        Optional<RequestValidator.Rejection> rejection = validator.validate(request, now);
        if (rejection.isPresent() && !rejection.get().storable()) {
            log.info("format error in field group '{}'", rejection.get().reason());
            return new AuthorizationResult(null, rejection.get().code(), null, false, null, null);
        }
        String payloadHash = fingerprint.of(request);
        TokenizedCard card = null;
        try {
            card = tokenizer.tokenize(request.pan());
        } catch (InvalidCardNumberException e) {
            if (rejection.isEmpty()) {
                rejection = Optional.of(new RequestValidator.Rejection(ResponseCode.INVALID_CARD_NUMBER,
                        "invalid-card-number", true));
            }
        }
        String actor = "acquirer:" + request.acquirerId();
        UUID id = UUID.randomUUID();
        MDC.put("authorizationId", id.toString());

        if (rejection.isPresent()) {
            NewAuthorization row = NewAuthorization.rejected(id, request, payloadHash, card, maskedPan(card, request),
                    rejection.get().code(), rejection.get().reason(), now);
            TokenizedCard tokenized = card;
            return recorder.recordRejection(row, actor)
                    .orElseGet(() -> duplicates.resolve(request, payloadHash, tokenized));
        }

        if (authorizations.insertIfAbsent(NewAuthorization.pending(id, request, payloadHash, card, now)).isEmpty()) {
            return duplicates.resolve(request, payloadHash, card);
        }

        RiskOutcome riskOutcome = risk.evaluate(new RiskContext(id, request, card, now));
        if (riskOutcome instanceof RiskOutcome.Decline decline) {
            return recorder.recordDecision(id, Decision.riskDeclined(decline), actor);
        }

        IssuerOutcome outcome = issuer.authorize(new IssuerRequest(id, card.token(), request.amountMinor(),
                request.currency(), request.mcc(), request.merchantId()));
        Decision decision = switch (outcome) {
            case IssuerOutcome.Answered answered -> Decision.issuer(answered.decision());
            case IssuerOutcome.Unavailable unavailable -> {
                log.warn("issuer unavailable ({}: {}), standing in", unavailable.trigger(), unavailable.detail());
                StandInDecision stip = standIn.decide(request, card, riskOutcome, unavailable.trigger());
                yield Decision.standIn(stip, AuthCodes.next(random), unavailable.trigger());
            }
        };
        return recorder.recordDecision(id, decision, actor);
    }

    private static String maskedPan(TokenizedCard card, AuthorizationRequest request) {
        if (card != null) {
            return card.maskedPan();
        }
        return DIGITS.matcher(request.pan()).matches() ? PanMasking.mask(request.pan()) : null;
    }
}
