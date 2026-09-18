# Banking Transaction Processor

A ledger for retail bank accounts: open accounts, deposit, withdraw, transfer between them, and
query balances and transaction history. Every movement of money produces a timestamped, immutable
ledger entry, and the balance of an account can always be recomputed by replaying its entries.

Java 17, Spring Boot 3.3.4, log4j2. 217 tests, one commit per phase, each with a message explaining
why the phase is shaped the way it is.

---

## Contents

- [Running it](#running-it)
- [The API](#the-api)
- [Error responses](#error-responses)
- [How it is put together](#how-it-is-put-together)
- [Decisions worth arguing about](#decisions-worth-arguing-about)
- [Testing](#testing)
- [Known limitations](#known-limitations)

---

## Running it

```bash
mvn -B test      # 217 tests, ~2 min
mvn -B package
java -jar target/ledger-service-0.1.0-SNAPSHOT.jar
```

The service listens on port 8080, backed by an in-memory H2 database, and needs no external setup.

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

An opening balance is not a magic starting number - it is recorded as a real `DEPOSIT` entry
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
      "currency": "GBP", "occurredAt": "2026-04-20T10:15:30Z", "narrative": "Opening balance" }
  ],
  "page": 0, "size": 50, "totalTransactions": 1, "totalPages": 1, "hasNext": false
}
```

An unknown account gives `404`, not an empty page - a mistyped id should not look like a customer
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

Deposits and withdrawals return the single ledger entry they created, including `balanceAfter` - so
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
  "amount": 100.00, "currency": "GBP", "occurredAt": "2026-04-20T10:15:31Z",
  "debit":  { "type": "TRANSFER_OUT", "direction": "DEBIT",  "balanceAfter": 650.50, "…": "…" },
  "credit": { "type": "TRANSFER_IN",  "direction": "CREDIT", "balanceAfter": 100.00, "…": "…" }
}
```

### Operational endpoints

`/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/health`, `/actuator/info`,
`/actuator/metrics`.

---

## Error responses

RFC 7807 `application/problem+json`, with three additions: a stable `code`, a `timestamp`, and a
`details` map carrying the facts a client would otherwise have to parse out of prose.

```json
{
  "type": "https://api.natwest.example/problems/insufficient-funds",
  "title": "The account does not hold enough funds for this withdrawal",
  "status": 422,
  "detail": "Account ACC-1001 holds 100.00 GBP which cannot cover a withdrawal of 130.00 GBP (short by 30.00 GBP)",
  "instance": "/api/v1/accounts/ACC-1001/withdrawals",
  "code": "LDG-1003",
  "timestamp": "2026-04-20T10:15:31Z",
  "details": { "accountId": "ACC-1001", "balance": "100.00", "requested": "130.00",
               "shortfall": "30.00", "currency": "GBP" }
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
| `LDG-4001` | Malformed request | 400 |
| `LDG-9001` | Internal error | 500 |

The bolded one is a choice rather than a default: **422, not 400, for insufficient funds.** The
request was well-formed and understood. Nothing about its syntax needs fixing; the account's state
forbade it. `400` would tell a client to go and correct its JSON.

`ErrorCode` itself carries no HTTP knowledge - the mapping lives in `exception/ErrorCodeHttpStatus`
as an exhaustive `switch` with **no `default` arm**, so adding an error code without deciding its
status fails the compile. That is the intended behaviour, not an oversight.

---

## How it is put together

```
com.natwest.ledger
├── controller/    AccountController, TransactionController - thin, no business logic
├── service/       AccountService, TransactionService - the use cases
├── model/         Money, Account, LedgerEntry, AccountId…    - no framework, no clock, no I/O
├── repository/    AccountRepository, LedgerRepository (ports) + jpa/, memory/ (adapters)
├── dto/           request/response records for the controllers
├── exception/     ErrorCode, LedgerException, ErrorCodeHttpStatus, GlobalExceptionHandler
├── observability/ correlation id
└── config/        properties, clock
```

Organised by **technical role** (controller, service, repository) rather than by architectural
layer, which is the conventional Spring Boot layout and the one most reviewers expect to open
first. `model` takes no dependency on Spring, JPA, HTTP or the current time; time is always passed
in as an argument, which is why the model's tests need no clock stubbing.

`repository` holds both sides of each port: the interface (`AccountRepository`) and its adapters
(`repository/jpa`, `repository/memory`) live together rather than in separate top-level packages.

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
than refusing the request - and it refuses arithmetic between different currencies rather than
inventing an exchange rate.

### The overdraft rule exists in exactly one place

`Account` has no `setBalance`. Every debit - withdrawal, transfer out - funnels through one private
`debit()` method that holds the single zero-floor check. A rule implemented once cannot be
implemented inconsistently, and there is nowhere else for a new operation to forget it.

Each operation *returns* the `LedgerEntry` it caused. The balance change and its audit record are
produced by the same call, so it is not possible to move money and forget to write it down.

### Validation is not duplicated between the edge and the domain

Bean validation on the request DTOs covers **shape only** - required fields, string lengths. There
is no `@DecimalMin("0.01")` on `amount`. "An amount must be positive" is a business rule, it lives
in `Money`, and it stays there so it cannot drift out of step with a copy in an annotation. The
consequence is that the rule is exercised end-to-end by the API tests rather than short-circuited at
the edge.

A related distinction: an unknown ISO currency code is rejected at the edge as a malformed request
(`400 LDG-4001`), but a *real* currency that happens to be the wrong one (`USD` against a GBP
account) is passed through so the **domain** raises the mismatch (`LDG-1002`). The edge checks
whether the input is meaningful; the domain decides whether it is allowed.

### The ledger is append-only

`LedgerRepository` has no update and no delete. The database columns are mapped `updatable = false`
to back this up at the persistence layer, and transaction types are stored by **name** rather than
ordinal so that inserting a new enum value can never reinterpret history.

Statement ordering uses a generated `entry_sequence` identity column as the key, not `occurred_at`.
Both legs of a transfer deliberately share one instant, so ordering by timestamp would let the
database return them in either order.

### Concurrency: the overdraft rule has to survive a race

The single `debit()` check is necessary but not sufficient - two concurrent withdrawals could each
read the same balance and each conclude there are enough funds. The account row therefore carries a
JPA `@Version`, so the second writer's update fails and surfaces as `409 LDG-2003`.

One subtlety that is easy to get wrong: the repository adapter **re-loads** the entity inside the
transaction before writing to it. Saving a detached copy would silently disable optimistic locking -
the version check would compare a stale value against itself and always pass. There is a persistence
test asserting the version actually increments.

`ConcurrentAccountAccessTest` runs real threads against H2 and asserts *invariants* rather than exact
outcomes, since interleaving is not deterministic: the balance never goes negative, money is
conserved across opposing transfers, and the ledger always replays to the stored balance.

### A transfer is one transaction, not a saga

A transfer touches two accounts, but nothing outside this database needs to coordinate with it, so
one `@Transactional` method gives "both balance updates and both ledger entries commit together, or
neither does" for free. That is the simplest thing that satisfies the requirement.

Two ordering decisions inside it matter:

- **The accounts are loaded in a canonical order** (by identifier), not the order the caller named
  them. Two opposing simultaneous transfers - A to B and B to A - would otherwise acquire their row
  locks in opposite sequences and deadlock. Sorting first means every transfer in the system takes
  locks in the same direction, so the cycle cannot form.
- **The source is debited before the destination is credited.** If funds are short, the failure
  happens before anything has been credited, so there is nothing to undo.

### Correlation ids are sanitised, not trusted

An inbound `X-Correlation-Id` is written verbatim into log lines, which makes it an injection vector:
a newline in the header forges a log entry. Rather than escape it, anything not matching
`[A-Za-z0-9._-]{1,64}` is discarded and replaced with a generated id. Cheaper to reason about, and
there is no legitimate caller it inconveniences.

### Two log4j2 files rather than conditional appenders

`log4j2-spring.xml` (console + rolling file) is the default; `log4j2-json.xml` (ECS JSON) is
selected via `LOGGING_CONFIG=classpath:log4j2-json.xml` for structured log shipping. Spring Boot 3
does not support `<SpringProfile>` arbiters in log4j2 configuration, and a silently ignored
condition in your logging setup fails at the worst possible moment. Two explicit files, one switch.

---

## Testing

217 tests. `@Nested` classes and `@DisplayName` throughout, so the surefire output reads as a
specification rather than a list of method names.

Some conventions that are load-bearing:

- **Service tests wire the real in-memory adapters, not mocks**, and assert on state re-read from
  the repository. A mock would happily confirm that `save()` was called; re-reading catches the case
  where it was called with the wrong thing - or not at all.
- **Concurrency tests assert invariants**, not outcomes. With ten threads racing there is no single
  correct result, but "the balance never went negative" and "the ledger replays to the balance" hold
  every time.
- **Configuration is tested, not assumed.** `EnvironmentVariableBindingTest` pins the environment
  variable name (`LEDGER_BASE_CURRENCY`) that a deployment would rely on. `Log4j2ConfigurationTest`
  parses both logging configurations into an isolated `LoggerContext` so it can assert on them
  without disturbing the running logger.

---

## Known limitations

Named deliberately. Each is a decision with a reason, not something overlooked.

**Storage**
- H2 in memory: data is lost on restart. The adapter is written against standard JPA, so pointing
  it at PostgreSQL is a configuration change.
- `ddl-auto: create-drop`. A real deployment needs versioned migrations (Flyway or Liquibase) and
  `ddl-auto: validate`, so a schema change is a reviewed artefact rather than something Hibernate
  infers at boot.
- One internal `findByAccountId` is unpaged. It is not reachable from the API - the endpoint is
  paged - but it would need to go before a real deployment.

**Security**
- **No authentication or authorisation on any endpoint.** This is the largest gap, and it is the
  first thing that would need to change before this API is exposed outside a trusted network.
- No TLS termination in-process; assumed to be an ingress or mesh concern in a real deployment.

**Correctness under failure**
- **No idempotency keys on money movement.** A client that retries a deposit after a timeout will
  deposit twice. The fix is a caller-supplied key with a uniqueness constraint, and it is the first
  thing I would add.

**Scope**
- Single currency, no FX. `Money` refuses cross-currency arithmetic rather than inventing a rate.
- No overdraft facility, no interest, no fees - the brief asks for accounts, deposits, withdrawals,
  transfers, validation, a ledger and query APIs, and that is what this builds. Simple, on purpose.
