# 0004. Spring Batch for end-of-day settlement

## Context

End-of-day settlement reads every pending clearing record for a date, aggregates positions per
acquirer and per issuer, marks the records settled and produces a report. It must survive a crash
halfway through without double counting, must not run twice for a date, and must refuse to publish
positions that do not balance.

## Decision

`settlementJob` is a Spring Batch job whose only identifying parameter is `businessDate`, so a date
is one `JobInstance`. Spring Batch's job repository then gives the run-once and resume semantics:
a completed instance is never run again, a failed one restarts from the step that failed.

1. `accumulatePositions` (chunk-oriented): a `JdbcPagingItemReader` pages through records with
   status `PENDING_SETTLEMENT`, business date on or before the run date and no settlement date, by
   id (keyset paging). For each chunk the writer claims the records (`settlement_date = run date`)
   and inserts the chunk's partial sums into `settlement_partial`. The claim, the partials and the
   reader's restart point commit in the chunk transaction, so a chunk is counted entirely or not at
   all.
2. `publishPositions` (tasklet, one transaction): sum the partials into one position per party and
   currency, reconcile, insert `settlement_position` rows and mark the claimed records `SETTLED`.
   Reconciliation failure throws, the transaction rolls back and the step fails.
3. `writeReport` (tasklet): render the positions to CSV and move it into place atomically.

Reconciliation, per currency: every position's net follows from its gross and interchange;
acquirer net + issuer net + network net = 0 and the network keeps nothing; the acquirer and issuer
sides cover the same count, gross and interchange; and those equal a separate SQL aggregate over
the records claimed for the run.

## Consequences

- Restart is tested: a fault injected in chunk 2 leaves chunk 1 claimed and counted; the resumed
  run reads only the remaining records and produces the same positions as an uninterrupted run.
- A record that arrives after its date settled is picked up by the next run, because the reader
  takes every earlier pending date. The record keeps its business date; `settlement_date` says which
  run settled it.
- The REST call runs the job synchronously, which is fine for this data volume. A production
  deployment would launch asynchronously and let clients poll `GET /v1/settlement-runs/{date}`.
- A JVM killed mid-run leaves the execution `STARTED` in the job repository, which blocks a
  restart until an operator marks it failed. That is standard Spring Batch behaviour and is not
  automated here.
- Spring Batch metadata tables live in the clearing database, created by Spring Boot
  (`spring.batch.jdbc.initialize-schema=always`) from the DDL Spring Batch ships per vendor.
