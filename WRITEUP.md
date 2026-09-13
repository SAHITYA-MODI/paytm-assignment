# Wallet Service — Write-up

**In one paragraph.** Money is integer paise end to end. Conservation and no-overdraft
are enforced by row locks taken in a fixed order, so opposite-direction transfers between
the same two wallets cannot deadlock. Exactly-once lives in a `UNIQUE` constraint
committed in the *same transaction* as the debit and credit, so the money and the
idempotency record can never disagree. Get-or-create is one atomic `INSERT … ON CONFLICT`.
All four invariants are verified against a **running** instance by `scripts/burst_test.sh`
— 11 probes, committed to the repo, runnable against any URL including the deployed one.
Two bugs that this class of code hides well were found *by those probes* and fixed; both
are described below, because how they were caught matters more than that they were.

---

## Data model

**Wallet**: `id` (UUID), `user_id` (caller-supplied, `UNIQUE` — the get-or-create key),
`balance_paise` (`BIGINT`, `CHECK >= 0`, never floating point), `created_at`.

**Transfer**: `id` (UUID), `source_wallet_id`, `destination_wallet_id`, `amount_paise`
(`BIGINT`, `CHECK > 0`), `status` (`SUCCEEDED` / `DECLINED`, resolved synchronously inside
one locked transaction so no `PENDING` state is ever observable), `decline_reason`,
`idempotency_key` (`UNIQUE`), `request_fingerprint` (SHA-256 of the fields that define
"the same logical request"), `created_at`.

Both money invariants are additionally `CHECK` constraints in the schema. The application
already enforces them; the constraints are the backstop, so a future refactor or a manual
`UPDATE` cannot leave the system holding impossible money.

`POST /wallets` accepts an optional `initial_balance_paise`, honoured only when the wallet
is actually created. The spec has no deposit endpoint and wallets otherwise start at zero,
so there would be no way to fund one; this closes the gap without adding an endpoint. It
is also the only place money enters the system — no transfer path can mint or destroy
paise.

## The simplest-correct mechanism, and what was rejected

**Pessimistic locking.** Both wallets are fetched `SELECT … FOR UPDATE` inside one
transaction, always in a fixed order — numerically smaller wallet id first, regardless of
which is source or destination.

It is the simplest thing that is fully correct because the correctness argument is one
sentence: the row is locked, so nothing can interleave a read-then-write on it before
commit. No retry logic anywhere. The fixed order is what removes deadlock — two transfers
over the same pair in opposite directions would otherwise each hold one lock and wait for
the other; ordered, the second simply waits its turn. That order is consistent across the
whole write path (wallet rows, then the idempotency index), so there is no cycle at all.

**Rejected.** *Optimistic locking* (`@Version` + retry) is equally correct but needs
explicit retry-loop code and wastes the most work under exactly the contention being
graded. An *atomic conditional `UPDATE`* (`… WHERE balance_paise >= amount`) is elegant for
one row, but a transfer touches two rows in one transaction, so the same locking and
deadlock questions reappear — it relocates the mechanism rather than removing it.
*`SERIALIZABLE` + retry* gives the broadest guarantee with no manual locking, but failures
become Postgres-internal decisions rather than a business rule I wrote, and retry-storm
behaviour under load is harder to reason about and harder to demonstrate.

**Race-free get-or-create**: a single `INSERT … ON CONFLICT (user_id) DO NOTHING` followed
by a `SELECT`. Postgres's unique index is the entire mechanism — no application lock, no
retry loop, correct for any number of racing callers.

## Where idempotency lives

In `transfers.idempotency_key`, `UNIQUE` at the database level — the dedup point is the
index, not application memory or a cache, so it survives restarts and would still hold
across multiple instances. On `POST /transfers` the key is looked up first; if found, the
stored SHA-256 fingerprint of (source, destination, amount) is compared against the
incoming request. Match → the original result, unchanged. Mismatch → `409`, no second
debit.

