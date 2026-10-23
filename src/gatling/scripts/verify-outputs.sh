#!/usr/bin/env bash
# Verifies the intake leg's outcomes request-by-request after a Gatling run, and measures
# end-to-end latency (message publish → request COMPLETED in the processed log).
#
# This service makes no downstream POST per hearing: it records the register document in its own
# store. So where the informant register's verifier reads WireMock's request journal, this one reads
# the service's own Postgres — `processed_request` (one row per (source, requestId)) and the
# register rows of `processed_output` (one per recorded hearing). Read-only: nothing is written.
#
# Correlation: every published message has a requestId and a hearingId. A duplicate reuses both; a
# re-share reuses the hearingId under a fresh requestId. The hearing-payload stub and the Redis seed
# echo the hearingId back as hearing.id, so each recorded register row carries it. Every stubbed
# hearing has a surviving youth defendant and matches the stubbed youth court-register
# subscription, so the one correct outcome for every unique request is COMPLETED with
# completion_reason = recorded and one register row of its own. The stubbed payload's sharedTime is
# fixed, so every register of one hearing shares one register key: a hearing published under k
# distinct requests must end with k register rows, exactly one active and k - 1 superseded.
#
# Gates (any failure exits 1):
#   missing      — a unique request with no processed_request row, one still RECEIVED/RETRYING
#                  when the drain ends, or one COMPLETED/recorded with no register row
#   not-recorded — a request that ended anything other than COMPLETED/recorded: a no-op completion
#                  reason means the stubs stopped describing a register, and FAILED is a pipeline
#                  fault under load
#   supersession — a hearing whose register rows do not leave exactly one active: none active, or a
#                  re-share that recorded beside its predecessor instead of superseding it
#   unexpected   — a processed_request or register row in the run's window whose requestId was
#                  never published, or whose hearingId is not the one its request was published with
#   latency      — p95 / p99 publish-to-COMPLETED latency of the gated scenario above
#                  MAX_P95_MS / MAX_P99_MS
#
# A duplicate cannot add a register row (processed_output is UNIQUE on (source, request_id)), so
# duplicate safety is shown by the guard completing the request once, not by a row count. Broker
# duplicate detection is not exercised: the publisher cannot set the JMS MessageId.
#
# missing / not-recorded / supersession gate only requests published by GATED_SIMULATIONS. Every
# other simulation (StressSimulation: it exists to find the breaking point) is report-only — its
# counts are printed and written to the CSVs, tagged "report-only", but never fail the run.
# unexpected stays global: a row that cannot be attributed to any publish is a correctness fault
# whatever the load. The latency gate applies only when GATED_SCENARIO published something.
#
# The run's window starts WINDOW_SLACK_MS before the manifest's first publish; rows older than that
# belong to earlier runs and are ignored, so the store needs no reset between runs. Let the queue
# drain before re-running: a previous run's backlog recorded inside this window is "unexpected".
# The 18:00 generation leg must not run between the first publish and the end of verification:
# do not run across 18:00 Europe/London on a weekday where the YotResultsDistributionService flag is
# ON and generation is enabled. A register assembled into a batch is no longer one supersession can
# reach, so a later re-share records a second active row and this gate fails a correct service.
#
# Latency is measured across two clocks (the Gatling agent and the database server, whose now() is
# the service's single time authority); both are NTP-synced, so skew is expected to be well under
# the thresholds.
#
# Env:
#   MANIFEST              publish manifest CSV written by the simulations (required)
#   DATABASE_URL          reach Postgres directly with a libpq URI (local), e.g.
#                         postgresql://yotresultsdistribution:yotresultsdistribution@localhost:5432/yotresultsdistribution
#   NAMESPACE             or reach it via kubectl exec into a pod with psql on its path (pipeline),
#   POSTGRES_POD_SELECTOR   selected by this label selector (default: the validation stack's
#                           `postgres` release of the postgresql chart, primary pod),
#   POSTGRES_CONTAINER      in this container (default postgresql, that chart's container), as
#                           PGUSER / PGDATABASE (default
#                           yotresultsdistribution). No password is passed: psql authenticates
#                           with the pod's own environment or local socket trust, so no secret
#                           reaches a command line
#   MAX_P95_MS            p95 latency gate for the gated scenario (required)
#   MAX_P99_MS            p99 latency gate for the gated scenario (required)
#   GATED_SIMULATIONS     space-separated simulations whose outcomes are gated
#                         (default CapacitySimulation; others are reported only)
#   GATED_SCENARIO        simulation/scenario whose latency is gated
#                         (default CapacitySimulation/Capacity; others are reported only)
#   REPORT_DIR            where reports are written (default build/nft)
#   DRAIN_TIMEOUT_SECONDS max wait for requests to reach a terminal state (default 600)
#   STALL_SECONDS         stop waiting early if the terminal count stops moving (default 120)
#   WINDOW_SLACK_MS       how far before the first publish the run's window opens (default 60000)
set -euo pipefail

