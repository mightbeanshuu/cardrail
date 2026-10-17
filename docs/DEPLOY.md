# Public demo on Render

The demo runs on Render's free tier from the Blueprint in [`render.yaml`](../render.yaml), and the
console on Vercel at https://cardrail-demo.vercel.app. Free instances are small and sleep when idle,
so the demo is shaped by those limits. Everything below is specific to the `demo` Spring profile;
local runs and CI are unchanged.

## What runs where

| Component | Where | Notes |
|---|---|---|
| `cardrail-switch` | Render web service, free, Singapore | https://cardrail-switch.onrender.com, Docker image from `cardrail-switch/Dockerfile` |
| `cardrail-clearing` | Render web service, free, Singapore | https://cardrail-clearing.onrender.com, Docker image from `cardrail-clearing/Dockerfile` |
| PostgreSQL | Render Postgres `cardrail-db`, free | one database; the switch uses schema `switch`, clearing schema `clearing` |
| Redis | Render Key Value `cardrail-redis`, free | velocity counters only; internal connections only |
| `cardrail-console` | Vercel | calls both services cross-origin; CORS allows the Vercel origin and `localhost:4200` |

A free instance has 512 MB and a tenth of a CPU, so each image runs one JVM with the serial
collector, a 70 % heap ceiling, 512 KB thread stacks and the C1 compiler only (the flags are in the
Dockerfiles). The ISO 8583 TCP gateway is off (`cardrail.iso.enabled=false`): Render forwards HTTP
only. Swagger UI stays on at `/swagger-ui.html`; actuator exposes only `health` and `info`.

Each service owns its schema, as each owns its database outside the demo. Flyway creates the schema
and keeps its history there, and the JDBC URL sets `currentSchema`, so neither service can see the
other's tables.

## Events over HTTP instead of Kafka

Render has no managed Kafka, and a broker does not fit in a free instance. In the demo the switch's
outbox relay posts each `authorization.captured` and `authorization.reversed` event to clearing's
`POST /v1/events` (`cardrail.outbox.publisher=http`, `cardrail.events.transport=http`). Other event
types have no consumer, as a topic nobody reads, and are not sent.

- **Signed.** Each request carries `Cardrail-Timestamp` (epoch seconds) and `Cardrail-Signature`,
  the hex HMAC-SHA256 of `timestamp + "." + body` under `CARDRAIL_EVENTS_HMAC_SECRET`, plus
  `Cardrail-Event-Type` and `Cardrail-Event-Id`. Clearing compares in constant time and rejects a
  timestamp more than 5 minutes from its clock, so a recorded request cannot be replayed later
  (401). A replay inside the window changes nothing: clearing processes each
  `(authorizationId, eventType)` once, on the same path the Kafka listener uses.
- **Same retry semantics as Kafka.** Delivery counts only when clearing answers 2xx with its JSON
  acknowledgement; anything else leaves the outbox row unpublished for the next run, and later
  events for the same card wait behind it. An event clearing cannot process is answered 422 and,
  after `max-attempts` (500 in the demo), stays in the outbox for an operator, the counterpart of the
  dead-letter topic.
- **Kafka stays the default.** Every other profile, the test suites and CI use Kafka, and the HTTP
  path has its own tests on both sides (`HttpOutboxPublisherTest` and `DemoProfileIntegrationTest`
  in the switch, `EventSignatureVerifierTest`, `HttpEventsIntegrationTest` and
  `DemoProfileIntegrationTest` in clearing), including one signature test vector both sides check.

The switch calls clearing's public URL because free web services can send private-network requests
but cannot receive them.

## Test cards only

A public form that takes card numbers must never store a real one. In the demo profile the switch
processes only the published test card numbers below (`cardrail.cards.allowed-prefixes`). Any other
number is answered `14` before it is fingerprinted or tokenized: it reaches no table, not even
masked, and no log line. `DemoProfileIntegrationTest` checks this with a Luhn-valid Mastercard-range
number against `vault_record`, `card_authorization`, `audit_log`, `outbox` and the log output.

| Card | Account (INR) | Stand-in limit | Shows |
|---|---|---|---|
| `5555555555554444` | 50,000.00 | 5,000.00 | approval, capture, reversal, stand-in |
| `5200828282828210` | 25,000.00 | 2,500.00 | approval |
| `2223003122003222` | 10,000.00 | 1,000.00 | approval on a 2-series BIN |
| `5105105105105100` | 500.00 | 2,500.00 | `51` insufficient funds |
| `5425233430109903` | 10,000.00, blocked | 2,500.00 | `05` do not honor |
| `2223000048410010` | none | 1,000.00 | `14` from the issuer: a listed card with no account, so it is stored |

