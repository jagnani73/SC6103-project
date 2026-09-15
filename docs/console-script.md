# Client console script

Every prompt and every line the client prints. Language-independent by design, though
§22.1 has settled the client on Java.

The brief requires both programs to print what they send and what they receive, so the
console carries two interleaved streams: the **interaction** (menu, prompts, results)
and the **protocol trace** (bracketed lines). The trace uses the field order fixed in
`README.md` §10.2, so a client log can be read alongside the server's.

Operations 5 and 6 are `ADJUST_FARE` and `SET_FARE`, settled at `README.md` §22.2.

---

## 1. Startup

Everything on this banner is a startup-time fact that determines how the rest of the
session should be read. It belongs in every captured log (`README.md` §24.3).

```
SC6103 Flight Information Client
--------------------------------------------------------------
  server      192.168.1.20:5000
  local       0.0.0.0:51194
  client id   0x0000A31F
  semantics   at-most-once
  timeout     750 ms, 3 retries
  loss        request 0.00  reply 0.00
  rules       none
  seed        42
--------------------------------------------------------------
```

If the server rejects the first request with `ERR_SEMANTICS_MISMATCH`, print this and
exit rather than continuing — the server will refuse every subsequent request too:

```
Semantics mismatch. This client is at-least-once but the server is
at-most-once. Restart both with the same --semantics value.
```

---

## 2. Main menu

Redrawn after every completed operation.

```
  1) Find flights by route
  2) Show flight details
  3) Reserve seats
  4) Monitor seat availability
  5) Adjust fare
  6) Set fare
  0) Quit

choice>
```

Invalid entry, no request sent:

```
choice> 9
Not an option. Enter 0-6.
```

---

## 3. Operations

### 1 — Find flights by route

```
choice> 1
source> Singapore
destination> London

[SEND] req=17 op=QUERY_BY_ROUTE attempt=1
[RECV] req=17 status=OK

3 flights, Singapore to London

  ID     Departure (UTC)      Fare      Seats
  1934   2026-10-14 09:30     899.50      117
  2201   2026-10-15 23:05    1204.00        6
  0088   2026-10-16 07:15     742.25        0
```

Empty result is an error, not an empty list:

```
[RECV] req=18 status=ERR_NO_ROUTE_MATCH
No flights from Singapore to Reykjavik.
```

Truncation flag set:

```
Showing the first 64 matches. More exist.
```

### 2 — Show flight details

```
choice> 2
flight id> 1934

[SEND] req=19 op=QUERY_BY_ID attempt=1
[RECV] req=19 status=OK

Flight 1934
  departure   2026-10-14 09:30 UTC
  fare        899.50
  seats       117 available
```

```
[RECV] req=20 status=ERR_UNKNOWN_FLIGHT
No flight with ID 9999.
```

### 3 — Reserve seats

```
choice> 3
flight id> 1934
seats> 2

[SEND] req=21 op=RESERVE attempt=1
[RECV] req=21 status=OK

Reserved 2 seats on flight 1934. 115 now available.
```

```
[RECV] req=22 status=ERR_INSUFFICIENT_SEATS
Not enough seats available on flight 1934.
```

Client-side rejection, before any datagram is sent:

```
seats> 0
Seats must be at least 1.
```

### 4 — Monitor seat availability

See §5 for what happens after registration.

```
choice> 4
flight id> 1934
interval (seconds)> 60

[SEND] req=23 op=MONITOR attempt=1
[RECV] req=23 status=OK

Monitoring flight 1934 for 60s. 115 seats at registration.
Input is blocked until the interval ends.
```

### 5 — Adjust fare (non-idempotent)

```
choice> 5
flight id> 1934
change by> -50.00

[SEND] req=24 op=ADJUST_FARE attempt=1
[RECV] req=24 status=OK

Fare for flight 1934 changed by -50.00, now 900.00.
```

```
[RECV] req=25 status=ERR_INVALID_FARE
The resulting fare must be above 0 and at most 100000.00. Flight 1934 is unchanged.
```

Print the delta, not just the result. Under at-least-once with a lost reply the
operation runs twice, and a user who sees only the final figure cannot tell.

### 6 — Set fare (idempotent)

```
choice> 6
flight id> 1934
new fare> 950.00

[SEND] req=26 op=SET_FARE attempt=1
[RECV] req=26 status=OK

Fare for flight 1934 is now 950.00.
```

Running the identical request again prints the identical result. That is the point of
the operation and it is worth showing during the demo.

### 0 — Quit

```
choice> 0
Closing socket. 26 requests sent, 0 timed out.
```

---

## 4. Retry and timeout

The same encoded bytes and the same `(clientId, requestId)` go out on every attempt.
The trace must make the reuse visible, because it is what lets the server recognise a
duplicate at all.

```
[SEND]  req=27 op=RESERVE attempt=1
[TIMEOUT] req=27 attempt=1 after 750ms
[RETRY] req=27 attempt=2 identical bytes
[RECV]  req=27 status=OK decision=cache-replay

Reserved 2 seats on flight 1934. 113 now available.
Server answered from its reply history — the operation ran once.
```

