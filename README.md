# Distributed Flight Information System — Technical Specification

SC6103 Distributed Systems, AY2026/27 Trimester 1. Group project (max 4 members).

A flight information service over UDP with a hand-written request-reply protocol:
byte-level marshalling, timeouts and retransmission, duplicate filtering, reply
histories, and server-to-client callbacks. No RPC framework is used; implementing
that layer is the assignment.

**Status:** design complete, implementation not started.

### How this document is organised

| Part | Contains | Read it when |
|---|---|---|
| **I — Specification** | What the system *is*. Normative, no argument. | Implementing. Two implementations are written from Part I alone. |
| **II — Design rationale** | Why each choice was made, and what was rejected. Decision records `D1`–`D18`. | Defending the design, or writing the report. |
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

12. [Decision records D1–D18](#12-decision-records)

**Part III — Delivery**

13. [Experiment plan](#13-experiment-plan)
14. [Test plan](#14-test-plan)
15. [Build and run](#15-build-and-run)
16. [Repository layout](#16-repository-layout)
17. [Delivery phases](#17-delivery-phases)
18. [Demo script](#18-demo-script)
19. [Requirement traceability](#19-requirement-traceability)
20. [Risk register](#20-risk-register)
21. [Administration](#21-administration)

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
  application layer     flight services, console UI
  ──────────────────────────────────────────────────────
  request-reply layer   retransmit, dedupe, history, callbacks
  marshalling layer     byte[] pack / unpack
  ──────────────────────────────────────────────────────
  transport             UDP sockets
```

The two middle layers are what the project asks students to build and what the
brief forbids obtaining from a framework.

**[HARD PATH]** The server is written in **C**, the client in **Java**. → D2

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

| Field | Type | Range |
|---|---|---|
| `day` | `int32` | 1–31 |
| `month` | `int32` | 1–12 |
| `hour` | `int32` | 0–23 |
| `minute` | `int32` | 0–59 |

No year, timezone or seconds.

### 2.3 Client identity  → D5

A client is the pair **(source IP address, source UDP port)**, taken from the
received datagram. The client never states its own identity on the wire.

One notion of identity serves three purposes: the reply-history key when combined
with `requestId` (§8.2), the owner key in the booking ledger (§2.4), and the
callback delivery address (§9).

### 2.4 Booking ledger **[HARD PATH]**  → D9

```
bookings : map (clientIp, clientPort, flightId) -> int32 seatsHeld
```

Invariant, asserted after every mutation:

```
flight.seatsAvailable  =  flight.totalSeats  −  Σ seatsHeld over all clients
```

A violation is logged as `ERR_SERVER_ERROR` severity. It is the signature of
at-least-once corruption, which makes the experiments in §13 self-checking.

### 2.5 Monitor registry

```
monitors : list of { flightId, clientIp, clientPort,
                     expiresAtMs, monitorRequestId, callbackSeq }
```

Behaviour in §9.

### 2.6 Reply history

```
history : map (clientIp, clientPort, requestId)
             -> { replyBytes[], length, storedAtMs }
```

Populated only under at-most-once. Behaviour in §8.2–§8.3.

### 2.7 Capacities  → D17

All server tables are fixed-capacity arrays. No dynamic allocation. A full monitor
registry returns `ERR_MONITOR_TABLE_FULL`.

---

## 3. Wire protocol

### 3.1 Framing  → D14

One operation is one request datagram and one reply datagram. No fragmentation, no
continuation, no multi-datagram reply. Every message fits one UDP payload of at
most **512 bytes**.

### 3.2 Byte order and alignment  → D3

- All multi-byte integers are **big-endian** (network byte order), two's complement.
- All primitives are 4 bytes, so **every field begins at an offset that is a
  multiple of 4**.
- Strings are padded to restore 4-byte alignment. Padding bytes are written as zero
  and must be ignored on read.

### 3.3 Primitive encodings

| Type | Encoding |
|---|---|
| `int32` | 4 bytes, big-endian, two's complement |
| `uint32` | 4 bytes, big-endian |
| `float32` | IEEE-754 binary32 bit pattern, transmitted big-endian |
| `string` | `uint32` byte length, then that many UTF-8 bytes, then 0–3 zero bytes to the next 4-byte boundary. No NUL terminator. → D15 |
| `sequence<T>` | `uint32` count, then `count` elements back to back |

There are **no type tags on the wire**. Both ends derive the layout from `opCode`.

### 3.4 Header  → D4

Every datagram begins with a fixed **20-byte** header.

| Offset | Type | Field | Meaning |
|---|---|---|---|
| 0 | `int32` | `messageType` | 0 = REQUEST, 1 = REPLY, 2 = CALLBACK |
| 4 | `int32` | `requestId` | monotonic per client process; echoed in the reply |
| 8 | `int32` | `opCode` | §4 |
| 12 | `int32` | `status` | replies and callbacks only; 0 in requests |
| 16 | `int32` | `ackUpTo` | requests only; highest `requestId` this client has received a reply for, or −1 |

The body begins at offset 20.

### 3.5 Size limits

| Constant | Value | Derivation |
|---|---|---|
| `MAX_DATAGRAM` | 512 bytes | fragmentation-free on any realistic path |
| `HEADER_SIZE` | 20 bytes | §3.4 |
| `MAX_BODY` | 492 bytes | derived |
| `MAX_STRING_BYTES` | 63 | place names; bounds the C receive buffer |
| `MAX_ROUTE_RESULTS` | 64 | 4 + 4 + 64×4 = 264 bytes, inside `MAX_BODY` |

---

## 4. Operation catalogue

The system's API surface. All bodies are shown as they appear after the 20-byte
header. Argument validation failures return `ERR_INVALID_ARGUMENT` throughout.

### Op 1 — `QUERY_BY_ROUTE` · idempotent

Query flight identifiers by source and destination.

| | Type | Field |
|---|---|---|
| **Request** | `string` | `source` |
| | `string` | `destination` |
| **Reply** | `int32` | `truncated` — 1 if matches were omitted, else 0 |
| | `uint32` | `count` |
| | `int32 × count` | `flightIds` |

Matching is case-insensitive on ASCII, exact otherwise. Returns
`ERR_NO_ROUTE_MATCH` when nothing matches — the brief requires an error, not an
empty list. Results are capped at `MAX_ROUTE_RESULTS` with `truncated = 1`. → D14

---

### Op 2 — `QUERY_BY_ID` · idempotent

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| **Reply** | `int32 × 4` | `departureTime` — day, month, hour, minute |
| | `float32` | `airfare` |
| | `int32` | `seatsAvailable` |

Errors: `ERR_UNKNOWN_FLIGHT`.

---

### Op 3 — `RESERVE` · **non-idempotent**

Relative decrement of the seat pool.

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `int32` | `numSeats` ≥ 1 |
| **Reply** | `int32` | `seatsAvailable` after |
| | `int32` | `seatsHeldByClient` after |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_INSUFFICIENT_SEATS`.
Fires callbacks on success (§9).

Both counters are returned so that under at-least-once with a lost reply, the
second execution returns a *different* `seatsAvailable` from the first — the
corruption is visible in the reply itself, not only in a later query.

---

### Op 4 — `MONITOR`

Register for seat-availability callbacks for a bounded interval.

| | Type | Field |
|---|---|---|
| **Request** | `int32` | `flightId` |
| | `int32` | `intervalMs` ≥ 1 |
| **Reply** | `int32` | `seatsAvailable` at registration time |
| | `int32` | `expiresInMs` |

Errors: `ERR_UNKNOWN_FLIGHT`, `ERR_MONITOR_TABLE_FULL`.

The snapshot gives the monitoring client a baseline to display before the first
callback. The client then blocks for the interval (§7.3).

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

Replaying this request releases seats the client no longer holds, breaking the
§2.4 invariant. That is the failure §13 measures.

---

### Op 6 — `SET_RESERVATION` · **idempotent** · **[HARD PATH]**

Set this client's booking on a flight to exactly `targetSeats`. Absolute
assignment. The brief's required idempotent extra operation. → D9

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

### Callback datagram · `messageType = 2`

Sent unsolicited. Not acknowledged, not retried. → D10

**Header:** `messageType = 2`, `requestId` = the `requestId` of the `MONITOR`
request being served, `opCode = 4`, `status = 0`.

| Type | Field |
|---|---|
| `int32` | `flightId` |
| `int32` | `seatsAvailable` |
| `int32` | `callbackSeq` — per-registration counter from 1 |

`callbackSeq` makes callback loss observable: a client seeing 1, 2, 4 reports one
lost update.

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

Error replies carry a header and **no body**. Human-readable text is generated
client-side from the code. → D4

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
    if hdr.messageType != REQUEST: continue

    log "RECV {from} req={hdr.requestId} op={hdr.opCode}"

    prune_history(from, hdr.ackUpTo)          # §8.3
    sweep_expired_monitors(now)               # §9.3

    if semantics == AT_MOST_ONCE:
        e = history_lookup(from, hdr.requestId)
        if e != NULL:
            log "DUPLICATE req={hdr.requestId} -> replaying stored reply"
            maybe_send(e.bytes, from)
            continue                          # no re-execution, no callbacks

    status, body = dispatch(hdr.opCode, buf + HEADER_SIZE, from)
    reply = marshal_reply(hdr.requestId, hdr.opCode, status, body)

    if semantics == AT_MOST_ONCE:
        history_store(from, hdr.requestId, reply)

    log "SEND {from} req={hdr.requestId} status={status}"
    maybe_send(reply, from)

    if status == OK and opCode in {RESERVE, CANCEL_SEATS, SET_RESERVATION}:
        fire_callbacks(flightId)              # §9.2

    assert_seat_invariant()                   # §2.4
```

Three properties of that loop are load-bearing and should be stated in the report:

1. **The duplicate path returns before dispatch.** Under at-most-once a duplicate
   neither re-executes nor re-fires callbacks; under at-least-once there is no
   duplicate path, so both happen. That single branch is the entire behavioural
   difference between the two semantics.
2. **Callbacks fire even when the reply is dropped.** A dropped reply models loss
   on the return path — the server did execute. This is what makes at-least-once
   corruption visible to a monitoring client.
3. **The invariant is asserted every iteration**, so corruption is detected when it
   occurs rather than inferred later.

### 6.2 Concurrency  → D11

One thread, no locking. Multiple concurrent monitoring clients are still supported,
because the registry is a list and needs no threading.

### 6.3 Seed data  → D18

The flight table is seeded from a hard-coded array at startup. It must contain at
least: two flights sharing a source/destination pair (multi-result queries), one
flight on a distinct route, and one flight with a small seat count (fast
exhaustion).

---

## 7. Client behaviour

### 7.1 Console interface

A numbered menu looping until the user selects quit, one entry per operation plus
quit. Every request sent and every reply or error received is printed. Text only.

### 7.2 Request/reply state machine

```
requestId = next++
bytes     = marshal_request(requestId, opCode, args, ackUpTo)

for attempt in 0 .. maxRetries:
    maybe_send(bytes)
    deadline = now + timeoutMs

    while now < deadline:
        pkt = receive(until deadline)
        if pkt == TIMEOUT: break

        if pkt.messageType == CALLBACK:
            handle_callback(pkt); continue        # not a reply; keep waiting
        if pkt.requestId != requestId:
            log "stale reply for req={pkt.requestId}, discarded"; continue
        if simulate_drop(dropReplyRate):
            log "DROPPED REPLY req={requestId}"; continue

        ackUpTo = max(ackUpTo, requestId)
        return pkt

    log "TIMEOUT req={requestId}, attempt {attempt+1}"

report timeout to user
```

Two details are load-bearing:

- **`requestId` does not change across retransmissions.** If it did, the server
  could not recognize a duplicate and at-most-once would be unimplementable.
- **Stale replies are discarded by `requestId`.** A reply to attempt 1 arriving
  after attempt 2 was sent must not be taken as the reply to a later request.

### 7.3 Monitoring mode  → D11

After a successful `MONITOR` reply the client blocks for `intervalMs`, printing
each callback and reporting any gap in `callbackSeq`. It accepts no user input
during this period. On expiry it prints a summary: callbacks received, callbacks
missed.

---

## 8. Invocation semantics

Both are implemented and selected by a CLI flag on both programs at startup. → D7

| Semantics | Retransmit request | Filter duplicates | On duplicate |
|---|---|---|---|
| at-least-once | yes | no | re-execute the operation |
| at-most-once | yes | yes | retransmit the stored reply |

Maybe-semantics is not implemented for the six operations. Callbacks exhibit it
regardless (§9.2).

### 8.1 At-least-once

No history. Every request that arrives is executed. Correct for operations 1, 2
and 6; corrupting for 3 and 5.

### 8.2 At-most-once

Keyed on **(clientIp, clientPort, requestId)**. → D5

The **marshalled reply bytes** are stored, not the structured result, so a
duplicate is answered by a buffer copy that is byte-identical to the original
without depending on marshaller determinism. → D8

### 8.3 Bounding the history  → D8

Three mechanisms, in order of precedence:

1. **Piggybacked watermark.** Every request carries `ackUpTo`. On receipt the
   server discards that client's entries with `requestId ≤ ackUpTo`. Exact, and
   costs no extra traffic.
2. **Per-client cap.** At most `MAX_HISTORY_PER_CLIENT` entries; oldest evicted
   when full.
3. **Idle expiry.** Entries older than `HISTORY_TTL_MS` are swept.

Mechanism 1 does the work; 2 and 3 make the bound absolute.

---

## 9. Callback subsystem

### 9.1 Registration

`MONITOR` appends `{flightId, clientIp, clientPort, expiresAtMs = now + intervalMs,
monitorRequestId, callbackSeq = 0}`. The address comes from the datagram, so no
address appears in the body.

Re-registering the same (client, flight) pair **replaces** the existing entry, so a
client cannot accumulate duplicate registrations by retransmitting.

### 9.2 Delivery  → D10

On a successful `RESERVE`, `CANCEL_SEATS` or `SET_RESERVATION`, the server walks
the registry for that `flightId`, skips expired entries, increments each
registration's `callbackSeq`, and sends one callback datagram per live registrant.

Delivery is **fire and forget** — no acknowledgement, no retry, no history. That is
*maybe* semantics in deck 3's taxonomy, and the report states so. `callbackSeq`
makes the resulting loss measurable rather than merely admitted.

The client that triggered the update also receives a callback if registered.

### 9.3 Expiry  → D10

Checked in two places: **lazily at delivery time**, so a registration that expired
since the last sweep never receives a late update; and **by a sweep each loop
iteration**, so memory is reclaimed with no reservation traffic. Delivery-time
checking is what makes expiry exact.

---

## 10. Fault injection

Loss is simulated in the application, not by an external tool. → D12

Four independent probabilities in [0.0, 1.0]:

| Program | Flag | Applied |
|---|---|---|
| server | `--drop-request` | after `recvfrom`, before processing |
| server | `--drop-reply` | before `sendto` |
| client | `--drop-request` | before `sendto` |
| client | `--drop-reply` | after `receive` |

Every simulated drop is logged with direction and `requestId`.

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

The C and Java implementations share no code. This section is the contract. → D2

### 11.1 Rules

| Rule | C side | Java side |
|---|---|---|
| Never `memcpy` a struct onto the wire | serialize field by field | n/a |
| Integers | `htonl` / `ntohl` on `uint32_t` | manual shifts; big-endian by construction |
| Floats | `memcpy` into `uint32_t`, then `htonl` | `Float.floatToIntBits` / `intBitsToFloat` |
| Bytes | `unsigned char` throughout | mask every read with `& 0xFF` — Java's `byte` is signed |
| Strings | length-prefixed, no NUL transmitted | `new String(b, off, len, StandardCharsets.UTF_8)` — never the platform default |
| Lengths | validate against `MAX_STRING_BYTES` before use | validate non-negative **and** in range before allocating |
| Width | `float`, `int32_t` | `float`, `int` |

Struct padding is the most likely cross-language bug: it differs by platform and
Java has no equivalent concept, so a `memcpy`'d struct produces bytes the Java side
cannot describe. A decoded length is untrusted input — a negative or oversized
`uint32` read into a signed Java `int` becomes an allocation bug if used directly.

### 11.2 Conformance vector

Both implementations must encode this record to **exactly** these 56 bytes.

```
flightId       = 1934
source         = "Singapore"
destination    = "London"
departureTime  = { day 14, month 10, hour 9, minute 30 }
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
0x0020  00 00 00 0E                              day    = 14
0x0024  00 00 00 0A                              month  = 10
0x0028  00 00 00 09                              hour   = 9
0x002C  00 00 00 1E                              minute = 30
0x0030  44 60 E0 00                              airfare = 899.5f
0x0034  00 00 00 78                              seatsAvailable = 120
                                                 total = 56 bytes
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
| D2 | Language split: C server, Java client | **[HARD PATH]** |
| D3 | Wire format: CDR-style, big-endian, aligned | |
| D4 | Fixed 20-byte header | |
| D5 | Request identity: (IP, port, requestId) | |
| D6 | Time as four `int32` fields | |
| D7 | Both invocation semantics, runtime-selected | mandated |
| D8 | History bounded by piggybacked acknowledgement | |
| D9 | The two extra operations | **[HARD PATH]** |
| D10 | Callbacks fire-and-forget with sequence numbers | |
| D11 | Single-threaded server, blocking monitor client | |
| D12 | In-process fault injection, four independent rates | |
| D13 | Fixed timeout, bounded retries | |
| D14 | One datagram per reply, with truncation | |
| D15 | UTF-8, byte lengths, no terminator | |
| D16 | Airfare as IEEE-754 `float32` | |
| D17 | Fixed-capacity server tables | |
| D18 | Hard-coded seed data | |

---

### D1 — Transport: UDP

**Decision.** UDP, with all reliability implemented in the application.

**Reasoning.** Mandated: the brief states the program "uses UDP (not TCP) sockets".

**Consequence.** Over TCP, deck 2 notes there is "no need to deal with
retransmission of messages, filtering of duplicates and maintaining histories" —
the project would have no content. UDP's omission failures are what give the
semantics comparison something to compare.

---

### D2 — Language split: C server, Java client **[HARD PATH]**

**Decision.** Server in C, client in Java.

**Reasoning.** The brief offers optional credit: "An additional but optional
requirement is to implement the client and the server in different programming
languages". It is the only place in the document offering marks for extra work.

Beyond marks, the split is the productive kind of difficulty. With one language on
both ends, a marshaller holding a wrong assumption still works, because both sides
hold the same wrong assumption. Across languages every layout assumption is checked
by an independent implementation. The extra effort lands inside the marshalling
code the project already grades, not in a new subsystem.

**Why C on the server specifically.** The demo needs two client machines plus a
server. A JVM deploys to whatever laptops the group owns without a second
toolchain; the server is one machine that can be prepared in advance.

**Alternatives rejected.**

- *Both ends in Java.* The brief's baseline. Forfeits the only optional credit, and
  hides marshalling bugs as above.
- *Both ends in C.* Same forfeiture, plus harder client deployment across
  heterogeneous laptops and no memory safety on the side parsing untrusted input.
- *C client, Java server.* Same credit, worse logistics: a working C toolchain and
  a successful build on two separate client machines on demo day, against a server
  machine that could otherwise be prepared in advance.

**Risk and mitigation.** Cross-language integration can fail late. Mitigated by the
phasing in §17: a Java-to-Java system is built first and retained as both fallback
demo and test oracle.

---

### D3 — Wire format: CDR-style, big-endian, 4-byte aligned

**Decision.** Binary, modelled on CORBA CDR. Fixed big-endian, 4-byte alignment,
length-prefixed strings, no type tags.

**Reasoning.** Deck 2 describes exactly this scheme, so the report cites it
directly. Omitting type tags is safe for a fixed operation set — the deck notes CDR
omits type information because "the sender and recipient have common knowledge of
the order and types of the data items in a message". Fixing byte order means
neither implementation negotiates or detects the peer's endianness, which matters
given D2. The brief itself points at `htonl`/`ntohl`.

**Alternatives rejected.**

- *Sender-endian with a flag, as CDR actually does* ("transmit in the sender's
  ordering and the recipient translates if necessary"). Doubles the decode paths,
  and each is exercised on only one architecture — so the untested path is the one
  that fails at the demo. The saving is a byte swap, irrelevant at this scale.
- *A tagged format (TLV), or Java serialization.* Serialization is banned outright.
  Hand-rolled TLV is legal but spends bytes and complexity transmitting type
  information both ends already know; the exercise is agreeing a layout, not
  discovering one.
- *A text or ASCII line protocol.* Easiest to debug, and rejected for that reason:
  the brief requires marshalling "the integer values, floating-point values and
  strings" into bytes, and encoding a float as decimal text sidesteps the IEEE-754
  question that is the interesting part.
- *Packed with no alignment padding.* Saves a few bytes. Rejected because alignment
  keeps the C side safe to read with aligned loads and keeps the CDR citation
  honest.

---

### D4 — Fixed 20-byte header

**Decision.** `messageType`, `requestId`, `opCode`, `status`, `ackUpTo`.

**Reasoning.** Deck 2 gives the minimal structure as `messageType` + `requestId` +
application-specific content. `opCode` is added because one port multiplexes six
operations and the dispatcher must choose a body parser. `status` is added because
the brief requires proper error messages, and a fixed field lets every error be
decoded before the body is touched. `ackUpTo` bounds the history (D8) without extra
traffic. `messageType = 2` distinguishes an unsolicited callback from a reply, so a
monitoring client needs no outstanding-request bookkeeping to tell them apart.

**Alternatives rejected.**

- *Error text in the body.* Forces the client to parse a body it may not
  understand, and invites charset problems across the C/Java boundary. A numeric
  code is language-neutral.
- *A separate error message type.* Redundant with `status`; doubles the reply paths
  for no gain.
- *Variable-length or optional header fields.* Constant offsets are the main
  defense against cross-language layout drift.
- *A separate acknowledgement message for history pruning.* Doubles request traffic
  to carry four bytes that fit in a datagram already being sent.

---

### D5 — Request identity: the (IP, port, requestId) triple

**Decision.** Client IP, client source port, and `requestId`. That triple keys the
reply history and owns booking-ledger entries.

**Reasoning.** Deck 2 defines the unique request identifier as `requestId` "+ an
identifier for the client process (e.g., Internet address + port number)".
`requestId` alone is not unique across clients, since every client counts from
zero. The server already learns the address from the datagram, so the triple costs
nothing on the wire. The brief independently defines callback registration as
recording "the Internet address and the port number of the client", so this is the
brief's own notion of client identity — using one notion for all three purposes
follows from that.

**Alternatives rejected.**

- *A per-request UUID.* Removes the dependence on address and port; rejected as 16
  bytes per message to solve a problem the transport solves for free, plus a
  hand-written generator on both sides.
- *A client-chosen identifier in the body.* Unverified — two clients picking the
  same value would silently share a history entry, and detecting that is more work
  than reading the source address.
- *`requestId` alone.* Incorrect; two clients collide on their first request.

**Disclosed limitation.** A client that restarts on the same port and resets its
counter reuses a triple. Acceptable because the history is bounded and the demo
does not restart clients mid-run, but it is the honest weakness of the scheme and
belongs in the report rather than being found by a marker.

---

### D6 — Time as four `int32` fields

**Decision.** Day, month, hour, minute as four separate `int32` values.

**Reasoning.** The brief asks the group to "design your own data structure to
represent time", making this graded rather than incidental. Discrete integer fields
marshal identically in C and Java with no library, and are directly readable in the
required console trace output.

**Alternatives rejected.**

- *A 64-bit Unix epoch timestamp.* More compact and the obvious production choice.
  Rejected because `time_t` width and signedness vary across C platforms while
  Java's `long` is fixed, introducing a portability question with no upside; and
  because it reads as sidestepping the instruction rather than answering it.
- *An ISO-8601 string.* Converts a numeric marshalling problem into a string one,
  which the format already exercises elsewhere.

---

### D7 — Both invocation semantics, runtime-selected

**Decision.** At-least-once and at-most-once both implemented, chosen by CLI flag.

**Reasoning.** Mandated. The brief specifies "which semantics to use can be
specified as an argument in the command that starts the client/server". The design
follows deck 3's summary table, where the semantics are the combination of
retransmission, duplicate filtering, and re-execute versus retransmit-stored-reply.

---

### D8 — History bounded by piggybacked acknowledgement

**Decision.** A watermark on every request, plus a per-client cap and an idle
timeout as backstops. Marshalled bytes stored, not structured results.

**Reasoning.** Deck 2 poses this question directly — "suggest methods to prevent
the history from growing forever" — so answering it well is directly reportable.
Piggybacking costs four bytes on a datagram already in flight and needs no
acknowledgement traffic. Storing bytes means a duplicate is answered by a buffer
copy, guaranteed byte-identical to the original.

**Alternatives rejected.**

- *Unbounded history.* Correct but unbounded; the deck flags this as the problem to
  solve.
- *Time-based expiry alone.* The correct window is unknowable. Too short and a
  legitimate retransmission is re-executed, silently degrading at-most-once to
  at-least-once. Retained only as a backstop.
- *Fixed-size LRU per client.* A weaker watermark: discards entries that may still
  be needed while keeping entries already known to be acknowledged.
- *Storing the structured result and re-marshalling on a duplicate.* Byte-identity
  would then depend on marshaller determinism. Storing bytes removes the question.

---

### D9 — The two extra operations **[HARD PATH]**

**Decision.** `CANCEL_SEATS` (non-idempotent, relative) and `SET_RESERVATION`
(idempotent, absolute), both writing to the same `seatsAvailable` field through the
per-client booking ledger.

**Reasoning.** The brief requires two extra operations, "one of them should be
idempotent and the other should be non-idempotent". Choosing two *writes against
the same state* means the pair differs in exactly one variable — relative
arithmetic versus absolute assignment — so the experiment isolates idempotency as
the cause of divergence. One harness exercises both, and both produce visible,
checkable effects on the seat count. This mirrors deck 2's own contrast between
"increasing a variable by 3" and operations whose repetition changes nothing.

**Alternatives rejected.**

- *A read-only query as the idempotent operation.* Much easier and the common
  choice. Rejected because a read cannot corrupt state, so at-least-once trivially
  produces the right answer and the comparison demonstrates nothing. The
  interesting claim is that a **write** can be replay-safe.
- *An administrative operation such as `ADD_FLIGHT` or `DELETE_FLIGHT`.*
  Orthogonal to the seat state the rest of the system — including callbacks — is
  built around. It would need its own test path and would not interact with the
  monitor mechanism.
- *`CANCEL_SEATS` operating on the global pool rather than a per-client booking.*
  Simpler, and rejected because "cancel seats I never booked" is not meaningful and
  because without ownership `SET_RESERVATION` has no well-defined subject.

**Cost accepted.** Both require the booking ledger of §2.4, which the brief does not
ask for. That is the price of an experiment that proves something.

---

### D10 — Callbacks fire-and-forget with sequence numbers

**Decision.** Server-side registry, unsolicited datagrams, no acknowledgement, no
retry, per-registration `callbackSeq`. Expiry checked both lazily at delivery and
by a periodic sweep.

**Reasoning.** The brief describes registration by address and port, delivery
"every time a seat reservation is made by any client on the flight", and removal
after the interval expires. Not retrying is a deliberate, disclosed choice: an
unacknowledged push is *maybe* semantics in deck 3's taxonomy. `callbackSeq` turns
that from an admission into a measurement.

**Alternatives rejected.**

- *Reliable callbacks with acknowledgement and retry.* Technically stronger,
  rejected on scope: a second retransmission state machine running in the opposite
  direction, which the brief neither requires nor rewards, and which the
  single-threaded server would have to block on while holding up the request loop.
- *Client polling instead of push.* The brief specifies callback.
- *Sweep-only expiry.* A registration expiring between sweeps would receive one
  late update. Checking at delivery time is exact.
- *Suppressing self-notification to the triggering client.* Couples the delivery
  loop to the requester's identity for a cosmetic gain.

---

### D11 — Single-threaded server, blocking monitor client

**Decision.** One thread on the server request path, no locking. A monitoring
client blocks until its interval expires.

**Reasoning.** Both are explicitly sanctioned. The brief permits assuming requests
are "well separated in time" and states no per-request thread is needed; likewise
"the client simply waits for the updates from the server during the monitor
interval" and "you do not have to use multiple threads at a client". Concurrent
monitoring clients are still supported, since the registry is a list.

Avoiding threads removes a whole class of demo failure and keeps the report's
correctness argument about invocation semantics rather than about locking.

**Alternatives rejected.**

- *Thread-per-request or a thread pool.* No marks, and it would force the history
  and booking ledger to become thread-safe, introducing races a marker could
  mistake for semantics bugs.
- *A threaded client that stays interactive while monitoring.* Same reasoning; the
  brief anticipates and permits the blocking design.

---

### D12 — In-process fault injection, four independent rates

**Decision.** Separate request-path and reply-path drop probabilities on both
programs, applied in the application and logged.

**Reasoning.** The brief requires loss simulated "in your program". Independent
rates are necessary because deck 2 distinguishes request-lost, reply-lost and
incomplete-interaction, and the second is the one that separates the semantics. A
single global rate cannot target it, which matters when the effect must be
reproduced on demand in front of a marker.

**Alternatives rejected.**

- *An external tool such as `tc netem` or Clumsy.* The brief says in-program, and it
  would need privileged configuration on demo machines.
- *One combined probability.* Cannot target the discriminating case.
- *Deterministic scripting, e.g. "drop the second reply".* Attractive for
  reproducible demos, rejected as the primary mechanism because the experiments
  need probabilistic loss to report rates over many trials. `--seed` (§15.2)
  provides reproducibility instead. May be added alongside as a demo aid.

---

### D13 — Fixed timeout, bounded retries

**Decision.** A fixed timeout and a bounded retry count, both settable by flag.
Exhausting retries reports a timeout to the user.

**Reasoning.** A fixed timeout makes runs comparable across trials and across the
two semantics. Bounding retries means a downed server produces a diagnosable error
rather than a hang.

**Alternatives rejected.**

- *Exponential backoff.* Correct for a real protocol; rejected because it makes the
  retransmission count depend on elapsed time, muddying an experiment that compares
  outcomes at a known retransmission count.
- *Infinite retries.* Undemonstrable — a lost-server case appears as a hung client.

---

### D14 — One datagram per reply, with truncation

**Decision.** Every reply fits one datagram. `QUERY_BY_ROUTE` caps results at
`MAX_ROUTE_RESULTS` and sets a truncation flag when matches were omitted.

**Reasoning.** UDP does not segment for the application; deck 2 notes messages
larger than the maximum "must be fragmented", and IP fragmentation means losing one
fragment loses the whole datagram — precisely under the loss conditions this project
induces. One datagram per reply also preserves the one-request-one-reply model the
entire duplicate-filtering design assumes.

**Alternatives rejected.**

- *Application-level fragmentation and reassembly.* Requires sequencing, reassembly
  buffers, and per-fragment retransmission — effectively rebuilding TCP — and breaks
  the assumption that one stored reply answers one duplicate request.
- *Letting IP fragment.* Degrades reliability silently, and worst under induced
  loss.
- *A paginated query with an offset argument.* A reasonable alternative that keeps
  all results reachable. **Deferred, not dismissed**: it adds a round trip and an
  operation the brief does not ask for. Revisit if the demo dataset makes
  truncation visible.

---

### D15 — UTF-8, byte lengths, no terminator

**Decision.** UTF-8 fixed explicitly on both sides; strings carry a byte length,
not a character count; the NUL terminator is never transmitted.

**Reasoning.** Java's platform default charset varies by machine and locale, so it
must be named. C has no charset concept and transmits whatever bytes are in the
buffer, so the contract must be set here. A byte length is unambiguous across both;
a character count is not, once multi-byte sequences appear. Transmitting the C
terminator would make the two implementations disagree by one byte on every string.

**Alternatives rejected.**

- *ASCII only.* Sufficient for demo data, rejected as an undocumented restriction
  that fails silently on the first non-ASCII place name.
- *NUL-terminated with no length prefix.* The brief explicitly suggests "adding the
  length information of the string in the marshalled message", and scanning for a
  terminator in an untrusted buffer is a parsing hazard on the C side.

---

### D16 — Airfare as IEEE-754 `float32`

**Decision.** 32-bit IEEE-754, transferred bit-for-bit.

**Reasoning.** The brief specifies a "floating-point value", and deck 2 records that
CDR represents floating point using the "IEEE standard (sign + exponent +
fractional part)", which both languages implement. Bit-level transfer avoids any
decimal formatting question. `float` rather than `double` removes a width to keep
synchronized across the language boundary.

**Alternatives rejected.**

- *Fixed-point integer cents.* The correct engineering choice for money, rejected
  because the brief asks for floating point and because transferring a float
  correctly across two languages is exactly the marshalling problem worth
  demonstrating.
- *`double`.* An unnecessary width to keep matched for a value needing no such
  precision.

---

### D17 — Fixed-capacity server tables

**Decision.** No dynamic allocation in the C server; all tables fixed-capacity with
a defined full-table error.

**Reasoning.** At this scale dynamic allocation buys nothing and adds a failure mode
to a program parsing network input. A full table becomes a clean protocol error
(`ERR_MONITOR_TABLE_FULL`) rather than an allocation failure.

**Alternative rejected.** *Growable tables.* Adds allocation-failure paths and
memory-lifetime bugs on the untrusted-input side, for capacity no demo will reach.

---

### D18 — Hard-coded seed data

**Decision.** The flight table is seeded from a hard-coded array at startup.

**Reasoning.** The brief states persistence is not compulsory and the minimum is to
hold flights in memory. Hard-coding makes the demo reproducible, removes file I/O
and parsing from the C side, and keeps the submission a single packed file.

**Alternative rejected.** *Loading from a config file.* Adds a parser and a path
dependency for no marks, and risks a missing file on the demo machine.

---
---

# Part III — Delivery

## 13. Experiment plan

The brief requires designing experiments to compare the two semantics and
discussing the results. Each runs `N = 20` trials with server-side injection only
(§10). The §2.4 invariant gives an automatic pass/fail per trial.

| # | Setup | Operation | Semantics | Expected |
|---|---|---|---|---|
| E1 | no loss | `RESERVE` | both | identical, correct |
| E2 | `--drop-reply 0.5` | `RESERVE` | at-least-once | **over-reserves**; final seats below expected |
| E3 | `--drop-reply 0.5` | `RESERVE` | at-most-once | correct; duplicates answered from history |
| E4 | `--drop-reply 0.5` | `CANCEL_SEATS` | at-least-once | **over-releases**; invariant violated |
| E5 | `--drop-reply 0.5` | `CANCEL_SEATS` | at-most-once | correct |
| E6 | `--drop-reply 0.5` | `SET_RESERVATION` | at-least-once | **correct despite re-execution** |
| E7 | `--drop-request 0.5` | `RESERVE` | both | correct — request loss alone does not discriminate |
| E8 | `--drop-reply 0.5`, monitor active | `RESERVE` | at-least-once | monitor sees two updates for one user action |
| E9 | 200 requests | any | at-most-once | history stays bounded with `ackUpTo`, grows without it |
| E10 | callback loss | `MONITOR` | — | monitor reports gaps in `callbackSeq` |

**E6 is the centerpiece.** It is the experiment a read-only idempotent operation
could not produce: a genuine mutation, executed twice, ending in the correct state.

**E7 is worth reporting as a negative result.** It shows the discriminating failure
is specifically *reply* loss, since a lost request is simply retried until it lands
under either semantics.

Record per trial: expected `seatsAvailable`, observed, retransmission count,
server-side duplicate count, and invariant pass/fail.

---

## 14. Test plan

| Level | Test |
|---|---|
| conformance | §11.2 vector encoded by C and Java, hex-compared byte for byte |
| unit | round-trip every primitive: `int32` boundaries, `float32` including negatives and zero, strings at lengths 0, 1, 3, 4, 63 (padding boundaries) |
| unit | header pack/unpack at all three `messageType` values |
| unit | every operation's request and reply body, round-tripped |
| boundary | `QUERY_BY_ROUTE` returning 0, 1, `MAX_ROUTE_RESULTS`, `MAX_ROUTE_RESULTS + 1` matches |
| boundary | `RESERVE` for exactly all remaining seats, and one more |
| boundary | `CANCEL_SEATS` for exactly the held count, and one more |
| boundary | `SET_RESERVATION` to 0, to current, and above capacity |
| negative | truncated datagram, oversized string length, negative length, unknown opcode |
| integration | Java client against Java server, all operations |
| integration | Java client against C server, all operations, diffed against the Java server |
| system | three-machine callback demo with two clients |
| system | all experiments in §13 |

The Java server exists as the oracle for the second integration row. Any difference
between the two servers for the same request is a C bug, and the diff localizes it
immediately.

---

## 15. Build and run

### 15.1 Building

Java, no build tool — plain `javac` keeps the submission a clean archive with no
dependency manifest:

```
javac -d out $(find src/java -name '*.java')
```

C:

```
make                      # Linux/macOS: cc -std=c11 -Wall -Wextra -O2
make PLATFORM=windows     # links ws2_32 for Winsock
```

### 15.2 Running

```
server  --port <port>
        [--semantics at-least-once|at-most-once]   default at-most-once
        [--drop-request <p>] [--drop-reply <p>]    default 0.0
        [--seed <n>]                               PRNG seed, for repeatable runs

client  --host <server-ip> --port <port>
        [--semantics at-least-once|at-most-once]   must match the server
        [--drop-request <p>] [--drop-reply <p>]    default 0.0
        [--timeout <ms>]                           default 2000
        [--retries <n>]                            default 3
```

The server address and port are supplied on the client's command line, as the brief
permits. The server learns each client's address from the received datagram, so
clients need no prior registration. Find the server's address with `ipconfig` on
Windows or `ip addr` on Linux.

Both programs print every request and reply they handle. `--seed` fixes the
drop-simulation PRNG so an experimental run can be reproduced exactly, including in
front of a marker.

---

## 16. Repository layout

```
docs/                     course brief
src/
  java/
    common/               marshalling, protocol constants, header codec
    client/               console UI, request/reply state machine
    server/               Java server — baseline and test oracle
  c/
    server/               C server — the graded cross-language implementation
tests/
  conformance/            §11.2 hex vector, both languages
  experiments/            §13 harness and result logs
scripts/                  build and demo helpers
```

`src/java/common` is shared between the Java client and Java server. It is **not**
shared with the C server, which is the point: the C implementation is written
against Part I, not against the Java code.

---

## 17. Delivery phases

The optional credit must not endanger the baseline, so a demonstrable system exists
before the difficult part begins.

| Phase | Deliverable | Exit criterion |
|---|---|---|
| 1 | Part I frozen | both implementers agree the spec is unambiguous |
| 2 | Java client + Java server: six operations, both semantics, callbacks | full §13 set passes on two machines |
| 3 | Conformance vector (§11.2) in both languages | C and Java produce identical bytes |
| 4 | C server | Java client passes §14 integration, diffed against the Java server |
| 5 | Report, demo rehearsal | three-machine run end to end |

Phase 4 does not start before phase 2 demos cleanly. **If phase 4 runs out of time,
phase 2 is a complete and submittable project.**

---

## 18. Demo script

Three machines: server, client A (reserving), client B (monitoring).

1. Start the server, `--semantics at-most-once`, no loss. Show the seeded table.
2. Client A: query by route, showing multiple matches. Then query by ID.
3. Client B: `MONITOR` the flight for 60 s. Show the baseline snapshot.
4. Client A: `RESERVE 2`. Client B prints the callback. Show `seatsAvailable` on
   both.
5. Client A: `CANCEL_SEATS 1`, then `SET_RESERVATION 5`. Client B prints each.
6. Restart with `--semantics at-least-once --drop-reply 0.5 --seed 42`. Client A:
   `RESERVE 2`. Show the client retransmitting, the server executing twice, client
   B receiving two callbacks, and the invariant assertion firing.
7. Restart with `--semantics at-most-once --drop-reply 0.5 --seed 42`. Repeat.
   Show `DUPLICATE ... replaying stored reply`, one callback, correct final count.
8. Repeat step 6 with `SET_RESERVATION` under at-least-once: two executions, still
   correct. This is the idempotency result.
9. Show the monitor interval expiring and the registration being dropped.

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
| Design request/reply formats, describe in report | §3, §4, D3, D4 |
| Marshal ints, floats, strings by hand; length-prefixed strings | §3.3, §11 |
| Network byte order | §3.2 |
| No RMI/RPC/CORBA/Java serialization/stream classes | D3, and `CLAUDE.md` constraints |
| Both invocation semantics, selectable by argument | §8, §15.2 |
| Request identifiers for duplicate detection | §8.2, D5 |
| Timeouts, duplicate filtering, histories | §7.2, §8.2, §8.3 |
| Simulate loss of requests and replies | §10, D12 |
| Experiments comparing the semantics, discussed in report | §13 |
| Multiple clients monitoring concurrently | §9, D11 |
| Client interface with a quit option | §7.1 |
| Print requests and replies on both sides | §6.1, §7.2 |
| In-memory storage only | D18 |
| Optional: client and server in different languages | D2 **[HARD PATH]** |
| Demo with clients and server on different computers | §18 |

---

## 20. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| C server not finished in time | loses the optional credit | phased delivery (§17); phase 2 is submittable alone |
| Cross-language byte mismatch found late | integration stalls | conformance vector built in phase 3, before the C server |
| Winsock/BSD divergence on the demo machine | server will not build on the day | fix the server OS before writing C; `make PLATFORM=` covers both |
| Firewall blocks UDP between laptops | demo fails | test the three-machine path in advance on a known-good network |
| Callback loss mistaken for a bug during marking | looks like a defect | `callbackSeq` reports gaps explicitly |
| Truncation on route query goes unnoticed | silent wrong answer | explicit `truncated` flag in the reply |
| Single-file submission omits a source file | incomplete submission | scripted packaging step; no resubmission is permitted |

---

## 21. Administration

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

### Open items

- Group membership and work split.
- Operating system of the server machine, which decides Winsock versus BSD sockets.
  This changes the C from the first line and blocks phase 4.

---

Copying code between groups, in either direction, scores zero for the project.
