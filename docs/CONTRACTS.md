# Service contracts

cardrail runs as two services plus a console:

| Component | Port | Owns |
|---|---|---|
| `cardrail-switch` (Spring Boot) | HTTP 8080, ISO 8583 TCP 8583 | authorization, holds, ledger, outbox, audit (PostgreSQL) |
| `cardrail-clearing` (Spring Boot) | HTTP 8081 | clearing records, interchange, settlement (PostgreSQL locally, Oracle in CI) |
| `cardrail-console` (Angular) | 4200 (dev server) | UI only, no state of its own |

The services share no code and no database. The only coupling is the Kafka event contract and the
REST APIs below. Each side keeps its own copy of the event DTO, and a contract test on each side parses
the sample events in this file.

## 1. Kafka events (switch → clearing)

Topic = `cardrail.` + event type. Key = card token (falls back to the authorization id), so all
events for one card are ordered within a partition. Value = UTF-8 JSON. Delivery is at-least-once
(transactional outbox), so **consumers must be idempotent** on `(authorizationId, eventType)`.

Event types: `authorization.approved`, `authorization.declined`, `authorization.reversed`,
`authorization.captured`, `authorization.expired`, `stip.advice`.

Fields (null fields are omitted):

| Field | Type | Notes |
|---|---|---|
| `eventType` | string | same as the topic suffix |
| `authorizationId` | UUID string | |
| `status` | string | AUTHORIZED, DECLINED, REVERSED, CAPTURED, EXPIRED |
| `responseCode` | string | ISO 8583 F39 |
| `authCode` | string | approvals only |
| `standIn` | boolean | true if decided by STIP |
| `standInTrigger` | string | `stip.advice` only: TIMEOUT, CIRCUIT_OPEN, ISSUER_ERROR |
| `reason` | string | decline rule, if any |
| `token` | string | card token; never a PAN |
| `maskedPan` | string | first 6 + last 4 |
| `amountMinor` | long | authorized amount, minor units |
| `currency` | int | ISO 4217 numeric, e.g. 356 |
| `capturedAmountMinor` | long | `authorization.captured` only |
| `mcc` | string | 4 digits |
| `merchantId` | string | F42 |
| `acquirerId` | string | F32 |
| `terminalId` | string | F41 |
| `stan` | string | F11 |
| `rrn` | string | F37 |
| `occurredAt` | ISO-8601 instant | |

Sample `authorization.captured`:

```json
{"eventType":"authorization.captured","authorizationId":"7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b",
 "status":"CAPTURED","responseCode":"00","authCode":"A1B2C3","standIn":false,
 "token":"9999991234567890","maskedPan":"555555******4444","amountMinor":25000,"currency":356,
 "capturedAmountMinor":20000,"mcc":"5411","merchantId":"MERCHANT000001","acquirerId":"123456",
 "terminalId":"TERM0001","stan":"000123","rrn":"629112000123","occurredAt":"2026-10-18T09:15:30Z"}
```

Sample `authorization.reversed`:

```json
{"eventType":"authorization.reversed","authorizationId":"7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b",
 "status":"REVERSED","responseCode":"00","authCode":"A1B2C3","standIn":false,
 "token":"9999991234567890","maskedPan":"555555******4444","amountMinor":25000,"currency":356,
 "mcc":"5411","merchantId":"MERCHANT000001","acquirerId":"123456","terminalId":"TERM0001",
 "stan":"000123","rrn":"629112000123","occurredAt":"2026-10-18T09:20:00Z"}
```

## 2. Switch REST API (consumed by the console)

Base `http://localhost:8080`. Errors are RFC 7807 `application/problem+json`. Business declines are
HTTP 200 with a decline `responseCode`.

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/authorizations` | JSON mirror of an 0100 |
| GET | `/v1/authorizations` | **search (added by the integration work)**: query params `status`, `responseCode`, `merchantId`, `last4`, `standIn` (bool), `from`, `to` (ISO instants), `page` (0-based), `size` (default 20, max 100). Response: `{"items":[AuthorizationView...],"page":0,"size":20,"total":123}`, newest first |
| GET | `/v1/authorizations/{id}` | `AuthorizationView` |
| POST | `/v1/authorizations/{id}/reversal` | |
| POST | `/v1/authorizations/{id}/capture` | body `{"amountMinor": 20000}` (optional; full capture if absent) |
| GET | `/v1/audit/verify` | `{"ok":true,"entries":N}` or the first broken seq |
| POST | `/dev/issuer/faults` | dev profile only: issuer latency / failure injection |

`AuthorizationView` (what `GET /v1/authorizations/{id}` returns today): id, status, responseCode,
authCode, standIn, maskedPan, token, amountMinor, currency, capturedAmountMinor, mcc, merchantId,
acquirerId, terminalId, stan, rrn, createdAt, updatedAt. The console must read the real DTO in
`cardrail-switch/src/main/java/dev/cardrail/sw/adapter/web/` rather than trust this list.

CORS: the switch and clearing services allow `http://localhost:4200` (configurable
`cardrail.web.allowed-origins`).

## 3. Clearing REST API (consumed by the console)

Base `http://localhost:8081`.

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/clearing-records?date=YYYY-MM-DD&page=&size=` | records created from captures (and reversals of captures, if any) |
| POST | `/v1/settlement-runs` | body `{"businessDate":"YYYY-MM-DD"}`; runs the end-of-day job, idempotent per date |
| GET | `/v1/settlement-runs/{businessDate}` | run status + net positions |
| GET | `/v1/settlement-runs/{businessDate}/report.csv` | settlement report |
