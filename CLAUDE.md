# SC6103 — Distributed Flight Information System

NTU SC6103 Distributed Systems course project, AY2026/27 Trimester 1.
**Group project**, at most four members, with the work split recorded on the report
cover page as a percentage per member.

A client–server flight information system over **UDP** in which the request-reply
middleware layer is implemented by hand: byte-level marshalling, timeouts and
retransmission, duplicate filtering, reply histories, and server-to-client
callbacks. No RPC framework is used, because writing that layer is the exercise.

- Brief: `docs/Course Project.pdf`
- Full specification and design rationale: `README.md`
- Lecture decks: one directory up in `../`; decks 2 (Interprocess Communication)
  and 3 (Distributed Objects & Remote Invocation) are what this project implements

Code deadline **2026-10-14**. Live demo **2026-10-17**.

## Posture: the harder path is the intended path

Where the brief offers a choice, this project deliberately takes the more difficult
option to compete for the optional credit. That is a standing decision, not an
accident, and it should not be "simplified" away.

Concretely:

- **The client and server are written in different languages** (working assumption:
  C++17 server, Java client). The brief calls this "an additional but optional
  requirement". Both ends in one language is the baseline the brief expects.
- **The two extra operations are a matched idempotent/non-idempotent pair over the
  same mutable state**, rather than the easier route of pairing a write against a
  read-only query. This requires per-client seat-ownership tracking the brief does
  not demand.
- **Seat ownership is tracked per client**, so `CANCEL_SEATS` and `SET_RESERVATION`
  operate on "my booking" rather than on the global pool.

If a change would make one of these easier by abandoning the harder option, that is
a regression. Raise it rather than doing it.

The counterweight: none of this may put the baseline at risk. Delivery is phased so
a working system exists before the hard parts land — see "Delivery phases".

## Hard constraints

From the brief. Violating any of these fails the project, so check this list before
adding a dependency or an import.

- **UDP only.** No TCP anywhere in the request path.
- **No middleware.** No RMI, RPC, CORBA, Java object serialization, or `java.io`
  stream classes used for wire encoding. No Protobuf, JSON, Kryo, or equivalent.
- **Marshal into a raw byte array by hand.** Integers, floats and strings packed
  manually; strings carry an explicit length prefix.
- **Network byte order** (big-endian) on the wire, everywhere.
- Server state is **in-memory only**. Persistence is explicitly not required.
- Console UI only. No GUI.
- Both sides must **print every request and reply** they handle.
- Copying code between groups, in either direction, scores zero.

## Architecture at a glance

`README.md` is the authoritative specification and carries the rationale for every
decision, including rejected alternatives. Update it when a design decision changes;
this file is the short form.

```
  application    flight services, console UI
  ─────────────────────────────────────────
  request-reply  retransmit, dedupe, history, callbacks   <- the project
  marshalling    byte[] pack/unpack
  ─────────────────────────────────────────
  UDP            sockets
```

**Every field in every message is exactly 4 bytes, big-endian.** No `uint8`, no
`uint16`, anywhere, including the header. Java has no unsigned types, so uniform
width is what keeps two independent implementations honest. This rule is
load-bearing — see `README.md` D23.

Fixed 40-byte header:

```
 0  uint32  magic        0x53433631 ("SC61")
 4  int32   version      1
 8  int32   messageType  0=REQUEST 1=REPLY 2=CALLBACK 3=MONITOR_END
12  int32   opCode
16  int32   semantics    0=at-least-once 1=at-most-once
20  uint32  clientId     random nonzero, fixed for the client's lifetime
24  int32   requestId    monotonic per client
28  int32   ackUpTo      requests only; history-pruning watermark
32  int32   status       replies and callbacks only
36  int32   payloadLen   exact body length; check against the datagram length
```

A request is identified by **(clientId, requestId)** — not by network address. That
pair keys both the reply history and the booking ledger. The observed source
endpoint is used only to address callbacks.

Two rules that are easy to violate and expensive to debug:

