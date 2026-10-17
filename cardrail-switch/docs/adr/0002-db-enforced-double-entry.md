# 0002. Double entry enforced by the database

## Context

Every movement of money (hold, release, expiry, capture) must be a balanced journal: its entries
sum to zero. If only the application checks this, any bug, script or manual fix can break the books
without anyone noticing. The entries of one journal are inserted one at a time, so a per-row check
would reject every journal halfway through.

## Decision

- `journal_entry` has a `DEFERRABLE INITIALLY DEFERRED` constraint trigger that, at commit, sums the
  journal's entries and raises `check_violation` if they are not zero.
- `journal` and `journal_entry` reject `UPDATE` and `DELETE`; corrections are new journals.
- `issuer_account` has `CHECK (available_minor >= 0)` as a backstop to the row-locked funds check.
- The domain `Journal` record also refuses unbalanced postings, so a bug fails fast with a clear
  message before reaching the database.

## Consequences

- An unbalanced journal cannot be committed by any client (`LedgerIntegrationTest` proves it fails
  at commit, not at insert).
- The check costs one indexed aggregate per entry at commit, which is small for 2 to 3 entry journals.
- Row triggers do not fire on `TRUNCATE`; the application role must not be granted it in production.
