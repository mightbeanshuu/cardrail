# 0007. Vault-backed tokenization

## Context

The switch must never store a PAN, but it needs a stable stand-in for one: the token keys the
issuer account, the velocity counters and the Kafka partitioning, so the same card has to give the
same token on every request, on every instance and after every restart. Until now the switch used
`DevCardTokenizer`, an HMAC of the PAN cut to 19 digits. That is deterministic, but it cannot be
reversed, so clearing and disputes could never get the card number back, and it ties the token to
one key that can never rotate without changing every token.

`cardrail-vault` provides envelope encryption with versioned KEKs, an HMAC blind index for
idempotent lookup, and random 16-digit tokens. It defines a `VaultStore` SPI and a `KeyProvider`
SPI and leaves both to the integrator.

## Decision

- `cardrail.tokenizer=vault` is the default. `VaultCardTokenizer` implements the switch's
  `CardTokenizer` port over `TokenVault`; the dev tokenizer stays selectable with
  `cardrail.tokenizer=dev`.
- `JdbcVaultStore` keeps records in `vault_record` (V7) with unique constraints on `token` and
  `blind_index`. `insertIfAbsent` is one `INSERT ... ON CONFLICT DO NOTHING`. When nothing is
  inserted, a row with the same blind index means the PAN was tokenized first by someone else, and
  that row's token is returned; otherwise the random token collided, and the vault draws another.
  `rewrap` is a compare-and-set `UPDATE ... WHERE token = ? AND dek_key_version = ?`.
- Keys come from configuration as base64 (`cardrail.vault.keks[]` with id and status, and a separate
  `cardrail.vault.blind-index-key`). Tokens are stable across restarts because the blind index key
  is stable; the token itself is random and lives only in the table.
- The dev and test profiles carry fixed development keys, published in `application.yml` and
  labelled as not secret. Without a profile there are no keys and the switch does not start. In any
  other profile the switch refuses to start if a configured key matches a development key; the check
  is by SHA-256 fingerprint of the key bytes, so renaming the KEK id does not get around it.
- Every detokenize attempt, successful or not, is appended to the switch's hash-chained audit log in
  its own transaction before the vault returns the PAN. If the audit write fails, the PAN is not
  released.
- Logs use the vault's `PanRedactor(new TokenFormat())`, which masks PANs (also when grouped by
  spaces or dashes) and leaves vault tokens readable, so log lines can be correlated by card.

## Consequences

- Tokens change from 19 to 16 digits. Data tokenized by the dev tokenizer does not match vault
  tokens; switching tokenizer on an existing database is a migration, not a setting change.
- Tokenizing a known card costs one indexed `SELECT`; a first sighting adds an AES-GCM seal and one
  `INSERT`.
- The keys sit in the JVM heap next to the data. A deployment with an HSM or KMS would implement
  `KeyProvider` against it; that is the boundary this design leaves for it.
- Rotation is a configuration change and a restart (new KEK `ACTIVE`, old one `DECRYPT_ONLY`),
  followed by `TokenVault.rewrapAll()`. The switch does not yet run the rewrap on a schedule.
- The blind index key cannot rotate without re-indexing every record, which needs every PAN
  decrypted; that job does not exist.
- A caller must not detokenize after appending to the audit trail in the same transaction: the
  detokenize audit entry runs in a separate transaction and would wait for the chain lock the
  caller holds.
