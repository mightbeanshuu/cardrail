# 0001. Explicit SQL over JPA

## Context

The switch's correctness rests on specific database behaviour: `INSERT ... ON CONFLICT DO NOTHING`
for duplicate detection, `SELECT ... FOR UPDATE` on one account row for holds, `FOR UPDATE SKIP
LOCKED` for sweepers and the outbox relay, `UPDATE ... RETURNING` to read back what was written, a
transaction-scoped advisory lock for the audit chain, and constraints and triggers that the
application must not work around. An ORM hides exactly these details behind a session cache, dirty
checking and generated statements.

## Decision

Use Spring's `JdbcClient` with hand-written SQL and named parameters. Each adapter maps rows to
domain records explicitly. Flyway owns the schema.

## Consequences

- Every statement that touches money can be read, reviewed and explained, including its locks.
- No lazy loading, no flush ordering surprises, no accidental N+1 queries.
- More mapping code to write, and renaming a column means touching the SQL. Integration tests
  against real PostgreSQL catch the mistakes a compiler cannot.
