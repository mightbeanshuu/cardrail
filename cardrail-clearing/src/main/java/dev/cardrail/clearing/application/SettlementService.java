package dev.cardrail.clearing.application;

import dev.cardrail.clearing.domain.BusinessCalendar;
import dev.cardrail.clearing.port.SettlementStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class SettlementService {

    private final SettlementJobs jobs;
    private final SettlementStore store;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public SettlementService(SettlementJobs jobs, SettlementStore store, ClearingProperties properties, Clock clock) {
        this.jobs = jobs;
        this.store = store;
        this.calendar = properties.calendar();
        this.clock = clock;
    }

    /**
     * Settles a business date once. A completed date returns its stored result without running
     * anything; a failed one resumes from the step that failed.
     */
    public SettlementResult settle(LocalDate businessDate) {
        LocalDate current = currentBusinessDate();
        if (businessDate.isAfter(current)) {
            throw new BusinessDateNotStartedException(businessDate, current);
        }
        Optional<JobRun> last = jobs.lastRun(businessDate);
        if (last.isPresent() && last.get().status() == RunStatus.COMPLETED) {
            return new SettlementResult(view(businessDate, last.get()), false);
        }
        if (last.isPresent() && last.get().status() == RunStatus.RUNNING) {
            throw new SettlementRunInProgressException(businessDate);
        }
        SettlementRun run = view(businessDate, jobs.launch(businessDate));
        if (run.status() == RunStatus.FAILED) {
            throw new SettlementRunFailedException(run);
        }
        return new SettlementResult(run, true);
    }

    /** The business date events arriving now belong to; it can be settled now, and later ones cannot. */
    public LocalDate currentBusinessDate() {
        return calendar.businessDateOf(clock.instant());
    }

    public SettlementRun find(LocalDate businessDate) {
        return jobs.lastRun(businessDate)
                .map(run -> view(businessDate, run))
                .orElseThrow(() -> new SettlementRunNotFoundException(businessDate));
    }

    public String report(LocalDate businessDate) {
        SettlementRun run = find(businessDate);
        if (run.status() != RunStatus.COMPLETED) {
            throw new SettlementReportNotReadyException(businessDate, run.status());
        }
        return SettlementReport.render(businessDate, run.positions());
    }

    private SettlementRun view(LocalDate businessDate, JobRun run) {
        return new SettlementRun(businessDate, run.status(), run.startedAt(), run.finishedAt(), run.failure(),
                store.positions(businessDate));
    }
}
