# Endpoint smoke test results

Manual, black-box verification of both running services, done after the package restructure
(controller/service/model/dto/repository/exception/config/client) to confirm the rename changed no
behaviour. This complements, not replaces, the 285 + 32 automated JUnit tests — those exercise the
code directly; these hit real HTTP endpoints on services actually running locally, the way an
external client would.

## How this was produced

1. Both jars built with `mvn -B package` (ledger-service and compliance-service).
2. `ledger-service` started on port 8080, `compliance-service` on port 8081, both with
   `java -jar target\<artifact>.jar` — no profile overrides, so H2 in-memory and the default
   resilience4j configuration are in effect exactly as they ship.
3. [`run-endpoint-smoke-test.ps1`](run-endpoint-smoke-test.ps1) driven against both services with
   `Invoke-WebRequest`. Every case asserts an HTTP status and, where one applies, the specific
   `LDG-xxxx` error code in the response body — not status alone, since two different failures
   (e.g. insufficient funds vs compliance rejection) can share a status code.

Re-run it yourself with both services up:
```powershell
cd C:\Learnnig\ledger-service
powershell -ExecutionPolicy Bypass -File test-results\run-endpoint-smoke-test.ps1
```
Output is written back to `.git\smoke-results.txt`/`.csv` at the repo root (git-ignored); copy them
here if you want to keep a new run.

## Files

| File | What it shows |
|---|---|
| `endpoint-smoke-test-normal.txt` / `.csv` | Both services healthy. **29/29 passed.** |
| `endpoint-smoke-test-compliance-outage.txt` / `.csv` | `compliance-service` deliberately stopped mid-run. **25/31 passed** — see below, the failures here are the point. |
| `run-endpoint-smoke-test.ps1` | The script itself, for reproducing either run. |

## Normal run — 29/29

Covers every endpoint on both services:

- Accounts: open (with and without an opening balance), duplicate id → `409 LDG-2002`, get by id,
  get unknown id → `404 LDG-2001`, balance.
- Money movement: deposit, zero-amount deposit → `400 LDG-1001`, withdrawal, overdraft →
  `422 LDG-1003`, wrong-but-real currency → `400 LDG-1002`, unrecognised currency code →
  `400 LDG-4001`.
- Transfers: a normal approved transfer (both ledger legs share one reference and one timestamp),
  same-account transfer → `400 LDG-1004`, a transfer right at the compliance limit →
  `422 LDG-3001`, transfer to an unknown destination → `404 LDG-2001`.
- Statements: default paging, an explicit small page, an out-of-range page (empty window, not an
  error), and confirmation that an account opened at zero carries no opening ledger entry.
- `compliance-service` called directly: an approval and a limit-triggered refusal
  (`200 decision=REJECTED`, not a 4xx/5xx — refusing a transfer is a successful screening).
- `/actuator/health/liveness` and `/actuator/health/readiness` on both services.

## Compliance outage run — the fail-closed and saga-compensation behaviour

`compliance-service` was stopped, the suite run again, then it was restarted. Every case needing it
fails as expected (`503 LDG-3002`, or a transport error against the closed port) — that is the
correct behaviour under an outage, not a defect, which is why this file is kept separately from the
normal run rather than averaged into one pass count.

What it actually demonstrates, read from the transaction history captured mid-outage
(`endpoint-smoke-test-compliance-outage.txt`, case #20):

```
TRANSFER_OUT       100.00    balanceAfter  1100.00
TRANSFER_REVERSAL  100.00    balanceAfter  1200.00   "Reversed: compliance UNAVAILABLE (SCREENING_CALL_FAILED)"
TRANSFER_OUT     10000.00    balanceAfter 11200.00
TRANSFER_REVERSAL 10000.00   balanceAfter 21200.00   "Reversed: compliance UNAVAILABLE (SCREENING_CALL_FAILED)"
```

Two transfers were attempted while screening was down. Both reserved the debit, discovered
screening was unreachable, and reversed themselves — restoring the account to its exact
pre-transfer balance. Case #31 makes the same point as an explicit assertion: the source account's
balance before and after the refused transfer is identical (`21200.00` both times).

The service failed **closed**: it refused to move money rather than guessing, which is the
documented design in the README ("The service fails closed").

