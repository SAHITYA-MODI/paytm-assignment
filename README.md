# Paytm Wallet Service — Assignment

A wallet service with peer-to-peer transfers that stays correct under concurrency and
failure. Built for the Paytm PML R2 exercise.

Design reasoning, rejected alternatives and the AI disclosure are in
[`WRITEUP.md`](./WRITEUP.md).

---

## Getting started

**Prerequisites:** Docker. Nothing else — no JDK, no Maven, no Postgres on your machine.
The image builds and tests itself. (On macOS, [Colima](https://github.com/abiosoft/colima)
works as well as Docker Desktop: `brew install colima docker docker-compose && colima start`.)

### 1. Start the app and the database

```bash
git clone <this repo> && cd paytm-assignment
docker compose up --build
```

One command brings up two containers: Postgres first, then the app once Postgres reports
healthy. The first build takes a few minutes while Maven downloads dependencies and runs
the unit tests; later starts are seconds. Add `-d` to run in the background.

### 2. Confirm it is actually ready

Don't rely on the logs going quiet — ask the app:

```bash
curl -s localhost:8080/actuator/health
```

```json
{"status":"UP","components":{"db":{"status":"UP", ...}}}
```

`status: UP` with `db: UP` means the schema migrated, the connection pool is live, and
the entity mappings validated against the database.

### 3. Run the burst script — this is the proof

```bash
./scripts/burst_test.sh
```

It fires concurrent traffic at a running instance and asserts every graded invariant.
Expect **`11 passed, 0 failed`**:

```
PASS: GET /actuator/health -> UP
PASS: POST /transfers with no bearer token -> 401
PASS: 20 concurrent POST /wallets for a new user -> exactly 1 wallet, 1 balance, all 201
PASS: 15 concurrent identical transfers -> 1 transfer, byte-identical bodies, exactly one 4000-paise debit AND credit
PASS: same idempotency key + different body -> 409, balance untouched
PASS: sequential replay of the same key -> same transfer id
PASS: 20 concurrent overdrawing debits -> exactly 3 SUCCEEDED, 17 DECLINED, balance 0 (never negative), 30000 conserved
PASS: 30 concurrent opposite-direction transfers -> all resolved, conservation held, no negative balance
PASS: debiting another user's wallet -> 403
PASS: self-transfer, negative amount and zero amount all -> 400
PASS: GET /dashboard -> 200, and /metrics exposes latency histogram buckets
```

Point it at any instance, including the deployed one:

```bash
./scripts/burst_test.sh https://<deployed-host>
```

### 4. Watch the dashboard

Open **<http://localhost:8080/dashboard>** — a live view of the domain counters
(transfers created / succeeded / declined / idempotent replays / conflicts), request rate,
p50 and p99 latency, error rate, and a per-endpoint breakdown.

Best viewed while generating load: put the dashboard on one side of the screen and run
`./scripts/burst_test.sh` on the other.

Raw Prometheus exposition, if you would rather scrape it: `curl localhost:8080/metrics`

### 5. Watch the logs

Every line is a JSON object carrying a correlation id.

```bash
docker compose logs -f app                                  # raw stream
docker compose logs -f app | jq -c 'select(.event)'         # domain events only
```

Trace one request end to end by supplying your own correlation id:

```bash
curl -X POST localhost:8080/transfers -H 'X-Correlation-Id: my-trace-1' \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"from":"...","to":"...","amount_paise":1500,"idempotency_key":"k1"}'

docker compose logs --no-log-prefix app | jq -c 'select(.correlationId=="my-trace-1")'
```

```
{"event":"wallet_debited",     "walletId":"f91a…","newBalancePaise":3500}
{"event":"wallet_credited",    "walletId":"04f5…","newBalancePaise":1500}
{"event":"transfer_succeeded", "transferId":"9fb1…","amountPaise":1500}
{"event":"request_completed",  "method":"POST","path":"/transfers","status":201,"durationMs":54}
```

If you omit the header the app generates an id and returns it in the `X-Correlation-Id`
response header, so you can still find your own request afterwards.

### 6. Inspect the database (optional)

Postgres is published on `localhost:5432` (`wallet` / `wallet` / `wallet`), so any client
works — or use `psql` directly:

```bash
docker compose exec postgres psql -U wallet -d wallet

-- conservation and no-overdraft, checked against the data rather than the API
SELECT SUM(balance_paise) AS total, MIN(balance_paise) AS smallest FROM wallets;

-- exactly-once, as a one-line query: must always return zero rows
SELECT idempotency_key, COUNT(*) FROM transfers GROUP BY 1 HAVING COUNT(*) > 1;
```

### Stopping

```bash
docker compose down        # stop, keep the data
docker compose down -v     # stop and delete the database volume — clean slate
```

---

## API

Money is **integer paise** everywhere — request, response, storage, arithmetic. Never a
float, never rupees-as-decimal.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/wallets` | none | Get-or-create a wallet; returns its bearer token |
| GET | `/wallets/{id}` | bearer | Current balance |
| POST | `/transfers` | bearer | Move money; caller must own the source wallet |
| GET | `/transfers/{id}` | bearer | Transfer status |
| GET | `/metrics` | none | Prometheus exposition (also `/actuator/prometheus`) |
| GET | `/dashboard` | none | Live metrics dashboard (single self-contained page) |
| GET | `/actuator/health` | none | Liveness/readiness, incl. database connectivity |

### Auth

`POST /wallets` is the one open endpoint — it is where a caller obtains their token.
Everything else requires `Authorization: Bearer <token>`.

The token is `base64url(userId).base64url(HMAC-SHA256(secret, base64url(userId)))` —
stateless and self-verifying, so identifying a caller costs no database lookup and there
is no token table to keep in sync. It is deliberately *not* a credential system: no
expiry, no rotation, and `POST /wallets` hands out the token for any userId asked for.
The assignment states auth sophistication is not graded and that the token exists to
identify the caller; the HMAC is there so a caller cannot simply assert somebody else's
userId by editing the token. Authorization is enforced where money moves — you cannot
debit a wallet you do not own (`403`).

### Walkthrough

```bash
# 1. Create two wallets. Note the apiToken in each response.
curl -sX POST localhost:8080/wallets -H 'Content-Type: application/json' \
  -d '{"user_id":"alice","initial_balance_paise":50000}'
# {"id":"f91a…","userId":"alice","balancePaise":50000,"createdAt":"…","apiToken":"YWxpY2U.k3T…"}

curl -sX POST localhost:8080/wallets -H 'Content-Type: application/json' \
  -d '{"user_id":"bob"}'

# 2. Transfer, using the assignment's field names.
curl -sX POST localhost:8080/transfers \
  -H "Authorization: Bearer $ALICE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"from":"<alice wallet>","to":"<bob wallet>","amount_paise":1500,"idempotency_key":"demo-1"}'
# {"id":"9fb1…","amountPaise":1500,"status":"SUCCEEDED","declineReason":null,…}

# 3. Replay it — same key, same body. Same transfer, no second debit.
#    Same key with a different amount instead -> 409.
```

Request bodies accept both the assignment's literal spelling (`from`, `to`,
`amount_paise`, `idempotency_key`, `user_id`, `initial_balance_paise`) and the
descriptive camelCase names, so neither spelling is silently parsed into an empty
request.

### Response contract

| Situation | Status | Body |
|---|---|---|
| Transfer applied | `201` | `status: SUCCEEDED` |
| Insufficient funds | `201` | `status: DECLINED`, `declineReason: INSUFFICIENT_FUNDS` |
| Replay, same key + same body | `201` | byte-identical to the original response |
| Same key, different body | `409` | `IDEMPOTENCY_CONFLICT` |
| Missing/invalid bearer token | `401` | `UNAUTHORIZED` |
| Source wallet not owned by caller | `403` | `FORBIDDEN` |
| Unknown wallet or transfer | `404` | `NOT_FOUND` |
| Non-positive amount, missing field | `400` | `VALIDATION_ERROR` |
| Source and destination are the same wallet | `400` | `VALIDATION_ERROR` |
| Unexpected server failure | `500` | `INTERNAL_ERROR` (detail goes to the log, not the caller) |

**A decline is `201`, not an error status.** This is deliberate: a transfer resource
really is created and retrievable at `GET /transfers/{id}` either way, and the outcome is
the `status` field. It also makes "a retry returns the original result" literally true —
a replay returns the same body whatever the original outcome was, with no special case
for declines. The no-overdraft invariant is carried by the balance and the `status`, not
by the HTTP code.

---

## Invariants, and how each is enforced

| Invariant | Mechanism | Probed by |
|---|---|---|
| **Conservation** — total never changes | Both wallets locked `FOR UPDATE` in one transaction, in sorted id order | Probe 4 |
| **No overdraft** — balance never negative | Balance checked *after* the lock; plus a `CHECK (balance_paise >= 0)` backstop in the schema | Probe 3 |
| **Exactly-once** — same key applies once | `UNIQUE (idempotency_key)`, committed in the same transaction as the debit/credit | Probes 2, 2b, 2c |
| **Race-free get-or-create** | `INSERT … ON CONFLICT (user_id) DO NOTHING` | Probe 1 |

Full reasoning, rejected alternatives, and the deadlock argument: [`WRITEUP.md`](./WRITEUP.md).

---

## Observability

**Logs** — one JSON object per line (Logstash encoder), every line carrying a
`correlationId` from the `X-Correlation-Id` request header or generated per request and
echoed back in the response. Domain events are top-level fields, not text inside a
message string:

```bash
docker compose logs -f app | grep '"event"'
```

| Event | Meaning |
|---|---|
| `wallet_created` | get-or-create actually inserted |
| `wallet_debited` / `wallet_credited` | the two halves of a transfer |
| `transfer_succeeded` / `transfer_declined` | terminal outcome, with `reason` |
| `idempotent_replay_hit` | returned an existing transfer, no money moved |
| `idempotency_race_lost` | concurrent duplicate rolled back, re-read the winner |
| `idempotency_conflict` | same key, different body → 409 |
| `transfer_forbidden` / `unauthenticated` | rejected before any money moved |
| `request_completed` | method, path, status, durationMs |
| `wallet_not_found` | a read for a wallet id that doesn't exist |
| `wallet_get_or_create_hit_existing` | get-or-create returned an existing wallet (DEBUG) |
| `unhandled_exception` | an unexpected failure, with stack trace — the only ERROR this app emits |

Every event from one request shares a correlation id, so a single transfer traces as
`wallet_debited → wallet_credited → transfer_succeeded → request_completed`. Those three
are emitted *after* the transfer row is flushed, so a request that loses an idempotency
race never claims a debit it is about to roll back — across a full burst run,
`wallet_debited`, `wallet_credited` and `transfer_succeeded` all total exactly the number
of transfers actually committed. Health and
metrics scrapes get a correlation id but no `request_completed` line — otherwise the
10-second container healthcheck would bury everything else.

Two things are deliberately silenced so the stream stays meaningful. Hibernate's
`show-sql` writes to `System.out`, bypassing Logback entirely, and would interleave raw
multi-line SQL into the JSON. And Hibernate's `SqlExceptionHelper` logs the *expected*
idempotency-key constraint violation at ERROR — 14 ERROR lines per 15-way retry storm for
a routine, handled outcome — so it is off, with genuinely unexpected failures logged
instead by `ApiExceptionHandler` as `unhandled_exception` with a stack trace and
correlation id. The Spring banner is off too, so every line really is JSON:

```bash
docker compose logs --no-log-prefix app | jq -c 'select(.event)'
```

**Dashboard** — `GET /dashboard`. One self-contained HTML page, served by the app, that
polls `/metrics` and renders the domain counters, request rate, p50/p99 latency, error
rate and a per-endpoint breakdown. Open it beside a burst run to watch the numbers move.
Deliberately not Prometheus + Grafana in compose: the deployed free tier runs a single
container, so a dashboard needing two more services would exist locally and be missing at
the URL a reviewer is actually given. The tradeoff is no historical storage — rates are
computed from deltas between polls while the page is open.

**Metrics** — `GET /metrics` (Prometheus exposition; `/actuator/prometheus` serves the
same). Request rate, latency and error rate come from Micrometer's
`http_server_requests_seconds` histogram — real buckets, so p99 stays correct if this ever
runs on more than one instance. Domain counters:

```
transfers_total{outcome="succeeded"}
transfers_total{outcome="declined_insufficient_funds"}
transfers_idempotent_replays_total
transfers_idempotency_conflicts_total
```

Transfers *created* is `sum(transfers_total)` — a declined transfer is still a created
transfer with a row and an id. (Not named `transfers_created_total`: the Prometheus client
treats a `_created` infix as reserved and silently strips it — see)

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` | — | Provider-style `postgres://user:pass@host:port/db`. Parsed into JDBC form; `sslmode=require` added if absent. Takes precedence over the `DB_*` vars. |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `wallet` / `wallet` / `wallet` | Local/compose path. |
| `AUTH_TOKEN_SECRET` | a dev placeholder | **Set this in any deployed environment.** Changing it invalidates all issued tokens. |
| `PORT` | `8080` | Host-assigned port; the container healthcheck follows it. |
| `DB_POOL_SIZE` | `15` | The real concurrency ceiling for a lock-holding workload. Keep under the provider's connection cap. |

Schema is owned by Flyway (`V1__init_schema.sql`, `V2__money_invariants.sql`) and applied
at startup; Hibernate runs `ddl-auto: validate` and only checks that the entities still
match.

## Design decisions

_Each entry captures the decision and the reasoning, not just the outcome._

1. **Domain model & API contract.** `Wallet` / `Transfer` entities, four endpoints.
   Package-by-layer (`controller/`, `service/`, `repository/`, `entity/`, `dto/`,
   `exception/`, `auth/`, `web/`, `metrics/`, `util/`), replacing an initial
   package-by-feature layout.

2. **Concurrency control — pessimistic locking (Option A1).** `TransferExecutor.execute()`
   fetches both wallets `FOR UPDATE` in a fixed order (smaller wallet id first), so two
   transfers between the same pair in opposite directions cannot deadlock. Rejected
   alternatives are in `WRITEUP.md`.

3. **Get-or-create — `ON CONFLICT DO NOTHING` (Option B1).** One atomic statement;
   Postgres's unique index is the entire mechanism, no locking or retry code.

4. **Idempotency — check-then-act with the unique constraint as backstop (Option C1).**
   Lookup first; on a genuine race the loser's whole transaction rolls back and it
   re-reads the winner. Same-key/different-body → `409` via a SHA-256
   `RequestFingerprint`.

5. **`spring.jpa.open-in-view: false` — a correctness requirement, not tuning.** With the
   Spring Boot default, a wallet read earlier in the request is served back from the
   first-level cache by `findByIdForUpdate`, so the lock is taken but the balance is the
   stale pre-lock value — silently breaking conservation *and* no-overdraft. Found by the
   overdraft probe, not by reading the code. The ownership check also uses a scalar
   projection so it never materialises a `Wallet` at all.

6. **Timestamps truncated to microseconds.** Postgres `TIMESTAMPTZ` stores microseconds;
   `Instant.now()` carries nanoseconds. Untruncated, the request that performed a
   transfer serialised a different `createdAt` than every replay read back from the
   database — so replays were *not* byte-identical. Caught by asserting on whole response
   bodies rather than just ids.

7. **Persistence — Flyway.** `ddl-auto` moved from `update` to `validate`; V2 pushes the
   money invariants into the schema as `CHECK` constraints.

8. **Observability.** Correlation id in MDC + Logstash JSON encoder; Micrometer/Actuator
   with a `/metrics` alias at the path the assignment names.

9. **Auth — stateless HMAC bearer token.** Deliberately minimal; enforced where money
   moves. Rationale under "Auth" above.

10. **Testing.** `scripts/burst_test.sh` is the primary suite — it verifies the graded
    invariants against a *running* instance, which is the only place concurrency bugs are
    observable. `mvn test` adds 14 fast unit tests for the pure logic the burst script
    can only see as opaque (token forgery, fingerprint collisions, `DATABASE_URL`
    parsing); these run inside the Docker build.

11. **Containerisation.** Multi-stage build (Maven+JDK stage discarded), non-root `wallet`
    user, `HEALTHCHECK` against `/actuator/health` on `$PORT`, `exec`-form entrypoint so
    the JVM is PID 1 and receives `SIGTERM` directly.

12. **Log hygiene as a correctness concern.** The debit/credit/succeeded events are
    emitted after the transfer row is flushed, not next to the balance mutation — the
    natural placement made a 15-way retry storm report 15 debits for one real debit,
    because the losers logged before rolling back.

13. **A self-contained dashboard instead of Grafana.** The assignment allows `/metrics`
    *or* a dashboard; shipping the dashboard inside the app means it is live at the
    deployed URL, where a two-extra-container compose stack would not be. Trade: no
    historical storage.

---

## Running from an IDE

The Docker path above needs nothing installed. To run the app from IntelliJ instead —
useful for debugging — keep Postgres in Docker and run only the app in the IDE:

```bash
docker compose up -d postgres   # database only
docker compose stop app         # free port 8080 for the IDE
```

Then open **`pom.xml`** as a project (not the folder — selecting the POM is what makes
IntelliJ configure it as a Maven project) and run `WalletServiceApplication`.

No environment variables are needed: the defaults in `application.yml` already point at
`localhost:5432` with the same credentials Compose uses, and `AUTH_TOKEN_SECRET` has a
development default.

The burst script, dashboard and `psql` commands above all work unchanged. Add
`--spring.jpa.show-sql=true` to the run configuration when you need to see the SQL — it
is off by default because it writes to `System.out`, bypassing the JSON log encoder.

Building from the command line instead:

```bash
mvn spring-boot:run      # requires JDK 21+ and Maven on PATH
mvn test                 # 14 unit tests, no database needed
```
