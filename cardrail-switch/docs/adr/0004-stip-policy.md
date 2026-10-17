# 0004. Stand-in processing policy

## Context

If the issuer is slow or down, cardholders at the terminal still need an answer. Declining
everything hurts them and the merchant; approving everything exposes the network to unlimited loss,
because the network stands behind what it approves on the issuer's behalf.

## Decision

- The issuer call runs under a Resilience4j `TimeLimiter` (1.5 s by default) inside a
  `CircuitBreaker`. Timeouts count as failures, so a slow issuer opens the circuit like a failing
  one, and while open the switch stands in at once instead of making every cardholder wait.
- Stand-in approves only if the request passed every risk rule and the amount is at or below the
  stand-in limit of the longest matching BIN prefix for that currency (`stand_in_limit`; `''` is the
  catch-all). Anything else, including no configured limit, is 91.
- Stand-in decisions are flagged `stand_in = true`, hold no funds, and emit a `stip.advice` outbox
  event (approvals and declines) so the issuer can post them later.
- Any hold the issuer managed to place after the switch gave up is released in the same
  transaction that records the stand-in decision (see `IssuerLedger.voidHold`).

## Consequences

- Bounded exposure per transaction during an outage, controlled by reference data rather than code.
- Stand-in approvals are not captured by this module; that waits for issuer advice processing.
- The policy has no cumulative per-card stand-in limit yet; velocity still applies because it runs
  before routing.