: "${MANIFEST:?MANIFEST must be set}"
: "${MAX_P95_MS:?MAX_P95_MS must be set}"
: "${MAX_P99_MS:?MAX_P99_MS must be set}"
GATED_SIMULATIONS="${GATED_SIMULATIONS:-CapacitySimulation}"
GATED_SCENARIO="${GATED_SCENARIO:-CapacitySimulation/Capacity}"
REPORT_DIR="${REPORT_DIR:-build/nft}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-600}"
STALL_SECONDS="${STALL_SECONDS:-120}"
WINDOW_SLACK_MS="${WINDOW_SLACK_MS:-60000}"
POLL_SECONDS=5

mkdir -p "$REPORT_DIR"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

error() {
  if [ -n "${TF_BUILD:-}" ]; then echo "##vso[task.logissue type=error]$*"; else echo "ERROR: $*"; fi
}

# ── Postgres access (read-only queries; tab-separated, unaligned, no headers) ──
if [ -n "${DATABASE_URL:-}" ]; then
  sql() { psql "$DATABASE_URL" -X -q -At -F $'\t' -v ON_ERROR_STOP=1 -c "$1"; }
else
  : "${NAMESPACE:?NAMESPACE or DATABASE_URL must be set}"
  # The validation stack's Postgres (cpp-aks-deploy helmsman_nle.toml, release `postgres`), so the
  # shared ASB perf pipeline, which exports only NAMESPACE, needs no service-specific wiring
  POSTGRES_POD_SELECTOR="${POSTGRES_POD_SELECTOR:-app.kubernetes.io/instance=postgres,app.kubernetes.io/name=postgresql,app.kubernetes.io/component=primary}"
  POSTGRES_CONTAINER="${POSTGRES_CONTAINER-postgresql}"
  # items[*], not items[0]: an empty match is then an empty string the check below can report,
  # rather than a jsonpath error that set -e turns into an unexplained exit
  PG_POD=$(kubectl get pods -n "$NAMESPACE" -l "$POSTGRES_POD_SELECTOR" \
    -o jsonpath='{.items[*].metadata.name}' | awk '{ print $1 }')
  [ -n "$PG_POD" ] || { error "Postgres pod ($POSTGRES_POD_SELECTOR) not found in $NAMESPACE"; exit 1; }
  CONTAINER_ARGS=()
  [ -n "${POSTGRES_CONTAINER:-}" ] && CONTAINER_ARGS=(-c "$POSTGRES_CONTAINER")
  sql() {
    kubectl exec -i "$PG_POD" -n "$NAMESPACE" ${CONTAINER_ARGS[@]+"${CONTAINER_ARGS[@]}"} -- \
      psql -U "${PGUSER:-yotresultsdistribution}" \
      -d "${PGDATABASE:-yotresultsdistribution}" -X -q -At -F $'\t' -v ON_ERROR_STOP=1 -c "$1"
  }
fi

# ── Expectations ──
[ -s "$MANIFEST" ] || { error "Publish manifest $MANIFEST is missing or empty"; exit 1; }
PUBLISHES=$(( $(wc -l < "$MANIFEST") - 1 ))
tail -n +2 "$MANIFEST" | cut -d, -f3 | sort -u > "$WORK/request-ids"
UNIQUE=$(wc -l < "$WORK/request-ids" | tr -d ' ')
RESHARED=$(tail -n +2 "$MANIFEST" | cut -d, -f3,4 | sort -u | cut -d, -f2 | sort | uniq -d | wc -l | tr -d ' ')
# One awk pass, not sort | head: under pipefail, head closing early SIGPIPEs sort on a big manifest
FIRST_PUBLISH_MS=$(awk -F, 'NR > 1 && (min == "" || $5 + 0 < min) { min = $5 + 0 } END { print min }' "$MANIFEST")
WINDOW_START_MS=$(( FIRST_PUBLISH_MS - WINDOW_SLACK_MS ))
WINDOW="to_timestamp(${WINDOW_START_MS} / 1000.0)"
echo "Published $PUBLISHES messages: $UNIQUE unique requests, each expected COMPLETED/recorded;" \
  "$RESHARED hearings re-shared, each expected to supersede"

