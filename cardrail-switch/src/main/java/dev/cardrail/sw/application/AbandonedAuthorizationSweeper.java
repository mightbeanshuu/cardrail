package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.AuthorizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * A request that failed or whose instance died between claiming its transmission key and recording
 * the decision leaves a PENDING row. Once it is much older than any request can take, it is declined
 * 96 and any hold the issuer placed for it is released, so retransmissions get a definite answer.
 */
@Component
public class AbandonedAuthorizationSweeper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedAuthorizationSweeper.class);
    private static final String ACTOR = "scheduler:abandoned-sweeper";

    private final AuthorizationRepository authorizations;
    private final AuthorizationRecorder recorder;
    private final HoldProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AbandonedAuthorizationSweeper(AuthorizationRepository authorizations, AuthorizationRecorder recorder,
                                         HoldProperties properties, TransactionTemplate transactions, Clock clock) {
        this.authorizations = authorizations;
        this.recorder = recorder;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cardrail.holds.sweep-interval}")
    public int sweep() {
        Instant cutoff = clock.instant().minus(properties.abandonedAfter());
        int swept = 0;
        // One per transaction: each may touch a different account, and these rows are rare.
        while (Boolean.TRUE.equals(transactions.execute(status -> sweepOne(cutoff)))) {
            swept++;
        }
        return swept;
    }

    private boolean sweepOne(Instant cutoff) {
        List<StoredAuthorization> abandoned = authorizations.lockAbandoned(cutoff, 1);
        if (abandoned.isEmpty()) {
            return false;
        }
        StoredAuthorization a = abandoned.getFirst();
        log.warn("authorization {} was left pending since {}, declining it as abandoned", a.id(), a.createdAt());
        recorder.recordDecision(a.id(), Decision.abandoned(), ACTOR);
        return true;
    }
}
