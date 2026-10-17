# 0001. Clearing is a separate service, coupled to the switch only by events

## Context

Authorization and clearing have different shapes. Authorization is online: one request, an answer
in milliseconds, and an outage means declined cards at the till. Clearing is offline: it batches the
day's captures, prices them and produces settlement positions once a day. They scale differently,
change for different reasons and should not be able to take each other down.

## Decision

`cardrail-clearing` is its own Spring Boot deployable with its own database. It shares no code and
no tables with `cardrail-switch`. The only coupling is the Kafka event contract in
`docs/CONTRACTS.md` section 1. The switch writes events to a transactional outbox and relays them to
`cardrail.authorization.*` topics; clearing consumes `authorization.captured` and
`authorization.reversed`.

Clearing keeps its own copy of the event DTO (`AuthorizationEvent`). It is a tolerant reader:
unknown fields are ignored, fields clearing does not use are optional, and the fields it does need
are validated on arrival. A contract test parses the sample events copied from `CONTRACTS.md`; the
switch has the mirror-image test on its side.

## Consequences

- If clearing is down, the switch keeps authorizing. Events wait in Kafka and are consumed when
  clearing comes back; nothing on the authorization path depends on clearing.
- Each side deploys alone, as long as the contract holds. Adding a field to the event is
  backwards-compatible; renaming or removing one breaks a contract test first.
- Clearing can run on a different database. Here it runs on PostgreSQL locally and Oracle in CI,
  which a shared schema would have made impossible.
- There is no distributed transaction. A capture is cleared some time after the switch commits it;
  the business-date rule (ADR 0002) and the late-record sweep (ADR 0004) handle the delay.
- Two copies of the DTO can drift. The contract tests are the guard, so the samples in
  `CONTRACTS.md` must be updated whenever the payload changes.