# ── Drain: wait for every unique request to reach a terminal state and settle ──
# Counts only this run's requestIds, so rows from other traffic in the window cannot end it early
count_terminal() {
  if sql "SELECT request_id FROM processed_request
           WHERE source = 'RESULTS' AND created_at >= $WINDOW
             AND status IN ('COMPLETED', 'FAILED')" > "$WORK/terminal" 2>/dev/null; then
    awk 'NR == FNR { ids[$1] = 1; next } ($1 in ids) { n++ } END { print n + 0 }' \
      "$WORK/request-ids" "$WORK/terminal"
  else
    echo -1
  fi
}
START=$(date +%s); LAST=-1; LAST_CHANGE=$START; SETTLED=0
while :; do
  NOW=$(date +%s); COUNT=$(count_terminal)
  if [ "$COUNT" != "$LAST" ]; then
    echo "  drain: $COUNT / $UNIQUE requests terminal ($((NOW - START))s)"
    LAST=$COUNT; LAST_CHANGE=$NOW; SETTLED=0
  elif [ "$COUNT" -ge "$UNIQUE" ]; then
    # Reached: hold for 3 polls so late arrivals are caught too
    SETTLED=$((SETTLED + 1)); [ $SETTLED -ge 3 ] && break
  fi
  if [ $((NOW - LAST_CHANGE)) -ge "$STALL_SECONDS" ] && [ "$COUNT" -lt "$UNIQUE" ]; then
    echo "  drain: stalled at $COUNT for ${STALL_SECONDS}s"; break
  fi
  if [ $((NOW - START)) -ge "$DRAIN_TIMEOUT_SECONDS" ]; then
    echo "  drain: timeout after ${DRAIN_TIMEOUT_SECONDS}s at $COUNT"; break
  fi
  sleep $POLL_SECONDS
done

# ── Pull the run's window: every request row, and every register row ──
sql "SELECT request_id, hearing_id, status, coalesce(completion_reason, '-'),
            coalesce(failure_reason, '-'), attempts,
            (extract(epoch FROM updated_at) * 1000)::bigint
       FROM processed_request
      WHERE source = 'RESULTS' AND created_at >= $WINDOW" > "$WORK/requests.tsv"
sql "SELECT hearing_id, request_id, status, (superseded_at IS NOT NULL)
       FROM processed_output
      WHERE source = 'RESULTS' AND created_at >= $WINDOW AND hearing_id IS NOT NULL" \
  > "$WORK/registers.tsv"
echo "Processed log returned $(wc -l < "$WORK/requests.tsv" | tr -d ' ') requests and" \
  "$(wc -l < "$WORK/registers.tsv" | tr -d ' ') register rows"

# ── Correlate ──
awk -F'\t' -v OFS=',' \
    -v manifest="$MANIFEST" -v gatedsims="$GATED_SIMULATIONS" -v registers="$WORK/registers.tsv" \
    -v missing="$REPORT_DIR/missing-outputs.csv" -v notrec="$REPORT_DIR/not-recorded-outputs.csv" \
    -v supersession="$REPORT_DIR/supersession-outputs.csv" \
    -v unexpected="$REPORT_DIR/unexpected-outputs.csv" -v latency="$REPORT_DIR/e2e-latency.csv" '
