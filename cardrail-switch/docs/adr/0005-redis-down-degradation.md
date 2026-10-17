# 0005. Behaviour when Redis is unavailable

## Context

Velocity limits (attempts and amount per card in a rolling window) are counted in Redis with an
atomic Lua script on a sorted set, so every switch instance shares one count. Redis can fail. The
options are to fail closed (decline everything, which turns a cache outage into a payments outage),
fail open (skip velocity, exactly what a card-testing attack wants), or degrade.

## Decision

Degrade. When the Redis call fails (with a 200 ms command timeout), the velocity rule counts this
card's attempts in the window from `card_authorization` (indexed on token, currency, created_at)
and applies the normal limits multiplied by `cardrail.risk.velocity.degraded-ratio` (0.5 by
default). Each fallback increments `cardrail.risk.velocity.degraded` and logs a warning. Redis is
excluded from the readiness health group, so an instance is not taken out of service for it.

## Consequences

- Authorizations continue during a Redis outage with tighter limits, and the outage is visible in
  metrics.
- The database fallback is not atomic across concurrent requests for the same card; the stricter
  limits are what bounds that window.
- Once Redis returns it has not seen the attempts made during the outage, so for one window the
  counts are lower than reality.
