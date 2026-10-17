# cardrail-vault

PAN tokenization for the cardrail switch: card number primitives, a log redactor, envelope
encryption with versioned keys, a blind index for idempotent tokenization, and key rotation that
leaves card data untouched. JDK only at runtime (`javax.crypto`); no hand-written crypto primitives.

A learning-grade model. It is not PCI DSS validated and not a substitute for a certified HSM-backed vault.

## Threat model

Protects against:

- **A database dump without the keys.** Rows hold AES-256-GCM ciphertext, wrapped DEKs, an
  HMAC blind index, the masked PAN (first 6 + last 4) and the token. No plaintext PAN is ever stored.
- **Ciphertext swapping and tampering.** The PAN ciphertext is authenticated with the token as AAD.
  The wrapped DEK is authenticated with the token and KEK id as AAD. Moving ciphertext or a whole
  envelope to another row, relabelling the key version, flipping a bit or truncating any field
  raises `VaultIntegrityException`.
- **Accidental logging.** `Pan.toString()` is the masked form, exception messages never echo input,
  audit events carry no PAN, and `PanRedactor` scrubs Luhn-valid 13-19 digit runs from log text.
- **Guessing PANs from the index.** The blind index is a keyed HMAC, so the roughly 10^9 PANs per
  BIN cannot be brute-forced from a dump the way an unkeyed hash could.

Does not protect against:

- **A compromised application process.** The PAN is in memory during tokenize and after detokenize.
  `Pan` keeps digits in a wipeable `char[]`, but a PAN that was ever a `String` (the ISO 8583 field it
  came from, for example) cannot be wiped, and the GC may have copied any array before it was cleared.
- **Key theft.** `InMemoryKeyProvider` keeps keys in the same heap as the data. Real deployments keep
  KEKs in an HSM or cloud KMS. `KeyProvider` is the seam for that.
- **An authorized caller who misuses detokenize.** Every call is audited (`VaultAuditListener`,
  fail-closed), but deciding who may call it is the integrator's job.

## Envelope scheme

```
           per record                                  long-lived, versioned
  PAN ──AES-256-GCM──► ciphertext ‖ tag          KEK kek-vN (ACTIVE)  (HSM/KMS in production)
        key = DEK (random 256-bit)                    │
        IV  = random 96-bit                           │ AES-256-GCM, random 96-bit nonce
        AAD = "pan/v1" ‖ token                        ▼
                                          DEK ──► wrappedDek = nonce ‖ enc(DEK) ‖ tag
                                                  AAD = "dek/v1" ‖ token ‖ kekId

  blindIndex = HMAC-SHA256(indexKey, PAN)      indexKey is separate from every KEK

  row: token | blindIndex | wrappedDek | dekKeyVersion | iv | ciphertext | maskedPan | createdAt
```

**Why the KEK id is in the wrap AAD and not the PAN AAD.** Rotation rewraps the DEK under a new KEK and
must not touch the PAN ciphertext. So the PAN's AAD holds only values fixed for the life of the
record (the token). The changeable value, the KEK id, is bound where it changes: in the wrap.

**Why AES-GCM for the DEK wrap instead of `AESWrap` (RFC 3394).** RFC 3394 has no associated data, so
a wrapped DEK could not be bound to its token and key version, and would need a separate MAC to get
that. GCM gives authenticated wrapping with AAD using the same primitive as the data layer. The cost
is the random-nonce limit: NIST SP 800-38D caps a key at about 2^32 random-IV encryptions. Each
tokenize or rewrap uses one wrap under the active KEK, so rotate KEKs well before that volume.
Each DEK encrypts exactly one PAN, so the data-layer IVs never get near the limit.

## Tokens

16 digits: token BIN (default `999999`, configurable) + 9 `SecureRandom` digits + Luhn check digit.
Systems downstream that check length and Luhn accept a token as they would a PAN. Uniqueness is
enforced by the store at insert, and a collision is retried with fresh digits. A token BIN inside a
real scheme range is rejected, and a PAN that is itself in the token BIN is refused.

Real network tokens (for example Mastercard MDES) come from network-assigned token BIN ranges and
carry domain restrictions (merchant, channel, device) that the network enforces at authorization.
This module models only the vault mechanics.

## Key rotation

1. `keyProvider.rotate()`: the new KEK becomes ACTIVE and the previous one DECRYPT_ONLY. New records
   are wrapped under the new KEK at once. Existing records still detokenize.
2. `vault.rewrapAll()` (or `rewrapBatch(n)` from a scheduled job): each DEK is unwrapped with its old
   KEK and rewrapped under the active one, using compare-and-set on the key version. IV, ciphertext and
   blind index are not touched, and no PAN is decrypted.
3. Run `rewrapAll()` again after every writer has picked up the new key. When it returns `0`, no
   record is left under the old KEK. That also catches records sealed under the old key while
   rotation was in progress.
4. Retire the old KEK. A record still under a RETIRED KEK fails loudly with `KeyUnavailableException`
   (so does `rewrapAll()`). It never fails silently.

The blind index key does not rotate with the KEKs. Rotating it means recomputing every index, which
needs every PAN decrypted. That re-index job is not implemented here.

## Log redaction

`new PanRedactor().redact(line)` masks every Luhn-valid run of 13-19 digits, including runs grouped by
single spaces or dashes (`5555 5555 5555 4444` becomes `5555 55** **** 4444`). Runs that fail Luhn are
left as they are. It deliberately over-redacts: about one in ten unrelated 13-19 digit numbers passes
Luhn by chance. `new PanRedactor(tokenFormat)` leaves the vault's own tokens readable so log lines can
still be correlated by token. Digits glued to a PAN with no separator form a longer number and are
not treated as a PAN.

## Tests

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -B -o -pl cardrail-vault verify        # from the repository root
```

JUnit 5 + AssertJ + jqwik. Covers known Luhn vectors and Luhn properties, scheme boundaries
(2220/2221, 2720/2721), `Pan.toString` never showing the full PAN, the redactor never leaking a
generated PAN's hidden digits from random text, idempotent tokenization, 64 threads tokenizing one PAN,
tamper and swap detection on every stored field, rotation and rewrap, retired-key failure, and audit
events.