The `cache-replay` line appears only under at-most-once. Surfacing it turns duplicate
suppression into something a marker can watch rather than something described in the
report.

Under at-least-once the same sequence ends differently, and the client should not
pretend otherwise:

```
[RECV]  req=27 status=OK decision=re-executed
Reserved 2 seats on flight 1934. 111 now available.
```

Exhaustion:

```
[SEND]  req=28 op=RESERVE attempt=1
[TIMEOUT] req=28 attempt=1 after 750ms
[RETRY] req=28 attempt=2 identical bytes
[TIMEOUT] req=28 attempt=2 after 750ms
[RETRY] req=28 attempt=3 identical bytes
[TIMEOUT] req=28 attempt=3 after 750ms
[RETRY] req=28 attempt=4 identical bytes
[TIMEOUT] req=28 attempt=4 after 750ms

No reply after 4 attempts.
The server may or may not have executed this request. Query the
flight to find out.
```

**The wording is a requirement, not a style choice.** A lost request means the operation
never ran; a lost reply means it ran and the answer vanished. The client cannot
distinguish them, so it must not say "failed". `README.md` §7.2.

Injected loss is logged, so a demo trace reads as a narrative rather than an unexplained
gap:

```
[DROP] req=29 dir=request reason=forced-first-request
```

Discarded datagrams:

```
[IGNORE] req=26 reason=stale, awaiting req=27
[IGNORE] clientId=0x0000B004 reason=foreign
```

---

## 5. Monitor mode

Blocking loop until the client's own local deadline. No menu, no input accepted. The
brief permits this, which is why the client needs no threads.

```
Monitoring flight 1934 for 60s. 115 seats at registration.
Input is blocked until the interval ends.

[CALLBACK] seq=1 flight=1934 seats=113 event=req31    113 seats
[CALLBACK] seq=2 flight=1934 seats=112 event=req32    112 seats
[CALLBACK] seq=4 flight=1934 seats=108 event=req34    108 seats
           ^ gap: callback seq=3 was not delivered

[MONITOR-END] flight=1934 final-seq=4

Monitor ended. 3 of 4 updates received, 1 lost.
```

Three rules:

- **Report the gap, do not hide it.** Callbacks are fire-and-forget, so loss is
  documented behaviour (`README.md` D10), not a defect. A missing update that appears as
  nothing at all reads as a bug to whoever is marking.
- **Reconcile against `MONITOR_END`.** Its `final-seq` gives the true total, so the
  client can say *3 of 4* rather than only reporting gaps it happened to notice between
  updates that did arrive.
- **The local deadline bounds the wait.** `MONITOR_END` can itself be lost. The server
  sends it a few milliseconds after expiry, so the client keeps listening for one retry
  timeout past its deadline. If it still has not arrived, end on the timer and say so:

```
Monitor ended on local timer. MONITOR_END not received;
the lost-update count may be understated.
```

A repeated `eventRequestId` is the visible signature of at-least-once re-execution and
should be called out by name:

```
[CALLBACK] seq=2 flight=1934 seats=111 event=req31    111 seats
           ^ same event as seq=1 - server executed req31 twice
```

---

## 6. Status codes as user-facing text

Error replies carry **no body** (`README.md` D4), so every message here is composed by
the client from its own request. The client knows the flight ID it asked about; it does
not know the server's seat count. Do not write text that implies otherwise.

| Code | Shown to the user |
|---|---|
| 0 `OK` | no message — print the result |
| 1 `ERR_UNKNOWN_FLIGHT` | `No flight with ID <id>.` |
| 2 `ERR_NO_ROUTE_MATCH` | `No flights from <src> to <dst>.` |
| 3 `ERR_INSUFFICIENT_SEATS` | `Not enough seats available on flight <id>.` |
| 4 `ERR_INVALID_ARGUMENT` | `The server rejected an argument in that request.` |
| 5 `ERR_INVALID_FARE` | `The resulting fare must be above 0 and at most <max>. Flight <id> is unchanged.` |
| 6 `ERR_MALFORMED_REQUEST` | `Protocol error: the server could not parse that request.` |
| 7 `ERR_UNKNOWN_OPCODE` | `Protocol error: the server does not support that operation.` |
| 8 `ERR_MONITOR_TABLE_FULL` | `The server cannot accept more monitors right now. Try again shortly.` |
| 9 `ERR_SERVER_ERROR` | `The server reported an internal error. Check the server log.` |
| 10 `ERR_VERSION_MISMATCH` | `Protocol version mismatch. Client and server builds differ.` |
| 11 `ERR_SEMANTICS_MISMATCH` | `Semantics mismatch. Restart both with the same --semantics.` |
| 12 `ERR_REQUEST_ID_CONFLICT` | `Request ID collision. This is a client bug — report it.` |

Codes 1–5 are the request being refused on its merits: the system is working and the
answer is no. Show them where the user typed. Codes 6–12 are protocol or configuration
faults the user cannot fix from a prompt; print them as session-level warnings.
