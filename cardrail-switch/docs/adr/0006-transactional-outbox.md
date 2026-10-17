# 0006. Transactional outbox

## Context

Other systems (clearing, the issuer's stand-in advice processing, notifications) need to learn about
every authorization state change. Publishing to Kafka inside the business transaction would mean
either an event for a change that rolled back, or a committed change with no event if the publish
fails after commit.

## Decision

- Each state change appends its events to the `outbox` table in the same transaction as the change
  and its audit record.
- `OutboxRelay` polls on a schedule, locks a batch of unpublished rows in id order with `FOR UPDATE
  SKIP LOCKED` (so several instances can run it), publishes each to the topic
  `cardrail.<event type>` keyed by card token, waits for the broker's acknowledgement, and marks the
  row published. Failures increment `attempts` and record `last_error`; later events for the same
  key wait for the next run so a card's events stay in order.
- The producer runs with `enable.idempotence=true` and `acks=all`.
- The publisher is pluggable: `log` for running without a broker, `kafka` otherwise.

## Consequences

- An event exists if and only if its change committed.
- Delivery is at least once: a crash after the broker acknowledged but before the row was marked
  sends it again, so consumers deduplicate on the `cardrail-event-id` header.
- Events are delayed by up to one poll interval (1 s by default).
- Rows that exhaust `max-attempts` are left in place for an operator; ordering for that key is no
  longer guaranteed after that point.
