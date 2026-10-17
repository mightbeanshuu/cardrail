# cardrail-apisec

Server-side API security in the style Mastercard uses for its public APIs: OAuth 1.0a request
verification (RSA-SHA256 with `oauth_body_hash`) and JWE field-level encryption of JSON payloads, plus the
matching client-side signer and encryptor. The tests check interoperability against Mastercard's own
open-source client libraries. Runtime dependencies are the JDK (JCA) and Jackson; the Mastercard
libraries are test-scope only.

## Mastercard's scheme in brief

Every call to a Mastercard API is authenticated with one-legged OAuth 1.0a. There are no tokens. The
client registers an RSA key with the Developer Portal, gets a consumer key, and signs each request with
RSA-SHA256 over the RFC 5849 signature base string: the upper-case method, the normalised URL, and the
sorted query and `oauth_*` parameters. OAuth 1.0a only signs form-encoded bodies, so Mastercard adds the
`oauth_body_hash` extension (draft-eaton-oauth-bodyhash): base64(SHA-256(body)) goes into the signed
parameters, which binds a JSON body to the signature. `oauth_timestamp` and `oauth_nonce` give the server
what it needs to reject replays.

Sensitive fields such as card numbers are also encrypted at the application layer, so they stay protected
past TLS termination points. The sender generates a fresh AES-256 key, encrypts the selected JSON field
with AES-GCM (`enc: A256GCM`), wraps the AES key to the recipient's RSA public key with OAEP-SHA-256
(`alg: RSA-OAEP-256`), and replaces the field with a JWE compact string. The `kid` header names the
recipient key.

## What this module implements

| Class | Role |
|---|---|
| `OAuth1Verifier` | Strict `Authorization: OAuth ...` parsing, body-hash check (constant-time compare), signature base string reconstruction, RSA verification, ±skew timestamp window on an injectable `Clock`, nonce replay protection. Returns a sealed `VerificationResult`. |
| `OAuth1Signer` | Client side. Produces the same header as Mastercard's `OAuth.getAuthorizationHeader`. |
| `ConsumerKeyRegistry` / `InMemoryConsumerKeyRegistry` | consumer key → RSA public key (≥ 2048 bits). |
| `NonceStore` / `InMemoryNonceStore` | Atomic record-if-absent for `(consumerKey, nonce)`. Bounded, entries expire at timestamp + skew + 1 s, refuses (fail closed) rather than evicting a live entry when full. Uses a `ReentrantLock`, so virtual threads don't pin. |
| `Jwe` | Compact JWE with RSA-OAEP-256 + A256GCM only. AAD = ASCII of the encoded protected header (RFC 7516 §5.1). Strict base64url, duplicate-key-rejecting header parser. RFC 7516 §11.5 random-CEK substitution on unwrap failure. |
| `FieldLevelEncryption` | Encrypts and decrypts JSON fields by dotted path (`$`, `$.a.b`), wire-compatible with Mastercard's `JweEncryption`, `kid` = `KeyFingerprint.sha256Hex`, several decryption keys selected by `kid` (rotation). |

Rejection reasons are `MALFORMED_HEADER`, `MALFORMED_REQUEST`, `UNSUPPORTED_METHOD`, `STALE_TIMESTAMP`,
`UNKNOWN_CONSUMER`, `BODY_HASH_MISMATCH`, `BAD_SIGNATURE`, `REPLAYED_NONCE` and `NONCE_STORE_FULL`. The
nonce is recorded only after the signature verifies, so unauthenticated requests cannot fill the store or
use up a client's nonces.

## Interop matrix

Libraries: `com.mastercard.developer:oauth1-signer:1.6.0` and `com.mastercard.developer:client-encryption:1.8.9`.
Every row is an executed test, and RSA keys are generated per run.

### OAuth 1.0a (`MastercardOAuthInteropTest`)

| Case | Result |
|---|---|
| Mastercard signs, we verify: POST JSON; GET with `null` and `""` body; unicode body; query with escapes, repeated key, `+`, empty and valueless params; unescaped reserved chars `:/?@!$'()*,;`; empty pair `a=1&&b=2&`; raw non-ASCII query `name=Zoë&city=東京`; encoded path segment `%2F`; `HOST:443` upper case; `http://localhost:8080`; no path | `Verified` (13 requests) |
| Our `OAuth1Signer` with Mastercard's nonce and timestamp, same 13 requests | every header parameter identical, `oauth_signature` byte-for-byte |
| Body changed | `BODY_HASH_MISMATCH` |
| Body changed and `oauth_body_hash` recomputed | `BAD_SIGNATURE` |
| Path, host, scheme, port, query value, added or removed query param, method changed | `BAD_SIGNATURE` |
| `oauth_timestamp`, `oauth_nonce`, `oauth_consumer_key` edited; signature transplanted from another request | `BAD_SIGNATURE` |
| Same header replayed | `REPLAYED_NONCE` |
| Rejected attempt, then the genuine request with the same nonce | `Verified` (nonce not burned) |
| Server clock +6 min / −6 min / +4 min | `STALE_TIMESTAMP` / `STALE_TIMESTAMP` / `Verified` |
| Unknown consumer key; right consumer, wrong private key | `UNKNOWN_CONSUMER`; `BAD_SIGNATURE` |
| `oauth_signature_method` rewritten to `HMAC-SHA1` | `UNSUPPORTED_METHOD` |
| Mastercard `RSA_PSS_SHA256`: default config / PSS enabled / PSS enabled, method changed | `UNSUPPORTED_METHOD` / `Verified` / `BAD_SIGNATURE` |
| `?a=%FF` (not UTF-8) | `MALFORMED_REQUEST` |

