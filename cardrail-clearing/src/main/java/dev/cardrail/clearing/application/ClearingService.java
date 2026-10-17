package dev.cardrail.clearing.application;

import dev.cardrail.clearing.domain.BusinessCalendar;
import dev.cardrail.clearing.domain.ClearingRecord;
import dev.cardrail.clearing.domain.InterchangeRate;
import dev.cardrail.clearing.port.ClearingRecords;
import dev.cardrail.clearing.port.InterchangeRates;
import dev.cardrail.clearing.port.ProcessedEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Turns switch events into clearing records. Each event is handled in one transaction that writes
 * the business rows and the processed-event marker together, so a redelivered event either finds
 * the marker and does nothing, or the earlier attempt never committed anything.
 */
@Service
public class ClearingService {

    public static final String CAPTURED = "authorization.captured";
    public static final String REVERSED = "authorization.reversed";

    private static final Logger log = LoggerFactory.getLogger(ClearingService.class);

    private record Result(ProcessedEvents.Outcome outcome, String detail) {
    }

    private final ClearingRecords records;
    private final ProcessedEvents processed;
    private final InterchangeRates rates;
    private final BusinessCalendar calendar;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public ClearingService(ClearingRecords records, ProcessedEvents processed, InterchangeRates rates,
                           ClearingProperties properties, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.records = records;
        this.processed = processed;
        this.rates = rates;
        this.calendar = properties.calendar();
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public EventOutcome onCaptured(CapturedAuthorization c, String source) {
        return once(c.authorizationId(), CAPTURED, source, () -> {
            InterchangeRate rate = rates.rateFor(c.mcc());
            LocalDate businessDate = calendar.businessDateOf(c.occurredAt());
            records.insert(ClearingRecord.capture(c.authorizationId(), c.token(), c.maskedPan(), c.acquirerId(),
                    c.merchantId(), c.mcc(), c.currency(), c.capturedAmountMinor(), rate, businessDate,
                    c.occurredAt(), clock.instant()));
            return new Result(ProcessedEvents.Outcome.RECORDED, "business date " + businessDate);
        });
    }

    public EventOutcome onReversed(ReversedAuthorization r, String source) {
        return once(r.authorizationId(), REVERSED, source, () -> {
            Optional<ClearingRecord> capture = records.findCapture(r.authorizationId());
            if (capture.isEmpty()) {
                return new Result(ProcessedEvents.Outcome.IGNORED, "no capture to offset");
            }
            LocalDate businessDate = calendar.businessDateOf(r.occurredAt());
            if (!capture.get().businessDate().equals(businessDate)) {
                return new Result(ProcessedEvents.Outcome.IGNORED, "capture is in business date "
                        + capture.get().businessDate() + ", reversal in " + businessDate
                        + "; a cleared capture is undone by a refund or chargeback, not a reversal");
            }
            records.insert(capture.get().offset(r.occurredAt(), clock.instant()));
            return new Result(ProcessedEvents.Outcome.RECORDED, "offset in business date " + businessDate);
        });
    }

    private EventOutcome once(UUID authorizationId, String eventType, String source, Supplier<Result> work) {
        EventOutcome outcome;
        try {
            outcome = tx.execute(status -> {
                if (processed.contains(authorizationId, eventType)) {
                    return EventOutcome.DUPLICATE;
                }
                Result result = work.get();
                processed.add(authorizationId, eventType, result.outcome(), result.detail(), source, clock.instant());
                return EventOutcome.valueOf(result.outcome().name());
            });
        } catch (DuplicateKeyException e) {
            // A concurrent delivery of the same event committed first: the unique index made us wait for
            // its commit before failing, so its rows are durable and this one has nothing to add.
            outcome = EventOutcome.DUPLICATE;
        }
        log.info("{} {} from {}: {}", eventType, authorizationId, source, outcome);
        counter(eventType, outcome).increment();
        return outcome;
    }

    private Counter counter(String eventType, EventOutcome outcome) {
        return Counter.builder("cardrail.clearing.events")
                .description("Switch events consumed, by type and outcome")
                .tag("type", eventType)
                .tag("outcome", outcome.name())
                .register(meters);
    }
}
