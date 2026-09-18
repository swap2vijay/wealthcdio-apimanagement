# Endpoint smoke test results

Manual, black-box verification of the running service. This complements, not replaces, the 217
automated JUnit tests - those exercise the code directly; this hits real HTTP endpoints on the
service actually running locally, the way an external client would.

## How this was produced

1. The jar was built and run locally on port 8080 with no profile overrides, so H2 in-memory is in
   effect exactly as it ships.
2. [`run-endpoint-smoke-test.ps1`](run-endpoint-smoke-test.ps1) driven against the running service
   with `Invoke-WebRequest`. Every case asserts an HTTP status and, where one applies, the specific
   `LDG-xxxx` error code in the response body - not status alone, since two different failures
   (e.g. insufficient funds vs a same-account transfer) can share a status code.
3. Account ids are suffixed with the current time (`ACC-5<HHmmss>` / `ACC-6<HHmmss>`), so the script
   can be re-run against a service that has already accumulated state without colliding with
   accounts from a previous run.

Re-run it yourself with the service up:
```powershell
cd C:\Learnnig\ledger-service
powershell -ExecutionPolicy Bypass -File test-results\run-endpoint-smoke-test.ps1
```
Output is written to `test-results\endpoint-smoke-test-normal.txt` / `.csv`.

## Files

| File | What it shows |
|---|---|
| `endpoint-smoke-test-normal.txt` / `.csv` | **22/22 passed.** |
| `run-endpoint-smoke-test.ps1` | The script itself, for reproducing the run. |

## Coverage

- Accounts: open (with and without an opening balance), duplicate id -> `409 LDG-2002`, get by id,
  get unknown id -> `404 LDG-2001`, balance.
- Money movement: deposit, zero-amount deposit -> `400 LDG-1001`, withdrawal, overdraft ->
  `422 LDG-1003`, wrong-but-real currency -> `400 LDG-1002`, unrecognised currency code ->
  `400 LDG-4001`.
- Transfers: a normal approved transfer (both ledger legs share one reference and one timestamp,
  and both balances settle correctly), same-account transfer -> `400 LDG-1004`, transfer to an
  unknown destination -> `404 LDG-2001`.
- Statements: default paging, an explicit small page, an out-of-range page (empty window, not an
  error), and confirmation that an account opened at zero carries no opening ledger entry (only the
  transfer-in it later received).
- `/actuator/health/liveness` and `/actuator/health/readiness`.
