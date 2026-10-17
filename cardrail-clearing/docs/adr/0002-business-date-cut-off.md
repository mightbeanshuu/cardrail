# 0002. Business date from event time, with a cut-off in the settlement time zone

## Context

Settlement is per business date, and a business date is not the calendar date in UTC. Networks and
acquirers close the day at a cut-off in a local time zone, and transactions after the cut-off belong
to the next day. Clearing also receives events late (Kafka lag, a clearing outage), so the time an
event is processed says nothing about the day it belongs to.

## Decision

The business date is derived from the event's `occurredAt` only, never from processing time:
convert to the settlement zone (default `Asia/Kolkata`), and if the local time is at or after the
cut-off (default 23:00) the business date is the next calendar day. The cut-off instant itself
belongs to the next day. A midnight cut-off means calendar date. Both are configuration
(`cardrail.clearing.settlement-zone`, `cardrail.clearing.cut-off`).

The rule lives in one value object, `BusinessCalendar`, with boundary tests at 22:59:59.999999999
and 23:00:00 IST.

## Consequences

- Reprocessing the same event always yields the same business date, which idempotency depends on.
- An event can arrive after its business date has been settled. It is not lost: the next run
  settles every pending record whose business date is on or before the run's date (ADR 0004), and
  the record keeps its original business date next to the date it was settled on.
- A reversal is offset only within the capture's business date. Undoing a capture from an earlier
  day is a refund or chargeback, which is a different message flow; such a reversal is recorded as
  ignored with the reason.
- Clock skew on the switch moves events across the cut-off. That is accepted: the switch is the
  system of record for when an authorization was captured.
