#!/usr/bin/env bash
#
# One-command burst test reproducing the four graded invariants against a running
# wallet-service instance (see README.md "Invariants").
#
# Usage:
#   ./scripts/burst_test.sh [BASE_URL]
#   BASE_URL=https://your-deployed-app ./scripts/burst_test.sh
#
# Requires: bash, curl, python3 (used only for JSON parsing/assertions).
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }
json_field() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }
# uuidgen where available (macOS, util-linux), otherwise the kernel's UUID source.
# Deliberately not `date +%s%N`: BSD date has no %N and silently emits a literal "N",
# which collapses the uniqueness this whole script depends on.
if command -v uuidgen >/dev/null 2>&1; then
  uniq_id() { uuidgen | tr '[:upper:]' '[:lower:]'; }
else
  uniq_id() { cat /proc/sys/kernel/random/uuid; }
fi

# Creates a wallet and echoes "<wallet id> <bearer token>".
# POST /wallets is the one unauthenticated endpoint; it mints the token used everywhere else.
new_wallet() {
  curl -sS -X POST "$BASE_URL/wallets" -H 'Content-Type: application/json' \
    -d "{\"userId\":\"$1\",\"initialBalancePaise\":$2}" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['id'], d['apiToken'])"
}

# POST a transfer, body to <outfile>. Args: <token> <outfile> <from> <to> <amount> <key>
# Body uses the assignment's literal field spelling (from / to / amount_paise /
# idempotency_key) precisely so this script also proves those names are accepted.
post_transfer() {
  curl -sS -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -o "$2" \
    -d "{\"from\":\"$3\",\"to\":\"$4\",\"amount_paise\":$5,\"idempotency_key\":\"$6\"}" \
    >/dev/null 2>&1 || true
}

balance_of() {
  curl -sS "$BASE_URL/wallets/$1" -H "Authorization: Bearer $2" | json_field "['balancePaise']"
}

echo "== wallet-service burst test against $BASE_URL =="

# ---------------------------------------------------------------------------
# Probe 0: the service is actually up, and auth is actually enforced.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 0: health + auth enforcement ---"
HEALTH=$(curl -sS "$BASE_URL/actuator/health" | json_field "['status']")
if [ "$HEALTH" = "UP" ]; then
  pass "GET /actuator/health -> UP"
else
  fail "GET /actuator/health -> $HEALTH (expected UP)"
fi

UNAUTH_CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/transfers" \
  -H 'Content-Type: application/json' -d '{"from":"x","to":"y","amount_paise":1,"idempotency_key":"k"}')
if [ "$UNAUTH_CODE" = "401" ]; then
  pass "POST /transfers with no bearer token -> 401"
else
  fail "POST /transfers with no bearer token -> HTTP $UNAUTH_CODE (expected 401)"
fi

# ---------------------------------------------------------------------------
# Probe 1: race-free get-or-create.
# Fire N simultaneous POST /wallets for one brand-new user; expect exactly one wallet.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 1: concurrent get-or-create (race-free wallet creation) ---"
NEW_USER="burst-getcreate-$(uniq_id)"
TMPDIR1=$(mktemp -d)
for i in $(seq 1 20); do
  curl -sS -X POST "$BASE_URL/wallets" -H 'Content-Type: application/json' \
    -d "{\"userId\":\"$NEW_USER\",\"initialBalancePaise\":5000}" \
    -o "$TMPDIR1/r_$i.json" -w '%{http_code}\n' > "$TMPDIR1/c_$i" 2>/dev/null &
done
wait

read -r DISTINCT_WALLETS DISTINCT_BALANCES <<<"$(python3 -c "
import json, glob
ids, balances = set(), set()
for f in glob.glob('$TMPDIR1/r_*.json'):
    d = json.load(open(f))
    ids.add(d['id']); balances.add(d['balancePaise'])
print(len(ids), len(balances))
")"
NON_201=$(cat "$TMPDIR1"/c_* | grep -cv '^201$' || true)

if [ "$DISTINCT_WALLETS" -eq 1 ] && [ "$DISTINCT_BALANCES" -eq 1 ] && [ "$NON_201" -eq 0 ]; then
  pass "20 concurrent POST /wallets for a new user -> exactly 1 wallet, 1 balance, all 201"