BEGIN {
  ng = split(gatedsims, gs, " "); for (i = 1; i <= ng; i++) gated[gs[i]] = 1
  FS = ","
  while ((getline line < manifest) > 0) {
    if (line ~ /^simulation,/) continue
    split(line, f, ",")          # simulation,scenario,requestId,hearingId,publishedAtEpochMs
    r = f[3]
    if (!(r in pub)) { nreq[f[4]]++ }
    if (!(r in pub) || f[5] + 0 < pub[r]) {
      pub[r] = f[5] + 0; scen[r] = f[1] "/" f[2]; hear[r] = f[4]
      gate[r] = (f[1] in gated) ? "gated" : "report-only"
    }
    if (f[1] in gated) hgated[f[4]] = 1
  }
  FS = "\t"
  print "kind,id,status,reason" > unexpected
  # Register rows: hearingId, requestId, status, superseded
  while ((getline line < registers) > 0) {
    split(line, reg, "\t")
    if (!(reg[2] in pub)) {
      print "register", reg[2], reg[3], "requestId never published" > unexpected; nunexp++; continue
    }
    if (hear[reg[2]] != reg[1]) {
      print "register", reg[2], reg[3], "hearingId differs from the published one" > unexpected
      nunexp++; continue
    }
    rowof[reg[2]] = 1; rows[reg[1]]++
    if (reg[4] == "t") sup[reg[1]]++; else act[reg[1]]++
  }
  print "simulation/scenario,requestId,hearingId,status,completionReason,failureReason,attempts,gate" > notrec
  print "simulation/scenario,requestId,hearingId,status,gate" > missing
  print "hearingId,requests,registerRows,activeRows,supersededRows,gate" > supersession
}
{
  # Request rows: requestId, hearingId, status, completionReason, failureReason, attempts, updatedAtMs
  r = $1
  if (!(r in pub)) { print "request", r, $3, "requestId never published" > unexpected; nunexp++; next }
  if ($2 != hear[r]) { print "request", r, $3, "hearingId differs from the published one" > unexpected; nunexp++ }
  seen[r] = 1; st[r] = $3; cr[r] = $4; fr[r] = $5; att[r] = $6; done[r] = $7 + 0
}
END {
  print "simulation/scenario,requestId,hearingId,publishedAtEpochMs,completedAtEpochMs,latencyMs" > latency
  for (r in pub) {
    g = gate[r]; h = hear[r]
    if (!(r in seen) || (st[r] != "COMPLETED" && st[r] != "FAILED")) {
      print scen[r], r, h, ((r in seen) ? st[r] : "ABSENT"), g > missing; nmiss[g]++; continue
    }
    if (st[r] != "COMPLETED" || cr[r] != "recorded") {
      print scen[r], r, h, st[r], cr[r], fr[r], att[r], g > notrec; nnot[g]++; continue
    }
    if (!(r in rowof)) {
      print scen[r], r, h, "COMPLETED-WITHOUT-REGISTER", g > missing; nmiss[g]++; continue
    }
    print scen[r], r, h, pub[r], done[r], done[r] - pub[r] > latency
  }
  # One active register per hearing; every other row of it superseded
  for (h in nreq) {
    if (rows[h] + 0 == 0) continue        # its requests are already reported as missing
    g = (h in hgated) ? "gated" : "report-only"
    if (act[h] + 0 != 1) {
      print h, nreq[h], rows[h], act[h] + 0, sup[h] + 0, g > supersession; nsup[g]++
    }
    if (nreq[h] > 1 && g == "gated") nresh++
  }
  printf "%d %d %d %d %d %d %d %d\n", nmiss["gated"] + 0, nnot["gated"] + 0, nsup["gated"] + 0, \
    nunexp + 0, nmiss["report-only"] + 0, nnot["report-only"] + 0, nsup["report-only"] + 0, nresh + 0
}' "$WORK/requests.tsv" > "$WORK/counts"
read -r MISSING NOT_RECORDED SUPERSESSION UNEXPECTED REPORT_MISSING REPORT_NOT_RECORDED \
  REPORT_SUPERSESSION GATED_RESHARED < "$WORK/counts"

# ── Latency percentiles per scenario (nearest-rank) ──
percentiles() {  # stdin: latencies; prints "n p50 p95 p99 max"
  sort -n | awk '{ v[NR] = $1 } END {
    if (NR == 0) { print "0 - - - -"; exit }
    split("50 95 99", p, " ")
    out = NR
    for (i = 1; i <= 3; i++) { r = int((p[i] / 100) * NR); if (r < (p[i] / 100) * NR) r++; if (r < 1) r = 1; out = out " " v[r] }
    print out " " v[NR] }'
}
SUMMARY="$REPORT_DIR/output-verification.txt"
{
  echo "Output verification"
  echo "  published=$PUBLISHES unique=$UNIQUE reshared=$RESHARED"
  echo "  gated ($GATED_SIMULATIONS): missing=$MISSING notRecorded=$NOT_RECORDED supersession=$SUPERSESSION" \
    "(re-shared hearings checked=$GATED_RESHARED)"
  echo "  report-only (other simulations): missing=$REPORT_MISSING notRecorded=$REPORT_NOT_RECORDED supersession=$REPORT_SUPERSESSION"
  echo "  unexpected (all simulations, gated)=$UNEXPECTED"
  echo "  outcomes in window (status/completion_reason):"
  cut -f3,4 "$WORK/requests.tsv" | sort | uniq -c | sed 's/^/    /'
  echo "  register rows in window (status/superseded):"
  cut -f3,4 "$WORK/registers.tsv" | sort | uniq -c | sed 's/^/    /'
  echo "Publish → COMPLETED/recorded latency (ms)"
  printf "  %-40s %8s %8s %8s %8s %8s\n" "simulation/scenario" "n" "p50" "p95" "p99" "max"
  # Scenario names contain spaces ("Stress Ramp"), so iterate lines, not words
  tail -n +2 "$REPORT_DIR/e2e-latency.csv" | cut -d, -f1 | sort -u | while IFS= read -r s; do
    read -r N P50 P95 P99 MAX < <(tail -n +2 "$REPORT_DIR/e2e-latency.csv" | awk -F, -v s="$s" '$1 == s { print $6 }' | percentiles)
    printf "  %-40s %8s %8s %8s %8s %8s\n" "$s" "$N" "$P50" "$P95" "$P99" "$MAX"
  done
} | tee "$SUMMARY"

