# Invocation-semantics experiment results

Run on 2026-09-14 with server seeds 0-19 and 20 trials per probabilistic scenario.

| Experiment | Observation | Result |
|---|---:|---|
| E1 | 40/40 correct without loss | PASS |
| E2 | 7/20 at-least-once trials over-reserved | PASS |
| E3 | 20/20 at-most-once trials correct | PASS |
| E4 | 8/20 at-least-once fares over-adjusted | PASS |
| E5 | 20/20 at-most-once fares correct | PASS |
| E6 | 20/20 repeated `SET_FARE` results correct | PASS |
| E7 | 40/40 request-loss trials correct | PASS |
| E8 | 6/20 monitors saw repeated event request IDs | PASS |
| E9 | `ackUpTo` history evictions observed over 200 requests | PASS |
| E10 | 20/20 totals reconciled; all 20 runs lost callbacks | PASS |

Reproduce with `scripts/test-all.ps1 -Experiments` on Windows or
`scripts/test-all.sh --experiments` on Linux/macOS.
