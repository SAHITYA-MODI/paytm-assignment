# Wallet Service — Write-up

## Data model

**Wallet**: `id` (UUID), `user_id` (caller-supplied, `UNIQUE` — the key `POST /wallets`
get-or-creates on), `balance_paise` (`BIGINT`, `CHECK >= 0`, never floating point),
`created_at`.

**Transfer**: `id` (UUID), `source_wallet_id`, `destination_wallet_id`, `amount_paise`
(`BIGINT`, `CHECK > 0`), `status` (`SUCCEEDED` / `DECLINED` — resolved synchronously
inside one locked transaction, so no `PENDING` state is ever observable),
`decline_reason`, `idempotency_key` (`UNIQUE`), `request_fingerprint` (SHA-256 of the
fields that define "the same logical request"), `created_at`.

`POST /wallets` accepts an optional `initial_balance_paise`, honored only at the moment a
wallet is actually created. With no deposit endpoint in the spec and wallets otherwise
starting at 0, there would be no way to fund one at all; this closes the gap with the
smallest possible addition rather than a new endpoint. It is also the only place money
enters the system — conservation is a property of transfers, and no transfer path can
mint or destroy paise.

## The simplest-correct mechanism, and what we rejected

**Conservation + no-overdraft**: pessimistic locking. Both wallets are fetched with
`SELECT ... FOR UPDATE`, always in a fixed order (numerically smaller wallet id first,
regardless of which is source or destination), inside one transaction. It is the simplest
thing that is fully correct because the correctness argument is one sentence — the row is
locked, so nothing can interleave a read/write on it before commit — and it needs no
retry logic anywhere. The fixed order is what prevents deadlock: two transfers over the
same pair in opposite directions would otherwise each hold one lock and wait on the
other; ordering them means the second simply waits its turn. The lock order is globally
consistent across the whole write path (wallet rows, then the idempotency index), so
there is no cycle to deadlock on.

Rejected: **optimistic locking** (`@Version` + retry) is equally correct but needs
explicit retry-loop code and wastes more work under exactly the contention this is graded
on. An **atomic conditional `UPDATE`** (`... WHERE balance_paise >= amount`) is elegant
for one row, but a transfer touches two rows in one transaction, so the same
locking/deadlock concern reappears — it moves where the mechanism is expressed rather
than removing it. **`SERIALIZABLE` + retry** gives the broadest guarantee with no manual
locking, but failures become Postgres-internal decisions rather than a business rule we
wrote, and retry-storm behavior under load is harder to reason about and to demonstrate.

Two non-obvious things this depends on, both found by running the burst script rather
than by reasoning:

- **`spring.jpa.open-in-view` must be `false`.** With Spring Boot's default, one
  persistence context spans the entire HTTP request, so a wallet read *earlier* in the
  request (the ownership check) is served back from the first-level cache by
  `findByIdForUpdate` — taking the row lock but computing from the **stale pre-lock
  balance**. That breaks both conservation and no-overdraft while every line of locking
  code still looks correct. The ownership check additionally uses a scalar projection, so
  it never materialises a `Wallet` at all.
- **Statement order inside the transaction.** The transfer row is flushed *before* the
  success log and counter, so a request that loses the idempotency race throws out of the
  flush and never reaches them.

**Race-free get-or-create**: a single `INSERT ... ON CONFLICT (user_id) DO NOTHING`
followed by a `SELECT` on `user_id`. Postgres's unique index is the whole mechanism — no
application lock, no retry loop, correct for any number of racing callers.

## Where idempotency lives

In `transfers.idempotency_key`, `UNIQUE` at the database level — the dedup point is the
index, not application memory or a cache, so it survives restarts and would still hold
across multiple app instances. On `POST /transfers` the key is looked up first; if found,
the stored SHA-256 fingerprint of (source, destination, amount) is compared to the
incoming request. Match → the original result is returned unchanged. Mismatch → `409`,
and no second debit.

The remaining race — two identical requests both missing that lookup at the same instant
— is closed by the constraint itself. **The debit, the credit, and the idempotency row
are one transaction**, so the loser's `INSERT` fails and its balance changes roll back
with it; it then re-reads the winner's committed result and returns that. The money
movement and the idempotency record can never disagree, because there is no state in
which one committed without the other.

A declined transfer also consumes its key: it is a real, retrievable outcome, and
retrying a declined key must return that same decline rather than silently becoming a
fresh attempt that might succeed against a since-topped-up balance.

## Consistency vs. availability

Consistency, wherever a balance is involved. A transfer that cannot yet safely determine
its outcome blocks on the lock rather than guessing optimistically; one that would
overdraw is declined outright rather than provisionally allowed. For money, an incorrect
"success" is worse than a slower response or a clean rejection.

What we gave up, concretely: a hot wallet serializes rather than parallelizing, so
throughput on a single contended wallet is bounded by transaction duration; and the
service is unavailable for writes whenever Postgres is (there is no degraded read-only or
queue-and-reconcile mode). `lock_timeout` / `statement_timeout` bound the blocking so
contention queues but a wedged lock still fails cleanly instead of exhausting the pool.
Both are deliberate trades, not oversights.

## Observability

Logs are one JSON object per line, every line carrying a correlation id (supplied by the
caller via `X-Correlation-Id` or generated per request), so one transfer traces as
`wallet_debited → wallet_credited → transfer_succeeded → request_completed`. Those events
are emitted after the transfer row is flushed, so a request that loses an idempotency race
never reports a debit it is about to roll back — log counts, metric counters and committed
database rows all reconcile exactly across a burst run.

Metrics are Prometheus exposition at `/metrics`: request rate, latency (real histogram
buckets, so p99 stays correct across instances) and error rate from Micrometer, plus
`transfers_total{outcome}`, `transfers_idempotent_replays_total` and
`transfers_idempotency_conflicts_total`. `/dashboard` renders them live from a single
self-contained page served by the app itself — chosen over Prometheus + Grafana in compose
because the free tier runs one container, so that stack would exist locally and be absent
at the URL under review. The cost of that choice is no historical storage.

## AI: directed vs. decided

Built with Claude Code as an active collaborator, not on autopilot.

**Directed** (developer chose, AI implemented): every concurrency decision. For the
locking mechanism, the get-or-create fix, and the idempotency mechanism, Claude laid out
multiple concrete options with tradeoffs and code sketches, and the developer picked —
pessimistic locking + `ON CONFLICT` + check-then-act — after being walked through what
each alternative would cost. Also directed: package structure, scope boundaries (adding a
funding path rather than a new endpoint), and the decision to defer deployment until the
correctness work was done.

**Decided** (AI chose, developer accepted): the observability stack's mechanics
(Logstash encoder, MDC for correlation ids, Micrometer meter naming), the bearer-token
scheme, the Flyway migration layout, and the specific probes in the burst script. The
`open-in-view` and timestamp-precision bugs above were found by AI-written probes and
fixed the same way — reviewed, not rubber-stamped.

## Free-tier cost note

₹0. Development and verification run locally on Docker via Colima (app + Postgres), and
the deployment target is a free container host plus a free managed Postgres — no card,
no paid add-on. The only configuration deployment needs is `DATABASE_URL` (parsed and
upgraded to `sslmode=require` automatically) and `AUTH_TOKEN_SECRET`; the image is sized
for a 256–512 MB free instance via `MaxRAMPercentage` and SerialGC.
