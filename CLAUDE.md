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

Where the brief offers a choice, this project takes the more difficult option **when
the difficulty buys marks**. That is a standing decision, not an accident, and it
should not be "simplified" away.

Concretely:

- **The client and server are written in different languages** — C++17 server, Java
  client, settled at `README.md` §22.1. The brief calls this "an additional but
  optional requirement", and it is the only sentence in the document offering marks
  for extra work. Both ends in one language is the baseline the brief expects.
  **The C++ server is the only server.** The earlier plan to write a Java server
  first, as a baseline and a test oracle, was dropped on 3 September 2026: all four
  members already believed the project was one server and one client, and a shared
  model across four people beats a fallback nobody was counting on.
- **The two extra operations are a matched idempotent/non-idempotent pair over the
  same mutable field** — `ADJUST_FARE` and `SET_FARE` on `airfare` — rather than the
  easier route of pairing a write against a read-only query. A read cannot corrupt
  state, so at-least-once trivially produces the right answer and the comparison
  demonstrates nothing.

If a change would make either of these easier by abandoning the harder option, that
is a regression. Raise it rather than doing it.

**Where the harder path was deliberately declined.** The extra operations were
previously specified as `CANCEL_SEATS`/`SET_RESERVATION` over a per-client booking
ledger. That was reversed at §22.2 on 3 September 2026: it earns no additional marks,
`RESERVE` already supplies the live at-least-once corruption demo, and the ledger
brought an orphaned-seat leak that had to be disclosed. Difficulty is worth taking
where the brief pays for it and not otherwise — see `README.md` D9 for the argument
and D22 for what was withdrawn.

**There is no counterweight left.** Nothing is being built twice, so an unfinished
C++ server is not a forfeited optional credit; it is a project with nothing to
demonstrate. That risk is the price of the decision and it is accepted knowingly.
See "Delivery" for how the work is ordered around it.

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

That stack is built twice, in two languages that share no code: the Java client under
`src/java/sc6103/`, and the C++17 server. The server is written against Part I of
`README.md` and the vectors in `tests/conformance/vectors.md`, never by reading the
Java source. Each side is tested against the vectors, never against the other's
output.

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
pair keys the reply history. The observed source endpoint is used only to address
callbacks. A `CALLBACK` names the reservation that caused it by the same pair
(`eventClientId`, `eventRequestId`), never by the request ID alone, because every client
counts its request IDs from 0.

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
| 5 | `ADJUST_FARE(flightId, delta)` | no (required non-idempotent extra) |
| 6 | `SET_FARE(flightId, newFare)` | yes (required idempotent extra) |

Invocation semantics are selected by CLI flag and carried in the header. The server
is single-threaded on the request path.

## Delivery

The milestone table is in `README.md` §17. Four rules shape it, and they hold
whatever the table says:

- **One server, no fallback.** Nothing is written twice, so there is no partial
  system to submit and nothing to demo if the C++ server is unfinished. Order the
  work so a thin end-to-end path runs early and grows, never so the pieces meet for
  the first time near the deadline.
- **The Java client is the test driver.** It is finished, and it is the real client,
  not a harness. Every C++ milestone is proved by pointing it at the server and
  reading both logs. A server that only passes its own unit tests has been tested
  against the assumptions that wrote it.
- **The conformance vector is the contract.** With no single-language system to diff
  against, `tests/conformance/vectors.md` and `README.md` §11 are the only alignment
  mechanism there is. Write the C++ marshaller against the vectors and pass them
  before any socket code exists: an endianness or alignment fault costs minutes there
  and a day over UDP.
- **Prove three-machine reachability early.** Server bind, client traffic and
  callback delivery across three physical machines, on the network the demo will use.
  A blocking firewall or a NAT is cheap to work around now and fatal on demo morning
  (`README.md` §20).

Where things stand: the specification and both implementations are complete. The Java
console client is finished from the UI through the retry engine and UDP channel; the
C++17 server implements all six operations, both invocation semantics, monitoring and
fault injection. The automated suites currently pass 387 checks, and the E1-E10 harness,
which drives the real Java client, passes all ten experiment groups. A report draft exists. The remaining acceptance work
is physical: fill the report cover, prove three-machine reachability on the demo network,
capture final evidence, and rehearse the live demonstration.

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
- Adding an operation touches: the opcode table above, `README.md` §4,
  `src/java/sc6103/common/Protocol.java` and the body codecs beside it, the menu in
  `src/java/sc6103/client/ConsoleUi.java`, a vector in `tests/conformance/vectors.md`,
  and the C++ server's marshaller and dispatcher.
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

## Decisions closed

All three forks were closed on 3 September 2026. Full argument in `README.md` §22.
**Do not reopen these without a recorded reversal**, the way §22.2 was reversed.

1. **Language pairing** — C++17 server + Java client, with no Java server. Takes the
   brief's only optional credit. The C++ server is not an additive phase layered on a
   single-language system; it is the entire server side, and nothing exists to fall
   back to if it slips.
2. **Extra operations** — `ADJUST_FARE` / `SET_FARE` on `airfare`. Reversed from the
   earlier seat pair; see the posture section above and `README.md` D9.
3. **Server operating system** — native Windows, Winsock. Not WSL2: its default NAT
   makes a server inside it unreachable from other LAN machines, which would break the
   required three-machine demo (`README.md` §20). Prove three-machine reachability
   early, not on demo day.

Group membership and the work split remain open, and the report cover page requires
both.

Questions that *look* open but are already answered by the brief — fare type, dataset
format, opcode numbering, CLI syntax, submission packaging — are recorded in
`README.md` §23. Do not reopen them.

