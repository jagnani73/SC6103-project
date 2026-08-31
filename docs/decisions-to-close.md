# Three decisions to close

For one meeting. Nobody can write client or server code until #1 is called.

Full argument for each is in `README.md` §22. Recommendations below are one person's
view, with the reasoning shown so they can be argued against.

---

## What the brief actually rewards

Worth agreeing on this first, because it settles two of the three decisions.

The brief pays extra marks in exactly one place:

> **An additional but optional requirement** is to implement the client and the server in
> different programming languages – for example, one in C/C++ and one in Java.

Everything else it asks for is required work to be done well: request/reply message
design, hand-written marshalling, both invocation semantics, the loss experiments,
well-commented source, the report, and a working three-machine demo.

Two consequences:

- There is **no bonus for a GUI**. The brief says a console interface is fine and a
  graphical one "is not necessary".
- There is **no bonus for a cleverer pair of extra operations**. Any correct
  idempotent/non-idempotent pair satisfies the requirement equally.

So effort spent outside decision #1 does not buy marks. It buys a nicer project.

---

## 1. Language pairing — blocks everyone

| Option | Effect |
|---|---|
| **C++17 server + Java client** | Earns the optional credit. The existing C++ server design is untouched; only the client language changes. |
| C++17 both ends | Least work. Forfeits the credit. |
| C server + Java client | Same credit as option 1, but rebuilds a server already designed in C++17. |

**Recommendation: C++17 server + Java client.**

It is the only option that takes the one bonus the brief offers without discarding work
anyone has already done. The extra cost lands almost entirely inside the marshalling
layer — which is graded regardless, and which two independent implementations force us
to get right rather than merely consistent.

The risk is late integration failure, and it is managed by sequencing, not by avoiding
the decision: build a single-language pair first (M0–M5), freeze the byte-level
conformance vector (`README.md` §11.2), then write the second implementation against it.
If the second implementation runs out of time, M5 is a complete and submittable project
that forfeits only this credit.

**If we choose C++ on both ends instead**, four decisions invert and Part I of
`README.md` needs a revision pass rather than a patch: packed narrow types and a CRC-32
become correct again once Java is out of the picture (D6, D15, D21, D23).

---

## 2. Which two extra operations — blocks M3

Both satisfy the brief. Neither earns extra marks.

| Option | Effect |
|---|---|
| `ADJUST_FARE` / `SET_FARE` | Relative and absolute writes to `airfare`. No ownership tracking needed. |
| `CANCEL_SEATS` / `SET_RESERVATION` | Relative and absolute writes to seat counts. Needs a per-client booking ledger. |

**Recommendation: `ADJUST_FARE` / `SET_FARE`.**

The seat-based pair was argued for earlier in `README.md` D9 on the grounds that seat
corruption is visible live to a monitoring client. That argument is weaker than it
looked:

- `RESERVE` is already non-idempotent and already fires callbacks, so the live
  at-least-once corruption demo works either way. It does not depend on the extra
  operations at all.
- The report justification is identical for both pairs: two writes to the same field
  differing only in absolute versus relative, which isolates idempotency as the single
  variable.

What the seat pair uniquely adds is showing an *idempotent write* being replayed
harmlessly on the monitoring machine. Real, but small — and it costs a booking ledger,
per-client ownership tracking, and an orphaned-seat leak that has to be disclosed
(`README.md` D22).

Since no marks hang on the difference, take the cheaper pair.

**If we choose the fare pair**, `README.md` needs: D9 rewritten, the §2.4 booking ledger
and D22 removed, §4 operations 5–6 replaced, status code 5 repurposed from
`ERR_NOT_ENOUGH_HELD` to a negative-fare rejection, and float validation
(`isfinite`, non-negative) added to the request path — which currently carries no floats
at all.

---

## 3. Server operating system — blocks M6

Winsock and BSD sockets diverge from the server's first line, so this cannot be deferred.

**Warning worth reading before anyone picks WSL2.** WSL2 uses NAT by default, which means
a server running inside it is **not reachable from another machine on the LAN**. The brief
requires the demo to run with clients and server on different computers, and the callback
demonstration needs two client machines plus the server. A WSL2 server would fail that.

Options: run the server on native Linux, run it on native Windows with Winsock, or use
WSL2 with `networkingMode=mirrored` set in `.wslconfig` — and in that last case, verify
reachability from a second physical machine at M1, not on demo day.

---

## Already settled — do not reopen

These look like open questions and are answered by the brief. Detail in `README.md` §23.

| | |
|---|---|
| Airfare type | Floating point. Not integer currency units. |
| Flight fields | Fixed by the brief; departure time structure is ours to design. |
| Dataset format | Ours. Persistence is explicitly not required. |
| Opcode numbering, argument order | Ours. |
| Command-line syntax | Ours. Only needs to accept server address, port, and semantics. |
| Console versus GUI | Console is sufficient. A GUI earns nothing. |
| Submission | **One file** containing all source. **No resubmission.** One member submits. |
| Report cover | Every member's name **and the percentage of work each did**. |

---

## Dates

| | |
|---|---|
| Code and report due | 14 October 2026, 23:59 |
| Live demo | 17 October 2026 |
