# Distributed Flight Information System — Technical Specification

SC6103 Distributed Systems, AY2026/27 Trimester 1. Group project (max 4 members).

A flight information service over UDP with a hand-written request-reply protocol:
byte-level marshalling, timeouts and retransmission, duplicate filtering, reply
histories, and server-to-client callbacks. No RPC framework is used; implementing
that layer is the assignment.

**Status:** implementation complete for local automated verification; all three M0
forks remain closed (§22). A three-physical-machine rehearsal is still required before
the live demonstration.

| | |
|---|---|
| **Done** | Java client; C++17 server; 387 automated checks; E1-E10 experiment harness and results; report draft |
| **Next** | fill the report cover, run the three-machine firewall/reachability rehearsal, and capture final demo evidence |
| **Not automated** | physical multi-host demonstration and NTULearn submission |

**There is one server, written in C++17, and no fallback implementation.** §22.1 records
that decision and what it costs. Read it before planning work.

### How this document is organised

| Part | Contains | Read it when |
|---|---|---|
| **I — Specification** | What the system *is*. Normative, no argument. | Implementing. Both implementations are written from Part I alone. |
| **II — Design rationale** | Why each choice was made, and what was rejected. Decision records `D1`–`D25`, one withdrawn. | Defending the design, or writing the report. |
| **III — Delivery** | Experiments, tests, build, phases, demo, traceability, risk. | Planning and running the work. |

Part I states rules and cross-references the rationale as `→ D4`. Nothing is argued
twice.

