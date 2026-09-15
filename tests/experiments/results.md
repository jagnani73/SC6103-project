# Invocation-semantics experiment results

Run on 2026-09-15 with server seeds 0-19 and 20 trials per probabilistic scenario. Every
exchange went through the Java client's `RequestReply` with a 35 ms timeout and 9
retries, and loss was injected on the server only.

| Experiment | Observation | Result |
|---|---:|---|
| E1 | 40/40 correct without loss | PASS |
| E2 | 7/20 at-least-once trials over-reserved | PASS |
| E3 | 20/20 at-most-once trials correct | PASS |
| E4 | 8/20 at-least-once fares over-adjusted | PASS |
| E5 | 20/20 at-most-once fares correct | PASS |
| E6 | 20/20 `SET_FARE` results correct; re-executed in 15 of them | PASS |
| E7 | 40/40 request-loss trials correct | PASS |
| E8 | 6/20 monitors saw repeated event request IDs | PASS |
| E9 | largest history per client over 200 requests: 1 with `ackUpTo`, 64 without (136 cap evictions) | PASS |
| E10 | 20/20 monitor summaries reconciled; all 20 reported callback loss | PASS |

Reproduce with `scripts/test-all.ps1 -Experiments` on Windows or
`scripts/test-all.sh --experiments` on Linux/macOS.