- **The server's socket wait must be bounded** by the time until the next monitor
  expiry. A blocking `recvfrom` means registrations outlive their interval on an idle
  server, and `MONITOR_END` never fires. Regression test T15 exists for this.
- **At-most-once history entries carry a request fingerprint** — a hash of opcode plus
  body. A key hit with a different fingerprint is `ERR_REQUEST_ID_CONFLICT`, not a
  replay. The fingerprint stays server-side and never crosses the wire.

All expiry and timeout decisions use a monotonic clock, never wall-clock time.

| # | Operation | Idempotent |
|---|---|---|
| 1 | `QUERY_BY_ROUTE(source, dest)` | yes |
| 2 | `QUERY_BY_ID(flightId)` | yes |
| 3 | `RESERVE(flightId, n)` | no |
| 4 | `MONITOR(flightId, intervalMs)` | registration only |
| 5 | `CANCEL_SEATS(flightId, n)` | no (required non-idempotent extra) |
| 6 | `SET_RESERVATION(flightId, target)` | yes (required idempotent extra) |

Invocation semantics are selected by CLI flag and carried in the header. The server
is single-threaded on the request path.

## Delivery

Milestones M0–M8 are in `README.md` §17. The ordering rule: **M6 (the cross-language
server) does not start before M4 demos cleanly.** If M6 runs out of time, M5 is a
complete and submittable project that forfeits only the optional credit.

The conformance vector (`README.md` §11.2) is built at M6 *before* the second
implementation, not after — it is what catches alignment and endianness faults while
they are still cheap.

## Working on this repo

- Match the existing byte-offset conventions exactly. An off-by-one in the packer is
  invisible until it corrupts a float on the wire.
- **Never `memcpy` a struct onto the wire.** Padding differs by platform and Java
  has no equivalent. Serialize field by field.
- **Never type-pun a float through a cast** — `memcpy` into a `uint32_t`, then
  `htonl`. The cast is undefined behaviour in C++.
- In Java, mask every byte read with `& 0xFF`, and validate decoded lengths are
  non-negative and in range before allocating.
- Check remaining buffer length before every cursor read, on both sides.
- Adding an operation touches: the opcode table above, `README.md` §4, the
  marshaller on both sides, the server dispatcher, and the client menu.
- Comments are graded. Non-obvious protocol decisions get a comment explaining the
  *why*, with a lecture-slide or decision-record reference.
- Do not reach for a library to solve a problem the brief wants solved by hand. If
  something feels like it needs a serialization framework, that is the exercise.

## Deliverables

- Source code packed into a **single file**, submitted once per group via NTULearn.
  The module accepts no resubmission, so submit only the final version.
- Lab report: cover page with all member names and the percentage of work each
  member did; the request/reply message design; the rationale for the two extra
  operations; the loss-simulation experiments; and a discussion comparing the two
  invocation semantics.
- Live demo with server and clients on **different physical machines**. Three
  machines, since the callback demo needs one client reserving while another
  monitors.

## Open decisions

Three forks need group agreement at M0. Full argument in `README.md` §22.

1. **Language pairing** — C++17 server + Java client (assumed), versus C++ both ends
   (forfeits the credit), versus C server + Java client (discards existing work).
   Choosing C++ both ends inverts D6, D15, D21 and D23.
2. **Which two extra operations** — `CANCEL_SEATS`/`SET_RESERVATION` on seats
   (assumed; corruption visible live to a monitoring client) versus
   `ADJUST_FARE`/`SET_FARE` on airfare (less work, weaker demo).
3. **Server operating system** — decides Winsock versus BSD sockets, and changes the
   server from its first line. Note the WSL2 warning in `README.md` §20: a server
   inside WSL2 is unreachable from other LAN machines under default networking, which
   would break the required three-machine demo.

Questions that *look* open but are already answered by the brief — fare type, dataset
format, opcode numbering, CLI syntax, submission packaging — are recorded in
`README.md` §23. Do not reopen them.

Group membership and the work split are also still open.