GATED_PUBLISHED=$(tail -n +2 "$MANIFEST" | awk -F, -v s="$GATED_SCENARIO" '$1 "/" $2 == s' | wc -l | tr -d ' ')
read -r GN _ GP95 GP99 _ < <(tail -n +2 "$REPORT_DIR/e2e-latency.csv" | awk -F, -v s="$GATED_SCENARIO" '$1 == s { print $6 }' | percentiles)

# ── Gates ──
FAILED=0
if [ "$MISSING" -gt 0 ]; then
  error "$MISSING requests never reached a terminal state or recorded no register — see missing-outputs.csv"
  awk -F, 'NR == 1 || $NF == "gated"' "$REPORT_DIR/missing-outputs.csv" | head -6 || true; FAILED=1
fi
if [ "$NOT_RECORDED" -gt 0 ]; then
  error "$NOT_RECORDED requests ended other than COMPLETED/recorded — see not-recorded-outputs.csv"
  awk -F, 'NR == 1 || $NF == "gated"' "$REPORT_DIR/not-recorded-outputs.csv" | head -6 || true; FAILED=1
fi
if [ "$SUPERSESSION" -gt 0 ]; then
  error "$SUPERSESSION hearings without exactly one active register (supersession failure) — see supersession-outputs.csv"
  awk -F, 'NR == 1 || $NF == "gated"' "$REPORT_DIR/supersession-outputs.csv" | head -6 || true; FAILED=1
fi
if [ "$UNEXPECTED" -gt 0 ]; then
  error "$UNEXPECTED rows not attributable to a published request — see unexpected-outputs.csv"
  head -6 "$REPORT_DIR/unexpected-outputs.csv" || true; FAILED=1
fi
if [ $((REPORT_MISSING + REPORT_NOT_RECORDED + REPORT_SUPERSESSION)) -gt 0 ]; then
  echo "NOTE: report-only simulations had $REPORT_MISSING missing, $REPORT_NOT_RECORDED not-recorded and $REPORT_SUPERSESSION supersession outcomes (not gated)"
fi
if [ "$GATED_PUBLISHED" -eq 0 ]; then
  echo "NOTE: $GATED_SCENARIO published nothing in this run — latency gate not applied"
elif [ "$GN" -eq 0 ]; then
  error "No completed requests for gated scenario $GATED_SCENARIO — latency cannot be assessed"; FAILED=1
else
  if [ "$GP95" -gt "$MAX_P95_MS" ]; then error "$GATED_SCENARIO p95 latency ${GP95}ms > ${MAX_P95_MS}ms"; FAILED=1; fi
  if [ "$GP99" -gt "$MAX_P99_MS" ]; then error "$GATED_SCENARIO p99 latency ${GP99}ms > ${MAX_P99_MS}ms"; FAILED=1; fi
fi

if [ $FAILED -eq 0 ]; then
  LATENCY_RESULT="$GATED_SCENARIO p95=${GP95}ms p99=${GP99}ms"
  [ "$GATED_PUBLISHED" -eq 0 ] && LATENCY_RESULT="latency gate not applied"
  echo "Output verification passed: every gated request ($GATED_SIMULATIONS) was recorded once and" \
    "every hearing left one active register ($GATED_RESHARED re-shared); $LATENCY_RESULT"
fi
exit $FAILED