else
  fail "20 concurrent POST /wallets -> $DISTINCT_WALLETS distinct wallets, $DISTINCT_BALANCES distinct balances, $NON_201 non-201 responses (expected 1, 1, 0)"
fi
rm -rf "$TMPDIR1"

# ---------------------------------------------------------------------------
# Probe 2: exactly-once transfer under a concurrent retry storm.
# Fire the same transfer (same key) K times concurrently;
# expect exactly one debit/credit and identical responses.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 2: idempotent retry storm (exactly-once transfer) ---"
read -r SRC_ID SRC_TOKEN <<<"$(new_wallet "burst-storm-src-$(uniq_id)" 100000)"
read -r DST_ID DST_TOKEN <<<"$(new_wallet "burst-storm-dst-$(uniq_id)" 0)"
STORM_KEY="burst-storm-key-$(uniq_id)"

TMPDIR2=$(mktemp -d)
for i in $(seq 1 15); do
  post_transfer "$SRC_TOKEN" "$TMPDIR2/r_$i.json" "$SRC_ID" "$DST_ID" 4000 "$STORM_KEY" &
done
wait

read -r DISTINCT_TRANSFERS DISTINCT_STATUSES DISTINCT_BODIES <<<"$(python3 -c "
import json, glob
ids, statuses, bodies = set(), set(), set()
for f in glob.glob('$TMPDIR2/r_*.json'):
    d = json.load(open(f))
    ids.add(d['id']); statuses.add(d['status'])
    bodies.add(json.dumps(d, sort_keys=True))
print(len(ids), len(statuses), len(bodies))
")"
SRC_BALANCE_AFTER=$(balance_of "$SRC_ID" "$SRC_TOKEN")
DST_BALANCE_AFTER=$(balance_of "$DST_ID" "$DST_TOKEN")

if [ "$DISTINCT_TRANSFERS" -eq 1 ] && [ "$DISTINCT_STATUSES" -eq 1 ] && [ "$DISTINCT_BODIES" -eq 1 ] \
   && [ "$SRC_BALANCE_AFTER" -eq 96000 ] && [ "$DST_BALANCE_AFTER" -eq 4000 ]; then
  pass "15 concurrent identical transfers -> 1 transfer, byte-identical bodies, exactly one 4000-paise debit AND credit (src=$SRC_BALANCE_AFTER dst=$DST_BALANCE_AFTER)"
else
  fail "15 concurrent identical transfers -> $DISTINCT_TRANSFERS ids, $DISTINCT_STATUSES statuses, $DISTINCT_BODIES distinct bodies, src=$SRC_BALANCE_AFTER dst=$DST_BALANCE_AFTER (expected 1, 1, 1, 96000, 4000)"
fi

echo "--- Probe 2b: same idempotency key, different body -> expect 409 and no second debit ---"
CONFLICT_HTTP_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer $SRC_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"from\":\"$SRC_ID\",\"to\":\"$DST_ID\",\"amount_paise\":9999,\"idempotency_key\":\"$STORM_KEY\"}")
BALANCE_AFTER_CONFLICT=$(balance_of "$SRC_ID" "$SRC_TOKEN")
if [ "$CONFLICT_HTTP_CODE" = "409" ] && [ "$BALANCE_AFTER_CONFLICT" -eq 96000 ]; then
  pass "same idempotency key + different body -> 409, balance untouched ($BALANCE_AFTER_CONFLICT)"
else
  fail "same idempotency key + different body -> HTTP $CONFLICT_HTTP_CODE, balance=$BALANCE_AFTER_CONFLICT (expected 409, 96000)"
fi

echo "--- Probe 2c: replaying the original key still returns the original result ---"
REPLAY_ID=$(curl -sS -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer $SRC_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"from\":\"$SRC_ID\",\"to\":\"$DST_ID\",\"amount_paise\":4000,\"idempotency_key\":\"$STORM_KEY\"}" \
  | json_field "['id']")
