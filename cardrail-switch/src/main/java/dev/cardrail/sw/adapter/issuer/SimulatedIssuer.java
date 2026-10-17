package dev.cardrail.sw.adapter.issuer;

import dev.cardrail.sw.domain.authorization.AuthCodes;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.port.HoldPlacement;
import dev.cardrail.sw.port.IssuerDecision;
import dev.cardrail.sw.port.IssuerGateway;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.port.IssuerRequest;
import dev.cardrail.sw.port.IssuerUnavailableException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * An in-process issuer backed by the ledger: approval means the hold was placed. Latency is injected
 * before the account is touched, so a call cancelled by the switch's time limiter while sleeping
 * never places a hold; one cancelled later is cleaned up by {@link IssuerLedger#voidHold}.
 */
@Component
public class SimulatedIssuer implements IssuerGateway {

    private final IssuerLedger ledger;
    private final SimulatedIssuerFaults faults;
    private final RandomGenerator random = new SecureRandom();
    private final AtomicLong calls = new AtomicLong();

    public SimulatedIssuer(IssuerLedger ledger, SimulatedIssuerFaults faults) {
        this.ledger = ledger;
        this.faults = faults;
    }

    @Override
    public IssuerDecision authorize(IssuerRequest request) {
        calls.incrementAndGet();
        sleep(faults.current().latency());
        if (faults.shouldFail()) {
            throw new IssuerUnavailableException("simulated issuer failure");
        }
        HoldPlacement placement = ledger.placeHold(request.authorizationId(), request.token(), request.amountMinor(),
                request.currency());
        return switch (placement) {
            case PLACED -> IssuerDecision.approved(AuthCodes.next(random));
            case INSUFFICIENT_FUNDS -> IssuerDecision.declined(ResponseCode.INSUFFICIENT_FUNDS);
            case ACCOUNT_BLOCKED, ALREADY_VOIDED -> IssuerDecision.declined(ResponseCode.DO_NOT_HONOR);
            case NO_ACCOUNT -> IssuerDecision.declined(ResponseCode.INVALID_CARD_NUMBER);
            case CURRENCY_MISMATCH -> IssuerDecision.declined(ResponseCode.INVALID_TRANSACTION);
        };
    }

    /** Number of calls received, including ones that then failed or timed out. */
    public long calls() {
        return calls.get();
    }

    private static void sleep(Duration latency) {
        if (latency.isZero()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IssuerUnavailableException("cancelled while waiting", e);
        }
    }
}
