# SC6103 — Distributed Flight Information System

NTU SC6103 Distributed Systems course project, AY2026/27 Trimester 1.
**Group project**, at most four members, with the work split recorded on the report
cover page as a percentage per member.

A client–server flight information system over **UDP** in which the request-reply
middleware layer is implemented by hand: byte-level marshalling, timeouts and
retransmission, duplicate filtering, reply histories, and server-to-client
callbacks. No RPC framework is used, because writing that layer is the exercise.

Spec: `docs/Course Project.pdf`. Lecture decks are one directory up in `../`;
deck 2 (Interprocess Communication) and deck 3 (Distributed Objects & Remote
Invocation) are the material this project implements.

Code deadline **2026-10-14**. Live demo **2026-10-17**.

## Posture: the harder path is the intended path

Where the brief offers a choice, this project deliberately takes the more difficult
option to compete for the optional credit. That is a standing decision, not an
accident, and it should not be "simplified" away.

Concretely:

- **The client and server are written in different languages** (C server, Java
  client). The brief calls this "an additional but optional requirement". Both ends
  in one language would be markedly easier and is the baseline the brief expects.
- **The two extra operations are a matched idempotent/non-idempotent pair over the
  same mutable state**, rather than the easier route of pairing a write against a
  read-only query. This requires per-client seat-ownership tracking that the brief
  does not demand.
- **Seat ownership is tracked per client**, so `CANCEL_SEATS` and `SET_RESERVATION`
  operate on "my booking" rather than on the global pool.

If a change would make one of these easier by abandoning the harder option, that is
a regression. Raise it rather than doing it.

The counterweight: none of this may put the baseline at risk. Delivery is phased so
a working system exists before the hard parts land. See "Delivery phases" below.

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

Full rationale for every decision below lives in `README.md`. That document is the
one to update when a design decision changes, and it records rejected alternatives
so choices can be defended under questioning.

```
  application    flight services, console UI
  ─────────────────────────────────────────
  request-reply  retransmit, dedupe, history, callbacks   <- the project
  marshalling    byte[] pack/unpack
  ─────────────────────────────────────────
  UDP            sockets
```

Header on every datagram, big-endian, 4-byte aligned:

```
int32 messageType    0 = request, 1 = reply, 2 = callback
int32 requestId      monotonic per client process
int32 opCode
int32 status         reply only; 0 = OK, non-zero = error code
```

A request is identified by **(client IP, client port, requestId)**, not `requestId`
alone. That triple keys the reply history.

| # | Operation | Idempotent |
|---|---|---|
| 1 | `QUERY_BY_ROUTE(source, dest)` | yes |
| 2 | `QUERY_BY_ID(flightId)` | yes |
| 3 | `RESERVE(flightId, n)` | no |
| 4 | `MONITOR(flightId, intervalMs)` | — |
| 5 | `CANCEL_SEATS(flightId, n)` | no (required non-idempotent extra) |
| 6 | `SET_RESERVATION(flightId, target)` | yes (required idempotent extra) |

Invocation semantics (`at-least-once` / `at-most-once`) are selected by CLI flag at
startup. The server is single-threaded on the request path.

## Delivery phases

The bonus must not endanger the baseline, so build in this order and keep every
phase working:

1. **Wire format spec** written down first, as the contract between the two
   implementations.
2. **Java client + Java server.** This is the baseline deliverable and the fallback
   demo.
3. **C server**, ported against the spec. The Java server is retained as a test
   oracle — point the Java client at both and diff responses byte for byte.

Do not start phase 3 before phase 2 demos cleanly end to end on two machines.

## Working on this repo

- Match the existing byte-offset conventions in the marshalling code exactly. An
  off-by-one in the packer is invisible until it corrupts a float on the wire.
- **Never `memcpy` a C struct onto the wire.** Padding differs by platform and Java
  has no equivalent. Serialize field by field.
- In Java, mask every byte read with `& 0xFF`, and validate decoded lengths are
  non-negative before allocating.
- Adding an operation touches: the opcode table above, the wire spec in `README.md`,
  the marshaller on both sides, the server dispatcher, and the client menu.
- Comments are graded. Non-obvious protocol decisions get a comment explaining the
  *why*, with a lecture-slide reference where one applies.
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

## Open items

- Group membership and work split.
- Operating system of the server machine, which determines whether the C server
  targets Winsock or BSD sockets. This changes the C from the first line.
