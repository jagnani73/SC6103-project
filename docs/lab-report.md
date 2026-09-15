# SC6103 Distributed Systems Course Project Report

## Cover information

> Complete before submission: group member names exactly as shown on matriculation
> cards, contribution percentages summing to 100%, tutorial group, and student IDs.

| Member | Student ID | Contribution |
|---|---|---:|
| _To be completed_ | _To be completed_ | _To be completed_ |

## 1. Objective and architecture

The project implements a distributed flight information system with a Java 17 console
client and a C++17 server. The programs communicate only through UDP. The request-reply
layer, byte-level marshalling, retry policy, duplicate filtering, reply history, loss
simulation, and callbacks were implemented without an RPC or serialization framework.
Server state is kept in memory and is restored to the seeded dataset after a restart.

The Java client owns request identifiers, retransmits byte-identical requests after a
timeout, discards foreign or stale replies, and blocks locally during a monitor interval.
The single-threaded C++ server uses a bounded `select` wait so monitor expiry continues
even when there is no request traffic.

## 2. Request and reply design

Every datagram has a 40-byte header containing ten 4-byte, big-endian fields: magic,
version, message type, operation code, invocation semantics, client ID, request ID,
acknowledgement watermark, status, and payload length. Bodies contain manually encoded
`int32`, `uint32`, IEEE-754 `float32`, and length-prefixed UTF-8 strings padded to a
4-byte boundary. A datagram is limited to 512 bytes.

The six operations are route query, flight query, seat reservation, seat monitoring,
fare adjustment, and fare assignment. `ADJUST_FARE` is the required non-idempotent
extra operation because it applies a relative delta. `SET_FARE` is idempotent because
repeating the same absolute assignment leaves the same state. Both modify the same field,
which isolates idempotency rather than comparing an update with a read-only query.

## 3. Invocation semantics and fault tolerance

Under at-least-once semantics, every valid arrival is executed. A lost reply therefore
causes a retransmitted `RESERVE` or `ADJUST_FARE` to mutate state again. Under
at-most-once semantics, the server stores the exact reply bytes before sending them.
The history is keyed by `(clientId, requestId)` and includes a fingerprint of the opcode
and request body. A matching duplicate replays the cached bytes; a changed request using
the same identity receives `ERR_REQUEST_ID_CONFLICT`.

The client piggybacks `ackUpTo` so the server can prune acknowledged history entries.
A per-client cap and a 60-second idle expiry provide absolute bounds. Requests, replies,
and callbacks have seeded probabilistic drop gates, while one-shot drop rules make live
demonstrations deterministic.

Successful reservations notify every active monitor. Each fire-and-forget callback has a
per-registration sequence number and the event request ID. This exposes lost callbacks
and duplicate execution. `MONITOR_END` reports the final callback count. The end notice
may itself be lost, so the client's monotonic deadline bounds the wait. The client still
listens for one retry timeout past that deadline, because the server sends the notice from
its expiry sweep a few milliseconds after the interval ends.

## 4. Experiments and results

Each probabilistic scenario was run 20 times with server-side injection and seeds 0-19.
The harness drives the Java client's own `RequestReply` with a 35 ms timeout and 9
retries, and E10 reads the client's monitor summary.

| Experiment | Result |
|---|---|
| E1, no loss | 40/40 runs correct under both semantics |
| E2, reply loss + `RESERVE`, at-least-once | 7/20 over-reserved |
| E3, reply loss + `RESERVE`, at-most-once | 20/20 correct |
| E4, reply loss + `ADJUST_FARE`, at-least-once | 8/20 over-adjusted |
| E5, reply loss + `ADJUST_FARE`, at-most-once | 20/20 correct |
| E6, reply loss + `SET_FARE`, at-least-once | 20/20 correct, with `SET_FARE` re-executed in 15 of them |
| E7, request loss + `RESERVE` | 40/40 correct under both semantics |
| E8, reply loss with monitor active | 6/20 exposed duplicate event IDs |
| E9, 200 at-most-once requests | history peaked at 1 entry per client with `ackUpTo` and at the 64-entry cap without it |
| E10, callback loss | 20/20 monitor summaries reconciled; callback loss observed in all runs |

The results isolate reply loss as the important failure case. Request loss only delays an
operation until a request arrives. Reply loss occurs after execution, so at-least-once
retransmission can repeat a relative mutation. At-most-once replay prevents this for both
non-idempotent operations. The server re-executed `SET_FARE` in 15 of the 20
at-least-once trials and the fare was still correct in all 20, which shows that the
problem is the operation's idempotency rather than retransmission alone.

## 5. Verification and limitations

The automated checks include 56 C++ conformance checks covering every vector in
`tests/conformance/vectors.md`, 222 Java conformance assertions, 46 Java client-behaviour
assertions, and 63 Java-to-C++ UDP integration checks. They cover all operations,
cross-language decoding, error replies, reply replay, request-ID conflict, semantics
mismatch, concurrent monitors, idle expiry, deterministic reply loss, and restart
behaviour. Ten of the integration checks run the real Java client and its monitor session
against the server with a forced reply drop under both semantics. All 387 checks pass, and
all ten experiment groups pass.

The at-most-once guarantee and flight data last only for one server process. Callbacks
have maybe semantics and are not retried. Authentication, persistence, and encryption are
outside the assignment scope. A final three-physical-machine rehearsal and firewall check
must be completed on the actual demonstration network.
