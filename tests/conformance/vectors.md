# Conformance test vectors

Language-neutral byte vectors for the SC6103 flight-information wire protocol. The
Java client and the C++ server are both tested against this file; neither is tested
against the other's code. → `README.md` §11 (cross-language contract), D2.

Normative sources: `README.md` §3 (wire protocol), §4 (operation catalogue),
§5 (status codes), §11 (cross-language contract). **Nothing here is invented.**
Where a value was needed that those sections do not fix, it is listed in
[Conventions this file fixes](#conventions-this-file-fixes) and flagged in
[Discrepancies found](#discrepancies-found) — not silently decided.

**This file is data only. It contains no code and must never contain any.**

Contents: **7 vectors** (V1–V7), of which V2 is a group of 18 primitive encodings.

---

## How to use this file

For every vector, each implementation runs two tests:

1. **Encode.** Build the value described in the vector's "Value" block using the
   implementation's own marshaller, then assert the produced `byte[]` is **byte-for-byte
   equal** to the hex given here, and that its length equals the stated total.
2. **Decode.** Feed the hex here into the implementation's unmarshaller, assert every
   decoded field equals the value described, then re-encode the decoded value and assert
   it reproduces the same bytes (round-trip).

Rules that the assertions must respect:

- **Compare floats by bit pattern, not by value.** `-0.0 == 0.0` is true in both Java and
  C++, so a value comparison passes on a wrong sign bit. Compare
  `Float.floatToRawIntBits` / the `memcpy`'d `uint32_t` instead. → §11.1
- **Padding bytes are written as zero and ignored on read.** § 3.2. A decoder must not
  reject nonzero padding, but an encoder that emits nonzero padding fails test 1.
- **`payloadLen` must equal the datagram length minus 40** on every full-datagram vector.
  That check (§3.6 step 4) runs before any body decoding, so test it explicitly.
- **Java: mask every byte read with `& 0xFF`.** Java's `byte` is signed; the vectors
  contain `0xFF`, `0xA1`, `0xE0` and `0x80` precisely so a missing mask fails here rather
  than in the demo. → §11.1
- **A `uint32` whose top bit is set does not fit a Java `int`.** V2's `4294967295` and the
  `clientId` `0xA1B2C3D4` are deliberate: Java holds them as the negative `int` with the
  same bit pattern, and prints them via `Integer.toUnsignedString`. The wire bytes are
  what is asserted, never the signed decimal.
- Hex is written most-significant byte first, which is also transmission order
  (big-endian, §3.2). `0x0000` in a dump is the first byte of the datagram, so for
  full-datagram vectors the body begins at `0x0028` = 40.

Fixed constants used throughout: `MAGIC = 0x53433631` (`"SC61"`), `VERSION = 1`,
`HEADER_SIZE = 40`, `MAX_DATAGRAM = 512`, `MAX_BODY = 472`, `MAX_STRING_BYTES = 63`.
→ §3.4, §3.5

---

## Conventions this file fixes

Header field values that §3.4 leaves undefined for some message types. A vector cannot
omit a field — every datagram has all ten header fields — so each is pinned here with its
reasoning, and each is raised in [Discrepancies found](#discrepancies-found) as a README
amendment to make.

| Field | Where undefined | Value used here | Why |
|---|---|---|---|
| `ackUpTo` | §3.4 says "requests only"; no value is given for REPLY, CALLBACK or MONITOR_END | `-1` (`FF FF FF FF`) | `-1` is the field's own defined "nothing acknowledged" sentinel. `0` is a **legal** `ackUpTo` meaning "I hold a reply for requestId 0", so zero-filling would be indistinguishable from a real watermark if the field were ever read on the wrong message type. |
| `status` | §3.4 says "replies and callbacks only" — MONITOR_END is `messageType = 3`, neither | `0` | §4 fixes `status = 0` for CALLBACK; MONITOR_END is the same unsolicited, non-error class of message. |
| `semantics` | not stated to be echoed in REPLY / CALLBACK / MONITOR_END | echoed from the request (`1` in these vectors) | The server rejects a mismatched client outright (§6.1), so server and client are in the same mode by the time any reply is emitted; echoing keeps the field meaningful on captured traffic. |
| `clientId`, `requestId` in MONITOR_END | §4 fixes these for CALLBACK but the MONITOR_END subsection specifies only the body | the monitored registration's `clientId` and `monitorRequestId` | MONITOR_END terminates exactly one registration; without the registration's `requestId` a client with two monitors cannot tell which one ended. |

Values chosen freely by this file, carrying no normative weight — an implementation must
reproduce them only because the vector says so:

- `clientId` `0xA1B2C3D4` — the querying/reserving client (V3, V4, V7).
- `clientId` `0x0F0E0D0C` — the monitoring client (V5, V6). Distinct from the above so a
  test that crosses the two fails.
- `requestId` `7` (V3/V4), `8` (V7), `11` (the MONITOR registration, V5/V6),
  `22` (`eventRequestId`, the reserving client's request that caused the V5 callback).
  That reserving client is `0xA1B2C3D4`, so V5's `eventClientId` reuses the first
  `clientId` above.
- `semantics = 1` (at-most-once) throughout.

Both `clientId`s are nonzero, as §2.3 requires.

---

## V1 — FlightRecord (anchor vector)

Reproduced **exactly** as `README.md` §11.2 gives it. This is the anchor: if V1 fails,
nothing else in this file is worth running. It exercises `int32`, two padded strings,
the five-field `Time`, and a `float32` in one buffer.

This is a **codec anchor, not a datagram** — it has no header, and no operation in §4
transmits a whole FlightRecord (see [D-4](#d-4)).

**Value**

```
flightId       = 1934
source         = "Singapore"
destination    = "London"
departureTime  = { year 2026, month 10, day 14, hour 9, minute 30 }
airfare        = 899.5f
seatsAvailable = 120
```

**Bytes — total 60 (0x3C)**

```
offset  bytes                                    field
0x0000  00 00 07 8E                              flightId = 1934
0x0004  00 00 00 09                              source length = 9
0x0008  53 69 6E 67 61 70 6F 72 65 00 00 00      "Singapore" + 3 pad
0x0014  00 00 00 06                              destination length = 6
0x0018  4C 6F 6E 64 6F 6E 00 00                  "London" + 2 pad
0x0020  00 00 07 EA                              year   = 2026
0x0024  00 00 00 0A                              month  = 10
0x0028  00 00 00 0E                              day    = 14
0x002C  00 00 00 09                              hour   = 9
0x0030  00 00 00 1E                              minute = 30
0x0034  44 60 E0 00                              airfare = 899.5f     [F5]
0x0038  00 00 00 78                              seatsAvailable = 120
                                                 total = 60 bytes
```

**Flat hex**

```
00 00 07 8E 00 00 00 09 53 69 6E 67 61 70 6F 72
65 00 00 00 00 00 00 06 4C 6F 6E 64 6F 6E 00 00
00 00 07 EA 00 00 00 0A 00 00 00 0E 00 00 00 09
00 00 00 1E 44 60 E0 00 00 00 00 78
```

**Length check**

| Field | Size | Running end |
|---|---|---|
| `flightId` | 4 | 4 |
| `source` (4 len + 9 bytes + 3 pad) | 16 | 20 = 0x14 |
| `destination` (4 len + 6 bytes + 2 pad) | 12 | 32 = 0x20 |
| `departureTime` (5 × 4) | 20 | 52 = 0x34 |
| `airfare` | 4 | 56 = 0x38 |
| `seatsAvailable` | 4 | **60 = 0x3C** |

`4 + 16 + 12 + 20 + 4 + 4 = 60` ✓, and every field starts at a multiple of 4 ✓ (§3.2).

---

## V2 — Primitives in isolation

Each item is a standalone encoding; offsets are relative to the start of that field.
A failure here localises the fault to one primitive before any composite vector runs.

### V2.1 `int32` — 4 bytes each (§3.3, two's complement, big-endian)

| Value | Hex | Total size |
|---|---|---|
| `0` | `00 00 00 00` | 4 |
| `1` | `00 00 00 01` | 4 |
| `-1` | `FF FF FF FF` | 4 |
| `2147483647` (`INT32_MAX`) | `7F FF FF FF` | 4 |
| `-2147483648` (`INT32_MIN`) | `80 00 00 00` | 4 |

`-1` and `INT32_MIN` are the two values that expose a sign-extension or unsigned-shift
error. `-1` is also the on-wire form of `ackUpTo` when nothing is acknowledged (§3.4).

### V2.2 `uint32` — 4 bytes each

| Value | Hex | Total size |
|---|---|---|
| `0` | `00 00 00 00` | 4 |
| `4294967295` (`UINT32_MAX`) | `FF FF FF FF` | 4 |

`uint32` and `int32` share their encoding; only the interpretation differs. Java holds
`4294967295` as the `int` `-1` and must print it with `Integer.toUnsignedString`. The
byte assertion is what matters. → §11.1

### V2.3 `float32` — IEEE-754 binary32, 4 bytes each (§3.3, D16)

| Value | Hex | Total size | Footnote |
|---|---|---|---|
| `0.0f` | `00 00 00 00` | 4 | [F1] |
| `-0.0f` | `80 00 00 00` | 4 | [F2] |
| `1.0f` | `3F 80 00 00` | 4 | [F3] |
| `-1.0f` | `BF 80 00 00` | 4 | [F4] |
| `899.5f` | `44 60 E0 00` | 4 | [F5] |
| `0.1f` | `3D CC CC CD` | 4 | [F6] |

Full bit decompositions are in [Float bit patterns](#float-bit-patterns) below. Every one
was derived by hand and independently confirmed against the values `README.md` §11.2
states for `899.5f`.

`-0.0f` and `0.1f` are the two that catch real bugs: `-0.0f` catches a sign bit dropped by
a value-comparison assertion, and `0.1f` catches a decimal round-trip through text (any
implementation that formats and re-parses instead of transferring bits will not land on
`0x3DCCCCCD`).

### V2.4 `string` — `uint32` byte length, UTF-8 bytes, 0–3 zero pad (§3.3, D15)

Padding is `(4 − (len mod 4)) mod 4`. Total encoded size is always `4 + len + pad`, always
a multiple of 4. No NUL terminator is transmitted; the trailing zeros are alignment
padding, not a terminator.

**`""` (empty) — len 0, pad 0, total 4**

```
offset  bytes                                    field
0x0000  00 00 00 00                              length = 0
                                                 (no bytes, no pad)
                                                 total = 4 bytes
```

**`"A"` — len 1, pad 3, total 8**

```
offset  bytes                                    field
0x0000  00 00 00 01                              length = 1
0x0004  41                                       "A"
0x0005  00 00 00                                 3 pad
                                                 total = 8 bytes
```

**`"abc"` — len 3, pad 1, total 8**

```
offset  bytes                                    field
0x0000  00 00 00 03                              length = 3
0x0004  61 62 63                                 "abc"
0x0007  00                                       1 pad
                                                 total = 8 bytes
```

**`"abcd"` — len 4, pad 0, total 8**

```
offset  bytes                                    field
0x0000  00 00 00 04                              length = 4
0x0004  61 62 63 64                              "abcd"
                                                 (no pad — already aligned)
                                                 total = 8 bytes
```

`"abc"` and `"abcd"` differ by one content byte but encode to the same total size. An
implementation that pads unconditionally (always emitting 1–4 zeros) passes `"abc"` and
fails `"abcd"` — which is why both are here.

**63-byte string (`MAX_STRING_BYTES`) — len 63, pad 1, total 68**

Content, exactly 63 ASCII bytes: uppercase A–Z, lowercase a–z, digits 0–9, then `A`.

```
ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789A
```

```
offset  bytes                                              field
0x0000  00 00 00 3F                                        length = 63
0x0004  41 42 43 44 45 46 47 48 49 4A 4B 4C 4D 4E 4F 50    "ABCDEFGHIJKLMNOP"
0x0014  51 52 53 54 55 56 57 58 59 5A 61 62 63 64 65 66    "QRSTUVWXYZabcdef"
0x0024  67 68 69 6A 6B 6C 6D 6E 6F 70 71 72 73 74 75 76    "ghijklmnopqrstuv"
0x0034  77 78 79 7A 30 31 32 33 34 35 36 37 38 39 41       "wxyz0123456789A"
0x0043  00                                                 1 pad
                                                           total = 68 bytes
```

Length check: `4 + 63 + 1 = 68` ✓; `0x0004 + 63 = 0x0043`, pad byte at `0x0043`, next
field would begin at `0x0044 = 68`, a multiple of 4 ✓.

63 is the largest legal string (§3.5). A decoder must **reject a decoded length of 64 or
more, and any negative length, before allocating** — §11.1. That rejection is a decoder
test, not a vector, since a malformed input has no correct encoding.

**Encoded-size summary**

| String | Byte length | Pad | Total |
|---|---|---|---|
| `""` | 0 | 0 | 4 |
| `"A"` | 1 | 3 | 8 |
| `"abc"` | 3 | 1 | 8 |
| `"abcd"` | 4 | 0 | 8 |
| 63-byte max | 63 | 1 | 68 |
| `"Singapore"` (used in V1, V3) | 9 | 3 | 16 |
| `"London"` (used in V1, V3) | 6 | 2 | 12 |

---

## V3 — REQUEST · `QUERY_BY_ROUTE("Singapore", "London")`

Op 1, §4. First full datagram: 40-byte header plus a two-string body.

**Value**

```
header:  messageType = 0 (REQUEST), opCode = 1 (QUERY_BY_ROUTE)
         version = 1, semantics = 1 (at-most-once)
         clientId = 0xA1B2C3D4, requestId = 7, ackUpTo = 6
         status = 0 (requests carry 0, §3.4), payloadLen = 28
body:    source      = "Singapore"
         destination = "London"
```

`ackUpTo = 6` says this client already holds the reply for requestId 6, so the server may
discard history entries with `requestId ≤ 6` for this client (§8.3, D8). It is deliberately
not `-1`, so the field is exercised with a real watermark.

**Bytes — total 68 (0x44) = 40 header + 28 body**

```
offset  bytes                                    field
0x0000  53 43 36 31                              magic = 0x53433631 "SC61"
0x0004  00 00 00 01                              version = 1
0x0008  00 00 00 00                              messageType = 0 (REQUEST)
0x000C  00 00 00 01                              opCode = 1 (QUERY_BY_ROUTE)
0x0010  00 00 00 01                              semantics = 1 (at-most-once)
0x0014  A1 B2 C3 D4                              clientId = 0xA1B2C3D4
0x0018  00 00 00 07                              requestId = 7
0x001C  00 00 00 06                              ackUpTo = 6
0x0020  00 00 00 00                              status = 0 (unused in requests)
0x0024  00 00 00 1C                              payloadLen = 28
                                                 --- body begins at 0x0028 = 40 ---
0x0028  00 00 00 09                              source length = 9
0x002C  53 69 6E 67 61 70 6F 72 65 00 00 00      "Singapore" + 3 pad
0x0038  00 00 00 06                              destination length = 6
0x003C  4C 6F 6E 64 6F 6E 00 00                  "London" + 2 pad
                                                 total = 68 bytes
```

**Flat hex**

```
53 43 36 31 00 00 00 01 00 00 00 00 00 00 00 01
00 00 00 01 A1 B2 C3 D4 00 00 00 07 00 00 00 06
00 00 00 00 00 00 00 1C 00 00 00 09 53 69 6E 67
61 70 6F 72 65 00 00 00 00 00 00 06 4C 6F 6E 64
6F 6E 00 00
```

**Length check**

- Body: `"Singapore"` 16 + `"London"` 12 = **28** = `0x1C` = `payloadLen` ✓
- Datagram: `40 + 28 = 68` ✓, so `received == HEADER_SIZE + payloadLen` holds (§3.6 step 4)
- `68 ≤ 512` (`MAX_DATAGRAM`) ✓ and `28 ≤ 472` (`MAX_BODY`) ✓
- Body offsets: `0x28 + 16 = 0x38` ✓, `0x38 + 12 = 0x44 = 68` ✓

The V1 encodings of `"Singapore"` and `"London"` are byte-identical to this body's, at
`0x0004`–`0x001F` in V1 and `0x0028`–`0x0043` here. A cross-check between the two catches
an encoder whose string routine depends on its absolute buffer position.

---

## V4 — REPLY · `QUERY_BY_ROUTE`, status OK, 3 results

The reply to V3. Echoes `opCode`, `clientId` and `requestId` from the request (§3.4).

**Value**

```
header:  messageType = 1 (REPLY), opCode = 1, version = 1, semantics = 1
         clientId = 0xA1B2C3D4, requestId = 7, ackUpTo = -1 (see conventions)
         status = 0 (OK), payloadLen = 20
body:    truncated = 0
         count     = 3
         flightIds = 88, 1934, 2201
```

**`flightIds` are in ascending order**, which §4 Op 1 requires: "The repository's
iteration order is not an interface, and a test that asserts on results must not depend on
it." The task description listed these ids as 1934, 2201, 88 — on the wire they are sorted
to 88, 1934, 2201. An implementation that emits repository order fails this vector, which
is the point of including an out-of-order-looking set.

**Bytes — total 60 (0x3C) = 40 header + 20 body**

```
offset  bytes                                    field
0x0000  53 43 36 31                              magic = 0x53433631 "SC61"
0x0004  00 00 00 01                              version = 1
0x0008  00 00 00 01                              messageType = 1 (REPLY)
0x000C  00 00 00 01                              opCode = 1 (QUERY_BY_ROUTE, echoed)
0x0010  00 00 00 01                              semantics = 1 (at-most-once)
0x0014  A1 B2 C3 D4                              clientId = 0xA1B2C3D4 (echoed)
0x0018  00 00 00 07                              requestId = 7 (echoed)
0x001C  FF FF FF FF                              ackUpTo = -1 (unused in replies)
0x0020  00 00 00 00                              status = 0 (OK)
0x0024  00 00 00 14                              payloadLen = 20
                                                 --- body begins at 0x0028 = 40 ---
0x0028  00 00 00 00                              truncated = 0
0x002C  00 00 00 03                              count = 3
0x0030  00 00 00 58                              flightIds[0] = 88
0x0034  00 00 07 8E                              flightIds[1] = 1934
0x0038  00 00 08 99                              flightIds[2] = 2201
                                                 total = 60 bytes
```

**Flat hex**

```
53 43 36 31 00 00 00 01 00 00 00 01 00 00 00 01
00 00 00 01 A1 B2 C3 D4 00 00 00 07 FF FF FF FF
00 00 00 00 00 00 00 14 00 00 00 00 00 00 00 03
00 00 00 58 00 00 07 8E 00 00 08 99
```

**Length check**

- Body: `truncated` 4 + `count` 4 + `3 × 4` = **20** = `0x14` = `payloadLen` ✓
- Datagram: `40 + 20 = 60` ✓
- Body offsets: `0x28`, `0x2C`, then ids at `0x30`, `0x34`, `0x38`, ending `0x3C = 60` ✓
- At the cap, `count = 64` would give `4 + 4 + 256 = 264 ≤ 472` ✓ (§3.5), so no legal
  result set can overflow the datagram.

`flightIds[1] = 1934` is V1's `flightId`, and `0x0034` here holds the same four bytes
`00 00 07 8E` as V1's `0x0000`.

---

## V5 — CALLBACK

Unsolicited, unacknowledged, never retried (§4 CALLBACK, D10). `opCode = 0` and
`status = 0` are fixed by §4.

**Value**

```
header:  messageType = 2 (CALLBACK), opCode = 0, version = 1, semantics = 1
         clientId  = 0x0F0E0D0C  (the monitoring client)
         requestId = 11          (the MONITOR registration being served, §4)
         ackUpTo = -1 (see conventions), status = 0, payloadLen = 20
body:    flightId       = 1934
         seatsAvailable = 117
         eventClientId  = 0xA1B2C3D4  (clientId of the client whose operation caused this update)
         eventRequestId = 22     (requestId of the operation that caused this update)
         callbackSeq    = 1      (per-registration counter, from 1)
```

`requestId = 11` and `eventRequestId = 22` are different on purpose: `requestId` belongs to
the monitoring client's own MONITOR, while `eventRequestId` belongs to a *different*
client's RESERVE. An implementation that writes one into the other's slot produces a
datagram that decodes without error and means the wrong thing, so the two values must not
be allowed to collide in a test.

The same holds one field earlier. The header's `clientId` is the monitoring client and
`eventClientId` is the reserving client, and they differ here for the same reason.
`eventClientId` is a `uint32` with its top bit set, so a Java decoder that reads it as a
signed `int` gets a negative number where this vector expects `0xA1B2C3D4`.

**Bytes — total 60 (0x3C) = 40 header + 20 body**

```
offset  bytes                                    field
0x0000  53 43 36 31                              magic = 0x53433631 "SC61"
0x0004  00 00 00 01                              version = 1
0x0008  00 00 00 02                              messageType = 2 (CALLBACK)
0x000C  00 00 00 00                              opCode = 0 (fixed for callbacks, §4)
0x0010  00 00 00 01                              semantics = 1 (at-most-once)
0x0014  0F 0E 0D 0C                              clientId = 0x0F0E0D0C
0x0018  00 00 00 0B                              requestId = 11 (the MONITOR)
0x001C  FF FF FF FF                              ackUpTo = -1 (unused)
0x0020  00 00 00 00                              status = 0 (fixed for callbacks, §4)
0x0024  00 00 00 14                              payloadLen = 20
                                                 --- body begins at 0x0028 = 40 ---
0x0028  00 00 07 8E                              flightId = 1934
0x002C  00 00 00 75                              seatsAvailable = 117
0x0030  A1 B2 C3 D4                              eventClientId = 0xA1B2C3D4
0x0034  00 00 00 16                              eventRequestId = 22
0x0038  00 00 00 01                              callbackSeq = 1
                                                 total = 60 bytes
```

**Flat hex**

```
53 43 36 31 00 00 00 01 00 00 00 02 00 00 00 00
00 00 00 01 0F 0E 0D 0C 00 00 00 0B FF FF FF FF
00 00 00 00 00 00 00 14 00 00 07 8E 00 00 00 75
A1 B2 C3 D4 00 00 00 16 00 00 00 01
```

**Length check**

- Body: `5 × 4` = **20** = `0x14` = `payloadLen` ✓
- Datagram: `40 + 20 = 60` ✓
- Body offsets `0x28`, `0x2C`, `0x30`, `0x34`, `0x38`, ending `0x3C = 60` ✓

---

## V6 — MONITOR_END

Sent once when a registration expires. Advisory only, unacknowledged, may itself be lost;
the client's local deadline stays authoritative (§4 MONITOR_END, D20).

**Value**

```
header:  messageType = 3 (MONITOR_END), opCode = 0, version = 1, semantics = 1
         clientId  = 0x0F0E0D0C, requestId = 11   (same registration as V5)
         ackUpTo = -1, status = 0 (see conventions), payloadLen = 8
body:    flightId    = 1934
         callbackSeq = 4   (total callbacks sent for this registration)
```

Paired with V5 this is a complete registration story: the client saw `callbackSeq = 1` and
is told the final count was 4, so it reports three updates missed rather than merely
detecting a gap.

**Bytes — total 48 (0x30) = 40 header + 8 body**

```
offset  bytes                                    field
0x0000  53 43 36 31                              magic = 0x53433631 "SC61"
0x0004  00 00 00 01                              version = 1
0x0008  00 00 00 03                              messageType = 3 (MONITOR_END)
0x000C  00 00 00 00                              opCode = 0
0x0010  00 00 00 01                              semantics = 1 (at-most-once)
0x0014  0F 0E 0D 0C                              clientId = 0x0F0E0D0C
0x0018  00 00 00 0B                              requestId = 11 (the MONITOR)
0x001C  FF FF FF FF                              ackUpTo = -1 (unused)
0x0020  00 00 00 00                              status = 0
0x0024  00 00 00 08                              payloadLen = 8
                                                 --- body begins at 0x0028 = 40 ---
0x0028  00 00 07 8E                              flightId = 1934
0x002C  00 00 00 04                              callbackSeq = 4
                                                 total = 48 bytes
```

**Flat hex**

```
53 43 36 31 00 00 00 01 00 00 00 03 00 00 00 00
00 00 00 01 0F 0E 0D 0C 00 00 00 0B FF FF FF FF
00 00 00 00 00 00 00 08 00 00 07 8E 00 00 00 04
```

**Length check**

- Body: `2 × 4` = **8** = `payloadLen` ✓
- Datagram: `40 + 8 = 48` ✓
- Body offsets `0x28`, `0x2C`, ending `0x30 = 48` ✓

V5 and V6 differ in exactly one header byte (`0x000B`: `02` versus `03`) plus their bodies.
A dispatcher that switches on `messageType` correctly must still route these apart.

---

## V7 — Error REPLY · `ERR_UNKNOWN_FLIGHT`, no body

"Error replies carry a header and no body. Human-readable text is generated client-side
from the code." (§5, D4.) The reply to a `QUERY_BY_ID` for a flight that does not exist.

**Value**

```
header:  messageType = 1 (REPLY), opCode = 2 (QUERY_BY_ID, echoed), version = 1
         semantics = 1, clientId = 0xA1B2C3D4, requestId = 8
         ackUpTo = -1 (see conventions)
         status = 1 (ERR_UNKNOWN_FLIGHT), payloadLen = 0
body:    none
```

**Bytes — total 40 (0x28), header only**

```
offset  bytes                                    field
0x0000  53 43 36 31                              magic = 0x53433631 "SC61"
0x0004  00 00 00 01                              version = 1
0x0008  00 00 00 01                              messageType = 1 (REPLY)
0x000C  00 00 00 02                              opCode = 2 (QUERY_BY_ID, echoed)
0x0010  00 00 00 01                              semantics = 1 (at-most-once)
0x0014  A1 B2 C3 D4                              clientId = 0xA1B2C3D4 (echoed)
0x0018  00 00 00 08                              requestId = 8 (echoed)
0x001C  FF FF FF FF                              ackUpTo = -1 (unused in replies)
0x0020  00 00 00 01                              status = 1 (ERR_UNKNOWN_FLIGHT)
0x0024  00 00 00 00                              payloadLen = 0
                                                 --- no body ---
                                                 total = 40 bytes
```

**Flat hex**

```
53 43 36 31 00 00 00 01 00 00 00 01 00 00 00 02
00 00 00 01 A1 B2 C3 D4 00 00 00 08 FF FF FF FF
00 00 00 01 00 00 00 00
```

**Length check**

- Ten header fields × 4 bytes = **40** = `HEADER_SIZE` ✓
- `payloadLen = 0`, so `received == 40 + 0 == 40` ✓ (§3.6 step 4)
- The datagram ends at `0x0028`, the offset at which a body would have begun. There is no
  padding, no terminator and no zero-length body marker — a zero `payloadLen` is the whole
  statement.

This vector is the one that catches a receiver which unconditionally reads a body, and a
sender which pads every datagram to a minimum size.

---

## Float bit patterns

IEEE-754 binary32: `[sign 1 bit][biased exponent 8 bits][mantissa 23 bits]`, transmitted
most-significant byte first (§3.3, D16). Bias is 127; for a normal number the value is
`(-1)^sign × 1.mantissa × 2^(exponent − 127)`. Each decomposition below was derived by
hand; the assembled nibbles are shown so the arithmetic can be checked without a machine.

<a id="f1"></a>**[F1] `0.0f` → `00 00 00 00`**

```
sign     = 0
exponent = 0        (biased; the all-zero exponent encodes zero/subnormal,
                     so there is no implicit leading 1)
mantissa = 0
bits     = 0 00000000 00000000000000000000000
nibbles  = 0000 0000 0000 0000 0000 0000 0000 0000  =  0x00000000
```

<a id="f2"></a>**[F2] `-0.0f` → `80 00 00 00`**

```
sign     = 1
exponent = 0
mantissa = 0
bits     = 1 00000000 00000000000000000000000
nibbles  = 1000 0000 0000 0000 0000 0000 0000 0000  =  0x80000000
```

Differs from `0.0f` in exactly one bit. `0.0f == -0.0f` is true in both languages, so this
must be asserted on the bit pattern.

<a id="f3"></a>**[F3] `1.0f` → `3F 80 00 00`**

```
1.0 = 1.0 × 2^0                    unbiased exponent 0
sign     = 0
exponent = 0 + 127 = 127 = 0x7F = 01111111
mantissa = 0                       (the significand's leading 1 is implicit)
bits     = 0 01111111 00000000000000000000000
nibbles  = 0011 1111 1000 0000 0000 0000 0000 0000  =  0x3F800000
```

<a id="f4"></a>**[F4] `-1.0f` → `BF 80 00 00`**

```
sign     = 1                       (only the sign bit differs from 1.0f)
exponent = 127 = 01111111
mantissa = 0
bits     = 1 01111111 00000000000000000000000
nibbles  = 1011 1111 1000 0000 0000 0000 0000 0000  =  0xBF800000
```

<a id="f5"></a>**[F5] `899.5f` → `44 60 E0 00`** — the value in V1, from `README.md` §11.2

```
899        = 512 + 256 + 128 + 2 + 1 = 1110000011b
899.5      = 1110000011.1b
normalised = 1.1100000111b × 2^9    unbiased exponent 9
sign     = 0
exponent = 9 + 127 = 136 = 0x88 = 10001000
mantissa = 1100000111 followed by 13 zeros  (10 + 13 = 23 bits)
bits     = 0 10001000 11000001110000000000000
nibbles  = 0100 0100 0110 0000 1110 0000 0000 0000  =  0x4460E000
```

Exactly representable — 899.5 is a dyadic rational, so no rounding occurs and the stored
value is the decimal value. This matches `README.md` §11.2 verbatim: "sign 0, biased
exponent 136, mantissa `1100000111` then zeros, giving `0x4460E000`."

<a id="f6"></a>**[F6] `0.1f` → `3D CC CC CD`**

```
0.1        = 1.6 × 2^-4            unbiased exponent -4
sign     = 0
exponent = -4 + 127 = 123 = 0x7B = 01111011
fraction 0.6 in binary = 0.1001100110011001100110011001...  ("1001" repeating)
first 23 bits            10011001100110011001100
bit 24 is 1 with a nonzero tail, so round-to-nearest rounds up:
mantissa =               10011001100110011001101
bits     = 0 01111011 10011001100110011001101
nibbles  = 0011 1101 1100 1100 1100 1100 1100 1101  =  0x3DCCCCCD
```

**Not** exactly representable. The stored value is
`0.100000001490116119384765625`, i.e. `(1 + 5033165/8388608) × 2^-4`. This is the vector
that fails if a float ever crosses the wire as text.

---

## Discrepancies found — **all resolved**

Points where these vectors could not be derived from `README.md` §3–§5 and §11 without a
choice, or where those sections were internally incomplete. **Nothing was silently
fixed**, and every item below has since been written into the specification.

**Status: closed on 3 September 2026.** Each entry keeps its original finding and gains a
resolution line naming the section that now settles it. No vector byte changed — the
README adopted what these vectors already asserted, which is the outcome this file was
built to produce. The findings are retained rather than deleted because they are the
evidence that building the conformance vector *before* the second implementation catches
specification gaps while they are still cheap (README §20).

A sixth item, **D-6**, was found later and is recorded at the end.

<a id="d-1"></a>**D-1 · `ackUpTo` has no defined value outside REQUEST.**
§3.4 describes it as "requests only; highest `requestId` this client has a reply for, or
−1", but never says what a REPLY, CALLBACK or MONITOR_END must put there. Compare
`status`, which §3.4 explicitly pins to "0 in requests". Two implementations will
otherwise disagree — C++ zero-initialising a header struct writes `0`, while a Java
builder defaulting to the "nothing acked" sentinel writes `-1`. Both are defensible and
they differ on the wire. These vectors use `-1`, because `0` is a legal watermark value
("I hold the reply for requestId 0") and so cannot double as "unused".

**RESOLVED.** README §3.4 row 28 now reads "−1 when the client holds no reply yet, and −1
in every non-REQUEST message", with the C++/Java divergence spelled out beneath the
table.

<a id="d-2"></a>**D-2 · MONITOR_END's header is unspecified beyond `messageType`.**
§4's CALLBACK subsection pins `requestId`, `opCode` and `status`; the MONITOR_END
subsection specifies only `messageType = 3` and the two body fields. `clientId`,
`requestId`, `opCode`, `semantics` and `status` are left open. `requestId` matters
functionally: a client holding two monitor registrations cannot tell which one ended
without it. These vectors mirror CALLBACK's rules.

**RESOLVED.** README §4 MONITOR_END now carries a **Header** line specifying `clientId`
and `requestId` copied from the expired registration, `opCode = 0`, `status = 0`,
`semantics` echoed and `ackUpTo = −1`, and states why `requestId` is load-bearing.

<a id="d-3"></a>**D-3 · `semantics` is not stated to be echoed in replies.**
§3.4 defines the field and §6.1 uses it to reject a mismatched client, but no section says
what the server writes back in a REPLY, CALLBACK or MONITOR_END. Echoing the request's
value is the only choice that keeps a packet capture self-describing, and it is what these
vectors assert.

**RESOLVED.** README §3.4 row 16 now reads "Echoed unchanged in REPLY, CALLBACK and
MONITOR_END", with the reasoning stated below the table.

<a id="d-4"></a>**D-4 · The §11.2 conformance record is not a message, and omits
`totalSeats`.**
§2.1's flight record has seven fields; the §11.2 vector encodes six, leaving out
`totalSeats`. No operation in §4 transmits a whole flight record either — `QUERY_BY_ID`
returns `departureTime`, `airfare` and `seatsAvailable`, and `QUERY_BY_ROUTE` returns only
ids. So V1 is a codec exercise, not a datagram layout, and an implementer could reasonably
read §11.2 as defining an on-the-wire `FlightRecord` that does not exist. The vector is
reproduced here byte-for-byte as the README has it; this note is about how it is
*labelled*, not its bytes.

**RESOLVED.** README §11.2 now opens with a paragraph stating that the record is a
marshaller anchor rather than a datagram layout, that no operation transmits a whole
flight record, that it exercises all four primitives with both padding cases, and that
`totalSeats` is server-side seed state that never crosses the wire.

<a id="d-5"></a>**D-5 · `QUERY_BY_ROUTE`'s reply mixes `int32 truncated` with
`uint32 count`.**
§4 Op 1 types `truncated` as `int32` and `count` as `uint32`, though both are small
non-negative counts and `count` is bounded by `MAX_ROUTE_RESULTS = 64`. Encoding is
identical under §3.3, so no vector byte changes and this is not a wire-format problem —
but the Java side must not conclude that `count` needs unsigned handling at this width.
Recorded so a reviewer does not read the mismatch as a specification error. No amendment
required; §3.3's `sequence<T>` already prescribes a `uint32` count, so Op 1 is consistent
with it and only `truncated` reads oddly beside it.

**RESOLVED — no change made.** Confirmed as a non-issue and left as it stands. Recorded
so a future reviewer does not re-raise it.

**No discrepancy was found in V1.** Regenerating §11.2's record field by field from §2.1,
§2.2 and §3.3 reproduces the README's 60 bytes exactly, including the `899.5f` bit pattern
`0x4460E000` and both pad counts (3 after `"Singapore"`, 2 after `"London"`).

---

<a id="d-6"></a>**D-6 · `ackUpTo` was never required to sit below its own `requestId`.**
Found after the five above, while tracing the at-most-once path rather than the byte
layout — so it is a protocol gap, not a vector gap, and no fixture here changes.

§6.1 prunes the reply history on receipt, *before* the duplicate lookup. That ordering is
only correct while a request's watermark stays strictly below the identifier it rides on.
For a correct client it does: §7.2 raises `ackUpTo` only after a reply arrives, by which
point `requestId` has already advanced past it. For a buggy client it does not, and a
request carrying `ackUpTo == requestId` makes the server delete the very entry that
request's own retransmission will need. Every retransmission is then treated as new and
at-most-once degrades to at-least-once — silently, under exactly the loss conditions the
mechanism exists to handle.

Same class of failure as D24's reused `requestId`, and the same answer: the guarantee
exists for the case where something has already gone wrong, so the invariant it rests on
is checked rather than assumed.

**RESOLVED.** README §8.3 states it as "Hard constraint 2 — watermark ordering", §6.1
rejects a violation with `ERR_INVALID_ARGUMENT` before pruning, and §6.1's load-bearing
list grew from five properties to six.
