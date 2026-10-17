# 0003. Idempotent consumer: processed-event marker in the business transaction

## Context

The switch publishes through a transactional outbox, which gives at-least-once delivery: after a
relay crash or a consumer rebalance, the same event can arrive twice, or on two consumers at once.
A clearing record created twice is money settled twice.

## Decision

`processed_event` has primary key `(authorization_id, event_type)`. Each event is handled in one
database transaction that checks the marker, writes the clearing rows and inserts the marker. The
Kafka offset is acknowledged (`AckMode.MANUAL`) only after that transaction has committed.

- Redelivery after commit: the marker exists, the handler does nothing and returns `DUPLICATE`.
- Crash before commit: nothing was written, the offset was not committed, the event is redelivered
  and processed normally.
- Two deliveries racing: both pass the check, both insert. The unique index makes the second
  insert wait for the first transaction and then fail; the handler maps `DuplicateKeyException`
  (ORA-00001 or SQLSTATE 23505) to `DUPLICATE` after its own transaction has rolled back. Because
  the failing insert waited for the first commit, the first delivery's rows are durable by then.
- `clearing_record` has its own unique key `(authorization_id, record_type)` as a second line.

Events that can never be processed (unreadable JSON, wrong topic, missing or malformed fields) throw
`InvalidEventException`, which `DefaultErrorHandler` treats as not retryable: the record goes
straight to `cardrail.clearing.dlt` with the original topic, partition, offset and exception in
headers, and the partition moves on. Other failures are retried with bounded exponential back-off
and then dead-lettered too.

## Consequences

- Exactly-once effect on the clearing database without Kafka transactions or a distributed
  transaction.
- A reversal for an authorization that was never captured is still marked (outcome `IGNORED`), so
  a capture arriving later does not make a redelivered reversal start offsetting it.
- A database outage longer than the retry budget sends healthy events to the DLT. They can be
  replayed onto the source topic from the headers, and replay is safe because of the marker.
  Infinite retry would avoid that but would stall the partition behind one record.
- The marker table grows with every event. It needs a retention job keyed on `processed_at`,
  longer than the switch's maximum redelivery window; not built here.