ORIGINAL_ID=$(python3 -c "
import json, glob
print(json.load(open(sorted(glob.glob('$TMPDIR2/r_*.json'))[0]))['id'])
")
if [ "$REPLAY_ID" = "$ORIGINAL_ID" ]; then
  pass "sequential replay of the same key -> same transfer id ($REPLAY_ID)"
else
  fail "sequential replay returned $REPLAY_ID, original was $ORIGINAL_ID"
fi
rm -rf "$TMPDIR2"

# ---------------------------------------------------------------------------
# Probe 3: no overdraft under contention.
# Fire far more concurrent debits than the wallet can fund. Exactly the affordable
# number must succeed, every other must be cleanly DECLINED, and the balance must
# land on 0 without ever going negative.
#
# This is the probe that actually distinguishes a correct implementation: Probe 4's
# amounts are all affordable, so it would pass even against a service with no balance
# check at all.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 3: no overdraft (30000 paise, 20 concurrent debits of 10000) ---"
read -r POOR_ID POOR_TOKEN <<<"$(new_wallet "burst-overdraft-src-$(uniq_id)" 30000)"
read -r SINK_ID SINK_TOKEN <<<"$(new_wallet "burst-overdraft-dst-$(uniq_id)" 0)"

TMPDIR3=$(mktemp -d)
for i in $(seq 1 20); do
  post_transfer "$POOR_TOKEN" "$TMPDIR3/r_$i.json" "$POOR_ID" "$SINK_ID" 10000 "burst-od-$i-$(uniq_id)" &
done
wait

read -r SUCCEEDED DECLINED OTHER <<<"$(python3 -c "
import json, glob
s = d = o = 0
for f in glob.glob('$TMPDIR3/r_*.json'):
    try:
        st = json.load(open(f)).get('status')
    except Exception:
        o += 1; continue
    if st == 'SUCCEEDED': s += 1
    elif st == 'DECLINED': d += 1
    else: o += 1
print(s, d, o)
")"
POOR_AFTER=$(balance_of "$POOR_ID" "$POOR_TOKEN")
SINK_AFTER=$(balance_of "$SINK_ID" "$SINK_TOKEN")

if [ "$SUCCEEDED" -eq 3 ] && [ "$DECLINED" -eq 17 ] && [ "$OTHER" -eq 0 ] \
   && [ "$POOR_AFTER" -eq 0 ] && [ "$SINK_AFTER" -eq 30000 ]; then
  pass "20 concurrent overdrawing debits -> exactly 3 SUCCEEDED, 17 DECLINED, balance 0 (never negative), 30000 conserved"
else
  fail "overdraft probe -> $SUCCEEDED succeeded, $DECLINED declined, $OTHER malformed, src=$POOR_AFTER dst=$SINK_AFTER (expected 3, 17, 0, 0, 30000)"
fi
rm -rf "$TMPDIR3"

# ---------------------------------------------------------------------------
# Probe 4: conservation under contention.
# Many concurrent transfers among a small set of wallets, including both
# directions (A->B and B->A) at once; total balance must be unchanged and no
# balance may go negative.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 4: conservation under contention (opposite-direction transfers) ---"
read -r A_ID A_TOKEN <<<"$(new_wallet "burst-cons-a-$(uniq_id)" 50000)"
read -r B_ID B_TOKEN <<<"$(new_wallet "burst-cons-b-$(uniq_id)" 50000)"
TOTAL_BEFORE=100000

TMPDIR4=$(mktemp -d)
for i in $(seq 1 15); do
  post_transfer "$A_TOKEN" "$TMPDIR4/ab_$i.json" "$A_ID" "$B_ID" 3000 "burst-ab-$i-$(uniq_id)" &
  post_transfer "$B_TOKEN" "$TMPDIR4/ba_$i.json" "$B_ID" "$A_ID" 2500 "burst-ba-$i-$(uniq_id)" &
done
wait

NON_TERMINAL=$(python3 -c "
import json, glob
bad = 0
for f in glob.glob('$TMPDIR4/*.json'):
    try:
        if json.load(open(f)).get('status') not in ('SUCCEEDED', 'DECLINED'): bad += 1
    except Exception:
        bad += 1
print(bad)
")
A_BALANCE_AFTER=$(balance_of "$A_ID" "$A_TOKEN")
B_BALANCE_AFTER=$(balance_of "$B_ID" "$B_TOKEN")
TOTAL_AFTER=$((A_BALANCE_AFTER + B_BALANCE_AFTER))

if [ "$TOTAL_AFTER" -eq "$TOTAL_BEFORE" ] && [ "$A_BALANCE_AFTER" -ge 0 ] && [ "$B_BALANCE_AFTER" -ge 0 ] \
   && [ "$NON_TERMINAL" -eq 0 ]; then
  pass "30 concurrent opposite-direction transfers -> all resolved, conservation held (total=$TOTAL_AFTER), no negative balance (A=$A_BALANCE_AFTER, B=$B_BALANCE_AFTER)"
else
  fail "conservation: total before=$TOTAL_BEFORE after=$TOTAL_AFTER (A=$A_BALANCE_AFTER, B=$B_BALANCE_AFTER), $NON_TERMINAL non-terminal/errored responses"
fi
rm -rf "$TMPDIR4"

# ---------------------------------------------------------------------------
# Probe 5: a caller cannot debit a wallet that isn't theirs.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 5: cross-user debit is rejected ---"
FORBIDDEN_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer $B_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"from\":\"$A_ID\",\"to\":\"$B_ID\",\"amount_paise\":1000,\"idempotency_key\":\"burst-forbidden-$(uniq_id)\"}")
if [ "$FORBIDDEN_CODE" = "403" ]; then
  pass "debiting another user's wallet -> 403"
else
  fail "debiting another user's wallet -> HTTP $FORBIDDEN_CODE (expected 403)"
fi

# ---------------------------------------------------------------------------
# Probe 6: input validation. A self-transfer is a no-op that would otherwise
# report SUCCEEDED having moved nothing; a non-positive amount must not be a
# backdoor to crediting yourself.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 6: invalid requests are rejected ---"
bad_request() {
  curl -sS -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $A_TOKEN" -H 'Content-Type: application/json' -d "$1"
}
SELF_CODE=$(bad_request "{\"from\":\"$A_ID\",\"to\":\"$A_ID\",\"amount_paise\":100,\"idempotency_key\":\"burst-self-$(uniq_id)\"}")
NEG_CODE=$(bad_request "{\"from\":\"$A_ID\",\"to\":\"$B_ID\",\"amount_paise\":-5000,\"idempotency_key\":\"burst-neg-$(uniq_id)\"}")
ZERO_CODE=$(bad_request "{\"from\":\"$A_ID\",\"to\":\"$B_ID\",\"amount_paise\":0,\"idempotency_key\":\"burst-zero-$(uniq_id)\"}")

if [ "$SELF_CODE" = "400" ] && [ "$NEG_CODE" = "400" ] && [ "$ZERO_CODE" = "400" ]; then
  pass "self-transfer, negative amount and zero amount all -> 400"
else
  fail "validation: self=$SELF_CODE negative=$NEG_CODE zero=$ZERO_CODE (expected 400, 400, 400)"
fi

# ---------------------------------------------------------------------------
# Probe 7: the observability surfaces the assignment requires are reachable.
# ---------------------------------------------------------------------------
echo
echo "--- Probe 7: observability endpoints ---"
DASH_CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/dashboard")
METRICS_HAS_LATENCY=$(curl -sS "$BASE_URL/metrics" | grep -c '^http_server_requests_seconds_bucket' || true)
if [ "$DASH_CODE" = "200" ] && [ "$METRICS_HAS_LATENCY" -gt 0 ]; then
  pass "GET /dashboard -> 200, and /metrics exposes latency histogram buckets ($METRICS_HAS_LATENCY series)"
else
  fail "observability: /dashboard -> $DASH_CODE, latency bucket series=$METRICS_HAS_LATENCY (expected 200, >0)"
fi

# ---------------------------------------------------------------------------
# Domain counters, after all of the above.
# ---------------------------------------------------------------------------
echo
echo "--- Domain counters (GET /metrics) ---"
curl -sS "$BASE_URL/metrics" | grep -E '^transfers_' | grep -v '^#' || echo "(no transfers_* series found)"

echo
echo "== Summary: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]