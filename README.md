# Banking Transaction Processor

A ledger for retail bank accounts: open accounts, deposit, withdraw, transfer between them, and
query balances and transaction history. Every movement of money produces a timestamped, immutable
ledger entry, and the balance of an account can always be recomputed by replaying its entries.

Java 17, Spring Boot 3.3.4, log4j2. Two services, 317 tests, nine commits — one per phase, each
with a commit message explaining why the phase is shaped the way it is.

---

## Contents

- [Running it](#running-it)
- [The API](#the-api)
- [Error responses](#error-responses)
- [How it is put together](#how-it-is-put-together)
- [Decisions worth arguing about](#decisions-worth-arguing-about)
- [Testing](#testing)
- [Deployment artefacts](#deployment-artefacts)
- [What is verified and what is not](#what-is-verified-and-what-is-not)
- [Known limitations](#known-limitations)
- [On the scope of this submission](#on-the-scope-of-this-submission)
- [Reading the commits](#reading-the-commits)

---

## Running it

This repository holds **two independent Maven projects**, not one aggregator:

| Path | Artefact | Port |
|---|---|---|
| `./pom.xml` | `ledger-service` — accounts, ledger, APIs | 8080 |
| `./compliance-service/pom.xml` | `compliance-service` — transfer screening (Service B) | 8081 |

### Tests

```bash
mvn -B test                                  # ledger-service     — 285 tests, ~2.5 min
mvn -B -f compliance-service/pom.xml test    # compliance-service —  32 tests
```

### Both services locally

```bash
mvn -B package
mvn -B -f compliance-service/pom.xml package

# Terminal 1 — screening must be up first, or transfers are refused with LDG-3002
java -jar compliance-service/target/compliance-service-0.1.0-SNAPSHOT.jar

# Terminal 2
java -jar target/ledger-service-0.1.0-SNAPSHOT.jar
```

Deposits, withdrawals and queries work with only the ledger service running. **Transfers** need the
compliance service, and are refused rather than waved through when it is unreachable — see
[failing closed](#the-service-fails-closed).

### With Docker Compose

```bash
docker compose up --build
```

Configuration is entirely by environment variable, so the compose file and the Helm ConfigMaps set
the same names. Those names are pinned by a test (`EnvironmentVariableBindingTest`) in each service,
because nothing else in the build reads those files:

| Variable | Effect |
|---|---|
| `LOGGING_CONFIG` | `classpath:log4j2-json.xml` for structured ECS logs; omit for human-readable |
| `LEDGER_COMPLIANCE_BASE_URL` | where the ledger service finds screening |
| `LEDGER_COMPLIANCE_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | bounded waits, e.g. `500ms` / `2s` |
| `LEDGER_BASE_CURRENCY` | the single currency this service operates in |
| `COMPLIANCE_SINGLE_TRANSFER_LIMIT` | the screening ceiling |
| `COMPLIANCE_BLOCKEDACCOUNTS_0`, `_1`, … | blocked counterparties (indexed list) |

### On Kubernetes

```bash
helm upgrade --install compliance-service deploy/helm/compliance-service -n ledger --create-namespace
helm upgrade --install ledger-service     deploy/helm/ledger-service     -n ledger
```

Compliance first, deliberately — the reverse order gives a window where every transfer fails.
Neither chart creates an Ingress; see [limitations](#known-limitations) for why.

---

## The API

### Accounts

```
POST   /api/v1/accounts                        open an account          201 + Location
GET    /api/v1/accounts/{id}                   account and balance      200
GET    /api/v1/accounts/{id}/balance           balance only             200
GET    /api/v1/accounts/{id}/transactions      statement, paged         200
```

**Open an account.** `currency` is optional and defaults to the configured base currency;
`openingBalance` is optional and defaults to zero.

```http
POST /api/v1/accounts
{ "accountId": "ACC-1001", "holderName": "A Customer", "openingBalance": 500.00, "currency": "GBP" }
```
```json
{ "accountId": "ACC-1001", "holderName": "A Customer", "balance": 500.00, "currency": "GBP" }
```

An opening balance is not a magic starting number — it is recorded as a real `DEPOSIT` entry
narrated `"Opening balance"`, so replaying the ledger reconciles to the balance. An account opened
at zero therefore has no entries at all.

**Statement.** `?page=` is zero-based and defaults to `0`; `?size=` defaults to 50 and is capped
server-side at 200, so a caller cannot decide how much memory this service allocates. Oldest first.

```json
{
  "accountId": "ACC-1001",
  "transactions": [
    { "entryId": "6b8bfe7a-…", "reference": "997c99bb-…", "accountId": "ACC-1001",
      "type": "DEPOSIT", "direction": "CREDIT", "amount": 500.00, "balanceAfter": 500.00,
      "currency": "GBP", "occurredAt": "2026-09-15T15:54:40.746224Z", "narrative": "Opening balance" }
  ],
  "page": 0, "size": 50, "totalTransactions": 1, "totalPages": 1, "hasNext": false
}
```

An unknown account gives `404`, not an empty page — a mistyped id should not look like a customer
who has never transacted. An out-of-range page *does* give an empty window, because that is a normal
thing for a paging client to do at a boundary.

### Money movement

```
POST   /api/v1/accounts/{id}/deposits          201
POST   /api/v1/accounts/{id}/withdrawals       201
POST   /api/v1/transfers                       201
```

```http
POST /api/v1/accounts/ACC-1001/deposits
{ "amount": 250.50, "currency": "GBP", "narrative": "Salary" }
```

Deposits and withdrawals return the single ledger entry they created, including `balanceAfter` — so
a client learns the new balance without a follow-up read.

```http
POST /api/v1/transfers
{ "sourceAccountId": "ACC-1001", "destinationAccountId": "ACC-1002",
  "amount": 100.00, "currency": "GBP", "narrative": "Rent" }
```

A transfer returns a receipt containing **both** legs. They share one `reference` and one
`occurredAt`, which is what makes the two halves recognisable as one event in an audit:

```json
{
  "reference": "c1e5d1de-…", "sourceAccountId": "ACC-1001", "destinationAccountId": "ACC-1002",
  "amount": 100.00, "currency": "GBP", "occurredAt": "2026-09-15T15:54:41.441947700Z",
  "debit":  { "type": "TRANSFER_OUT", "direction": "DEBIT",  "balanceAfter": 650.50, "…": "…" },
  "credit": { "type": "TRANSFER_IN",  "direction": "CREDIT", "balanceAfter": 100.00, "…": "…" }
}
```

### Screening (Service B)

```
POST   /api/v1/screenings                      200
```

```http
{ "reference": "c1e5d1de-…", "sourceAccountId": "ACC-1001",
  "destinationAccountId": "ACC-1002", "amount": 100.00, "currency": "GBP" }
```
```json
{ "reference": "c1e5d1de-…", "decision": "APPROVED", "reason": null, "screenedAt": "…" }
```

A refusal is **`200 OK` with `"decision": "REJECTED"`**, never a 4xx or 5xx. This matters: a firm
"no" is a successful screening, and returning an error status for it would trip the caller's circuit
breaker and stop it from calling a perfectly healthy dependency. There is a test for exactly that.

### Operational endpoints

`/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/health` (which surfaces
circuit-breaker state), `/actuator/info`, `/actuator/metrics` — on both services.

---

## Error responses

RFC 7807 `application/problem+json`, with three additions: a stable `code`, a `timestamp`, and a
`details` map carrying the facts a client would otherwise have to parse out of prose.

```json
{
  "type": "https://api.natwest.example/problems/insufficient-funds",
  "title": "The account does not hold enough funds for this withdrawal",
  "status": 422,
  "detail": "Account ACC-1001 holds 20200.00 GBP which cannot cover a withdrawal of 999999.00 GBP (short by 979799.00 GBP)",
  "instance": "/api/v1/accounts/ACC-1001/withdrawals",
  "code": "LDG-1003",
  "timestamp": "2026-09-15T15:54:41.320889100Z",
  "details": { "accountId": "ACC-1001", "balance": "20200.00", "requested": "999999.00",
               "shortfall": "979799.00", "currency": "GBP" }
}
```

| Code | Meaning | HTTP |
|---|---|---|
| `LDG-1001` | Amount not positive | 400 |
| `LDG-1002` | Currency mismatch | 400 |
| `LDG-1003` | Insufficient funds | **422** |
| `LDG-1004` | Source and destination are the same account | 400 |
| `LDG-1005` | Malformed account identifier | 400 |
| `LDG-2001` | Account not found | 404 |
| `LDG-2002` | Account already exists | 409 |
| `LDG-2003` | Concurrent modification, safe to retry | 409 |
| `LDG-3001` | Refused by compliance screening | **422** |
| `LDG-3002` | Screening unavailable, retryable | **503** |
| `LDG-3003` | Transfer compensation failed — needs reconciliation | **500** |
| `LDG-4001` | Malformed request | 400 |
| `LDG-9001` | Internal error | 500 |

The bolded ones are choices rather than defaults:

- **422, not 400, for insufficient funds and compliance refusal.** The request was well-formed and
  understood. Nothing about its syntax needs fixing; the account's state or the rules said no. `400`
  would tell a client to go and correct its JSON.
- **503, not 500, for screening unavailable.** It is transient and the same request may well succeed
  in a minute. `503` says "come back", `500` says "we are broken".
- **500, not 503, for compensation failure.** This one is deliberately *not* retryable. It means a
  debit was applied and could not be reversed, so a retry would debit the customer twice. It needs a
  human, and the `details` map says so with `requiresManualReconciliation: true`.

`ErrorCode` itself carries no HTTP knowledge — the mapping lives in `exception/ErrorCodeHttpStatus`
as an exhaustive `switch` with **no `default` arm**, so adding an error code without deciding its
status fails the compile. That is the intended behaviour, not an oversight.

---

## How it is put together

```
com.natwest.ledger
├── controller/    AccountController, TransactionController — thin, no business logic
├── service/       AccountService, TransactionService, TransferSaga + steps — the use cases
├── model/         Money, Account, LedgerEntry, AccountId…    — no framework, no clock, no I/O
├── repository/    AccountRepository, LedgerRepository (ports) + jpa/, memory/ (adapters)
├── client/        ComplianceGateway (port), HttpComplianceGateway (adapter), assessment types
├── dto/           request/response records for the controllers
├── exception/     ErrorCode, LedgerException, ErrorCodeHttpStatus, GlobalExceptionHandler
├── observability/ correlation id
└── config/        properties, clock, HTTP client
```

This is the conventional Spring Boot layout, organised by **technical role** (controller, service,
repository) rather than by **architectural layer** (domain, application, infrastructure) — the
project used the latter through Phase 7 and was restructured to the former afterwards, once it was
clear the codebase had grown large enough that a reviewer's first question would be "where do I find
the X". The trade-off is real and worth naming: `model` now sits next to `exception` rather than
being isolated behind `service`, so nothing stops a `dto` or `client` class from reaching into
`model` directly. Discipline replaces a compiler-enforced boundary. What doesn't change is the
dependency direction in practice — `model` still takes no dependency on Spring, JPA, HTTP or the
current time; time is always passed in as an argument, which is why the model's tests need no clock
stubbing.

`repository` and `client` each hold both sides of a port: the interface (`AccountRepository`,
`ComplianceGateway`) and its adapters (`repository/jpa`, `repository/memory`; the one
`HttpComplianceGateway` implementation) live together rather than in separate top-level packages,
since a Spring Boot codebase this size rarely needs to swap an adapter without also looking at its
contract.

The JPA adapter has entities that are **separate types** from the domain model (`AccountEntity`,
`LedgerEntryEntity` in `repository/jpa`, versus `Account` in `model`). Hibernate needs a no-arg
constructor and setters; `Account` refuses to provide either, because a `setBalance` would let any
caller bypass the overdraft rule. Rather than weaken the domain to suit the ORM, the two are mapped
across.

---

## Decisions worth arguing about

### Money is never a `double`

`Money` wraps `BigDecimal` plus a `Currency`, fixed at two decimal places. It **rejects** a value
with more precision rather than rounding it, because silently rounding a customer's money is worse
than refusing the request — and it refuses arithmetic between different currencies rather than
inventing an exchange rate.

### The overdraft rule exists in exactly one place

`Account` has no `setBalance`. Every debit — withdrawal, transfer out — funnels through one private
`debit()` method that holds the single zero-floor check. A rule implemented once cannot be
implemented inconsistently, and there is nowhere else for a new operation to forget it.

Each operation *returns* the `LedgerEntry` it caused. The balance change and its audit record are
produced by the same call, so it is not possible to move money and forget to write it down.

### Validation is not duplicated between the edge and the domain

Bean validation on the request DTOs covers **shape only** — required fields, string lengths. There is
no `@DecimalMin("0.01")` on `amount`. "An amount must be positive" is a business rule, it lives in
`Money`, and it stays there so it cannot drift out of step with a copy in an annotation. The
consequence is that the rule is exercised end-to-end by the API tests rather than short-circuited at
the edge.

A related distinction: an unknown ISO currency code is rejected at the edge as a malformed request
(`400 LDG-4001`), but a *real* currency that happens to be the wrong one (`USD` against a GBP
account) is passed through so the **domain** raises the mismatch (`LDG-1002`). The edge checks
whether the input is meaningful; the domain decides whether it is allowed.

### The ledger is append-only

`LedgerRepository` has no update and no delete. A correction is a new compensating entry, not an
edit — `TRANSFER_REVERSAL` rather than deleting the `TRANSFER_OUT`. The database columns are mapped
`updatable = false` to back this up at the persistence layer, and transaction types are stored by
**name** rather than ordinal so that inserting a new enum value can never reinterpret history.

Statement ordering uses a generated `entry_sequence` identity column as the key, not `occurred_at`.
Both legs of a transfer deliberately share one instant, so ordering by timestamp would let the
database return them in either order.

### Concurrency: the overdraft rule has to survive a race

The single `debit()` check is necessary but not sufficient — two concurrent withdrawals could each
read the same balance and each conclude there are enough funds. The account row therefore carries a
JPA `@Version`, so the second writer's update fails and surfaces as `409 LDG-2003`.

One subtlety that is easy to get wrong: the repository adapter **re-loads** the entity inside the
transaction before writing to it. Saving a detached copy would silently disable optimistic locking —
the version check would compare a stale value against itself and always pass. There is a persistence
test asserting the version actually increments.

`ConcurrentAccountAccessTest` runs real threads against H2 and asserts *invariants* rather than exact
outcomes, since interleaving is not deterministic: the balance never goes negative, money is
conserved across opposing transfers, the ledger always replays to the stored balance, and funds are
never stranded.

### A transfer is a saga, not a database transaction

A remote screening call sits in the middle of a transfer. Holding a database transaction open across
that call would mean holding row locks and a pooled connection for the duration of someone else's
HTTP latency — and it still would not help, because a database rollback cannot un-send an HTTP
request.

So the transfer is an orchestrated saga with three committed steps and one compensation:

```
requireBothAccountsExist  →  debitSource  →  screen  →  creditDestination
                                  ↓ (refused, unavailable, or credit failed)
                             reverseDebit  →  TRANSFER_REVERSAL entry
```

Each step is `@Transactional(REQUIRES_NEW)` and touches exactly **one** account, which makes the
classic two-row deadlock structurally impossible. They live in a separate bean from the orchestrator
because Spring's proxying makes a self-invoked `@Transactional` method silently inert — the
orchestrator itself is deliberately *not* transactional.

**Why debit before screening, not after?** Reserving the funds first guarantees they are still there
once approval arrives. Screening first would be tidier on the statement, but a concurrent withdrawal
could empty the account between the approval and the debit. The cost is that a refused transfer
leaves a debit *and* a reversal on the statement — arguably a fuller audit trail than no trace at
all. This is visible in a real run:

```
TRANSFER_OUT       6000.00   balanceAfter 14100.00
TRANSFER_REVERSAL  6000.00   balanceAfter 20100.00   "Reversed: compliance REJECTED (SINGLE_TRANSFER_LIMIT_EXCEEDED)"
```

**Compensation retries; the forward path does not.** This asymmetry was *discovered*, not designed.
A concurrency test failed with `LDG-3003` because the compensation lost its own optimistic-lock race
against unrelated traffic on the same account. The reasoning that followed: a forward failure has a
caller who can decide to retry, while compensation has nobody — if it gives up, money is stranded.
It now retries up to five times with a short backoff, and only on `OptimisticLockingFailureException`,
never on a genuine fault. This is safe precisely because each step is `REQUIRES_NEW`, so a failed
attempt has already rolled back completely.

### Resilience4j is wired in code, not annotations

```java
CircuitBreaker.decorateSupplier(breaker, Retry.decorateSupplier(retry, call))
```

That composition is `circuitBreaker(retry(call))`, and the order is the whole point: the breaker
records **one** result per logical request, and when it is open the call fails fast with no backoff
sleep. The annotation-driven equivalent depends on aspect ordering, which is invisible in the source;
get it the wrong way round and retry starts retrying `CallNotPermittedException` while the breaker
counts once per attempt.

Failures are also classified by whose fault they are. A `4xx` from screening means *our* request was
malformed — that is a bug in this service, and it is excluded from both the breaker and the retry, so
one of our defects cannot trip a healthy dependency out of service. A `5xx` or a timeout counts.

#### The service fails closed

`ComplianceGateway.screen` never throws for a transport failure. It returns a third outcome,
`UNAVAILABLE`, alongside `APPROVED` and `REJECTED` — so a caller cannot accidentally treat "we don't
know" as "yes" by forgetting a `catch`. And when screening is unavailable, transfers **stop**
(`503 LDG-3002`) rather than proceeding unscreened. For a compliance control that is the only
defensible default, even though it means an outage in Service B reduces the ledger service's
functionality.

Deposits and withdrawals are unaffected. `docker-compose.yml` intentionally uses
`depends_on: service_started` rather than `service_healthy` so this behaviour is observable rather
than hidden behind a startup gate.

### Correlation ids are sanitised, not trusted

An inbound `X-Correlation-Id` is written verbatim into log lines, which makes it an injection vector:
a newline in the header forges a log entry. Rather than escape it, anything not matching
`[A-Za-z0-9._-]{1,64}` is discarded and replaced with a generated id. Cheaper to reason about, and
there is no legitimate caller it inconveniences.

The id propagates to Service B over an outbound request interceptor, so one identifier follows a
transfer across both processes. A transfer additionally scopes a `transferReference` onto the logging
context for the duration of the saga only.

### Two log4j2 files rather than conditional appenders

`log4j2-spring.xml` (console + rolling file) is the default; `log4j2-json.xml` (ECS JSON) is selected
in containers via `LOGGING_CONFIG`. Spring Boot 3 does not support `<SpringProfile>` arbiters in
log4j2 configuration, and a silently ignored condition in your logging setup fails at the worst
possible moment. Two explicit files, one switch.

---

## Testing

317 tests. `@Nested` classes and `@DisplayName` throughout, so the surefire output reads as a
specification rather than a list of method names.

Some conventions that are load-bearing:

- **Service tests wire the real in-memory adapters, not mocks**, and assert on state re-read from the
  repository. A mock would happily confirm that `save()` was called; re-reading catches the case
  where it was called with the wrong thing — or not at all.
- **Concurrency tests assert invariants**, not outcomes. With ten threads racing there is no single
  correct result, but "the balance never went negative" and "the ledger replays to the balance" hold
  every time.
- **Configuration is tested, not assumed.** `ComplianceResilienceConfigurationTest` asserts the
  production YAML values, because a typo in a resilience4j key yields a silently *default* circuit
  breaker rather than an error. `EnvironmentVariableBindingTest` pins the environment variable names
  the deployment files use. `Log4j2ConfigurationTest` parses both logging configurations into an
  isolated `LoggerContext` so it can assert on them without disturbing the running logger.
- **The HTTP client is tested against a real socket** — a small JDK `HttpServer` that can be scripted
  to return a specific status or stall past the read timeout — not a mocked `RestClient`. Timeouts
  and circuit-breaker behaviour only mean something over a real connection. Every case also asserts
  the request *count*, which is how "an open circuit sends nothing at all" gets proved.

---

## Deployment artefacts

- `Dockerfile`, `compliance-service/Dockerfile` — multi-stage, so no JDK, Maven or dependency cache
  reaches the runtime image. Dependencies resolve before source is copied, so editing a Java file
  does not invalidate the cached dependency layer. Tests run inside the image build, so an image
  cannot be produced from code that does not pass them.
- `docker-compose.yml` — both services, wired together.
- `Jenkinsfile` — both projects built in parallel, images tagged with the commit sha (never `latest`,
  which makes "what is in production?" unanswerable), production gated on a human.
- `deploy/helm/{ledger,compliance}-service/` — one chart per service, matching their independent
  deployability.

A few container-level choices:

- `-XX:MaxRAMPercentage` rather than `-Xmx`, so the heap tracks whatever memory limit Kubernetes
  applies instead of being wrong the moment that limit changes.
- `+ExitOnOutOfMemoryError` — a JVM that has exhausted its heap cannot be trusted to process a
  payment. Better to die and be replaced.
- Exec-form `ENTRYPOINT`, so the JVM is PID 1 and receives `SIGTERM`. Wrapped in a shell it would
  not, and every rolling deploy would stall for the full termination grace period.
- No `HEALTHCHECK`, and no `curl` or `wget` in the image. Kubernetes ignores `HEALTHCHECK` and uses
  the chart's probes; adding a network tool to a banking image to satisfy an instruction the
  orchestrator disregards is a gift to an attacker for nothing in return.

### The two charts differ, and the difference is the point

|  | ledger-service | compliance-service |
|---|---|---|
| Replicas | **1** | 2 |
| Autoscaling | off | on |
| Rollout | `Recreate` | `RollingUpdate`, `maxUnavailable: 0` |
| Disruption budget | none | `minAvailable: 1` |

Almost every difference traces to one fact: the ledger service currently holds state (H2 in memory)
and the compliance service holds none.

A second ledger replica would not share the ledger — it would have its own database and invent a
parallel one, so two customers on different pods could see different balances. That makes
`replicaCount: 1` a *correctness* constraint, not a capacity choice, and it is why the rollout uses
`Recreate` despite the brief outage: a rolling update would briefly run two pods with contradictory
ledgers, and a few seconds of honest downtime beats a few seconds of contradictory answers. A
PodDisruptionBudget would be worse than useless there — `minAvailable: 1` of 1 permits no evictions,
so a node drain would block forever.

The autoscaler and its template are present and switched **off** rather than omitted, so that
enabling them after the move to a shared database is a values change rather than chart work.

Both charts also: point liveness at the *liveness* group only (a probe that includes downstream
dependencies restarts healthy pods whenever a dependency wobbles, turning someone else's outage into
a self-inflicted restart loop); set a CPU request but no CPU limit (throttling during GC or JIT
produces latency spikes that look like application faults); run non-root with a read-only root
filesystem, all capabilities dropped and no service account token; and carry a `checksum/config`
annotation so that editing configuration actually rolls the pods instead of leaving them on the old
values.

---

## What is verified and what is not

Docker, Helm and kubectl are all absent from the machine this was written on, and there was no
Jenkins controller and no cluster. **No image was built, no chart was rendered or linted, and no
pipeline stage ever ran.** Those files are considered and reviewed, not proven, and they should be
read that way.

Rather than leave the whole of that work unevidenced, the parts that do not need a container runtime
were verified by running the two packaged jars and driving them over HTTP:

- Both projects package cleanly; 285 + 32 tests green; the jar names the Dockerfiles glob for match
  exactly one file each.
- ECS/JSON logging to stdout works from `LOGGING_CONFIG` outside any test harness.
- All four probe paths the charts reference return `200 {"status":"UP"}`.
- A full business flow: open, deposit, withdraw, overdraft refused with the shortfall, transfer,
  paged history, 404 on an unknown account — with balances reconciling by ledger replay.
- Environment-variable configuration reaching a real process, proved rather than assumed: with
  `COMPLIANCE_SINGLE_TRANSFER_LIMIT=5000.00`, a 6000.00 transfer was refused. The compiled-in default
  is 10000.00, so it would otherwise have been approved.
- Saga compensation observed live on the statement, as shown [above](#a-transfer-is-a-saga-not-a-database-transaction).
- One correlation id crossing the process boundary and appearing in both services' logs, with
  `transferReference` correctly scoped to the saga's own log lines.

Not verified: image builds and layer caching, the non-root user resolving inside a container,
`readOnlyRootFilesystem` against the JVM's need for `/tmp`, `SIGTERM`-driven graceful shutdown
(Windows offers no clean way to send one), chart rendering, and every Jenkins step.

---

## Known limitations

Named deliberately. Each is a decision with a reason, not something overlooked.

**Storage**
- H2 in memory: data is lost on restart, and it is what forces `replicaCount: 1`. The adapter is
  written against standard JPA, so pointing it at PostgreSQL is a configuration change.
- `ddl-auto: create-drop`. A real deployment needs versioned migrations (Flyway or Liquibase) and
  `ddl-auto: validate`, so a schema change is a reviewed artefact rather than something Hibernate
  infers at boot.
- One internal `findByAccountId` is unpaged. It is not reachable from the API — the endpoint is
  paged — but it would need to go before a real deployment.

**Security**
- **No authentication or authorisation on any endpoint.** This is the largest gap. It is why neither
  Helm chart creates an Ingress: exposing an unauthenticated money-moving API outside the cluster
  would be the worst decision available here. Authentication first, then an Ingress.
- No TLS termination in-process; assumed to be an ingress or mesh concern.

**Correctness under failure**
- **No idempotency keys on money movement.** A client that retries a deposit after a timeout will
  deposit twice. The fix is a caller-supplied key with a uniqueness constraint, and it is the first
  thing I would add.
- Saga state is not persisted. A crash between the debit and the credit leaves a reserved debit that
  no process will unwind, because the orchestrator's state lives only in the JVM. Recovery would need
  the saga journalled and a sweeper for stale ones.
- Compensation retry is bounded and in-process. When it is exhausted, `LDG-3003` says so honestly
  and flags the transfer for manual reconciliation, but there is no automatic reconciliation job.

**Scope**
- Single currency, no FX. `Money` refuses cross-currency arithmetic rather than inventing a rate.
- Two independent Maven builds rather than one aggregator, because the requirement was for `src/` at
  the repository root. The cost is two build commands and a pipeline that has to know about both.
- The two services share no code by design, so the correlation-id filter is duplicated. Deliberate:
  a shared library would couple their release cycles, which is what separating them was for.

---

## On the scope of this submission

The brief asks for simplicity and lists five criteria: test-driven development, clean code, handling
edge cases, object-oriented design, and git history. It also asks for containerisation, a second
service, a circuit breaker and a saga.

Those two things pull against each other, and it is worth saying so rather than pretending they
don't. A distributed saga across two services with compensation is not the simplest way to move money
between two rows in a database — a single transaction is. What the extra machinery buys is a
demonstration of how the failure modes are reasoned about; what it costs is more moving parts than
the acceptance criteria strictly need.

The approach taken was to keep that cost off the domain. `Money`, `Account` and `LedgerEntry` in
`model/` know nothing about sagas, circuit breakers, HTTP or Kubernetes — the distributed concerns
live in `service/TransferSaga` and `client/`. So the core is still small enough to read in one
sitting and test without a framework, and the rest can be evaluated, or ignored, separately.

If only the five criteria were being marked, Phases 1–4 are the submission and Phases 5–8 are
optional extras. They are separate commits for exactly that reason.

---

## Reading the commits

One commit per phase, each message a design note explaining the reasoning and the alternatives
rejected. Read in order they document how the design arrived where it did — including two places
where a test contradicted an assumption and the design changed as a result.

| Commit | Phase |
|---|---|
| `32f20d3` | Model the banking domain, test-first |
| `9507a84` | Application layer — use cases, ports and adapters |
| `ee6956f` | REST API and one place that owns every error response |
| `0ea2d3e` | Persistence, and making the overdraft rule true under concurrency |
| `4c95570` | Service B, and a client that survives it being down |
| `c6c0387` | The transfer becomes a saga, and learns to undo itself |
| `e7ff4fe` | Logs you can actually follow across two services |
| `b0c637d` | Containerisation and delivery — Docker, Jenkins, Helm |
| *this one* | This document |

The two corrections worth looking up: Phase 6, where a concurrency test failed because compensation
lost its own optimistic-lock race and the retry asymmetry was added in response; and Phase 8, where
a test disproved an assumption about how Spring binds hyphenated properties from environment
variables.