Where the brief offers a choice, the harder option is taken **when the difficulty
buys marks**. Those are marked **[HARD PATH]**. The one place the cheaper option was
deliberately chosen is recorded in D9, with the argument, rather than left implicit.

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
10. [Fault injection and observability](#10-fault-injection-and-observability)
11. [Cross-language contract](#11-cross-language-contract)

**Part II — Design rationale**

12. [Decision records D1–D25](#12-decision-records)

**Part III — Delivery**

13. [Experiment plan](#13-experiment-plan)
14. [Test plan](#14-test-plan)
15. [Build and run](#15-build-and-run)
16. [Repository layout](#16-repository-layout)
17. [Delivery milestones](#17-delivery-milestones)
18. [Demo script](#18-demo-script)
19. [Requirement traceability](#19-requirement-traceability)
20. [Risk register](#20-risk-register)
21. [Assumptions and boundaries](#21-assumptions-and-boundaries)
22. [Decisions closed](#22-decisions-closed)
23. [Settled from the brief](#23-settled-from-the-brief)
24. [Team workflow and ownership](#24-team-workflow-and-ownership)
25. [Administration](#25-administration)

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

**[HARD PATH]** The server and client are written in **different languages** — a C++17
server and a Java client, settled at §22.1. There is no second server in either language:
the C++ implementation is the only one, and nothing checks it except this specification,
the §11.2 vectors and a client written independently from both. → D2

### Component responsibilities

| Component | Side | Responsibility |
|---|---|---|
| Console client | Java | Parses commands; blocks during a monitor interval; prints replies, errors, callbacks and timeout status |
| Client reliability layer | Java | Owns `(clientId, requestId)`, applies timeout/retry policy, accepts only matching replies |
| Binary codec | **both, twice** | Marshals and unmarshals headers and bodies with strict bounds and byte-order conversion. Written independently on each side from §3 — never shared, never ported |
| Server dispatcher | C++ | Validates magic/version, selects the operation, applies duplicate policy, builds the status reply |
| Flight services | C++ | Query, reserve, monitor, and the two fare-mutation business rules |
| State managers | C++ | Flight repository, reply history, monitor registry, loss-simulator config |

Everything below the console and above the socket exists **twice**, once per language,
and the two copies are held together by Part I and the §11.2 vectors alone. That is the
cost §22.1 accepts and the reason §11's rules are normative rather than advisory.

---

## 2. Data model

### 2.1 Flight record

| Field | Type | Notes |
|---|---|---|
| `flightId` | `int32` | unique, assigned at seed time |
| `source` | `string` | max 63 bytes |
| `destination` | `string` | max 63 bytes |
| `departureTime` | `Time` | §2.2 |
| `airfare` | `float32` | IEEE-754 binary32; **mutable** by operations 5 and 6 → D16, D9 |
| `totalSeats` | `int32` | fixed at seed time; the baseline the `RESERVE` experiments compare against |
| `seatsAvailable` | `int32` | mutable by `RESERVE`; what the callback mechanism reports |

### 2.2 Time  → D6

Five `int32` fields: `year`, `month`, `day`, `hour`, `minute`. Ranges 1970–9999,
1–12, 1–31, 0–23, 0–59. No timezone; all times are in one agreed local zone. No
seconds.

### 2.3 Client identity  → D5

A client generates a random nonzero **`clientId`** (`uint32`) once at process start
and carries it in every request header. That identifier, paired with `requestId`,
is the reply-history key.

The **observed source IP and port** of a datagram is used for one purpose only:
addressing callbacks. It is never read from the payload, so a client cannot direct
callbacks at a third party.

There is no per-client ownership of any resource. Every operation acts on global
flight state, so no operation needs to know who booked what. → D9

### 2.4 Mutable state and the experiment oracle

Exactly two fields change after seeding:

| Field | Changed by | Arithmetic |
|---|---|---|
| `seatsAvailable` | `RESERVE` | relative decrement |
| `airfare` | `ADJUST_FARE`, `SET_FARE` | relative, then absolute |

Because both are single scalars mutated by a known number of client-issued
operations, each experiment in §13 carries an **exact expected value** computed
from the seed data and the trial script:

```
expected seatsAvailable  =  totalSeats − Σ (seats reserved by successful RESERVE)
expected airfare         =  seededFare + Σ (deltas of successful ADJUST_FARE)
                            or, if a SET_FARE succeeded last, that target exactly
```

The server recomputes and logs the expected value alongside the observed one after
every mutation, and a divergence is logged at `ERR_SERVER_ERROR` severity. A
divergence is the signature of at-least-once re-execution, which is what makes the
§13 experiments self-checking rather than requiring manual inspection.

No ownership table is needed to do this, which is the direct benefit of the fare pair
over the seat pair rejected in D9.

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

Every datagram begins with a fixed **40-byte** header.

| Offset | Type | Field | Meaning |
|---|---|---|---|
| 0 | `uint32` | `magic` | `0x53433631` — ASCII `"SC61"`. Any other value: drop silently. |
| 4 | `int32` | `version` | protocol version, currently 1. Mismatch: `ERR_VERSION_MISMATCH`. |
| 8 | `int32` | `messageType` | 0 = REQUEST, 1 = REPLY, 2 = CALLBACK, 3 = MONITOR_END |
| 12 | `int32` | `opCode` | §4; 0 on CALLBACK and MONITOR_END |
| 16 | `int32` | `semantics` | 0 = at-least-once, 1 = at-most-once. **Echoed unchanged in REPLY, CALLBACK and MONITOR_END.** |
| 20 | `uint32` | `clientId` | random nonzero, fixed for the client's lifetime |
| 24 | `int32` | `requestId` | monotonic per client; echoed in the reply |
| 28 | `int32` | `ackUpTo` | requests only; highest `requestId` this client has a reply for. **−1 when the client holds no reply yet, and −1 in every non-REQUEST message.** |
| 32 | `int32` | `status` | replies and callbacks only; 0 in requests |
| 36 | `int32` | `payloadLen` | exact body length in bytes, 0 to `MAX_BODY` |

The body begins at offset 40.

`magic` rejects unrelated traffic arriving on the port. `version` catches the two
implementations drifting apart. `semantics` lets the server reject a client running
in the opposite mode outright, rather than serving it and producing experimental
results that look valid and mean nothing. `payloadLen` is redundant with the datagram length on a
well-formed message, which is the point: `received == HEADER_SIZE + payloadLen` is a
one-line truncation and disagreement check that runs before anything is decoded.

Two rules above are stated because leaving them implicit lets the two implementations
disagree on bytes neither side reads, which then shows up as a conformance-vector
failure with no obvious cause:

- **`ackUpTo` is −1, not 0, outside a REQUEST.** Zero is a legal watermark meaning "I
  hold the reply for request 0", so it cannot double as "unused". A C++ header struct
  that is zero-initialised writes 0 here; a Java builder defaulting to the sentinel
  writes −1. Both are defensible in isolation and they differ on the wire.
- **`semantics` is echoed rather than zeroed.** The field exists so a mode mismatch is
  refused rather than silently served (D19); echoing it lets the client confirm the
  server agreed, and keeps a packet capture self-describing without reference to the
  request that provoked it.

### 3.5 Size limits

| Constant | Value | Derivation |
|---|---|---|
| `MAX_DATAGRAM` | 512 bytes | fragmentation-free on any realistic path |
| `HEADER_SIZE` | 40 bytes | §3.4 |
| `MAX_BODY` | 472 bytes | derived |
| `MAX_STRING_BYTES` | 63 | place names; bounds the receive buffer |
| `MAX_ROUTE_RESULTS` | 64 | 4 + 4 + 64×4 = 264 bytes, inside `MAX_BODY` |

### 3.6 Decoder validation order

Both implementations validate in exactly this order. The order is normative, not
stylistic: each step establishes what the next step is allowed to trust.

1. Received length is at least `HEADER_SIZE`.
2. Read the fixed header fields. **Do not trust `payloadLen` yet.**
3. Verify `magic`. A mismatch is logged and dropped with no reply — the datagram may
   not be from a client of this system at all.
4. Verify `version`, a known `messageType`, and `received == HEADER_SIZE + payloadLen`.
5. Verify `opCode` is in range, then decode that operation's body with a bounds check
   before every cursor advance.
6. Validate field values: dates form a real calendar date, strings are within
   `MAX_STRING_BYTES`, counts fit the remaining body, seat counts and intervals are in
   range, floats are finite.
7. If the header carried a trustworthy `clientId` and `requestId`, reply with a status
   code. Otherwise log and drop.

Step 6's float check matters even though no current operation accepts a float
argument (§4). It is a standing rule so that adding one cannot introduce a NaN or
infinity into the flight table by omission.

---

## 4. Operation catalogue

All bodies are shown as they appear after the 40-byte header. Argument validation
failures return `ERR_INVALID_ARGUMENT` throughout.

### Op 1 — `QUERY_BY_ROUTE` · idempotent

| | Type | Field |
|---|---|---|
| **Request** | `string` | `source` |
| | `string` | `destination` |
| **Reply** | `int32` | `truncated` — 1 if matches were omitted, else 0 |
| | `uint32` | `count` |
| | `int32 × count` | `flightIds` |

Matching trims outer ASCII whitespace, applies ASCII case folding, then compares
exactly. Returns `ERR_NO_ROUTE_MATCH` when nothing matches — the brief requires an
error, not an empty list. Results are capped at `MAX_ROUTE_RESULTS` with
`truncated = 1`. → D14

**`flightIds` are returned in ascending order.** The repository's iteration order is
not an interface, and a test that asserts on results must not depend on it.

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

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_INSUFFICIENT_SEATS`.
Fires callbacks on success (§9). **The only operation that does.**

The post-state is returned rather than a bare acknowledgement so that under
at-least-once with a lost reply, the second execution returns a *different*
`seatsAvailable` from the first — the corruption is visible in the reply itself, not
only in a later query.

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

### Op 5 — `ADJUST_FARE` · **non-idempotent**

Add a signed `delta` to a flight's airfare. Relative arithmetic on a single scalar.
The brief's required non-idempotent extra operation. → D9

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `float32` | `delta` — finite; may be negative |
| **Reply** | `float32` | `airfare` after |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_INVALID_FARE`, `ERR_INVALID_ARGUMENT`.
Does **not** fire callbacks — the monitor mechanism reports seat availability only
(§9), which is the cost accepted in D9.

`ERR_INVALID_FARE` is returned when the *resulting* fare would leave the legal range
`0 < fare ≤ MAX_FARE`; the stored value is unchanged in that case.
`ERR_INVALID_ARGUMENT` covers a `delta` that is NaN or infinite, rejected at the codec
before it is ever applied (§3.6 step 6).

This is deck 2's canonical non-idempotent operation — *"increasing a variable by 3:
2 + 3 = 5; 5 + 3 = 8; 8 + 3 = 11"* — instantiated on a real field. Replaying it applies
the delta twice, so the fare diverges from the expected value of §2.4. That is the
failure §13 measures.

---

### Op 6 — `SET_FARE` · **idempotent**

Set a flight's airfare to exactly `newFare`. Absolute assignment.
The brief's required idempotent extra operation. → D9

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `float32` | `newFare` — finite, `0 < newFare ≤ MAX_FARE` |
| **Reply** | `float32` | `airfare` after — equals `newFare` on success |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_INVALID_FARE`, `ERR_INVALID_ARGUMENT`.
Does not fire callbacks, for the same reason as Op 5.

The server assigns rather than accumulates, so a second execution writes the same
value and the final state is identical. This is a genuine mutation that is
nevertheless replay-safe, which is the claim experiment E6 exists to demonstrate — and
the reason the idempotent operation is a **write** rather than a read-only query
(D9).

**`MAX_FARE` is 100000.0f**, a shared constant alongside the §3.5 size limits. It
exists so that "reject an out-of-range fare" is a testable boundary in both
implementations rather than a matter of taste.

---

### `CALLBACK` · `messageType = 2`

Sent unsolicited. Not acknowledged, not retried. → D10

**Header:** `messageType = 2`, `requestId` = the `requestId` of the `MONITOR`
registration being served, `opCode = 0`, `status = 0`.

| Type | Field |
|---|---|
| `int32` | `flightId` |
| `int32` | `seatsAvailable` |
| `uint32` | `eventClientId` — the `clientId` of the client whose operation caused this update |
| `int32` | `eventRequestId` — the `requestId` of the operation that caused this update |
| `int32` | `callbackSeq` — per-registration counter from 1 |

`callbackSeq` makes callback loss observable: a client seeing 1, 2, 4 reports one
lost update. `eventClientId` and `eventRequestId` together let the client distinguish
two callbacks caused by one duplicated operation (same pair) from two distinct
operations (different pairs) — the direct observable signal of at-least-once
re-execution.

The event is named by both fields because that pair is the request's identity (D5).
Every client counts its `requestId`s from 0, so two clients that each reserve once send
the same `requestId`, and a monitor comparing that field alone would report a
re-execution that never happened.

---

### `MONITOR_END` · `messageType = 3`

Sent once when a registration expires. **Advisory only** — it is unacknowledged and
may itself be lost, so the client's local deadline remains authoritative. → D20

**Header:** identical in shape to CALLBACK — `messageType = 3`, `clientId` and
`requestId` copied from the `MONITOR` registration that expired, `opCode = 0`,
`status = 0`, `semantics` echoed, `ackUpTo = −1`. `requestId` is load-bearing: a
client holding two registrations cannot otherwise tell which one ended.

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
| 5 | `ERR_INVALID_FARE` | resulting airfare outside `0 < fare ≤ MAX_FARE`; stored value unchanged |
| 6 | `ERR_MALFORMED_REQUEST` | body did not parse, or length disagreed |
| 7 | `ERR_UNKNOWN_OPCODE` | opcode not in 1–6 |
| 8 | `ERR_MONITOR_TABLE_FULL` | registry at capacity |
| 9 | `ERR_SERVER_ERROR` | internal invariant violation |
| 10 | `ERR_VERSION_MISMATCH` | header `version` not recognised |
| 11 | `ERR_SEMANTICS_MISMATCH` | client and server are in different invocation modes |
| 12 | `ERR_REQUEST_ID_CONFLICT` | history holds these IDs with a different request fingerprint |

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
    # The wait is BOUNDED. See "timer independence" below.
    wait = min(monitors.time_until_next_expiry(), MAX_POLL_MS)

    if socket_readable_within(wait):
        n, from = recvfrom(socket)
        process_one_datagram(n, from, buf)

    expire_monitors_and_send_monitor_end(now)    # §9.3
    evict_expired_history(now)                   # §8.3


process_one_datagram(n, from, buf):
    if simulate_drop(dropRequestRate):
        log "DROP request from={from} reason=injected"; return

    if n < HEADER_SIZE: return
    hdr = parse_header(buf)                      # validation order: §3.6
    if hdr.magic != MAGIC: log "DROP alien datagram from={from}"; return
    if hdr.version != VERSION: send_error(ERR_VERSION_MISMATCH, hdr, from); return
    if hdr.messageType != REQUEST: return
    if n != HEADER_SIZE + hdr.payloadLen: send_error(ERR_MALFORMED_REQUEST,...); return
    if hdr.semantics != serverSemantics: send_error(ERR_SEMANTICS_MISMATCH,...); return

    log "RECV client={hdr.clientId} req={hdr.requestId} op={hdr.opCode}"

    # ackUpTo must sit strictly below the requestId it rides on, or this request
    # prunes its own history entry before the lookup below. See §8.3.
    if hdr.ackUpTo >= hdr.requestId:
        send_error(ERR_INVALID_ARGUMENT, hdr, from); return
    prune_history(hdr.clientId, hdr.ackUpTo)     # §8.3

    if serverSemantics == AT_MOST_ONCE:
        fp = fingerprint(hdr.opCode, buf + HEADER_SIZE, hdr.payloadLen)   # §8.2
        e  = history_lookup(hdr.clientId, hdr.requestId)
        if e != NULL:
            if e.fingerprint != fp:
                log "CONFLICT req={hdr.requestId} fingerprint differs"
                send_error(ERR_REQUEST_ID_CONFLICT, hdr, from); return
            log "CACHE_HIT req={hdr.requestId} replaying stored reply"
            maybe_send(e.bytes, from)
            return                               # no re-execution, no callbacks

    status, body = dispatch(hdr.opCode, buf + HEADER_SIZE, hdr.clientId, from)
    reply = marshal_reply(hdr, status, body)

    if serverSemantics == AT_MOST_ONCE:
        history_store(hdr.clientId, hdr.requestId, fp, reply)   # store BEFORE sending

    log "SEND req={hdr.requestId} status={status}"
    maybe_send(reply, from)

    if status == OK and opCode == RESERVE:
        fire_callbacks(flightId, hdr.requestId)  # §9.2

    assert_expected_state()                      # §2.4
```

Six properties are load-bearing and belong in the report:

1. **Timer independence.** The socket wait is bounded by the nearest monitor expiry,
   never an unbounded block. Monitor expiry, `MONITOR_END` delivery and history
   eviction must not depend on a future request happening to arrive — on an idle
   server a blocking `recvfrom` would leave registrations alive past their interval,
   which is a correctness failure, not a tidiness one.
2. **The duplicate path returns before dispatch.** Under at-most-once a duplicate
   neither re-executes nor re-fires callbacks; under at-least-once there is no
   duplicate path, so both happen. That single branch is the entire behavioural
   difference between the two semantics.
3. **The reply is stored in the history before the send is attempted**, so a reply
   lost in transit is still available to answer the retransmission.
4. **Callbacks fire even when the reply is dropped.** A dropped reply models loss on
   the return path — the server did execute. This is what makes at-least-once
   corruption on `RESERVE` visible to a monitoring client.
5. **`ackUpTo` is validated before it is acted on.** A watermark at or above the
   request's own `requestId` would erase the entry that request is about to need, so
   it is refused rather than honoured. → §8.3
6. **Expected state is asserted every iteration**, so divergence is detected when it
   occurs rather than inferred later. → §2.4

All expiry decisions use a **monotonic clock**. No protocol behaviour may depend on
wall-clock time, which can jump.

### 6.2 Concurrency  → D11

One thread, no locking. Multiple concurrent monitoring clients are still supported,
because the registry is a list and needs no threading. Callbacks are emitted
sequentially.

### 6.3 Seed data  → D18

The flight table is seeded from a hard-coded array at startup. It must contain at
least: two flights sharing a source/destination pair (multi-result queries), one
flight on a distinct route, one flight with a small seat count (fast exhaustion), and
a spread of airfares — including one high enough and one low enough that a small
`ADJUST_FARE` delta can drive the result outside `0 < fare ≤ MAX_FARE` and exercise
`ERR_INVALID_FARE` without a contrived argument.

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
user input during this period. It prints a summary when `MONITOR_END` arrives, or when
the deadline plus a grace window of one retry timeout has passed, whichever comes
first: callbacks received, callbacks missed, and any `(eventClientId, eventRequestId)`
pair seen more than once. During the grace window the client listens only for
`MONITOR_END`. → D20

---

## 8. Invocation semantics

Both are implemented and selected by a CLI flag on both programs at startup. → D7

| Semantics | Client | Server on duplicate | Consequence |
|---|---|---|---|
| at-least-once | timeout, then resend the identical datagram up to R retries | execute every valid arrival; no history lookup | a duplicated `RESERVE` or `ADJUST_FARE` changes state twice |
| at-most-once | same retry policy, same stable IDs | history hit replays the exact cached reply; new IDs execute once, then cache | both idempotent and non-idempotent operations stay correct under loss |

Maybe-semantics is not implemented for the six operations. Callbacks and
`MONITOR_END` exhibit it regardless (§9.2, §4).

### 8.1 At-least-once

No history. Every request that arrives is executed. Correct for operations 1, 2, 4
and 6; corrupting for 3 and 5.

### 8.2 At-most-once

Keyed on **(clientId, requestId)** — not on network address, so a retransmission
stays recognisable across a NAT rebinding or a client restart. → D5

Each history entry also stores a **request fingerprint**: a stable hash of `opCode`
plus the exact request body. On a key hit the server compares fingerprints. A match
replays the cached reply; a mismatch returns `ERR_REQUEST_ID_CONFLICT` and executes
nothing. Without this, a client that reuses a `requestId` for different arguments —
through a bug, a counter reset, or wraparound — is silently answered with the reply
to a different question, which is a worse failure than either semantics is supposed
to permit. → D24

The fingerprint is computed and compared **entirely within the server**. It never
appears on the wire, so the two implementations need not agree on the hash function
and any stable one will do.

The **marshalled reply bytes** are stored, not the structured result, so a duplicate
is answered by a buffer copy that is byte-identical to the original without
depending on marshaller determinism. → D8

**Error replies are cached too.** An application-level error is a legitimate reply,
and re-executing to regenerate it would re-run validation against state that may
have changed since. Only datagrams too malformed to yield a trustworthy `clientId`
and `requestId` go uncached — there is nothing to key them on.

### 8.3 Bounding the history  → D8

Three mechanisms, in order of precedence:

1. **Piggybacked watermark.** Every request carries `ackUpTo`. On receipt the server
   discards that client's entries with `requestId ≤ ackUpTo`. Exact, and costs no
   extra traffic.
2. **Per-client cap.** At most `MAX_HISTORY_PER_CLIENT` entries; oldest evicted when
   full.
3. **Idle expiry.** Entries older than `HISTORY_TTL_MS` are swept.

Mechanism 1 does the work; 2 and 3 make the bound absolute.

**Hard constraint 1 — TTL floor.** `HISTORY_TTL_MS > timeoutMs × (maxRetries + 1)`. A
TTL shorter than the client's maximum retry window would let an entry expire while a
legitimate retransmission is still in flight, silently degrading at-most-once to
at-least-once. The server checks the floor when it is compiled, against the client's
default 750 ms timeout and 3 retries, and the build fails if it does not hold. A running
server cannot see a client's `--timeout` or `--retries`, so a client whose retry window
is longer than the 60 s TTL can still break the guarantee. The client prints both values
in its banner, which makes such a run visible in its log.

**Hard constraint 2 — watermark ordering.** `ackUpTo < requestId` on every request,
and the server **rejects** a request that violates it with `ERR_INVALID_ARGUMENT`
before pruning (§6.1).

Mechanism 1 prunes on receipt, *before* the duplicate lookup. That ordering is correct
only while a request's watermark stays strictly below its own identifier — which holds
for a correct client, because §7.2 raises `ackUpTo` only after a reply arrives and
`requestId` has already advanced past it. It does not hold for a buggy one. A client
that sent `ackUpTo == requestId` would have the server delete the very entry its
retransmission depends on, and every retransmission would then be treated as new: at-most-once
degrades to at-least-once precisely under the loss conditions it exists to handle,
and the server reports nothing wrong.

This is the same class of failure as D24's reused `requestId` and it takes the same
answer — do not trust the client to be correct, check the invariant the mechanism
depends on and fail loudly. The check is one comparison.

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

On a successful `RESERVE` — the only operation that changes seat availability — the
server removes expired entries, snapshots the live registrants for that `flightId`,
increments each registration's `callbackSeq`, and sends one callback per registrant.

`ADJUST_FARE` and `SET_FARE` fire nothing. The brief scopes the monitor service to
*"updates made to the seat availability information of a flight"*, and a fare change
is not one. This is the disclosed cost of the fare pair (D9): at-least-once corruption
of the *extra* operations is observed at the issuing client and in the server log
rather than live on a third machine. `RESERVE` still supplies the live demonstration.

Delivery is **fire and forget** — no acknowledgement, no retry, no history. That is
*maybe* semantics in deck 3's taxonomy, and the report states so. `callbackSeq` and
the `(eventClientId, eventRequestId)` pair make the resulting loss and duplication
measurable rather than merely admitted.

The client that triggered the update also receives a callback if registered.

### 9.3 Expiry  → D10, D20

Checked in two places: **lazily at delivery time**, so a registration that expired
since the last sweep never receives a late update; and **by a sweep each loop
iteration**, so memory is reclaimed with no reservation traffic. Delivery-time
checking is what makes expiry exact.

On removal the server sends one `MONITOR_END`. It is advisory; the client's own
deadline remains authoritative.

---

## 10. Fault injection and observability

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

### 10.1 Deterministic controls  → D25

Probabilistic loss is what §13 measures over many trials, but it is the wrong tool
for a live demonstration: at *p* = 0.5 the drop that makes the point may simply not
happen while a marker is watching. Alongside the probability flags, both programs
accept one-shot rules that fire exactly once and then log `RULE_CONSUMED`:

```
--drop-first-request
--drop-first-reply <OPCODE>
--drop-first-callback
```

Scoping `--drop-first-reply` by opcode matters: a demo reserves, cancels and sets in
sequence, and the rule must fire on the operation being demonstrated rather than on
whichever request happens to arrive first.

Active rules and all seeds are printed at startup and recorded with the captured
logs (§12.3), so a scenario can be replayed exactly.

### 10.2 Log schema

Field order is fixed so logs captured on three machines can be read side by side.
Human-readable text is fine; stable field positions are not optional.

```
timestamp  role    event      dir       client  req  opcode  attempt  decision            state
12:04:18.221 CLIENT SEND      request   51      17   RESERVE 1        transmitted         -
12:04:18.223 SERVER EXECUTE   request   51      17   RESERVE 1        new                 seats=7
12:04:18.224 SERVER DROP      reply     51      17   RESERVE 1        forced-first-reply  seats=7
12:04:18.975 CLIENT RETRY     request   51      17   RESERVE 2        timeout             -
12:04:18.977 SERVER CACHE_HIT reply     51      17   RESERVE 2        replay              seats=7
```

Never log a bare `failed` or `duplicate`. Every line carries the client, request ID
and opcode, or it cannot be correlated against the other two machines' logs.

**Minimum events.** Client: command parsed, request encoded, request dropped or
sent, wait started, timeout, retry, reply ignored, reply matched, callback received,
monitor expired, final timeout. Server: datagram received, validation failure,
semantics mismatch, history miss / hit / conflict, service executed, state before
and after, reply cached, reply dropped or sent, callback dropped or sent,
subscription added / updated / expired, history eviction.

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

**This record is a marshaller anchor, not a datagram layout.** No operation in §4
transmits a whole flight record — `QUERY_BY_ID` returns time, fare and seats, and
`QUERY_BY_ROUTE` returns only identifiers. It exists because it exercises all four
primitives of §3.3 in one fixture, with both padding cases and a float, and because it
is the shape deck 2 uses for its own CDR worked example. It deliberately omits
`totalSeats`, which is server-side seed state and never crosses the wire.

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
| D4 | Fixed 40-byte header, error codes without text | |
| D5 | Request identity: `(clientId, requestId)` | |
| D6 | Time as five `int32` fields | |
| D7 | Both invocation semantics, runtime-selected | mandated |
| D8 | History bounded by piggybacked acknowledgement | |
| D9 | The two extra operations | |
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
| D22 | ~~Bookings are never expired~~ | **withdrawn** |
| D23 | Uniform 4-byte field width | |
| D24 | Request fingerprints in the history | |
| D25 | Deterministic fault rules alongside probabilistic loss | |

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

**Decision.** The two programs are written in different languages: **C++17 server,
Java client.** Closed at §22.1 on 3 September 2026.

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

- *Both ends in C++, or both ends in Java.* The brief's baseline. Forfeits the only
  optional credit on offer, and hides marshalling bugs as above.
- *C rather than C++ on the server.* Equivalent on the wire, since §11 forbids the
  language features that would differ. C++17 is chosen because it is what the server work
  targets; nothing in this specification depends on the choice.
- *C++ client, Java server.* Same credit, worse logistics: it needs a working C++
  toolchain and a successful build on two separate client machines on demo day, where the
  chosen direction needs one prepared server machine and a JVM on the rest.
- *Keeping a Java server alongside the C++ one*, as oracle and fallback. This was the
  plan until 3 September 2026 and §22.1 records why it was dropped: the group already
  understood the project to have one server, and a specification four people read
  differently is worse than a specification with a thinner safety net.

**Risk and mitigation.** Cross-language integration can fail late, and with no fallback
that failure now ends the project rather than costing a bonus. Three things absorb it,
all in §17's phasing: the §11.2 conformance vectors are encoded in C++ **before** any
server logic, so layout faults surface against a hex dump rather than a live demo; the
finished Java client drives every C++ milestone, so the server is answering an
independent reading of Part I from its first operation onward; and M2 proves the build,
the sockets and the three-machine network path in week one, when each is still cheap to
fix.

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

### D4 — Fixed 40-byte header, error codes without text

**Decision.** A fixed header of ten 4-byte fields (§3.4). Error replies carry a
status code and no body.

**Reasoning.** Deck 2 gives the minimal structure as `messageType` + `requestId` +
application-specific content. `opCode` is added because one port multiplexes six
operations and the dispatcher must choose a body parser. `status` lets every error be
decoded before the body is touched. `ackUpTo` bounds the history (D8) without extra
traffic. `magic`, `version` and `semantics` are guards, argued in D19. `payloadLen`
duplicates information the datagram length already carries, deliberately: comparing
the two is a single check that catches truncation and any disagreement about how
long the body should be, before a decoder has trusted anything. A fixed size means
constant offsets, the main defence against cross-language layout drift.

Error replies stay a fixed 40 bytes with no body, which keeps the duplicate-replay
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
sends it in every header. The reply history is keyed on `(clientId, requestId)`. The
observed source endpoint is used only to address callbacks.

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
client could impersonate another and read its cached replies. Out of scope for the
course (§21), but it is the honest cost of moving identity off the transport, and the
report should say so.

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
backstops, with `HISTORY_TTL_MS > timeoutMs × (maxRetries + 1)` checked when the server
is compiled, against the client's default retry settings (§8.3).
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

The watermark-ordering constraint (§8.3) is the same failure reached by a different
route. Pruning happens on receipt, before the duplicate lookup, so a request whose
`ackUpTo` reached its own `requestId` would delete the entry its retransmission needs.
A correct client cannot produce that, which is exactly why the server must check it:
the guarantee exists for the case where something has already gone wrong.

**Alternatives rejected.**

- *Unbounded history.* Correct but unbounded; the deck flags this as the problem to
  solve.
- *Trusting `ackUpTo` unvalidated.* Saves one comparison and reintroduces a silent
  degradation of the mechanism the field exists to serve.
- *TTL and LRU only.* Sound, and strictly weaker: both are heuristics that discard
  entries on a guess about when the client is finished, whereas the watermark is exact
  information the client already has. Retained here as backstops rather than as the
  primary mechanism.
- *Storing the structured result and re-marshalling on a duplicate.* Byte-identity
  would then depend on marshaller determinism.

---

### D9 — The two extra operations

**Decision.** `ADJUST_FARE` (non-idempotent, relative) and `SET_FARE` (idempotent,
absolute), both writing to the `airfare` field of the same flight record. No ownership
model and no per-client state.

**Reasoning.** The brief requires two extra operations, "one of them should be
idempotent and the other should be non-idempotent". Choosing two *writes against the
same field* means the pair differs in exactly one variable — relative arithmetic
versus absolute assignment — so the experiment isolates idempotency as the cause of
divergence rather than confounding it with the choice of target.

Deck 2 slide 76 defines the property with two examples: idempotent as set union
(`{1,2} ∪ {3} = {1,2,3}; {1,2,3} ∪ {3} = {1,2,3}`) and non-idempotent as *"increasing a
variable by 3: 2 + 3 = 5; 5 + 3 = 8; 8 + 3 = 11"*. `ADJUST_FARE` is that second example
applied to a real scalar field, and `SET_FARE` is an assignment with the same
absorbing property as the first. The pair instantiates the lecture's own illustration
rather than paraphrasing it.

Two secondary benefits, neither decisive on its own:

- **It puts a `float32` in a request body.** Before these operations, §4 carried a
  float in one direction only — `QUERY_BY_ID`'s reply. The brief requires marshalling
  "the integer values, floating-point values and strings", and D16's bit-for-bit IEEE-754
  transfer is now exercised in both directions across the language boundary, which is
  where a cross-language fault would actually hide.
- **The experiment oracle needs no ledger.** Expected fare is `seededFare + Σ deltas`,
  computable from the seed data and the trial script alone (§2.4).

**Alternatives rejected.**

- *`CANCEL_SEATS` / `SET_RESERVATION` on a per-client booking ledger.* The prior
  working assumption, and a genuinely stronger demonstration: seats are what the
  callback mechanism reports, so at-least-once corruption of the *extra* operations
  would be visible in real time on a third machine. Rejected because it buys that one
  demo moment with a per-client ownership table the brief never asks for, an orphaned-booking
  leak that has to be disclosed, and a `seatsHeldByClient` field on two reply bodies.
  The decisive point is that `RESERVE` is already non-idempotent and already fires
  callbacks, so the live corruption demo survives intact without the ledger — what the
  seat pair uniquely added was watching an *idempotent* write replay harmlessly on the
  monitoring machine, and that is a small return on a whole subsystem. See §22.2.
- *A read-only query as the idempotent operation.* Much easier and the common choice.
  A read cannot corrupt state, so at-least-once trivially produces the right answer
  and the comparison demonstrates nothing. The interesting claim is that a **write**
  can be replay-safe, and both surviving operations are writes.
- *`ADD_FLIGHT` / `DELETE_FLIGHT`.* Orthogonal to the state the rest of the system is
  built around; needs its own test path and never touches the monitor mechanism.
- *Fare as fixed-point integer cents.* Correct engineering for money and rejected under
  D16 — the brief names a floating-point value, and moving fare mutation onto integers
  would discard the float-in-a-request benefit that motivates this pair.

**Cost accepted.** Fare changes are invisible to the callback mechanism (§9.2), so
experiment E6's idempotent-replay result is observed at the issuing client and in the
server log rather than live on a monitoring machine. This is a real reduction in demo
strength and the report should say so plainly rather than omit it.

**Deviation noted.** This reverses the prior working assumption and is the one place
where this project deliberately takes the cheaper of two compliant options. It is
recorded rather than silent because `CLAUDE.md`'s standing posture is the opposite, and
that file is updated in the same change.

---

### D10 — Callbacks fire-and-forget with sequence numbers

**Decision.** Server-side registry, unsolicited datagrams, no acknowledgement, no
retry. Each callback carries `callbackSeq`, `eventClientId` and `eventRequestId`.
Expiry is checked both lazily at delivery and by a periodic sweep.

**Reasoning.** The brief describes registration by address and port, delivery "every
time a seat reservation is made by any client on the flight", and removal after the
interval expires. Not retrying is a deliberate, disclosed choice: an unacknowledged
push is *maybe* semantics in deck 3's taxonomy. `callbackSeq` turns that from an
admission into a measurement. `eventClientId` and `eventRequestId` together distinguish
two callbacks caused by one duplicated operation from two genuinely distinct operations,
which is what makes at-least-once re-execution directly observable at the monitor rather
than merely inferred from the seat count. The monitor applies the rule the server uses
to filter duplicates, which deck 2 states as "messages from the same client with same
requestId".

**Alternatives rejected.**

- *Naming the event by `eventRequestId` alone.* Four bytes shorter, and wrong for the
  reason D5 gives: two clients collide on their first request. A monitor watching two
  reserving clients would report a re-execution that never happened, and under
  at-most-once that contradicts the semantics on display.
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

- *Thread-per-request or a thread pool.* No marks, and it would force the reply history
  and the flight repository to become thread-safe, introducing races a marker could
  mistake for semantics bugs.
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

The client does wait a little past its own deadline, though. Both programs time the same
interval from nearly the same instant, and the server sends `MONITOR_END` from its expiry
sweep, which runs a few milliseconds after the nominal expiry. With no allowance, the
client's deadline won that race in 4 of 10 lossless loopback sessions during the
15 September 2026 verification, and each of those sessions warned that its lost-update
count might be understated when nothing had been lost. The client therefore keeps
listening for `MONITOR_END` alone for one retry timeout after its deadline. The wait is
still bounded, so a `MONITOR_END` that really is lost delays the summary by at most that
timeout.

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

### D22 — Bookings are never expired · **WITHDRAWN**

**Status.** Withdrawn when §22.2 closed on the fare pair. There is no booking ledger,
so there are no bookings to expire and nothing to disclose.

The record is retained rather than renumbered because D-numbers are cited from source
comments and from `tests/conformance/vectors.md`, and silently reusing 22 for something
else would make those citations point at the wrong argument.

**What it said.** The ledger persisted for the server process lifetime, so seats held
by an exited client were never reclaimed and the available pool leaked monotonically.
A TTL was rejected because it would have made the idempotent extra operation
non-deterministic across a long demo, which is the property experiment E6 depends on.

**Why it is gone.** The leak was a direct consequence of the per-client ownership
model, and dropping that model in D9 removes it at the root rather than mitigating it.
This was the strongest argument against the seat pair and it is recorded here so the
report can show the cost was eliminated by a design choice, not overlooked.

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

The cost is real but irrelevant here: the header grows from 20 to 40 bytes and the time
structure from 6 to 20, inside a 512-byte budget carrying bodies of under 300 bytes.

**Alternatives rejected.**

- *Packed narrow types* — `uint16` lengths, `uint8` date components, byte-packed header
  flags. Clearly better for a single-language C++ implementation, where narrow types are
  native and free. The tradeoff inverts under D2: what is free in C++ is a hazard in
  Java, and this project has to be right in both.
- *Narrow types in the header only.* Splits the rule, which is the property that makes it
  useful.

---

### D24 — Request fingerprints in the history

**Decision.** Each at-most-once history entry stores a stable hash of `opCode` plus the
exact request body. A key hit with a mismatched fingerprint returns
`ERR_REQUEST_ID_CONFLICT` and executes nothing.

**Reasoning.** `(clientId, requestId)` identifies a request only if the client never
reuses a pair for different arguments. That assumption holds for a correct client and
fails for a buggy one, a counter reset, or `requestId` wraparound. Without the
fingerprint the failure is silent and severe: the server answers a question with the
reply to a different question, which is neither at-most-once nor at-least-once
behaviour but simply wrong. The fingerprint converts an invisible corruption into a
loud, testable error (T10 in §14).

It is cheap because it never leaves the server. The hash is computed and compared
inside one process, so the two implementations need not agree on it — none of the
cross-language objections that rule out an on-the-wire checksum (D21) apply.

**Alternatives rejected.**

- *Trusting the client not to reuse IDs.* The whole point of at-most-once is to behave
  correctly when something has gone wrong; assuming the client is well-behaved
  contradicts the failure model the mechanism exists to handle.
- *Storing the full request body instead of a hash.* Exact rather than probabilistic,
  and rejected as multiplying history memory by the request size to eliminate a
  collision probability already negligible at this scale.
- *Silently re-executing on a fingerprint mismatch.* Turns a detectable bug into
  at-least-once behaviour under a label claiming otherwise.

---

### D25 — Deterministic fault rules alongside probabilistic loss

**Decision.** Both programs support one-shot `--drop-first-*` rules in addition to the
seeded probabilistic gates. The rules fire once and log `RULE_CONSUMED`.

**Reasoning.** The two mechanisms answer different questions and neither substitutes
for the other. §13 needs probabilistic loss to report outcome rates across 20 trials.
A live demonstration needs the opposite: the drop must happen on the specific
operation being shown, on the first attempt, while someone is watching. At *p* = 0.5
there is a 50% chance the point is not made.

`--seed` alone is not sufficient. It makes a run reproducible, but only after someone
has hunted for a seed whose random sequence drops at the right moment — and that seed
stops working the instant the demo script changes order.

Scoping the reply rule by opcode is necessary because the demo issues several
operations in sequence and the interesting drop is rarely on the first one.

**Alternatives rejected.**

- *Probabilistic loss only.* The graded demonstration becomes a coin flip.
- *Deterministic rules only.* Removes the ability to report loss rates over trials,
  which §13 needs.
- *Scripting the drops externally.* Reintroduces the external-tool objection of D12,
  and the brief requires loss simulated in-program.

---
---

# Part III — Delivery

## 13. Experiment plan

The brief requires designing experiments to compare the two semantics and discussing
the results. Each runs `N = 20` trials with server-side injection only (§10). The §2.4
expected-value oracle gives an automatic pass/fail per trial.

| # | Setup | Operation | Semantics | Expected |
|---|---|---|---|---|
| E1 | no loss | `RESERVE` | both | identical, correct |
| E2 | `--drop-reply 0.5` | `RESERVE` | at-least-once | **over-reserves**; final seats below expected |
| E3 | `--drop-reply 0.5` | `RESERVE` | at-most-once | correct; duplicates answered from history |
| E4 | `--drop-reply 0.5` | `ADJUST_FARE` | at-least-once | **over-adjusts**; fare diverges from expected |
| E5 | `--drop-reply 0.5` | `ADJUST_FARE` | at-most-once | correct; duplicates answered from history |
| E6 | `--drop-reply 0.5` | `SET_FARE` | at-least-once | **correct despite re-execution** |
| E7 | `--drop-request 0.5` | `RESERVE` | both | correct — request loss alone does not discriminate |
| E8 | `--drop-reply 0.5`, monitor active | `RESERVE` | at-least-once | monitor sees two callbacks with the **same `(eventClientId, eventRequestId)`** |
| E9 | 200 requests | any | at-most-once | history stays bounded with `ackUpTo`, grows without it |
| E10 | `--drop-callback 0.5` | `MONITOR` | — | monitor reports gaps in `callbackSeq`; `MONITOR_END` gives the true total |

**E6 is the centrepiece.** It is the experiment a read-only idempotent operation could
not produce: a genuine mutation, executed twice, ending in the correct state. Pair it
with E4 in the report — same field, same loss rate, same retry policy, differing only
in relative versus absolute arithmetic. That is the isolation the whole design exists
to achieve.

**E4 and E5 are the pair the brief asks for by name**: "Show that at-least-once
invocation semantics can lead to wrong results for non-idempotent operations, while
at-most-once invocation semantics work correctly for all operations." 

**E7 is worth reporting as a negative result.** It shows the discriminating failure is
specifically *reply* loss, since a lost request is simply retried until it lands under
either semantics.

**E8 is the strongest live demonstration**, because the duplicate execution is visible
on a second machine as it happens, and the repeated `(eventClientId, eventRequestId)`
pair proves the two callbacks came from one user action rather than two.

Record per trial: the mutated field's expected value, its observed value,
retransmission count, server-side duplicate count, callbacks received versus sent, and
oracle pass/fail.

Fare comparisons are exact, not approximate. Every delta in the trial script is chosen
to be representable in binary32 (halves, quarters, and integers), so an expected fare
and an observed fare are compared bit-for-bit rather than within a tolerance. A
tolerance would hide exactly the small divergence a single duplicated `ADJUST_FARE`
produces.

`tests/java/sc6103/integration/ExperimentHarness.java` runs the plan. Every exchange
goes through the Java client's own `RequestReply`, and E10's counts come from its
`MonitorSession` summary, so the retry loop, reply matching and `ackUpTo` being measured
are the console client's. Two settings differ from a console session: the timeout is
35 ms with 9 retries, which keeps 20 trials per scenario quick on loopback, and loss is
injected on the server only. E6 counts the trials in which the server logged a
re-executed `SET_FARE`. E9 reads the per-client history size the server prints on every
`CACHE` line, once for a client that acknowledges and once for hand-built requests that
never do.

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
| boundary | `ADJUST_FARE` landing exactly on `MAX_FARE`, and one increment past it |
| boundary | `ADJUST_FARE` with a negative delta landing exactly above 0, and one past it into `ERR_INVALID_FARE` |
| boundary | `SET_FARE` to the current value, to `MAX_FARE`, to 0 and to a negative value |
| negative | `ADJUST_FARE` / `SET_FARE` with a NaN or infinite float argument → rejected at the codec, no state change |
| negative | truncated datagram, oversized string length, negative length, unknown opcode, bad magic, wrong version, `payloadLen` disagreeing with the received length |
| negative | reply arriving with a foreign `clientId` — must be discarded, not matched |
| negative | NaN and ±infinity rejected wherever a float is decoded |
| reliability | **T10** reuse `(clientId, requestId)` with a changed opcode or body under at-most-once → `ERR_REQUEST_ID_CONFLICT`, no service execution |
| reliability | **T13** client and server started in different modes → `ERR_SEMANTICS_MISMATCH`, no state change |
| reliability | **T15** monitor expiry on a fully idle server → registration removed and `MONITOR_END` sent with no request traffic whatsoever |
| reliability | **T12** state change, then server restart → initial dataset restored; report states the process-lifetime boundary |
| integration | §11.2 vectors encoded in C++ and hex-compared against the Java output, **before any server logic is written** |
| integration | Java client against the C++ server, all six operations, every status code reachable at least once |
| integration | Java client's own decoders exercised against real C++ replies — a body that decodes in Java but was encoded in C++ is the only proof the two agree |
| system | **T06** concurrent monitoring: two clients register for one flight, a third reserves; both receive the same seat count and `callbackSeq` before expiry, neither after |
| system | three-machine callback demo |
| system | all experiments in §13 |

**T15 is the regression test for the timer-independence rule** in §6.1. It must be run
with the server receiving no datagrams at all for the duration of the interval — a
version that sweeps expired monitors only on request arrival passes every other test
here and fails this one.

**There is no reference implementation to diff against.** §22.1 removed the Java server,
so nothing here compares two servers' answers to the same request. Three things replace
that comparison, and the test plan leans on all three rather than on any one:

- **The conformance vectors**, run in C++ before the server exists. They check the codec
  byte for byte against a fixed hex dump, which is a stronger check than agreement between
  two implementations that might share a misreading.
- **The Java client's own decoders.** It was written months before the server and refuses
  anything malformed, so a reply the client accepts is a reply that satisfies an
  independent reading of Part I. Every integration run is therefore also a protocol audit.
- **The §2.4 expected-value oracle**, which gives each experiment an automatic pass or
  fail without needing a second server to ask.

What none of these covers is server *behaviour* under duplication — history eviction,
fingerprint conflicts, callback sequencing. Those are checked only by T10, T13, T15 and
the E-series, which is why those tests carry more weight here than they would in a project
with a fallback implementation.

---

## 15. Build and run

### 15.1 Building

Two independent builds, sharing nothing. Neither uses a build tool or a third-party
dependency, which keeps the submission a clean single archive with no manifest to
reconstruct.

**Java client** — plain `javac`, or the wrapper scripts, which also run the conformance
suite:

```
scripts/build.ps1                         # Windows
scripts/build.sh                          # Linux/macOS
javac -d build/classes $(find src/java -name '*.java')    # equivalent, by hand
```

**C++ server** — Winsock on native Windows (§22.3):

```
make                      # c++ -std=c++17 -Wall -Wextra -O2; links ws2_32 on Windows
```

The Makefile detects Windows on its own. A Windows machine without `make` can build
and test the server with `scripts/test-all.ps1`, which calls `clang++` or `g++`
directly.

**Complete local verification** — builds both languages, runs four automated suites,
and optionally executes all E1-E10 experiments with 20 trials per probabilistic case:

```
scripts/test-all.ps1                 # Windows: 387 checks
scripts/test-all.ps1 -Experiments    # Windows: checks plus E1-E10
sh scripts/test-all.sh                  # Linux/macOS: 387 checks
sh scripts/test-all.sh --experiments    # Linux/macOS: checks plus E1-E10
```

Windows requires JDK 17+ and `clang++`/`g++` on `PATH` (or set `JAVA_HOME` and
`CXX`). Linux/macOS requires JDK 17+, a C++17 compiler, and `make`.

After the final tested commit is selected, create and integrity-check the single-file
NTULearn source archive with:

```
scripts/package.ps1       # Windows
sh scripts/package.sh     # Linux/macOS
```

The generated `dist/SC6103-project-source.zip` is intentionally ignored by Git. The
assignment permits no resubmission, so regenerate it from the final tagged commit and
inspect its contents before the group representative uploads it.

The C++ target keeps a POSIX path because a developer may build and unit-test the codec
on Linux, but **the demo server runs on native Windows** and that is the configuration
§17's M2 requires proven from a second physical machine.

### 15.2 Running

```
flight_server  --port <port>
               [--semantics at-least-once|at-most-once]   default at-most-once
               [--drop-request <p>] [--drop-reply <p>]    default 0.0
               [--drop-callback <p>]                      default 0.0
               [--drop-first-reply <OPCODE>]              one-shot, see §10.1
               [--drop-first-callback]                    one-shot
               [--seed <n>]                               PRNG seed, repeatable runs

flight_client  --host <server-ip> --port <port>
               [--semantics at-least-once|at-most-once]   must match the server
               [--drop-request <p>] [--drop-reply <p>]    default 0.0
               [--drop-first-request]                     one-shot
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

Both programs print every request and reply they handle, in the schema of §10.2. Each
prints its bound endpoint, the server endpoint, the selected semantics, the loss
configuration and the seed at startup, so a captured log is self-describing.

A `--semantics` mismatch is rejected with `ERR_SEMANTICS_MISMATCH` rather than
tolerated (D19). `--seed` fixes the probabilistic gates; the `--drop-first-*` rules
(§10.1) are what a live demonstration should use.

Host firewalls on all three machines must allow the chosen UDP port inbound, and client
return traffic to ephemeral ports. Clients must use the server machine's LAN address,
not a loopback address.

---

## 16. Repository layout

```
docs/                     course brief, console script
src/
  java/
    sc6103/common/        marshalling, protocol constants, header and body codecs
    sc6103/client/        console UI, request/reply state machine, monitor session
  cpp/
    server/               C++17 protocol codec and the only server
tests/
  conformance/            §11.2 hex vectors, encoded by both implementations
  java/                   Java-side conformance harness
  cpp/                    C++ conformance harness
  experiments/            §13 result summary
scripts/                  build, test, experiment and packaging helpers
```

**There is one server and it is written in C++17.** There is no Java server, no
reference implementation, and no fallback. §22.1 records why.

`src/java/common` and the C++ server share **no code, no headers and no generated
artefacts**. That is the entire point of §22.1: two implementations written independently
from Part I check each other's assumptions in a way one implementation cannot. It also
means the specification and the conformance vectors are the only things holding them
together, so a change to either is a change to the interface between two people's work.

---

## 17. Delivery milestones

The plan has one hard property: **the Java client is finished first and is the test
driver for every C++ milestone.** Each stage below is exercised by running the real
client against the server being built, not by a harness written alongside it. A server
that satisfies a test written by its own author proves less than one that answers a
client written months earlier from the same specification.

The corresponding hard risk: there is no fallback. Every milestone from M2 on is on the
critical path, and slipping M7 means demonstrating an incomplete system rather than a
complete one that forfeits a bonus. §20 carries the mitigations.

| | Milestone | Deliverables | Exit criterion |
|---|---|---|---|
| M0 | Protocol freeze | Part I agreed; §22 decisions closed; shared constants written | ✅ every field's byte layout is unambiguous; D-1…D-6 folded into Part I |
| M1 | Java client and vectors | codec, header, bodies, §11.2 vectors, full console client | ✅ 222 conformance assertions pass; all six operations drive end to end |
| M2 | C++ skeleton on the demo host | build target, Winsock socket bind, bounded receive loop, header decode, one operation | the Java client's `QUERY_BY_ID` round-trips from **a second physical machine**; §11.2 vectors encode byte-identically in C++ |
| M3 | C++ core services | all six operations, seeded table, §2.4 expected-value oracle | the client's §14 boundary tests pass at zero loss, driven from the console |
| M4 | C++ reliability semantics | reply history, fingerprints, `ackUpTo` pruning, both semantics, `ERR_SEMANTICS_MISMATCH` | T10, T13 pass; a retransmission visibly replays a cached reply instead of re-executing |
| M5 | C++ monitoring | registry, callbacks, `callbackSeq`, bounded expiry, `MONITOR_END` | T06 and T15 pass with two monitoring clients on two machines |
| M6 | Fault injection and experiments | server-side drop gates, `--seed`, one-shot rules, E1–E10 | every experiment produces its predicted result, with logs captured per §24.3 |
| M7 | Hardening and multi-host | malformed-input tests, firewall checks, clean-machine build, rehearsal | full §14 matrix passes or documented exceptions are agreed |
| M8 | Final package | tagged commit, packed source file, report | every document matches the tagged code; rehearsal completes in the allotted time |

The local software criteria through M6 are automated and passing. M2's second-physical-
machine condition, M7's clean demo-network/firewall checks, and M8's team-specific cover,
tag and rehearsal remain manual because they depend on the group's machines and identities
rather than repository code. The M8 source archive itself is generated and validated by
`scripts/package.ps1` or `scripts/package.sh`.

**M2 is the milestone that de-risks the project**, and it is deliberately larger than a
"hello world" would be. It forces the Winsock decision, the build, the byte-level
conformance check and the three-machine network path to all be proven in week one, while
each is still cheap to fix. Every one of those is capable of ending the project on demo
day, and none of them gets easier by being discovered later.

### Definition of done

A feature is not finished because it worked once on one machine. All of the
following must hold:

- The wire contract in Part I is updated in the **same** change, if the feature
  touched it.
- It builds from a clean checkout and passes its unit and integration tests.
- Success, invalid input, loss or timeout, and duplicate behaviour are all covered.
- Logs are sufficient for another member to diagnose it without a debugger.
- It is merged and exercised in the integrated build, not only on its author's
  branch.

---

## 18. Demo script

Three machines: server, client A (reserving), client B (monitoring).

1. Start the server, `--semantics at-most-once`, no loss. Show the seeded table.
2. Client A: query by route, showing multiple matches. Then query by ID.
3. Client B: `MONITOR` the flight for 60 s. Show the baseline snapshot.
4. Client A: `RESERVE 2`. Client B prints the callback. Show `seatsAvailable` on both.
5. Client A: `ADJUST_FARE -50.0`, then `SET_FARE 750.0`. Show the fare changing on a
   `QUERY_BY_ID`. Client B prints nothing — say out loud that this is expected, and
   why (§9.2).
6. Restart the server with `--semantics at-least-once --drop-first-reply 3` and both
   clients with `--semantics at-least-once`. Client B monitors the flight again, then
   client A sends `RESERVE 2`. The server executes, drops the reply
   (`DROP ... forced-first-reply`), and executes again when client A retransmits
   (`EXECUTE ... re-executed`). Client B prints two callbacks **with the same
   `eventClientId` and `eventRequestId`** (`event=reqN by=<client A's id>` on both) and
   the line "server executed reqN twice", and the server logs
   `ORACLE_ERROR ... diverged`.
7. Restart the server with `--semantics at-most-once --drop-first-reply 3` and both
   clients with `--semantics at-most-once`, then repeat step 6. The server answers the
   retransmission from its history (`CACHE_HIT ... replay`), client B gets one callback,
   and the seat count is correct.
8. Restart with `--semantics at-least-once --drop-first-reply 6` and send
   `SET_FARE 750.0`: two executions, final fare 750.00, `ORACLE_OK`. Restart with
   `--drop-first-reply 5` and send `ADJUST_FARE 10.0`: two executions, a fare one delta
   too high, and `ORACLE_ERROR`. Run the two back to back, because the contrast between
   them is the idempotency result.
9. Let client B's interval expire. It receives `MONITOR_END` and prints how many of the
   updates the server sent actually arrived.

Steps 6 to 8 are the graded core. Each `--drop-first-reply` rule fires once per server
start (D25), so restart the server before repeating a step. Rehearse until they run
without hesitation.

---

## 19. Requirement traceability

| Brief requirement | Satisfied by |
|---|---|
| UDP, not TCP | D1 |
| Service 1: query flight IDs by route, error if none | §4 Op 1 |
| Service 2: query time/fare/seats by ID, error if absent | §4 Op 2 |
| Service 3: reserve seats, ack, update, errors | §4 Op 3 |
| Service 4: monitor via callback for an interval | §4 Op 4, §9 |
| Two extra ops, one idempotent one not, described in report | §4 Ops 5–6, D9, §22.2 |
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
| Optional: client and server in different languages | D2, §22.1 **[HARD PATH]** |
| Demo with clients and server on different computers | §18 |
| Report cover lists members and each member's work percentage | §24.1, §25 |
| All source packed into one file; one submitter; no resubmission | §23, §25 |

---

## 20. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| **Server run under WSL2 is unreachable from other machines** | **three-machine demo fails on the day** | WSL2 NATs by default, so a LAN peer cannot reach a server inside it. Either set `networkingMode=mirrored` in `.wslconfig` and verify from a second machine, or run the server on native Linux or native Windows. Test this at M1, not at M8. |
| Late integration of separately written code | high — the classic group-project failure | integrate daily; `main` always builds; no exchanging archives. The Java client is already finished and on `main`, so C++ work integrates against something real from its first commit |
| Protocol drift between the two implementations | high | Part I frozen at M0; changes only by a review that both implementers sign off; conformance vector as a golden test |
| **C++ server not finished in time** | **project fails — there is no fallback** (§22.1) | M2 proves build, sockets and the three-machine path in week one; each later milestone is independently demonstrable, so an unfinished M6 still leaves a working M5 system to show; the Java client drives every stage so the server is never integrated late |
| No reference implementation to diff a suspect reply against | medium — cross-language faults are slower to localise | §11.2 vectors encoded in C++ before any server logic; the Java client's own decoders reject anything malformed, so every integration run doubles as a protocol audit (§14) |
| Cross-language byte mismatch found late | integration stalls | §11.2 vectors are encoded in C++ at M2, before any server logic, so a layout fault fails against a hex dump rather than during a live run |
| Winsock/BSD divergence on the demo machine | server will not build on the day | fix the server OS at M0; it changes the server from its first line |
| Firewall blocks UDP between laptops | demo fails | test the three-machine path early on the network the demo will use |
| Client/server semantics mismatch during experiments | results look valid but mean nothing | `semantics` header field, rejected on mismatch (D19) |
| History TTL shorter than the retry window | at-most-once silently degrades | compile-time check against the client's default retry settings (§8.3, D8); the client banner records any override |
| Reused request ID replays the wrong reply | silent wrong answer | request fingerprint, `ERR_REQUEST_ID_CONFLICT` (D24) |
| Monitor expiry never fires on an idle server | registrations outlive their interval | bounded socket wait (§6.1); regression test T15 |
| Probabilistic loss does not fire during the demo | the graded point is not made | deterministic `--drop-first-*` rules (D25) |
| Callback loss mistaken for a bug during marking | looks like a defect | `callbackSeq` and `MONITOR_END` report gaps explicitly |
| Truncation on route query goes unnoticed | silent wrong answer | explicit `truncated` flag in the reply |
| Generated code looks plausible but breaks a contract | high | small reviewed changes; tests before merge; the author explains what it does |
| Report claims do not match the final code | medium | every claim validated against logs from the tagged commit |
| Single-file submission omits a source file | incomplete submission | scripted packaging step; **no resubmission is permitted** |

---

## 21. Assumptions and boundaries

- Requests are separated enough in time for a single-threaded server, per the brief.
  The server nonetheless polls on a timer so monitor expiry does not depend on
  traffic arriving (§6.1).
- Flight data and reply history are memory-resident. **The at-most-once
  guarantee covers one server process lifetime only.** A restart clears all state,
  and the report discloses this wherever it affects an experiment.
- The route dataset is small enough that `QUERY_BY_ROUTE` may scan it linearly.
- Route matching is case-normalised exact text. No fuzzy or partial matching.
- Place names are UTF-8 on the wire; the seeded dataset is expected to be ASCII.
- Departure times are UTC. No local timezone is placed on the wire and no conversion
  is performed.
- All expiry and timeout decisions use a monotonic clock, so no behaviour depends on
  wall-clock adjustments.
- `clientId` is client-asserted and unverified. Authentication, authorisation and
  encryption are outside the course scope (D5).
- Callback delivery is observable but not guaranteed (D10).
- Any client may change any flight's airfare. There is no ownership or authorisation
  model, which is a deliberate consequence of D9 and is stated in the report rather
  than defended as realistic.

Production hardening would add durable state, authenticated messages, account-based
authorisation for mutating operations, rate limiting, metrics and a congestion-aware transport. All are
deliberately excluded: they are not part of the UDP learning objectives the brief sets.

---

## 22. Decisions closed

All three closed on **3 September 2026**, before feature work split. Each was a genuine
fork rather than a detail, so each closure records what was chosen, what was rejected,
and what would have followed from the other answer — the report needs the rejected
branch as much as the taken one.

### 22.1 Language pairing — **CLOSED: C++17 server, Java client, one server only**

| Option | Outcome |
|---|---|
| **C++17 server + Java client, no Java server** | **Chosen.** Takes the brief's optional credit, and gives the group one server to build rather than two. |
| C++17 server + Java client, Java server retained as oracle and fallback | Rejected on 3 September 2026, having been the working plan. See below. |
| C++17 or Java on both ends | Rejected. Forfeits the only optional credit the brief offers, for no saving the option above does not already give. |

The brief's wording carries the first half: *"An additional but optional requirement is
to implement the client and the server in different programming languages – for example,
one in C/C++ and one in Java."* It is the only sentence in the document offering marks
for extra work, and Java client plus C++ server takes it.

**The second half — dropping the Java server — was a reversal.** The earlier plan built a
Java server first, as three things at once: a test oracle to diff the C++ server against,
a fallback if C++ ran out of time, and a way to debug the at-most-once logic in the easier
language before porting it.

It was dropped because the whole group already believed there was only ever one server.
A specification that four people read differently is worse than a specification with a
weaker safety net, and the cost of the correction was rising with every day of work
written against the wrong assumption.

**What that costs, stated plainly rather than softened:**

- **There is no fallback.** If the C++ server is not finished, there is no working system
  to submit — not a reduced one. This converts every C++ risk from "loses the optional
  credit" to "fails the project", and §20 is rewritten accordingly.
- **There is no oracle.** §14's integration row previously diffed the C++ server against a
  Java one. That comparison is gone. The conformance vectors of §11.2 replace part of it —
  they check the *codec* byte for byte — but nothing now independently checks server
  *behaviour*: history eviction, fingerprint conflicts, callback sequencing.
- **The at-most-once logic gets written once, in the harder language**, without a working
  version to compare against.

**What absorbs the risk instead:**

- **The Java client is finished, and it is the test driver.** Every C++ milestone in §17 is
  exercised by the real client rather than by a harness the server's own author wrote. A
  server that satisfies its author's test proves less than one that answers a client
  written independently from the same specification.
- **M2 front-loads every project-ending risk.** Winsock, the build, the byte-level
  conformance check and the three-machine network path are all proven in week one, when
  each is still cheap.
- **The conformance vectors are now the contract.** §11.2 is checked in C++ before the
  server logic is written, so an endianness or alignment fault surfaces against a hex dump
  rather than against a live demo.

**For the record, had a single language been chosen for both ends**, four decisions would
have inverted, because each was decided on cross-language grounds: D23 (uniform 4-byte
width) reverses to packed narrow types, D6 and D15 follow it, and D21 reverses because
the "CRC-32 names several incompatible variants" objection vanishes with one
implementation. Part I would have needed a revision pass rather than a patch. Recorded
so the report can show the wire format is a consequence of the language decision rather
than an independent preference.

### 22.2 Which two extra operations — **CLOSED: `ADJUST_FARE` / `SET_FARE`**

| Option | Outcome |
|---|---|
| **`ADJUST_FARE` / `SET_FARE`** | **Chosen.** No ownership model, no leak. Instantiates deck 2 slide 76's own non-idempotent example, and puts a `float32` in a request body for the first time. |
| `CANCEL_SEATS` / `SET_RESERVATION` | Rejected. Stronger live demo — the idempotent replay would be visible on a monitoring machine — bought with a per-client booking ledger, an orphaned-seat leak, and extra reply fields. |

Both satisfy the brief, which states only that one operation be idempotent and the
other not, and expresses no preference. The full argument is in D9; the cost accepted
is that fare changes fire no callbacks (§9.2), so E6's result is observed at the client
and in the log rather than live on a third machine.

The decisive point: `RESERVE` is already non-idempotent and already fires callbacks, so
the live at-least-once corruption demo survives without the ledger. What the seat pair
uniquely added was a whole subsystem for one additional demo moment.

### 22.3 Server operating system — **CLOSED: native Windows, Winsock**

The C++ server targets Winsock on native Windows. Winsock and BSD sockets diverge from
the server's first line — `WSAStartup`/`WSACleanup`, `closesocket` rather than `close`,
`SOCKET` rather than `int` — so this had to be settled before any socket code was
written.

Native Windows is chosen because the demo runs on group laptops and it puts no
virtualisation layer between the server and the LAN. This is the weakest-grounded of the
three closures: the brief says only *"You may work on your own laptops and use your
laptops for demonstration"*, so it is a practical choice rather than a required one.

**This decision now carries more weight than it did.** With no Java server, the socket
layer has no second implementation to fall back on and no machine-independent
alternative: whatever this choice implies is what the demo runs on. Prove three-machine
reachability at **M2**, on the network the demo will use, before any operation beyond the
first is written. Do not demo from inside WSL2 — see the §20 entry for why its default
NAT makes a server unreachable from other LAN machines.

---

## 23. Settled from the brief

These are points that look like open design questions but are answered by
`docs/Course Project.pdf`. They are recorded here so they are not reopened.

| Question | Answer |
|---|---|
| Flight fields | Fixed: identifier (integer), source and destination (variable-length strings), departure time (*"design your own data structure"*), airfare (**floating-point value**), seat availability (integer). `totalSeats` is our addition, held server-side as the baseline for the §2.4 oracle and never transmitted. |
| Initial dataset | No mandatory records and no file format. Persistence is not compulsory; in-memory is the stated minimum. A version-controlled fixture is ours to choose. |
| Operation names, opcodes, argument order | Ours. The brief fixes the four services semantically and nothing about their encoding. |
| Fare representation | **Floating point, settled.** The brief says "the airfare (floating-point value)". Integer currency units are excluded. `float` versus `double` is ours (D16). |
| Route matching rules and string limits | Ours. The brief says only that the places are variable-length strings and suggests length prefixes. |
| Monitor interval bounds | Ours. The brief specifies no range. |
| Command-line syntax | Ours. The brief requires only that the server address and port, and the semantics choice, are *passable* as arguments. Named options satisfy it. |
| Application-level checksum | Permitted, not required — the brief is silent. D21 declines it, and that decision follows from §22.1 having closed on two languages. |
| Submission format | **One file** containing all source. **No resubmission** — submit only the final version. One member submits for the whole group. |
| Report cover page | Must list every member **and the percentage of work each contributed**. This is explicit in the brief and easy to overlook. |
| Demonstration | Clients and server on **different computers**. The callback demonstration needs two clients on two machines plus the server. |

The brief also states: *"For the remaining design and implementation details, make
your own reasonable assumptions and decisions."* Anything not listed above or in §22
is a recorded choice, not a pending question, and should be defended in the report
rather than deferred.

---

## 24. Team workflow and ownership

Four members, and the report must attribute a work percentage to each. The
allocation below gives every member an assessable deliverable while keeping the wire
contract owned by the people who can review it.

### 24.1 Ownership

| Role | Primary deliverables | Review boundary |
|---|---|---|
| **A — Protocol and client** | Part I constants, Java codec, console client, request IDs, retry loop, reply/callback demultiplexing, conformance vectors | B reviews protocol changes; C supplies test scenarios |
| **B — C++ server** | the whole server: Winsock layer, C++ codec written from Part I, event loop, dispatcher, repository, services, reply history, subscriptions, callback generation, fault injection | A reviews protocol use but **must not** hand over Java source as a reference — §22.1's value depends on the two implementations being independent |
| **C — Integration and testing** | §14 matrix, deterministic scenario scripts, evidence capture, merge coordination, clean-machine smoke tests | owns acceptance, **not** unilateral protocol design |
| **D — Documentation and demo** | report, this document's consistency with the code, diagrams, slides, demo narration, final packaging | validates every claim against logs from the tagged commit |

### 24.2 Repository discipline

- One repository. **Do not exchange source archives as the normal workflow** — the
  single most common way a four-person project fails is four divergent copies merged
  the night before.
- Short branches (`feat/client-retry`, `test/reply-loss`), opened as draft pull
  requests early so interface collisions surface before the work is finished.
- `main` stays buildable. Integrate at least once per working day.
- A change to Part I — a field, opcode, status code, invariant or retry rule — is its
  own reviewed change, never buried inside a feature.
- Tag the final tested commit and generate every piece of report evidence from that
  tag, not from a local copy.

### 24.3 Evidence package

For each demonstrated scenario, save the command lines, seed and active fault rules,
commit hash, server log, client logs, and one sentence of expected-versus-observed.
Name them consistently, for example
`evidence/E04_at_least_once_cancel_reply_loss/server.log`.

The report's experiment section is written from these files. Capturing them as the
experiments run costs nothing; reconstructing them afterwards is not possible.

---

## 25. Administration

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