The list matches the console's `cardrail-console/src/app/demo/demo-cards.ts`; change both together.
MCC `7995` (gambling) is blocked by the reference data and answered `57`. Any future expiry works;
the console sends `3012`. The accounts are opened on first start and left alone after that, so
captures spend them down; balances do not reset by themselves (see Resetting).

Visitors can slow down or fail the simulated issuer with `PUT /dev/issuer/faults` (latency up to
10 s, failure rate 0 to 1) to watch stand-in processing take over; `DELETE` restores it. The setting
is shared by every visitor, so it reverts by itself after 2 minutes
(`cardrail.issuer.sim.reset-after`); `GET` and `PUT` return `resetsAt`. `POST /dev/accounts` stays
dev-only.

`POST`, `PUT` and `DELETE` are limited to 20 at once and 60 a minute per client address, answered
with a 429 problem and `Retry-After`, with the CORS headers on it and `Retry-After` exposed, so
the console can read it. The address is the first `X-Forwarded-For` hop, which a client
can set, so this is a brake on careless load, not a security control.

## Cold starts

A free web service sleeps after 15 minutes without inbound requests and takes about a minute to
wake up. Background work does not keep it awake, so:

- The first request after a quiet spell is slow. The console pings `/actuator/health` on both
  services (CORS allows it, `GET` only) to wake them and shows that it is waiting.
- If clearing is asleep, the switch's first deliveries time out (15 s each) and are retried every
  second until clearing answers; the events stay in the outbox meanwhile, still in order per card.
- If the switch is asleep, its outbox relay and hold sweeper are asleep too; they catch up on start.

Clearing settles on request only. A visitor can run settlement for the current business date
(`GET /v1/business-date` returns it: Asia/Kolkata, 23:00 cut-off) and see the positions and the CSV.
A settled date is final, so captures that arrive after it settle with the next business date.

## Deploying

1. Push the repository to GitHub with `render.yaml` at the root, on `main`.
2. In the Render Dashboard: New, Blueprint, pick the repository. Render creates both services, the
   database, the Key Value instance and the `cardrail-shared` environment group, generating every
   secret marked `generateValue`.
3. Wait for both health checks: `/actuator/health/readiness` on the switch (Redis left out on
   purpose: without it velocity checks degrade and authorizations go on) and `/actuator/health` on
   clearing.
4. If Render could not give a service its plain `onrender.com` name, set
   `CARDRAIL_OUTBOX_HTTP_BASE_URL` on the switch to clearing's real URL, and point the console at
   the real URLs.

Pushes to `main` redeploy both services.

## Environment

| Variable | Service | Source | |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | both | `demo` | |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | both | `cardrail-db` | the JDBC URL is built from them in `application.yml` |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | both | `cardrail-db` | |
| `SPRING_DATA_REDIS_URL` | switch | `cardrail-redis` connection string | |
| `CARDRAIL_OUTBOX_HTTP_BASE_URL` | switch | `https://cardrail-clearing.onrender.com` | |
| `CARDRAIL_VAULT_KEKS_0_ID`, `_STATUS` | switch | `demo-kek-1`, `ACTIVE` | |
| `CARDRAIL_VAULT_KEKS_0_KEY`, `CARDRAIL_VAULT_BLIND_INDEX_KEY` | switch | generated | |
| `CARDRAIL_PAYLOAD_HASH_KEY` | switch | generated | keys the duplicate-detection fingerprint |
| `CARDRAIL_EVENTS_HMAC_SECRET` | both | generated, group `cardrail-shared` | one value for both services |
| `CARDRAIL_WEB_ALLOWED_ORIGINS` | both | group `cardrail-shared` | `https://cardrail-demo.vercel.app,http://localhost:4200` |
| `MANAGEMENT_ENDPOINTS_WEB_CORS_ALLOWED_ORIGINS` | both | group `cardrail-shared` | the same origins, for the console's wake-up ping to `/actuator/health` |
| `PORT` | both | Render | the entrypoint passes it as `server.port` |

Render's generated values are 32 random bytes in base64, which is what the vault expects for a KEK
and a blind index key, so no key needs to be set by hand. They are generated once, on creation.
Changing a KEK or the blind index key later makes the stored card records unreadable or unfindable,
and the switch refuses to start with the published development keys, as in any non-dev profile.

## Limits of the free tier

- **The database expires.** A free Render Postgres database expires 30 days after creation and is
  deleted 14 days later unless upgraded. Recreate it (and redeploy, so Flyway rebuilds both schemas
  and the switch reopens the test accounts) or move it to a paid plan.
- **750 free instance hours a month** per workspace, shared by both services. Sleeping services do
  not use them.
- **Redis is in memory only.** A restart loses the velocity counters, nothing else.

## Resetting

To start over with full balances, drop both schemas and redeploy:

```sql
DROP SCHEMA switch CASCADE;
DROP SCHEMA clearing CASCADE;
```

The vault keys can stay: a fresh `vault_record` has nothing encrypted under the old ones.
