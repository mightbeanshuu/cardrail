package dev.cardrail.clearing.adapter.batch;

import dev.cardrail.clearing.application.JobRun;
import dev.cardrail.clearing.application.RunStatus;
import dev.cardrail.clearing.application.SettlementJobs;
import dev.cardrail.clearing.application.SettlementRunInProgressException;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The business date is the job's only identifying parameter, so each date is one JobInstance:
 * Spring Batch itself refuses to run a completed instance again and resumes a failed one.
 */
@Component
class BatchSettlementJobs implements SettlementJobs {

    private static final int MAX_FAILURE_LENGTH = 500;

    private final JobLauncher launcher;
    private final JobRepository repository;
    private final Job settlementJob;

    BatchSettlementJobs(JobLauncher launcher, JobRepository repository, Job settlementJob) {
        this.launcher = launcher;
        this.repository = repository;
        this.settlementJob = settlementJob;
    }

    static JobParameters parameters(LocalDate businessDate) {
        return new JobParametersBuilder()
                .addLocalDate(SettlementJobConfiguration.BUSINESS_DATE, businessDate, true)
                .toJobParameters();
    }

    @Override
    public Optional<JobRun> lastRun(LocalDate businessDate) {
        return Optional.ofNullable(repository.getLastJobExecution(SettlementJobConfiguration.JOB, parameters(businessDate)))
                .map(BatchSettlementJobs::toRun);
    }

    @Override
    public JobRun launch(LocalDate businessDate) {
        try {
            return toRun(launcher.run(settlementJob, parameters(businessDate)));
        } catch (JobExecutionAlreadyRunningException e) {
            throw new SettlementRunInProgressException(businessDate);
        } catch (JobInstanceAlreadyCompleteException e) {
            return lastRun(businessDate).orElseThrow();
        } catch (JobRestartException | JobParametersInvalidException e) {
            throw new IllegalStateException("settlement job for " + businessDate + " cannot be launched", e);
        }
    }

    private static JobRun toRun(JobExecution execution) {
        BatchStatus status = execution.getStatus();
        RunStatus runStatus = status == BatchStatus.COMPLETED ? RunStatus.COMPLETED
                : status.isRunning() ? RunStatus.RUNNING
                : RunStatus.FAILED;
        return new JobRun(runStatus, instant(execution.getStartTime()), instant(execution.getEndTime()),
                runStatus == RunStatus.FAILED ? failure(execution) : null);
    }

    private static String failure(JobExecution execution) {
        String description = execution.getExitStatus().getExitDescription();
        if (description == null || description.isBlank()) {
            description = execution.getStepExecutions().stream()
                    .filter(s -> s.getStatus() == BatchStatus.FAILED)
                    .map(StepExecution::getExitStatus)
                    .map(e -> e.getExitDescription())
                    .findFirst()
                    .orElse(execution.getStatus().name());
        }
        String firstLine = description.lines().findFirst().orElse(description);
        return firstLine.length() <= MAX_FAILURE_LENGTH ? firstLine : firstLine.substring(0, MAX_FAILURE_LENGTH);
    }

    // Spring Batch 5 records times as LocalDateTime in the JVM's zone.
    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
