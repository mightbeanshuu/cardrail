# 0003. Duplicate detection through the unique key

## Context

Acquirers retransmit when they do not get an answer in time, so the same authorization can arrive
twice, sometimes concurrently on different switch instances. A transmission is identified by
acquirer id, STAN, transmission date/time and terminal id. "Look it up, then insert if absent" has
a race: two copies can both see nothing and both be processed, holding the money twice.

## Decision

- `card_authorization` has a unique constraint on `(acquirer_id, stan, transmission_datetime,
  terminal_id)`.
- The first step after tokenization is `INSERT ... ON CONFLICT ON CONSTRAINT ... DO NOTHING
  RETURNING ...`. Getting the row back means this request owns the key. PostgreSQL makes a
  concurrent insert of the same key wait for the first one and then report the conflict.
- The losing request reads the existing row and compares a keyed HMAC-SHA256 of the whole message:
  equal means a retransmission and the stored response is replayed (same id, same auth code);
  different means a reused identifier and the answer is 94.
- The payload hash is keyed because it covers the PAN, and an unkeyed hash of a PAN can be reversed
  by enumerating card numbers.
- The key is claimed with status `PENDING` before the issuer is called. A retransmission of a
  `PENDING` request waits up to `in-flight-wait` for the decision, then replays it or answers 94.

## Consequences

- Exactly one authorization per transmission, whatever the concurrency
  (`DuplicateDetectionIntegrationTest` sends 20 copies at once).
- `PENDING` is a status the original spec did not list; it never appears in a response.
- Messages that fail format validation (30) are not stored, because their identifying fields cannot
  be trusted, so they are not deduplicated either.
