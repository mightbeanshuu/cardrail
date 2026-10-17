package dev.cardrail.clearing.application;

import java.time.LocalDate;
import java.util.Optional;

/** Runs the end-of-day settlement job. One job instance per business date. */
public interface SettlementJobs {

    Optional<JobRun> lastRun(LocalDate businessDate);

    /**
     * Runs the job to completion or failure: a fresh run for a new date, a resume for a failed one.
     *
     * @throws SettlementRunInProgressException if a run for the date is executing now
     */
    JobRun launch(LocalDate businessDate);
}
