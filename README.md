# Distributed Flight Information System — Technical Specification

SC6103 Distributed Systems, AY2026/27 Trimester 1. Group project (max 4 members).

A flight information service over UDP with a hand-written request-reply protocol:
byte-level marshalling, timeouts and retransmission, duplicate filtering, reply
histories, and server-to-client callbacks. No RPC framework is used; implementing
that layer is the assignment.

**Status:** design complete, implementation not started. Three decisions still need
group sign-off — see [§22 Open decisions](#22-open-decisions).

### How this document is organised

| Part | Contains | Read it when |
|---|---|---|
| **I — Specification** | What the system *is*. Normative, no argument. | Implementing. Both implementations are written from Part I alone. |
| **II — Design rationale** | Why each choice was made, and what was rejected. Decision records `D1`–`D23`. | Defending the design, or writing the report. |
| **III — Delivery** | Experiments, tests, build, phases, demo, traceability, risk. | Planning and running the work. |

Part I states rules and cross-references the rationale as `→ D4`. Nothing is argued
twice.

Where the brief offers a choice, the harder option is taken deliberately to compete
for the optional credit. Those are marked **[HARD PATH]**.

---

## Contents

**Part I — Specification**
1. [Overview](#1-overview)
2. [Data model](#2-data-model)
3. [Wire protocol](#3-wire-protocol)
4. [Operation catalogue](#4-operation-catalogue)
5. [Status codes](#5-status-codes)
6. [Server behaviour](#6-server-behaviour)
7. [Client behaviour](#7-client-behaviour)
8. [Invocation semantics](#8-invocation-semantics)
9. [Callback subsystem](#9-callback-subsystem)
10. [Fault injection](#10-fault-injection)
11. [Cross-language contract](#11-cross-language-contract)

**Part II — Design rationale**

12. [Decision records D1–D23](#12-decision-records)

**Part III — Delivery**

13. [Experiment plan](#13-experiment-plan)
14. [Test plan](#14-test-plan)
15. [Build and run](#15-build-and-run)
16. [Repository layout](#16-repository-layout)
17. [Delivery phases](#17-delivery-phases)
18. [Demo script](#18-demo-script)
19. [Requirement traceability](#19-requirement-traceability)
20. [Risk register](#20-risk-register)
21. [Assumptions and boundaries](#21-assumptions-and-boundaries)
22. [Open decisions](#22-open-decisions)
23. [Administration](#23-administration)

---
---

# Part I — Specification

Normative. Both implementations are written from this part without reference to
each other's source.

## 1. Overview

The server holds a table of flights in memory and exposes six operations on a
single UDP port. Clients reach it from a text console. There is no persistence and
no database.

```
  Console UI  →  Client codec  →  UDP  →  Dispatcher  →  Flight services  →  Repository
```

```
  application layer     flight services, console UI
  ──────────────────────────────────────────────────────
  request-reply layer   retransmit, dedupe, history, callbacks
  marshalling layer     byte[] pack / unpack
  ──────────────────────────────────────────────────────
  transport             UDP sockets
```

The two middle layers are what the project asks students to build and what the
brief forbids obtaining from a framework.

**[HARD PATH]** The server and client are written in **different languages** —
working assumption **C++17 server, Java client**, pending group sign-off. → D2, §22

### Component responsibilities

| Component | Responsibility |
|---|---|
| Console client | Parses commands; blocks during a monitor interval; prints replies, errors, callbacks and timeout status |
| Client reliability layer | Owns `(clientId, requestId)`, applies timeout/retry policy, accepts only matching replies |
| Binary codec | Marshals and unmarshals headers and bodies with strict bounds and byte-order conversion |
| Server dispatcher | Validates magic/version, selects the operation, applies duplicate policy, builds the status reply |
| Flight services | Query, reserve, monitor, cancel and set-reservation business rules |
| State managers | Flight repository, reply history, monitor registry, booking ledger, loss-simulator config |

---

## 2. Data model

### 2.1 Flight record

| Field | Type | Notes |
|---|---|---|
| `flightId` | `int32` | unique, assigned at seed time |
| `source` | `string` | max 63 bytes |
| `destination` | `string` | max 63 bytes |
| `departureTime` | `Time` | §2.2 |
| `airfare` | `float32` | IEEE-754 binary32 → D16 |
| `totalSeats` | `int32` | fixed at seed time |
| `seatsAvailable` | `int32` | mutable; the state all experiments observe |

### 2.2 Time  → D6

Five `int32` fields: `year`, `month`, `day`, `hour`, `minute`. Ranges 1970–9999,
1–12, 1–31, 0–23, 0–59. No timezone; all times are in one agreed local zone. No
seconds.

### 2.3 Client identity  → D5

A client generates a random nonzero **`clientId`** (`uint32`) once at process start
and carries it in every request header. That identifier, paired with `requestId`,
is the reply-history key and the owner key in the booking ledger.

The **observed source IP and port** of a datagram is used for one purpose only:
addressing callbacks. It is never read from the payload, so a client cannot direct
callbacks at a third party.

### 2.4 Booking ledger **[HARD PATH]**  → D9, D22

```
bookings : map (clientId, flightId) -> int32 seatsHeld
```

Invariant, asserted after every mutation:

```
flight.seatsAvailable  =  flight.totalSeats  −  Σ seatsHeld over all clients
```

A violation is logged at `ERR_SERVER_ERROR` severity. It is the signature of
at-least-once corruption, which makes the experiments in §13 self-checking.

Bookings persist for the server process lifetime and are never expired. → D22

### 2.5 Monitor registry

```
monitors : list of { flightId, clientId, clientEndpoint,
                     expiresAtMs, monitorRequestId, callbackSeq }
```

Behaviour in §9.

### 2.6 Reply history

```
history : map (clientId, requestId)
             -> { replyBytes[], length, storedAtMs }
```

Populated only under at-most-once. Behaviour in §8.2–§8.3.

### 2.7 Capacities  → D17

All server tables are fixed-capacity. No dynamic allocation on the request path. A
full monitor registry returns `ERR_MONITOR_TABLE_FULL`.

---

## 3. Wire protocol

### 3.1 Framing  → D14

One operation is one request datagram and one reply datagram. No fragmentation, no
continuation, no multi-datagram reply. Every message fits one UDP payload of at
most **512 bytes**.

### 3.2 Byte order and width  → D3, D23

- All multi-byte integers are **big-endian** (network byte order), two's complement.
- **Every field in every message is exactly 4 bytes.** There are no `uint8` or
  `uint16` fields anywhere, including in the header.
- Consequently every field begins at an offset that is a multiple of 4. Strings are
  padded with zero bytes to restore that alignment; padding is ignored on read.

The uniform-width rule is the single strongest guard against the two independent
implementations disagreeing, and bandwidth is irrelevant at this scale. → D23

### 3.3 Primitive encodings

| Type | Encoding |
|---|---|
| `int32` | 4 bytes, big-endian, two's complement |
| `uint32` | 4 bytes, big-endian |
| `float32` | IEEE-754 binary32 bit pattern, transmitted big-endian |
| `string` | `uint32` byte length, then that many UTF-8 bytes, then 0–3 zero bytes to the next 4-byte boundary. No NUL terminator. → D15 |
| `sequence<T>` | `uint32` count, then `count` elements back to back |

There are **no type tags on the wire**. Both ends derive the layout from `opCode`.

### 3.4 Header  → D4, D19

Every datagram begins with a fixed **36-byte** header.

| Offset | Type | Field | Meaning |
|---|---|---|---|
| 0 | `uint32` | `magic` | `0x53433631` — ASCII `"SC61"`. Any other value: drop silently. |
| 4 | `int32` | `version` | protocol version, currently 1. Mismatch: `ERR_VERSION_MISMATCH`. |
| 8 | `int32` | `messageType` | 0 = REQUEST, 1 = REPLY, 2 = CALLBACK, 3 = MONITOR_END |
| 12 | `int32` | `opCode` | §4; 0 on CALLBACK and MONITOR_END |
| 16 | `int32` | `semantics` | 0 = at-least-once, 1 = at-most-once |
| 20 | `uint32` | `clientId` | random nonzero, fixed for the client's lifetime |
| 24 | `int32` | `requestId` | monotonic per client; echoed in the reply |
| 28 | `int32` | `ackUpTo` | requests only; highest `requestId` this client has a reply for, or −1 |
| 32 | `int32` | `status` | replies and callbacks only; 0 in requests |

The body begins at offset 36.

`magic` rejects unrelated traffic arriving on the port. `version` catches the two
implementations drifting apart. `semantics` lets the server detect a client running
in the opposite mode and warn, rather than silently producing meaningless
experimental results.

### 3.5 Size limits

| Constant | Value | Derivation |
|---|---|---|
| `MAX_DATAGRAM` | 512 bytes | fragmentation-free on any realistic path |
| `HEADER_SIZE` | 36 bytes | §3.4 |
| `MAX_BODY` | 476 bytes | derived |
| `MAX_STRING_BYTES` | 63 | place names; bounds the receive buffer |
| `MAX_ROUTE_RESULTS` | 64 | 4 + 4 + 64×4 = 264 bytes, inside `MAX_BODY` |

---

## 4. Operation catalogue

All bodies are shown as they appear after the 36-byte header. Argument validation
failures return `ERR_INVALID_ARGUMENT` throughout.

### Op 1 — `QUERY_BY_ROUTE` · idempotent

| | Type | Field |
|---|---|---|
| **Request** | `string` | `source` |
| | `string` | `destination` |
| **Reply** | `int32` | `truncated` — 1 if matches were omitted, else 0 |
| | `uint32` | `count` |
| | `int32 × count` | `flightIds` |

Matching is case-normalised exact text. Returns `ERR_NO_ROUTE_MATCH` when nothing
matches — the brief requires an error, not an empty list. Results are capped at
`MAX_ROUTE_RESULTS` with `truncated = 1`. → D14

---

### Op 2 — `QUERY_BY_ID` · idempotent

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| **Reply** | `int32 × 5` | `departureTime` — year, month, day, hour, minute |
| | `float32` | `airfare` |
| | `int32` | `seatsAvailable` |

Errors: `ERR_UNKNOWN_FLIGHT`.

---

### Op 3 — `RESERVE` · **non-idempotent**

Relative decrement of the seat pool. Atomic check-and-decrement.

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `int32` | `numSeats` ≥ 1 |
| **Reply** | `int32` | `seatsAvailable` after |
| | `int32` | `seatsHeldByClient` after |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_INSUFFICIENT_SEATS`.
Fires callbacks on success (§9).

Both counters are returned so that under at-least-once with a lost reply, the second
execution returns a *different* `seatsAvailable` from the first — the corruption is
visible in the reply itself, not only in a later query.

---

### Op 4 — `MONITOR`

Register for seat-availability callbacks for a bounded interval. Registration is
idempotent: re-registering the same `(clientId, flightId)` replaces the entry.

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `int32` | `intervalMs` ≥ 1 |
| **Reply** | `int32` | `seatsAvailable` at registration time |
| | `int32` | `expiresInMs` |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_MONITOR_TABLE_FULL`.

The snapshot gives the monitoring client a baseline before the first callback. The
client then blocks for the interval (§7.3).

---

### Op 5 — `CANCEL_SEATS` · **non-idempotent** · **[HARD PATH]**

Release *n* seats from this client's own booking. Relative increment of the pool.
The brief's required non-idempotent extra operation. → D9

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `int32` | `numSeats` ≥ 1 |
| **Reply** | `int32` | `seatsAvailable` after |
| | `int32` | `seatsHeldByClient` after |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_NOT_ENOUGH_HELD`.
Fires callbacks on success.

Replaying this request releases seats the client no longer holds, breaking the §2.4
invariant. That is the failure §13 measures.

---

### Op 6 — `SET_RESERVATION` · **idempotent** · **[HARD PATH]**

Set this client's booking on a flight to exactly `targetSeats`. Absolute assignment.
The brief's required idempotent extra operation. → D9

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `int32` | `targetSeats` ≥ 0 (0 releases the whole booking) |
| **Reply** | `int32` | `seatsAvailable` after |
| | `int32` | `seatsHeldByClient` after — equals `targetSeats` on success |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_INSUFFICIENT_SEATS`.
Fires callbacks on success, **including when the delta is zero**, so a monitoring
client observes that a duplicate arrived and changed nothing.

The server computes `delta = targetSeats − currentlyHeld` and applies it. A second
execution yields `delta = 0`, so the final state is identical. This is a genuine
mutation that is nevertheless replay-safe.

---

### `CALLBACK` · `messageType = 2`

Sent unsolicited. Not acknowledged, not retried. → D10

**Header:** `messageType = 2`, `requestId` = the `requestId` of the `MONITOR`
registration being served, `opCode = 0`, `status = 0`.

| Type | Field |
|---|---|
| `int32` | `flightId` |
| `int32` | `seatsAvailable` |
| `int32` | `eventRequestId` — the `requestId` of the operation that caused this update |
| `int32` | `callbackSeq` — per-registration counter from 1 |

`callbackSeq` makes callback loss observable: a client seeing 1, 2, 4 reports one
lost update. `eventRequestId` lets the client distinguish two callbacks caused by
one duplicated operation (same value) from two distinct operations (different
values) — the direct observable signal of at-least-once re-execution.

---

### `MONITOR_END` · `messageType = 3`

Sent once when a registration expires. **Advisory only** — it is unacknowledged and
may itself be lost, so the client's local deadline remains authoritative. → D20

| Type | Field |
|---|---|
| `int32` | `flightId` |
| `int32` | `callbackSeq` — total callbacks the server sent for this registration |

The final count lets the client report exactly how many updates it missed rather
than only detecting gaps between the ones that arrived.

---

## 5. Status codes

| Code | Name | Meaning |
|---|---|---|
| 0 | `OK` | success |
| 1 | `ERR_UNKNOWN_FLIGHT` | no flight with that identifier |
| 2 | `ERR_NO_ROUTE_MATCH` | no flight on that source/destination pair |
| 3 | `ERR_INSUFFICIENT_SEATS` | request exceeds availability |
| 4 | `ERR_INVALID_ARGUMENT` | out-of-range or nonsensical argument |
| 5 | `ERR_NOT_ENOUGH_HELD` | cancelling more seats than the client holds |
| 6 | `ERR_MALFORMED_REQUEST` | body did not parse, or length disagreed |
| 7 | `ERR_UNKNOWN_OPCODE` | opcode not in 1–6 |
| 8 | `ERR_MONITOR_TABLE_FULL` | registry at capacity |
| 9 | `ERR_SERVER_ERROR` | internal invariant violation |
| 10 | `ERR_VERSION_MISMATCH` | header `version` not recognised |

Error replies carry a header and **no body**. Human-readable text is generated
client-side from the code. → D4

A datagram with a bad `magic` is dropped silently and logged — no reply, since it
may not be from a client of this system at all. Every other malformed datagram that
carries a usable `clientId` and `requestId` receives an error reply.

---

## 6. Server behaviour

### 6.1 Request loop

```
loop forever:
    n, from = recvfrom(socket)

    if simulate_drop(dropRequestRate):
        log "DROPPED REQUEST from {from}"; continue

    if n < HEADER_SIZE: continue
    hdr = parse_header(buf)
    if hdr.magic != MAGIC: log "alien datagram from {from}"; continue
    if hdr.version != VERSION: reply(ERR_VERSION_MISMATCH); continue
    if hdr.messageType != REQUEST: continue
    if hdr.semantics != serverSemantics: warn "semantics mismatch"

    log "RECV {from} client={hdr.clientId} req={hdr.requestId} op={hdr.opCode}"

    prune_history(hdr.clientId, hdr.ackUpTo)     # §8.3
    sweep_expired_monitors(now)                  # §9.3

    if semantics == AT_MOST_ONCE:
        e = history_lookup(hdr.clientId, hdr.requestId)
        if e != NULL:
            log "DUPLICATE req={hdr.requestId} -> replaying stored reply"
            maybe_send(e.bytes, from)
            continue                             # no re-execution, no callbacks

    status, body = dispatch(hdr.opCode, buf + HEADER_SIZE, hdr.clientId, from)
    reply = marshal_reply(hdr, status, body)

    if semantics == AT_MOST_ONCE:
        history_store(hdr.clientId, hdr.requestId, reply)   # store before sending

    log "SEND {from} req={hdr.requestId} status={status}"
    maybe_send(reply, from)

    if status == OK and opCode in {RESERVE, CANCEL_SEATS, SET_RESERVATION}:
        fire_callbacks(flightId, hdr.requestId)  # §9.2

    assert_seat_invariant()                      # §2.4
```

Four properties of that loop are load-bearing and belong in the report:

1. **The duplicate path returns before dispatch.** Under at-most-once a duplicate
   neither re-executes nor re-fires callbacks; under at-least-once there is no
   duplicate path, so both happen. That single branch is the entire behavioural
   difference between the two semantics.
2. **The reply is stored in the history before the send is attempted**, so a reply
   lost in transit is still available to answer the retransmission.
3. **Callbacks fire even when the reply is dropped.** A dropped reply models loss on
   the return path — the server did execute. This is what makes at-least-once
   corruption visible to a monitoring client.
4. **The invariant is asserted every iteration**, so corruption is detected when it
   occurs rather than inferred later.

### 6.2 Concurrency  → D11

One thread, no locking. Multiple concurrent monitoring clients are still supported,
because the registry is a list and needs no threading. Callbacks are emitted
sequentially.

### 6.3 Seed data  → D18

The flight table is seeded from a hard-coded array at startup. It must contain at
least: two flights sharing a source/destination pair (multi-result queries), one
flight on a distinct route, and one flight with a small seat count (fast
exhaustion).

---

## 7. Client behaviour

### 7.1 Console interface

A numbered menu looping until the user selects quit, one entry per operation plus
quit. Every request sent and every reply, error or callback received is printed.
Text only.

### 7.2 Request/reply state machine

```
requestId = next++
bytes     = marshal_request(clientId, requestId, opCode, args, ackUpTo)

for attempt in 0 .. maxRetries:
    maybe_send(bytes)                         # identical bytes every attempt
    deadline = now + timeoutMs

    while now < deadline:
        pkt = receive(until deadline)
        if pkt == TIMEOUT: break
        if pkt.magic != MAGIC or pkt.version != VERSION: continue

        if pkt.messageType in {CALLBACK, MONITOR_END}:
            handle_async(pkt); continue       # not a reply; keep waiting
        if pkt.clientId != clientId or pkt.requestId != requestId:
            log "stale/foreign reply, discarded"; continue
        if simulate_drop(dropReplyRate):
            log "DROPPED REPLY req={requestId}"; continue

        ackUpTo = max(ackUpTo, requestId)
        return pkt

    log "TIMEOUT req={requestId}, attempt {attempt+1}"

report TIMEOUT to user, without claiming whether the server executed
```

Three details are load-bearing:

- **`requestId` and `clientId` do not change across retransmissions.** If they did,
  the server could not recognise a duplicate and at-most-once would be
  unimplementable.
- **Stale replies are discarded by `(clientId, requestId)`.** A reply to attempt 1
  arriving after attempt 2 was sent must not be taken as the reply to a later
  request.
- **Final exhaustion reports a timeout without asserting what happened.** Under
  either semantics the client genuinely cannot know whether the operation ran.

### 7.3 Monitoring mode  → D11

After a successful `MONITOR` reply the client blocks until its local monotonic
deadline, printing each callback and reporting gaps in `callbackSeq`. It accepts no
user input during this period. On expiry — or on receiving `MONITOR_END`, whichever
comes first — it prints a summary: callbacks received, callbacks missed, and any
`eventRequestId` seen more than once.

---

## 8. Invocation semantics

Both are implemented and selected by a CLI flag on both programs at startup. → D7

| Semantics | Client | Server on duplicate | Consequence |
|---|---|---|---|
| at-least-once | timeout, then resend the identical datagram up to R retries | execute every valid arrival; no history lookup | a duplicated `RESERVE` or `CANCEL_SEATS` changes state twice |
| at-most-once | same retry policy, same stable IDs | history hit replays the exact cached reply; new IDs execute once, then cache | both idempotent and non-idempotent operations stay correct under loss |

Maybe-semantics is not implemented for the six operations. Callbacks and
`MONITOR_END` exhibit it regardless (§9.2, §4).

### 8.1 At-least-once

No history. Every request that arrives is executed. Correct for operations 1, 2 and
6; corrupting for 3 and 5.

### 8.2 At-most-once

Keyed on **(clientId, requestId)** — not on network address, so a retransmission
stays recognisable across a NAT rebinding or a client restart. → D5

The **marshalled reply bytes** are stored, not the structured result, so a duplicate
is answered by a buffer copy that is byte-identical to the original without
depending on marshaller determinism. → D8

### 8.3 Bounding the history  → D8

Three mechanisms, in order of precedence:

1. **Piggybacked watermark.** Every request carries `ackUpTo`. On receipt the server
   discards that client's entries with `requestId ≤ ackUpTo`. Exact, and costs no
   extra traffic.
2. **Per-client cap.** At most `MAX_HISTORY_PER_CLIENT` entries; oldest evicted when
   full.
3. **Idle expiry.** Entries older than `HISTORY_TTL_MS` are swept.

Mechanism 1 does the work; 2 and 3 make the bound absolute.

**Hard constraint:** `HISTORY_TTL_MS > timeoutMs × (maxRetries + 1)`. A TTL shorter
than the client's maximum retry window would let an entry expire while a legitimate
retransmission is still in flight, silently degrading at-most-once to at-least-once.
This is checked at server startup and refuses to run if violated.

---

## 9. Callback subsystem

### 9.1 Registration

`MONITOR` appends `{flightId, clientId, clientEndpoint, expiresAtMs = now +
intervalMs, monitorRequestId, callbackSeq = 0}`. The endpoint is the observed source
address of the datagram, never a payload value, so a client cannot aim callbacks at
a third party.

Re-registering the same `(clientId, flightId)` **replaces** the existing entry, so a
client cannot accumulate duplicate registrations by retransmitting.

### 9.2 Delivery  → D10

On a successful `RESERVE`, `CANCEL_SEATS` or `SET_RESERVATION`, the server removes
expired entries, snapshots the live registrants for that `flightId`, increments each
registration's `callbackSeq`, and sends one callback per registrant.

Delivery is **fire and forget** — no acknowledgement, no retry, no history. That is
*maybe* semantics in deck 3's taxonomy, and the report states so. `callbackSeq` and
`eventRequestId` make the resulting loss and duplication measurable rather than
merely admitted.

The client that triggered the update also receives a callback if registered.

### 9.3 Expiry  → D10, D20

Checked in two places: **lazily at delivery time**, so a registration that expired
since the last sweep never receives a late update; and **by a sweep each loop
iteration**, so memory is reclaimed with no reservation traffic. Delivery-time
checking is what makes expiry exact.

On removal the server sends one `MONITOR_END`. It is advisory; the client's own
deadline remains authoritative.

---

## 10. Fault injection

Loss is simulated in the application, not by an external tool. → D12

Every gate is a **seeded Bernoulli trial**, and is applied **after encoding**, so the
codec runs on exactly the same path whether or not the datagram is transmitted. Every
suppressed and every transmitted message is logged with direction, `clientId`,
`requestId`, opcode, attempt number and outcome.

| Program | Flag | Applied |
|---|---|---|
| server | `--drop-request` | after `recvfrom`, before processing |
| server | `--drop-reply` | after encoding, before `sendto` |
| server | `--drop-callback` | after encoding, before `sendto` |
| client | `--drop-request` | after encoding, before `sendto` |
| client | `--drop-reply` | after `receive` |

**Experiments use server-side injection only.** A server-side reply drop means the
operation genuinely executed and the client genuinely did not hear — the exact
scenario separating the two semantics. Client-side injection composes with it and
would confound the measured rate.

Deck 2's three scenarios map as:

| Deck 2 scenario | Reproduced by |
|---|---|
| request message lost | `--drop-request` on the server |
| reply message lost | `--drop-reply` on the server |
| incomplete interaction | `--drop-reply` on the server with a short client `--timeout` |

---

## 11. Cross-language contract

The two implementations share no code. This section is the contract. → D2

### 11.1 Rules

| Rule | C / C++ side | Java side |
|---|---|---|
| Never `memcpy` a struct onto the wire | serialise field by field | n/a |
| Integers | `htonl` / `ntohl` on `uint32_t` | manual shifts; big-endian by construction |
| Floats | `memcpy` into `uint32_t`, then `htonl`; no type-punning cast | `Float.floatToIntBits` / `intBitsToFloat` |
| Bytes | `unsigned char` throughout | mask every read with `& 0xFF` — Java's `byte` is signed |
| Strings | length-prefixed, no NUL transmitted | `new String(b, off, len, StandardCharsets.UTF_8)` — never the platform default |
| Lengths | validate against `MAX_STRING_BYTES` before use | validate non-negative **and** in range before allocating |
| Cursor reads | check remaining length before every read | same |
| Width | `int32_t` / `uint32_t` / `float` only | `int` / `float` only |

Struct padding is the most likely cross-language bug: it differs by platform and Java
has no equivalent concept, so a `memcpy`'d struct produces bytes the Java side cannot
describe. A decoded length is untrusted input — a negative or oversized `uint32` read
into a signed Java `int` becomes an allocation bug if used directly.

### 11.2 Conformance vector

Both implementations must encode this record to **exactly** these 60 bytes.

```
flightId       = 1934
source         = "Singapore"
destination    = "London"
departureTime  = { year 2026, month 10, day 14, hour 9, minute 30 }
airfare        = 899.5f
seatsAvailable = 120
```

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
0x0034  44 60 E0 00                              airfare = 899.5f
0x0038  00 00 00 78                              seatsAvailable = 120
                                                 total = 60 bytes
```

`899.5f` is sign 0, biased exponent 136, mantissa `1100000111` then zeros, giving
`0x4460E000`. Different bytes here mean an endianness or alignment fault.

---
---

# Part II — Design rationale

One record per decision. Part I cross-references these as `→ Dn`. Each states what
was chosen, why, what else was considered, and why it was rejected.

## 12. Decision records

| | Decision | |
|---|---|---|
| D1 | Transport: UDP | mandated |
| D2 | Server and client in different languages | **[HARD PATH]** |
| D3 | Wire format: CDR-style, big-endian, aligned | |
| D4 | Fixed 36-byte header, error codes without text | |
| D5 | Request identity: `(clientId, requestId)` | |
| D6 | Time as five `int32` fields | |
| D7 | Both invocation semantics, runtime-selected | mandated |
| D8 | History bounded by piggybacked acknowledgement | |
| D9 | The two extra operations | **[HARD PATH]** |
| D10 | Callbacks fire-and-forget with sequence numbers | |
| D11 | Single-threaded server, blocking monitor client | |
| D12 | In-process fault injection, seeded, after encoding | |
| D13 | Fixed timeout, bounded retries | |
| D14 | One datagram per reply, with truncation | |
| D15 | UTF-8, byte lengths, no terminator | |
| D16 | Airfare as IEEE-754 `float32` | |
| D17 | Fixed-capacity server tables | |
| D18 | Hard-coded seed data | |
| D19 | Magic, version and semantics guards | |
| D20 | `MONITOR_END` as an advisory message | |
| D21 | No application-level checksum | |
| D22 | Bookings are never expired | |
| D23 | Uniform 4-byte field width | |

---

### D1 — Transport: UDP

**Decision.** UDP, with all reliability implemented in the application.

**Reasoning.** Mandated: the brief states the program "uses UDP (not TCP) sockets".

**Consequence.** Over TCP, deck 2 notes there is "no need to deal with
retransmission of messages, filtering of duplicates and maintaining histories" — the
project would have no content. UDP's omission failures are what give the semantics
comparison something to compare.

---

### D2 — Server and client in different languages **[HARD PATH]**

**Decision.** The two programs are written in different languages. Working
assumption: **C++17 server, Java client.** Needs group sign-off — §22.

**Reasoning.** The brief offers optional credit: "An additional but optional
requirement is to implement the client and the server in different programming
languages – for example, one in C/C++ and one in Java." It is the only place in the
document offering marks for extra work.

Beyond marks, the split is the productive kind of difficulty. With one language on
both ends, a marshaller holding a wrong assumption still works, because both sides
hold the same wrong assumption. Across languages every layout assumption is checked
by an independent implementation. The extra effort lands inside the marshalling code
the project already grades, not in a new subsystem.

**Why C++ on the server, Java on the client.** The brief's own example names C/C++ and
Java as the pairing. The demo needs two client machines plus a server: a JVM deploys
to whatever laptops the group owns without a second toolchain, while the server is one
machine that can be prepared in advance. The group's server work already targets
C++17.

**Alternatives rejected.**

- *Both ends in C++.* The brief's baseline. Forfeits the only optional credit on
  offer, and hides marshalling bugs as above.
- *Both ends in Java.* Same forfeiture, and discards the server work already under way.
- *C rather than C++ on the server.* Equivalent on the wire, since §11 forbids the
  language features that would differ. C++17 is chosen because it is what the server
  work targets; nothing in this specification depends on the choice.
- *C++ client, Java server.* Same credit, worse logistics: it needs a working C++
  toolchain and a successful build on two separate client machines on demo day.

**Risk and mitigation.** Cross-language integration can fail late. Mitigated by the
phasing in §17: a single-language system is built first and retained as both fallback
demo and test oracle.

---

### D3 — Wire format: CDR-style, big-endian, aligned

**Decision.** Binary, modelled on CORBA CDR. Fixed big-endian, 4-byte alignment,
length-prefixed strings, no type tags.

**Reasoning.** Deck 2 describes exactly this scheme, so the report cites it directly.
Omitting type tags is safe for a fixed operation set — the deck notes CDR omits type
information because "the sender and recipient have common knowledge of the order and
types of the data items in a message". Fixing byte order means neither implementation
negotiates or detects the peer's endianness, which matters given D2. The brief itself
points at `htonl`/`ntohl`.

**Alternatives rejected.**

- *Sender-endian with a flag, as CDR actually does* ("transmit in the sender's
  ordering and the recipient translates if necessary"). Doubles the decode paths, and
  each is exercised on only one architecture — so the untested path is the one that
  fails at the demo. The saving is a byte swap, irrelevant at this scale.
- *A tagged format (TLV), or Java serialization.* Serialization is banned outright.
  Hand-rolled TLV spends bytes and complexity transmitting type information both ends
  already know; the exercise is agreeing a layout, not discovering one.
- *A text or ASCII line protocol.* Easiest to debug, and rejected for that reason: the
  brief requires marshalling "the integer values, floating-point values and strings"
  into bytes, and encoding a float as decimal text sidesteps the IEEE-754 question
  that is the interesting part.

---

### D4 — Fixed 36-byte header, error codes without text

**Decision.** A fixed header of nine 4-byte fields (§3.4). Error replies carry a
status code and no body.

**Reasoning.** Deck 2 gives the minimal structure as `messageType` + `requestId` +
application-specific content. `opCode` is added because one port multiplexes six
operations and the dispatcher must choose a body parser. `status` lets every error be
decoded before the body is touched. `ackUpTo` bounds the history (D8) without extra
traffic. `magic`, `version` and `semantics` are guards, argued in D19. A fixed size
means constant offsets, the main defence against cross-language layout drift.

Error replies stay a fixed 36 bytes with no body, which keeps the duplicate-replay
path a trivial fixed-size buffer copy and means the client can word and localise its
own messages.

**Alternatives rejected.**

- *`status:uint16` + `error_length:uint16` + UTF-8 error text in the body.* Gives the
  server control of the wording, which is a real benefit. Rejected because it makes
  error replies variable-length for no functional gain, and because the client can
  generate better text from a code than the server can from a context-free string.
  This is a close call, not a clear win.
- *A separate error message type.* Redundant with `status`; doubles the reply paths.
- *A separate acknowledgement message for history pruning.* Doubles request traffic to
  carry four bytes that fit in a datagram already being sent.

---

### D5 — Request identity: `(clientId, requestId)`

**Decision.** The client generates a random nonzero `clientId` at process start and
sends it in every header. The history and booking ledger are keyed on
`(clientId, requestId)`. The observed source endpoint is used only to address
callbacks.

**Reasoning.** Deck 2 defines the unique request identifier as `requestId` "+ an
identifier for the client process (e.g., Internet address + port number)". The
address is given as an *example* of a process identifier, not a requirement, so a
generated `clientId` satisfies the definition directly.

Decoupling identity from the network endpoint fixes two real failure modes:

- **Client restart.** An address-keyed scheme lets a restarted client on the same
  ephemeral port reuse a `(endpoint, requestId)` triple that is still in the history,
  so a fresh request is answered with a stale cached reply. A fresh random `clientId`
  makes that impossible.
- **NAT rebinding.** If the mapping changes between a request and its retransmission,
  an address-keyed server sees a new client and re-executes — silently degrading
  at-most-once to at-least-once at exactly the moment the guarantee matters.

**Alternatives rejected.**

- *`(IP, port, requestId)`.* Reads identity straight off the transport, so it costs no
  bytes and needs no generator — the scheme deck 2 names directly. Rejected for the two
  failure modes above, both of which corrupt at-most-once silently rather than loudly.
  Note that the common argument for moving off the address — that the OS may change a
  client's ephemeral port mid-session — is **not** valid: a bound socket keeps its
  port. Restart and NAT rebinding are the real reasons.
- *A per-request UUID.* 16 bytes per message to solve what 4 bytes solve, plus a
  hand-written generator on both sides.
- *`requestId` alone.* Incorrect; two clients collide on their first request.

**Disclosed limitation.** `clientId` is client-asserted and unverified: a malicious
client could impersonate another and read its cached replies or manipulate its
bookings. Out of scope for the course (§21), but it is the honest cost of moving
identity off the transport, and the report should say so.

---

### D6 — Time as five `int32` fields

**Decision.** Year, month, day, hour, minute as five `int32` values, in one agreed
local timezone. No seconds.

**Reasoning.** The brief asks the group to "design your own data structure to
represent time", making this graded rather than incidental. Discrete integer fields
marshal identically in both languages with no library, and are directly readable in
the required console trace output. Year is included because a flight schedule without
one is not a credible record, and at uniform 4-byte width it costs one field.

**Alternatives rejected.**

- *A 64-bit Unix epoch timestamp.* More compact and the obvious production choice.
  Rejected because `time_t` width and signedness vary across platforms while Java's
  `long` is fixed, introducing a portability question with no upside; and because it
  reads as sidestepping the instruction rather than answering it.
- *Packed `uint16` year with `uint8` month/day/hour/minute* — six bytes instead of
  twenty. Rejected under D23: narrow types are the main cross-language hazard, and
  twenty bytes is free here.
- *An ISO-8601 string.* Converts a numeric marshalling problem into a string one.

---

### D7 — Both invocation semantics, runtime-selected

**Decision.** At-least-once and at-most-once both implemented, chosen by CLI flag and
carried in the header (D19).

**Reasoning.** Mandated. The brief specifies "which semantics to use can be specified
as an argument in the command that starts the client/server". The design follows deck
3's summary table, where the semantics are the combination of retransmission,
duplicate filtering, and re-execute versus retransmit-stored-reply.

---

### D8 — History bounded by piggybacked acknowledgement

**Decision.** A watermark on every request, plus a per-client cap and an idle TTL as
backstops, with `HISTORY_TTL_MS > timeoutMs × (maxRetries + 1)` enforced at startup.
Marshalled bytes are stored, not structured results, and stored before the send is
attempted.

**Reasoning.** Deck 2 poses this question directly — "suggest methods to prevent the
history from growing forever" — so answering it well is directly reportable.
Piggybacking costs four bytes on a datagram already in flight and needs no
acknowledgement traffic. Storing bytes means a duplicate is answered by a buffer copy,
guaranteed byte-identical to the original.

The TTL constraint matters more than its brevity suggests. Without it the failure is
silent and severe: an entry expires mid-retry, the retransmission is treated as new,
and at-most-once degrades to at-least-once precisely under the loss conditions it
exists to handle.

**Alternatives rejected.**

- *Unbounded history.* Correct but unbounded; the deck flags this as the problem to
  solve.
- *TTL and LRU only.* Sound, and strictly weaker: both are heuristics that discard
  entries on a guess about when the client is finished, whereas the watermark is exact
  information the client already has. Retained here as backstops rather than as the
  primary mechanism.
- *Storing the structured result and re-marshalling on a duplicate.* Byte-identity
  would then depend on marshaller determinism.

---

### D9 — The two extra operations **[HARD PATH]**

**Decision.** `CANCEL_SEATS` (non-idempotent, relative) and `SET_RESERVATION`
(idempotent, absolute), both writing to `seatsAvailable` through the per-client
booking ledger.

**Reasoning.** The brief requires two extra operations, "one of them should be
idempotent and the other should be non-idempotent". Choosing two *writes against the
same state* means the pair differs in exactly one variable — relative arithmetic
versus absolute assignment — so the experiment isolates idempotency as the cause of
divergence. This mirrors deck 2's own contrast between "increasing a variable by 3"
and operations whose repetition changes nothing.

Operating on **seats** rather than any other field is the substantive choice: seats
are what the callback mechanism reports, so at-least-once corruption becomes visible
in real time to a monitoring client. That is experiment E8, and it is the strongest
thing in the demo.

**Alternatives rejected.**

- *`SET_FARE` / `ADJUST_FARE` on the airfare field.* The same absolute-versus-relative
  structure applied to a different field, and it satisfies the brief equally.
  Rejected on two grounds: fare changes are invisible to the callback mechanism, so
  the corruption cannot be demonstrated live to a monitoring client; and "any client
  may rewrite any fare" is a business rule with no ownership model behind it. Its
  genuine advantage is that it needs no booking ledger at all — see D22 for what that
  ledger costs. This remains a live option; see §22.2.
- *A read-only query as the idempotent operation.* Much easier and the common choice.
  A read cannot corrupt state, so at-least-once trivially produces the right answer
  and the comparison demonstrates nothing. The interesting claim is that a **write**
  can be replay-safe.
- *`ADD_FLIGHT` / `DELETE_FLIGHT`.* Orthogonal to the seat state the rest of the
  system is built around; needs its own test path and never touches the monitor
  mechanism.
- *`CANCEL_SEATS` on the global pool rather than a per-client booking.* Simpler, and
  rejected because "cancel seats I never booked" is not meaningful and because without
  ownership `SET_RESERVATION` has no well-defined subject.

**Cost accepted.** Both require the booking ledger of §2.4, which the brief does not
ask for, and which brings the orphaned-booking problem of D22.

---

### D10 — Callbacks fire-and-forget with sequence numbers

**Decision.** Server-side registry, unsolicited datagrams, no acknowledgement, no
retry. Each callback carries `callbackSeq` and `eventRequestId`. Expiry is checked
both lazily at delivery and by a periodic sweep.

**Reasoning.** The brief describes registration by address and port, delivery "every
time a seat reservation is made by any client on the flight", and removal after the
interval expires. Not retrying is a deliberate, disclosed choice: an unacknowledged
push is *maybe* semantics in deck 3's taxonomy. `callbackSeq` turns that from an
admission into a measurement. `eventRequestId` distinguishes two callbacks caused by
one duplicated operation from two genuinely distinct operations, which is what makes
at-least-once re-execution directly observable at the monitor rather than merely
inferred from the seat count.

**Alternatives rejected.**

- *Reliable callbacks with acknowledgement and retry.* Technically stronger, rejected
  on scope: a second retransmission state machine running in the opposite direction,
  which the brief neither requires nor rewards, and which the single-threaded server
  would have to block on while holding up the request loop.
- *Client polling instead of push.* The brief specifies callback.
- *Sweep-only expiry.* A registration expiring between sweeps would receive one late
  update. Checking at delivery time is exact.
- *Suppressing self-notification to the triggering client.* Couples the delivery loop
  to the requester's identity for a cosmetic gain.

---

### D11 — Single-threaded server, blocking monitor client

**Decision.** One thread on the server request path, no locking. A monitoring client
blocks until its interval expires.

**Reasoning.** Both are explicitly sanctioned. The brief permits assuming requests are
"well separated in time" and states no per-request thread is needed; likewise "the
client simply waits for the updates from the server during the monitor interval" and
"you do not have to use multiple threads at a client". Concurrent monitoring clients
are still supported, since the registry is a list.

Avoiding threads removes a whole class of demo failure and keeps the report's
correctness argument about invocation semantics rather than about locking.

**Alternatives rejected.**

- *Thread-per-request or a thread pool.* No marks, and it would force the history and
  booking ledger to become thread-safe, introducing races a marker could mistake for
  semantics bugs.
- *A threaded client that stays interactive while monitoring.* The brief anticipates
  and permits the blocking design.

---

### D12 — In-process fault injection, seeded, after encoding

**Decision.** Five independent seeded Bernoulli gates (§10), applied after encoding,
with every drop and every transmission logged.

**Reasoning.** The brief requires loss simulated "in your program". Independent rates
are necessary because deck 2 distinguishes request-lost, reply-lost and
incomplete-interaction, and the second is the one that separates the semantics. A
single global rate cannot target it, which matters when the effect must be reproduced
on demand in front of a marker.

Two details are easy to get wrong. **Callback loss needs its own gate**, or experiment
E10 has nothing to measure — the request and reply gates cannot produce it. And **the
gate sits after encoding**, so the codec runs on an identical path whether or not the
datagram is transmitted, keeping protocol-level tests deterministic under injected
loss.

**Alternatives rejected.**

- *An external tool such as `tc netem` or Clumsy.* The brief says in-program, and it
  would need privileged configuration on demo machines.
- *One combined probability.* Cannot target the discriminating case.
- *Deterministic scripting, e.g. "drop the second reply".* Attractive for reproducible
  demos, rejected as the primary mechanism because the experiments need probabilistic
  loss to report rates over many trials. `--seed` provides reproducibility instead.

---

### D13 — Fixed timeout, bounded retries

**Decision.** 750 ms timeout, 3 retries, both settable by flag. Exhausting retries
reports a timeout without claiming whether the server executed.

**Reasoning.** A fixed timeout makes runs comparable across trials and across the two
semantics. Bounding retries means a downed server produces a diagnosable error rather
than a hang. 750 ms is ample on a LAN and keeps a demo of retry behaviour brisk
enough to hold attention; a multi-second timeout makes the same demonstration drag.

Reporting a bare timeout matters: under either semantics the client genuinely cannot
distinguish a lost request from a lost reply, and claiming otherwise in the UI would
misrepresent the failure model.

**Alternatives rejected.**

- *Exponential backoff.* Correct for a real protocol; rejected because it makes the
  retransmission count depend on elapsed time, muddying an experiment that compares
  outcomes at a known retransmission count.
- *Infinite retries.* Undemonstrable — a lost-server case appears as a hung client.

---

### D14 — One datagram per reply, with truncation

**Decision.** Every reply fits one 512-byte datagram. `QUERY_BY_ROUTE` caps results at
`MAX_ROUTE_RESULTS` and sets a truncation flag when matches were omitted.

**Reasoning.** UDP does not segment for the application; deck 2 notes messages larger
than the maximum "must be fragmented", and IP fragmentation means losing one fragment
loses the whole datagram — precisely under the loss conditions this project induces.
One datagram per reply also preserves the one-request-one-reply model the entire
duplicate-filtering design assumes.

**Alternatives rejected.**

- *A 1,200-byte cap.* Safe on an Ethernet LAN and gives more headroom for route
  results. Rejected because 512 is fragmentation-free on any path
  including one with a 576-byte MTU, the headroom is not needed (64 route results
  occupy 264 bytes), and "fragmentation-free everywhere" is the cleaner claim in a
  report.
- *Application-level fragmentation and reassembly.* Requires sequencing, reassembly
  buffers, and per-fragment retransmission — effectively rebuilding TCP — and breaks
  the assumption that one stored reply answers one duplicate request.
- *Letting IP fragment.* Degrades reliability silently, and worst under induced loss.
- *A paginated query with an offset argument.* Keeps all results reachable.
  **Deferred, not dismissed**: it adds a round trip and an operation the brief does not
  ask for. Revisit if the demo dataset makes truncation visible.

---

### D15 — UTF-8, byte lengths, no terminator

**Decision.** UTF-8 fixed explicitly on both sides; strings carry a byte length, not a
character count; the NUL terminator is never transmitted.

**Reasoning.** Java's platform default charset varies by machine and locale, so it must
be named. C++ has no charset concept and transmits whatever bytes are in the buffer, so
the contract must be set here. A byte length is unambiguous across both; a character
count is not, once multi-byte sequences appear. Transmitting a terminator would make the
two implementations disagree by one byte on every string.

**Alternatives rejected.**

- *ASCII only.* Sufficient for demo data, rejected as an undocumented restriction that
  fails silently on the first non-ASCII place name.
- *NUL-terminated with no length prefix.* The brief explicitly suggests "adding the
  length information of the string in the marshalled message", and scanning for a
  terminator in an untrusted buffer is a parsing hazard.
- *`uint16` length prefixes.* Two bytes rather than four on every string. Rejected
  under D23.

---

### D16 — Airfare as IEEE-754 `float32`

**Decision.** 32-bit IEEE-754, transferred bit-for-bit via `memcpy` into an integer,
never a type-punning cast.

**Reasoning.** The brief specifies a "floating-point value", and deck 2 records that CDR
represents floating point using the "IEEE standard (sign + exponent + fractional part)",
which both languages implement. Bit-level transfer avoids any decimal formatting
question. `float` rather than `double` removes a width to keep synchronised across the
language boundary. The `memcpy` is not stylistic: reinterpreting a `float` through a
pointer cast violates strict aliasing and is undefined behaviour in C++.

**Alternatives rejected.**

- *Fixed-point integer cents.* The correct engineering choice for money, rejected
  because the brief asks for floating point and because transferring a float correctly
  across two languages is exactly the marshalling problem worth demonstrating.
- *`double`.* An unnecessary width to keep matched for a value needing no such
  precision.

---

### D17 — Fixed-capacity server tables

**Decision.** No dynamic allocation on the request path; all tables fixed-capacity with
a defined full-table error.

**Reasoning.** At this scale dynamic allocation buys nothing and adds a failure mode to a
program parsing network input. A full table becomes a clean protocol error
(`ERR_MONITOR_TABLE_FULL`) rather than an allocation failure.

**Alternative rejected.** *Growable containers*, for example an `unordered_map` keyed by
flight ID. Idiomatic in C++ and rejected for uniformity with the bounds-checking
discipline of §11.1: every length and index is validated before use, and fixed capacity
makes the bound trivially auditable.

---

### D18 — Hard-coded seed data

**Decision.** The flight table is seeded from a hard-coded array at startup.

**Reasoning.** The brief states persistence is not compulsory and the minimum is to hold
flights in memory. Hard-coding makes the demo reproducible, removes file I/O and parsing
from the server, and keeps the submission a single packed file.

**Alternative rejected.** *Loading from a config file.* Adds a parser and a path
dependency for no marks, and risks a missing file on the demo machine.

---

### D19 — Magic, version and semantics guards

**Decision.** Three guard fields in the header: `magic = 0x53433631`, `version`, and
`semantics`.

**Reasoning.** Twelve bytes buy three distinct protections:

- **`magic`** rejects unrelated traffic on the port. A UDP socket receives whatever is
  addressed to it, and without a magic check a stray datagram is parsed as a request.
- **`version`** is worth far more here than in a single-language project. With two
  independent implementations, the format is the only thing holding them together, and a
  version bump is how one side announces it changed. Without it a mismatch surfaces as
  corrupted values.
- **`semantics`** lets the server detect a client running in the opposite mode. A silent
  mismatch produces experimental results that look valid and mean nothing, which is the
  worst possible failure for §13.

**Alternatives rejected.**

- *No guards at all.* Saves twelve bytes in a 512-byte budget that is not under
  pressure, at the cost of turning three loud failures into three silent ones.
- *Negotiating the version in a handshake.* Adds a round trip and connection state to a
  connectionless protocol, for information that fits in one field.

---

### D20 — `MONITOR_END` as an advisory message

**Decision.** The server sends one `MONITOR_END` when a registration expires, carrying
the total `callbackSeq`. It is unacknowledged and the client's local deadline stays
authoritative.

**Reasoning.** The client can already time its own interval, so this is not a
correctness requirement — it earns its place by carrying the
server's final callback count, which lets the client report exactly how many updates it
missed rather than only the gaps between the ones that arrived. That turns callback loss
from a qualitative observation into experiment E10's measurement.

Treating it as advisory is the correct framing: `MONITOR_END` can itself be lost, so a
client that waited for it rather than its own clock would hang.

**Alternative rejected.** *Reliable end-of-monitor notification.* Same objection as
reliable callbacks (D10): a retransmission state machine in the wrong direction for a
message the client does not need.

---

### D21 — No application-level checksum

**Decision.** No CRC or checksum field. UDP's own checksum is relied on for corruption
detection.

**Reasoning.** A CRC-32 over the header and payload is the obvious addition, and it is
rejected specifically *because* this project is cross-language.

"CRC-32" names several incompatible variants differing in polynomial reflection, initial
value and final XOR. Java's `java.util.zip.CRC32` is CRC-32/ISO-HDLC; a hand-written C++
table has to match it exactly. Adding the field would introduce a new way for the two
implementations to disagree, in order to detect the two implementations disagreeing.

The protections it would offer are already covered: UDP carries its own checksum for
transmission corruption, and the hex conformance vector of §11.2 catches format
divergence offline, earlier, and with a precise diff rather than a boolean.

**Alternative considered.** *Adopting the CRC anyway and pinning the variant in this
document.* Defensible — the risk is manageable if the polynomial and parameters are
specified. Rejected on cost/benefit rather than principle: it is real work on both sides
for a failure mode neither the transport nor the test plan leaves open.

---

### D22 — Bookings are never expired

**Decision.** The booking ledger persists for the server process lifetime. Seats held by
a client that has exited are never reclaimed.

**Reasoning.** This is a genuine limitation, disclosed rather than solved. Because
identity is a generated `clientId` (D5), an exited client's seats can never be reclaimed
even by the same user restarting, so the available pool leaks monotonically.

It is accepted because the alternatives are worse for a demo. A TTL on bookings would
make `SET_RESERVATION` non-deterministic across a long demo — the same request would
produce different results depending on how long the client had been idle — and that is
the operation whose determinism experiment E6 depends on.

Within one demo or experiment run the leak is invisible: the server is restarted between
runs, and no client exits mid-experiment. The report should state the limitation and note
that a production design would tie bookings to authenticated accounts with explicit
cancellation, rather than to a transport-level or process-level identity.

**Note.** This cost is a direct consequence of D9. Fare-based extra operations avoid it
entirely by not tracking ownership at all, which is a real advantage of that alternative
and is weighed in D9 and §22.2.

**Alternatives rejected.**

- *TTL on bookings.* Breaks E6's determinism, as above.
- *An explicit release-on-quit message.* Best-effort over UDP, so it does not solve the
  crash case, and adds an operation for a guarantee it cannot make.

---

### D23 — Uniform 4-byte field width

**Decision.** Every field in every message is exactly 4 bytes. No `uint8` or `uint16`
anywhere, including in the header and in string length prefixes.

**Reasoning.** Java has no unsigned types and a signed `byte`, so every narrow field
becomes a masking exercise (`& 0xFF`, `& 0xFFFF`) that is correct only if written
correctly at every single call site. Uniform width makes the rule auditable by
inspection: if a read is not four bytes, it is wrong. Given that the two implementations
share no code and no compiler, a rule that can be checked by looking is worth more than
the bytes it costs.

The cost is real but irrelevant here: the header grows from 20 to 36 bytes and the time
structure from 6 to 20, inside a 512-byte budget carrying bodies of under 300 bytes.

**Alternatives rejected.**

- *Packed narrow types* — `uint16` lengths, `uint8` date components, byte-packed header
  flags. Clearly better for a single-language C++ implementation, where narrow types are
  native and free. The tradeoff inverts under D2: what is free in C++ is a hazard in
  Java, and this project has to be right in both.
- *Narrow types in the header only.* Splits the rule, which is the property that makes it
  useful.

---
---

# Part III — Delivery

## 13. Experiment plan

The brief requires designing experiments to compare the two semantics and discussing
the results. Each runs `N = 20` trials with server-side injection only (§10). The §2.4
invariant gives an automatic pass/fail per trial.

| # | Setup | Operation | Semantics | Expected |
|---|---|---|---|---|
| E1 | no loss | `RESERVE` | both | identical, correct |
| E2 | `--drop-reply 0.5` | `RESERVE` | at-least-once | **over-reserves**; final seats below expected |
| E3 | `--drop-reply 0.5` | `RESERVE` | at-most-once | correct; duplicates answered from history |
| E4 | `--drop-reply 0.5` | `CANCEL_SEATS` | at-least-once | **over-releases**; invariant violated |
| E5 | `--drop-reply 0.5` | `CANCEL_SEATS` | at-most-once | correct |
| E6 | `--drop-reply 0.5` | `SET_RESERVATION` | at-least-once | **correct despite re-execution** |
| E7 | `--drop-request 0.5` | `RESERVE` | both | correct — request loss alone does not discriminate |
| E8 | `--drop-reply 0.5`, monitor active | `RESERVE` | at-least-once | monitor sees two callbacks with the **same `eventRequestId`** |
| E9 | 200 requests | any | at-most-once | history stays bounded with `ackUpTo`, grows without it |
| E10 | `--drop-callback 0.5` | `MONITOR` | — | monitor reports gaps in `callbackSeq`; `MONITOR_END` gives the true total |

**E6 is the centrepiece.** It is the experiment a read-only idempotent operation could
not produce: a genuine mutation, executed twice, ending in the correct state.

**E7 is worth reporting as a negative result.** It shows the discriminating failure is
specifically *reply* loss, since a lost request is simply retried until it lands under
either semantics.

**E8 is the strongest live demonstration**, because the duplicate execution is visible
on a second machine as it happens, and `eventRequestId` proves the two callbacks came
from one user action rather than two.

Record per trial: expected `seatsAvailable`, observed, retransmission count,
server-side duplicate count, callbacks received versus sent, and invariant pass/fail.

---

## 14. Test plan

| Level | Test |
|---|---|
| conformance | §11.2 vector encoded by both implementations, hex-compared byte for byte |
| unit | round-trip every primitive: `int32` boundaries, `float32` including negatives, zero and denormals, strings at lengths 0, 1, 3, 4, 63 (padding boundaries) |
| unit | header pack/unpack at all four `messageType` values |
| unit | every operation's request and reply body, round-tripped |
| boundary | `QUERY_BY_ROUTE` returning 0, 1, `MAX_ROUTE_RESULTS`, `MAX_ROUTE_RESULTS + 1` matches |
| boundary | `RESERVE` for exactly all remaining seats, and one more |
| boundary | `CANCEL_SEATS` for exactly the held count, and one more |
| boundary | `SET_RESERVATION` to 0, to current, and above capacity |
| negative | truncated datagram, oversized string length, negative length, unknown opcode, bad magic, wrong version |
| negative | reply arriving with a foreign `clientId` — must be discarded, not matched |
| integration | full operation set against a single-language server |
| integration | Java client against the C++ server, all operations, diffed against the single-language server |
| system | concurrent monitoring: two clients register for one flight, a third reserves; both receive the same new seat count before expiry and none after |
| system | three-machine callback demo |
| system | all experiments in §13 |

The single-language server exists as the oracle for the second integration row. Any
difference between the two servers for the same request is a bug in the cross-language
implementation, and the diff localises it immediately.

---

## 15. Build and run

### 15.1 Building

Java, no build tool — plain `javac` keeps the submission a clean archive with no
dependency manifest:

```
javac -d out $(find src/java -name '*.java')
```

C++:

```
make                      # Linux/macOS: c++ -std=c++17 -Wall -Wextra -O2
make PLATFORM=windows     # links ws2_32 for Winsock
```

### 15.2 Running

```
flight_server  --port <port>
               [--semantics at-least-once|at-most-once]   default at-most-once
               [--drop-request <p>] [--drop-reply <p>]    default 0.0
               [--drop-callback <p>]                      default 0.0
               [--seed <n>]                               PRNG seed, repeatable runs

flight_client  --host <server-ip> --port <port>
               [--semantics at-least-once|at-most-once]   must match the server
               [--drop-request <p>] [--drop-reply <p>]    default 0.0
               [--timeout <ms>]                           default 750
               [--retries <n>]                            default 3
               [--seed <n>]
```

The server address and port are supplied on the client's command line, as the brief
permits. The server binds one UDP socket on all interfaces. Each client binds an
ephemeral port and uses that same socket for both replies and callbacks. The server
learns each client's address from the received datagram, so clients need no prior
registration. Find the server's address with `ipconfig` on Windows or `ip addr` on
Linux.

Both programs print every request and reply they handle. `--seed` fixes the
drop-simulation PRNG so an experimental run can be reproduced exactly, including in
front of a marker. A `--semantics` mismatch between client and server is detected from
the header and warned about (D19) rather than silently tolerated.

Host firewalls on all three machines must allow the chosen UDP port inbound, and client
return traffic.

---

## 16. Repository layout

```
docs/                     course brief
src/
  java/
    common/               marshalling, protocol constants, header codec
    client/               console UI, request/reply state machine
    server/               Java server — baseline and test oracle
  cpp/
    server/               C++17 server — the graded cross-language implementation
tests/
  conformance/            §11.2 hex vector, both languages
  experiments/            §13 harness and result logs
scripts/                  build and demo helpers
```

`src/java/common` is shared between the Java client and Java server. It is **not**
shared with the C++ server, which is the point: that implementation is written against
Part I, not against the Java code.

---

## 17. Delivery phases

The optional credit must not endanger the baseline, so a demonstrable system exists
before the difficult part begins.

| Phase | Deliverable | Exit criterion |
|---|---|---|
| 1 | Part I frozen | both implementers agree the spec is unambiguous |
| 2 | Java client + Java server: six operations, both semantics, callbacks | full §13 set passes on two machines |
| 3 | Conformance vector (§11.2) in both languages | both produce identical bytes |
| 4 | C++ server | Java client passes §14 integration, diffed against the Java server |
| 5 | Report, demo rehearsal | three-machine run end to end |

Phase 4 does not start before phase 2 demos cleanly. **If phase 4 runs out of time,
phase 2 is a complete and submittable project** — it simply forfeits the optional
credit.

---

## 18. Demo script

Three machines: server, client A (reserving), client B (monitoring).

1. Start the server, `--semantics at-most-once`, no loss. Show the seeded table.
2. Client A: query by route, showing multiple matches. Then query by ID.
3. Client B: `MONITOR` the flight for 60 s. Show the baseline snapshot.
4. Client A: `RESERVE 2`. Client B prints the callback. Show `seatsAvailable` on both.
5. Client A: `CANCEL_SEATS 1`, then `SET_RESERVATION 5`. Client B prints each.
6. Restart with `--semantics at-least-once --drop-reply 0.5 --seed 42`. Client A:
   `RESERVE 2`. Show the client retransmitting, the server executing twice, client B
   receiving two callbacks **with the same `eventRequestId`**, and the invariant
   assertion firing.
7. Restart with `--semantics at-most-once --drop-reply 0.5 --seed 42`. Repeat. Show
   `DUPLICATE ... replaying stored reply`, one callback, correct final count.
8. Repeat step 6 with `SET_RESERVATION` under at-least-once: two executions, still
   correct. This is the idempotency result.
9. Show the monitor interval expiring, `MONITOR_END` arriving, and client B reporting
   its received-versus-sent callback count.

Steps 6–8 are the graded core. Rehearse until they run without hesitation.

---

## 19. Requirement traceability

| Brief requirement | Satisfied by |
|---|---|
| UDP, not TCP | D1 |
| Service 1: query flight IDs by route, error if none | §4 Op 1 |
| Service 2: query time/fare/seats by ID, error if absent | §4 Op 2 |
| Service 3: reserve seats, ack, update, errors | §4 Op 3 |
| Service 4: monitor via callback for an interval | §4 Op 4, §9 |
| Two extra ops, one idempotent one not, described in report | §4 Ops 5–6, D9 |
| Design request/reply formats, describe in report | §3, §4, D3, D4, D19 |
| Marshal ints, floats, strings by hand; length-prefixed strings | §3.3, §11 |
| Network byte order | §3.2 |
| No RMI/RPC/CORBA/Java serialization/stream classes | D3, and `CLAUDE.md` constraints |
| Both invocation semantics, selectable by argument | §8, §15.2 |
| Request identifiers for duplicate detection | §8.2, D5 |
| Timeouts, duplicate filtering, histories | §7.2, §8.2, §8.3 |
| Simulate loss of requests and replies | §10, D12 |
| Experiments comparing the semantics, discussed in report | §13 |
| Multiple clients monitoring concurrently | §9, D11, §14 |
| Client interface with a quit option | §7.1 |
| Print requests and replies on both sides | §6.1, §7.2 |
| In-memory storage only | D18 |
| Optional: client and server in different languages | D2 **[HARD PATH]** |
| Demo with clients and server on different computers | §18 |

---

## 20. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Cross-language server not finished in time | loses the optional credit | phased delivery (§17); phase 2 is submittable alone |
| Cross-language byte mismatch found late | integration stalls | conformance vector built in phase 3, before the C++ server |
| Winsock/BSD divergence on the demo machine | server will not build on the day | fix the server OS before writing C++; `make PLATFORM=` covers both |
| Firewall blocks UDP between laptops | demo fails | test the three-machine path in advance on a known-good network |
| Client/server semantics mismatch during experiments | results look valid but mean nothing | `semantics` header field, warned on mismatch (D19) |
| History TTL shorter than the retry window | at-most-once silently degrades | startup check enforces the constraint (D8) |
| Callback loss mistaken for a bug during marking | looks like a defect | `callbackSeq` and `MONITOR_END` report gaps explicitly |
| Truncation on route query goes unnoticed | silent wrong answer | explicit `truncated` flag in the reply |
| Single-file submission omits a source file | incomplete submission | scripted packaging step; no resubmission is permitted |

---

## 21. Assumptions and boundaries

- Requests are separated enough in time for a single-threaded server, per the brief.
- Flight data, bookings and reply history are memory-resident. Guarantees cover one
  server process lifetime; a restart clears all state, and the report discloses this
  where it affects an experiment.
- Route matching is case-normalised exact text. No fuzzy matching, no partial matches.
- Departure times are in one agreed timezone; no conversion is performed.
- `clientId` is client-asserted and unverified. Authentication, authorisation and
  encryption are outside the course scope (D5).
- Callback delivery is observable but not guaranteed (D10).
- Seats held by an exited client are never reclaimed (D22).

Production hardening would add durable state, authenticated messages, account-based
booking ownership, rate limiting, metrics and a congestion-aware transport. All are
deliberately excluded: they are not part of the UDP learning objectives the brief sets.

---

## 22. Open decisions

Three items need group agreement before phase 2 starts. Each is a genuine fork, not a
detail.

### 22.1 Language pairing — **blocks phases 2 and 4**

| Option | Effect |
|---|---|
| **C++17 server + Java client** *(assumed here)* | Earns the optional credit, and keeps the server work already under way. Requires one member to write the client in Java. |
| C++17 both ends | Forfeits the optional credit. Least total work. |
| C server + Java client | Same credit as option 1, but rebuilds a server that is already being written in C++. |

Option 1 is the working assumption throughout this document: it is the only one that
takes the credit without discarding work. If the group prefers option 2, then D2, D6,
D15 and D23 all need revisiting — packed narrow types become the better choice once
both ends are C++.

### 22.2 Which two extra operations — **blocks phase 2**

| Option | Effect |
|---|---|
| **`CANCEL_SEATS` / `SET_RESERVATION`** *(assumed here)* | Corruption is visible live to a monitoring client (E8). Requires the booking ledger and accepts the leak in D22. |
| `ADJUST_FARE` / `SET_FARE` | No ledger, no leak, less work. Corruption is invisible to the callback mechanism, weakening the demo. |

Both satisfy the brief. The choice is whether the stronger demonstration is worth the
booking ledger. See D9 and D22 for the full argument.

### 22.3 Server operating system — **blocks phase 4**

Winsock and BSD sockets diverge from the first line of the server. Fix the demo
machine's OS before anyone writes socket code.

---

## 23. Administration

| | |
|---|---|
| Code and report due | 14 October 2026, 23:59 |
| Live demo | 17 October 2026 |

Submission via NTULearn: **one file, one member per group, no resubmission**. Submit
only when the final version is ready.

The report cover page must list every member and **the percentage of work each
contributed**.

### Team

_To be completed._

---

Copying code between groups, in either direction, scores zero for the project.