The remaining race — two identical requests both missing that lookup in the same instant —
is closed by the constraint itself. **The debit, the credit and the idempotency row are one
transaction**, so the loser's `INSERT` fails and its balance changes roll back with it; it
then re-reads the winner's committed result and returns that. There is no state in which
one committed without the other.

A declined transfer also consumes its key: it is a real, retrievable outcome, and retrying
it must return that same decline rather than quietly becoming a fresh attempt that might
succeed against a since-topped-up balance.

## Consistency vs. availability

Consistency, wherever a balance is involved. A transfer that cannot yet safely determine
its outcome blocks on the lock rather than guessing; one that would overdraw is declined
outright rather than provisionally allowed. For money, an incorrect "success" is worse than
a slower response or a clean rejection.

What that costs, concretely: a hot wallet serialises rather than parallelises, so
throughput on one contended wallet is bounded by transaction duration; and writes are
unavailable whenever Postgres is, because there is no degraded read-only or
queue-and-reconcile mode. Because the design blocks, the blocking is bounded — `lock_timeout`
and `statement_timeout` let contention queue normally while a genuinely wedged lock fails
cleanly instead of draining the connection pool. These are chosen trades, not oversights.

## Observability

One JSON object per line, every line carrying a correlation id (the caller's
`X-Correlation-Id` or one generated and returned), so a single transfer reads as
`wallet_debited → wallet_credited → transfer_succeeded → request_completed`.

Those events are emitted *after* the transfer row is flushed. Emitting them next to the
balance mutation — the natural placement — made a 15-way retry storm report 15 debits for
one real debit, because the losers logged before rolling back. After the fix, **log counts,
metric counters and committed database rows reconcile exactly** across a full burst run.
Hibernate's `SqlExceptionHelper` is silenced for the same reason: it reported the expected,
handled idempotency violation as an ERROR, 14 times per storm.

Metrics are Prometheus exposition at `/metrics` — request rate, latency as real histogram
buckets (so p99 stays correct across instances) and error rate from Micrometer, plus
`transfers_total{outcome}`, `transfers_idempotent_replays_total` and
`transfers_idempotency_conflicts_total`. `/dashboard` renders them live from one
self-contained page served by the app. That was chosen over Prometheus + Grafana in
compose deliberately: the free tier runs a single container, so that stack would exist on
my laptop and be absent at the URL actually under review. The cost is no historical
storage.

## AI: directed vs. decided

Claude Code was an active collaborator, not an autopilot.

**Directed** — I chose, it implemented: every concurrency decision. For the locking
mechanism, the get-or-create fix and the idempotency strategy, it laid out multiple
concrete options with tradeoffs and code sketches, and I picked — pessimistic locking,
`ON CONFLICT`, check-then-act — after being walked through what each alternative would
cost. Also mine: the package structure, the scope boundary on funding wallets without
adding an endpoint, the decision to finish correctness before deploying, and the call to
keep the codebase lean rather than let it grow.

**Decided** — it chose, I reviewed and accepted: the observability mechanics (Logstash
encoder, MDC correlation ids, Micrometer meter naming), the bearer-token scheme, the
Flyway layout, and the specific probes in the burst script. I read the diagnosis in each case before accepting the
fix; the `open-in-view` explanation in particular I checked against the observed
asymmetry (all 20 credits applied, only 2 debits) before believing it.

## Free-tier cost note

**₹0.** Development and verification run locally on Docker (app + Postgres). Deployment
targets a free container host plus free managed Postgres — no card, no paid add-on;
`fly.toml` and `render.yaml` are both committed. Deployment needs exactly two settings:
`DATABASE_URL` (parsed from the provider's `postgres://` form into JDBC and upgraded to
`sslmode=require` automatically) and `AUTH_TOKEN_SECRET`. The image is sized for a
256–512 MB free instance via `MaxRAMPercentage` and SerialGC, runs as a non-root user, and
is built so the JVM is PID 1 and receives `SIGTERM` directly — a redeploy drains rather
than being killed mid-transfer.