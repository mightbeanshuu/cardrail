# cardrail-iso8583

ISO 8583 codec, TCP framing and EMV BER-TLV parsing for cardrail. Java 21, no runtime dependencies.

This implements one documented ASCII variant of ISO 8583:1987 (see `docs/SPEC.md`). It is not any
card network's dialect. Real networks use their own field definitions, private-use fields, and often
binary bitmaps, BCD-packed numerics or EBCDIC, none of which this module claims to speak.

## Wire format

```
[2-byte big-endian length] MTI(4) PRIMARY-BITMAP(16 hex) [SECONDARY-BITMAP(16 hex)] fields...
```

- MTI and all field data are ASCII. Bitmaps are 16 uppercase hex characters each, bit 1 leftmost.
- Bit 1 of the primary bitmap is set if and only if a field 65-128 is present; then the secondary
  bitmap follows.
- Variable fields carry a 2-digit (LLVAR) or 3-digit (LLLVAR) ASCII length prefix that counts characters.
- Fixed `n` fields are left-zero-padded, fixed `an`/`ans` fields right-space-padded. Values that are too
  long are rejected, never truncated.
- Field 55 is binary sent as hex ASCII, so its length prefix counts hex characters. The 999 limit
  therefore allows at most 998 hex chars (499 bytes).
- The frame length header covers the message only, not itself.

Why ASCII bitmaps: a message can be read in a hex dump or a log line without tooling, and every byte is
printable, which makes the golden tests readable. A binary bitmap would save 16 bytes per message, which
does not matter here.

## Fields

| Field | Name | Format |
|---|---|---|
| 2 | Primary account number | n..19 LLVAR |
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
| 42 | Card acceptor ID | ans15 |
| 43 | Card acceptor name/location | ans40 |
| 49 | Currency code, transaction | n3 |
| 55 | ICC data (BER-TLV) | b...999 LLLVAR, hex ASCII |
| 70 | Network management information code | n3 |
| 90 | Original data elements | n42 |

Character classes: `n` = `0-9`; `an` = letters, digits and space (space is needed for padding);
`ans` = printable ASCII `0x20-0x7E`; `b` = hex digits, lowercase accepted and normalised to uppercase.
`MessageSpec.builder(base)` defines other field sets; the codec rejects any field outside its spec.

## Responses

`IsoCodec.responseFor(request, responseCode, authId)` builds the 0110/0410/0810 (or advice response)
for a request. It sets the MTI with function digit + 1 and copies these fields when the request has them:

| Field | Echoed | Why |
|---|---|---|
| 2, 3, 4 | yes | PAN, processing code and amount identify what was decided |
| 7, 11 | yes | transmission time and STAN are how the acquirer matches the response |
| 12, 13 | yes | local time/date of the original |
| 32 | yes | acquirer ID, part of the match key |
| 37 | yes | retrieval reference number |
| 41, 42 | yes | terminal and merchant |
| 49 | yes | currency of field 4 |
| 70 | yes | network management code (0810 must carry it back) |
| 90 | yes | original data elements (0410 identifies the reversed transaction) |
| 38 | set | from `authId`; omitted when `null` (declines, 0810) |
| 39 | set | from `responseCode` |
| 14, 18, 19, 22, 43, 55 | no | request-only data; an issuer's field 55 response (ARPC) is built separately |

## Decoding is strict

`IsoCodec.decode` throws `IsoParseException` (field number or -1, byte offset, reason) for: truncated
input, a non-digit MTI, a non-hex bitmap, a secondary bitmap with no bits set, any bitmap bit for a field
not in the spec, a non-numeric or over-maximum length prefix, a character outside the field's class,
odd-length hex, and trailing bytes. Field 55 is checked to be hex; its TLV structure is parsed separately
with `BerTlv`, so a malformed chip payload can be answered rather than dropped at the codec.

## Logging

`IsoMessage.toString()` masks field 2 to first 6 + last 4 (fully masked below 13 digits, where that would
expose too much) and prints only the length of fields 35, 36, 45 (track data), 52 (PIN block) and 55.
`Tlv.toString()` does the same for EMV tags 5A (PAN) and 57 (track 2 equivalent).

## EMV (`dev.cardrail.iso8583.emv`)

`BerTlv` decodes and encodes BER-TLV per EMV Book 3 Annex B: multi-byte tags (low 5 bits `11111`,
then continuation bit 8), short-form lengths and long-form `81 xx` / `82 xx xx`, and constructed tags
(bit 6 of the first tag byte) parsed recursively, up to 8 levels. Indefinite length (`80`) is rejected.
`decodeHex`/`encodeHex` convert to and from the field 55 representation. `EmvTag` names the tags usually
present in an authorization request (9F26, 9F27, 9F10, 9F37, 9F36, 95, 9A, 9C, 5F2A, 82, 9F1A, 9F02, 9F03,
84, 9F33, 9F34, 9F35, 9F09, 9F1E, plus 5A and 57).

## Framing

`FrameReader.read()` returns `Optional.empty()` at a clean end of stream between frames, throws
`EOFException` if the stream ends inside a header or payload, and `ProtocolException` for a zero-length
frame or one longer than the configured maximum (checked before reading the payload). `FrameWriter`
writes header and payload in one call under a lock, so it can be shared by concurrent writers.

## Tests

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -q -pl cardrail-iso8583 verify
```

- `GoldenMessageTest`: 0100, 0110, 0400 (field 90, secondary bitmap) and 0800/0810 (field 70) built by
  hand, character by character, with each bitmap derived in comments and its set bits asserted.
- `IsoCodecStrictnessTest`: each rejection above, with the expected field and offset.
- `IsoCodecPropertyTest` (jqwik): `decode(encode(m)).equals(m)` over arbitrary valid messages;
  arbitrary bytes, plausible headers with garbage bodies, and mutated valid messages (overwrite,
  insert, delete, truncate) must decode or throw `IsoParseException`, nothing else; `toString()` never
  contains the PAN.
- `BerTlvTest`, `BerTlvPropertyTest`: a realistic field 55 round-trip, long-form lengths, nested
  constructed tags, malformed input, and fuzzing.
- `FramingTest`, `FieldModelTest`: framing edge cases, padding, MTI parsing.