### JWE (`MastercardJweInteropTest`)

| Case | Result |
|---|---|
| Mastercard encrypts `$.card` → `$.encryptedCard.encryptedValue` (default 16-byte IV), we decrypt | original document |
| Same with `withEncryptionIVSize(12)` | original document |
| Mastercard's default 16-byte IV against our strict `acceptedIvLengths(12)` | `MALFORMED` |
| Mastercard's header | `alg=RSA-OAEP-256`, `enc=A256GCM`, `cty=application/json`, `kid` = our `KeyFingerprint` = their `getEncryptionKeyFingerprint()` |
| We encrypt `$.card`, Mastercard decrypts | original document |
| Whole payload `$ ↔ {"encryptedData": ...}` (their default), both directions | original document |
| Primitive fields `"pan":"5555…"`, `"name":"Zoë"`: Mastercard → us, Mastercard → Mastercard, us → Mastercard | `pan` comes back as a number in the first two, as a string in the third (see below) |
| Key rotation: JWEs for two server keys, selected by `kid` | both decrypt |
| Ciphertext, tag, IV or encrypted-key bit flipped; protected header re-encoded with changed `cty` | `DECRYPTION_FAILED` |
| Tag truncated to 12 bytes; six parts; padded base64url; missing `kid`; duplicate `alg` in header JSON | `MALFORMED` |
| `alg` → `RSA-OAEP` / `dir`; `enc` → `A128CBC-HS256` / `A128GCM`; `zip` or `crit` present | `UNSUPPORTED_ALGORITHM` |
| Unknown `kid` | `UNKNOWN_KEY` |
| Encrypted to another key under our `kid` | `DECRYPTION_FAILED` (same error as a bad tag) |
| JCA `OAEPWithSHA-256AndMGF1Padding` with no params on a Mastercard-wrapped key | its MGF1 digest is SHA-1 and unwrap throws `BadPaddingException` |

Unit suites `OAuth1VerifierTest`, `InMemoryNonceStoreTest`, `SignatureBaseStringTest` and
`FieldLevelEncryptionTest` cover the header grammar, window edges at second granularity, 32-thread and
1000-virtual-thread nonce races, capacity, path validation, conflicts and decimal-scale preservation.

## Notes

**MGF1 defaults.** `Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")` without an
`OAEPParameterSpec` uses SHA-256 for the label hash but SHA-1 for MGF1. That is not RSA-OAEP-256, which
RFC 7518 §4.3 defines as SHA-256 for both. `Jwe` always passes
`new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSpecified.DEFAULT)`, and a test shows
the default spec failing on a key Mastercard wrapped.

**No algorithm confusion.** The protected header is attacker-controlled. `Jwe.decrypt` accepts exactly
`alg=RSA-OAEP-256` and `enc=A256GCM` and checks both before touching a key. It rejects `zip` and `crit`,
requires `kid`, and rejects duplicate header members, because with duplicates two parsers can read
different values. On the OAuth side, `oauth_signature_method` must be
in a configured set (default `RSA-SHA256` only).

**Query normalisation.** The verifier splits the raw query on `&` and `=` before decoding, signs every
value of a repeated name, and rejects malformed escapes and non-UTF-8 octets instead of replacing them.
Everything else follows Mastercard's signer rather than a strict reading of RFC 5849, because otherwise
their signatures would not verify. Where the two differ:

- Query names and values are used verbatim unless the query contains a `%` escape. If it does, every
  name and value is decoded and re-encoded.
- `+` is a literal plus, not a space.
- `oauth_*` values enter the base string exactly as they appear in the header. Mastercard leaves them
  unencoded, e.g. the `!` in consumer keys and the base64 `+/=` in `oauth_body_hash`.

**Other Mastercard behaviours the tests depend on.** The default GCM IV is 16 bytes, while RFC 7518 §5.3
requires 96 bits, so `acceptedIvLengths` defaults to {12, 16}. Our encryptor always uses 12. Primitive
fields are encrypted with `toString()`, so the string `"5555444433332222"` goes on the wire as bare digits
and comes back as a JSON number through either library. Encrypt objects, not bare strings. We encrypt
primitives as JSON text, which keeps the type through their decryptor. On decryption their library also
removes the whole encrypted container, including sibling fields. This module removes only the value field.
It also refuses to merge a decrypted field over a plaintext field of the same name.

**Not covered:** wildcard or array JSONPaths, the legacy (non-JWE) Mastercard field-level encryption
scheme, `A128CBC-HS256`, and form-encoded bodies as OAuth parameters (Mastercard's signer does not do that
either). The verifier needs the URI the client addressed, so behind a TLS-terminating proxy, rebuild it
from the public base URL.

## Sources read

- https://github.com/Mastercard/oauth1-signer-java: `OAuth.java`, `Util.java`, `SignatureMethod.java`
- https://github.com/Mastercard/client-encryption-java: `JweEncryption.java`, `JweObject.java`,
  `JweHeader.java`, `AESGCM.java`, `AESCBC.java`, `RSA.java`, `JsonParser.java`, `JsonEngine.java`,
  `EncryptionConfigBuilder.java`, `JweConfigBuilder.java`

The code was read from the `-sources.jar` that Maven Central publishes for exactly 1.6.0 and 1.8.9.

## Run

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd /Users/mac/cardrail
mvn -q -B -pl cardrail-apisec verify      # add -o once the Mastercard jars are in ~/.m2
```
