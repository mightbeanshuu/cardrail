# cardrail: build spec (internal contract for all modules)

This file is the contract between modules. Code against it. If something here is wrong or missing,
report it; do not silently diverge.

## What cardrail is

A card-payment **switch**: it sits between an acquirer (the merchant's bank, sending ISO 8583
authorization requests from terminals) and an issuer (the cardholder's bank, which holds the money).
It does what a network switch does at small scale:

1. Parse ISO 8583 `0100` authorization requests off a TCP socket (or REST for tooling).
2. Validate the card (Luhn, expiry, BIN), then **tokenize the PAN** so no raw card number is ever stored.
3. Detect **duplicate transmissions** (same acquirer + STAN + transmission time + terminal).
4. Run **risk rules** (per-transaction limit, MCC blocks, velocity count/amount per card).
5. Route to the **issuer**. If the issuer times out or its circuit breaker is open, fall back to
   **Stand-In Processing (STIP)**: decide on the issuer's behalf using conservative stand-in limits,
   and queue a STIP advice so the issuer can post it later.
6. On approval place a **hold** in a **double-entry ledger** under row-level locking (no overdraft
   under concurrency).
7. Write every state change to a **transactional outbox** (published to Kafka) and a
   **hash-chained, append-only audit log**.
8. Handle `0400` **reversals** (release hold, idempotent) and `0800` network echo.
9. End of day: **clearing and settlement**. Captured transactions become clearing records,
   interchange is computed per MCC category, and net settlement positions per issuer/acquirer are produced.

Honesty rule for the README and docs: this is a learning-grade model of a network switch. It is NOT
certified, NOT PCI-DSS compliant, and does not implement a real network's private ISO 8583 dialect.
Never claim otherwise. Only publish performance numbers that were measured, with the command to
reproduce them.

## Tech baseline

Java 21 (records, sealed interfaces, pattern matching, virtual threads). Maven multi-module.
Spring Boot 3.5.16 (only in cardrail-switch). PostgreSQL 17 + Redis are running locally via brew
(`localhost:5432`, user `mac`, no password; `localhost:6379`). There is NO Docker, so no Testcontainers.
Kafka tests use `spring-kafka-test` EmbeddedKafka. Package root: `dev.cardrail`.

Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` then
`mvn -q -pl <module> verify` from `/Users/mac/cardrail`.

## Module ownership (strict)

| Module | Owner | Depends on |
|---|---|---|
| `cardrail-iso8583` | codec agent | nothing (JDK only) |
| `cardrail-vault` | vault agent | nothing (JDK only) |
| `cardrail-switch` | switch agent | NOTHING from the other two during development: it defines its own ports; the orchestrator wires adapters later |
| root `pom.xml`, `docs/SPEC.md`, `README.md`, `.github/` | orchestrator | read-only for agents |

## ISO 8583 subset (cardrail-iso8583)

Encoding: ASCII for MTI, numeric and alphanumeric fields; bitmaps as 16 hex ASCII chars each (primary
always present; secondary present if bit 1 set). Wire framing: 2-byte big-endian unsigned length
header followed by the message bytes.

MTIs: `0100`/`0110` authorization request/response, `0400`/`0410` reversal, `0800`/`0810` network
management.

| Field | Name | Format |
|---|---|---|
| 2 | PAN | n..19 LLVAR |
| 3 | Processing code | n6 |
| 4 | Amount, transaction (minor units) | n12 |
| 7 | Transmission date/time MMDDhhmmss | n10 |
| 11 | STAN | n6 |
| 12 | Local time hhmmss | n6 |
| 13 | Local date MMDD | n4 |
| 14 | Expiry YYMM | n4 |
| 18 | MCC | n4 |
| 19 | Acquiring institution country code | n3 |
| 22 | POS entry mode | n3 |
| 32 | Acquiring institution ID | n..11 LLVAR |
| 37 | Retrieval reference number | an12 |
| 38 | Authorization ID response | an6 |
| 39 | Response code | an2 |
| 41 | Card acceptor terminal ID | ans8 |
| 42 | Card acceptor ID (merchant) | ans15 |
| 43 | Card acceptor name/location | ans40 |
| 49 | Currency code, transaction | n3 |
| 55 | ICC (EMV) data | b...999 LLLVAR, BER-TLV inside (carried as hex ASCII on the wire, so length counts hex chars) |
| 70 | Network management info code | n3 (301 = echo) |
| 90 | Original data elements | n42 (orig MTI 4 + orig STAN 6 + orig F7 10 + orig acquirer ID 11 + forwarding ID 11, zero-padded) |

Response codes used (F39): `00` approved, `05` do not honor, `12` invalid transaction,
`13` invalid amount, `14` invalid card number, `30` format error, `51` insufficient funds,
`54` expired card, `57` transaction not permitted to cardholder, `61` exceeds amount limit,
`65` exceeds frequency limit, `91` issuer unavailable (STIP declined), `94` duplicate transmission,
`96` system malfunction.

## Switch domain (cardrail-switch)

Ports the switch defines and codes against (adapters are wired at integration):
- `dev.cardrail.sw.port.CardTokenizer`: `TokenizedCard tokenize(String pan)` where `TokenizedCard` is
  `record TokenizedCard(String token, String maskedPan, String bin, String last4)`. Throws
  `InvalidCardNumberException` on bad Luhn/length.
- `dev.cardrail.sw.port.IssuerGateway`: `IssuerDecision authorize(IssuerRequest)`; the default
  implementation is an in-process simulated issuer backed by the ledger, with configurable latency and
  failure injection so STIP can be demonstrated.

Duplicate key: `(acquirer_id, stan, transmission_datetime, terminal_id)`. Same key + same payload hash:
replay the original response. Same key + different payload: respond `94`.

Ledger invariant: every journal's entries sum to zero. Available balance never goes negative. Holds
are released on reversal and expire after a configurable number of days.

STIP: when the issuer call times out (TimeLimiter) or the circuit breaker is open, approve only if
amount <= stand-in limit for the card's BIN/product and the risk score is low; otherwise `91`.
Every stand-in decision is flagged `stand_in = true` and emits a `stip.advice` outbox event.

PAN handling: the raw PAN exists only in memory during tokenization. Store token + masked PAN
(first 6 + last 4) only. Logs must never contain a full PAN.
