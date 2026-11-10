#!/usr/bin/env bash
# Verifies informant-register outputs request-by-request after a Gatling run, and
# measures end-to-end latency (message publish → Results POST received by WireMock).
#
# Correlation: every published message has a unique hearingId (duplicates reuse
# their original's). The hearing-payload stub echoes it back as hearing.id, so each
# Results POST body carries {hearingId, prosecutionAuthorityId}. The expected
# authorities are read from the stub itself (wiremock/mappings/hearing-payload.json).
#
# Gates (any failure exits 1):
#   missing     — a unique request × expected authority with no POST
#   extra       — a request × authority with more than one POST (dedupe failure,
#                 including duplicates that produced output)
#   unexpected  — a POST whose hearingId was never published, whose authority is
#                 not expected, or whose body is not parseable
#   latency     — p95 / p99 publish-to-POST latency of the gated scenario above
#                 MAX_P95_MS / MAX_P99_MS. A request's latency is the time until
#                 its LAST expected authority was POSTed (i.e. request complete).
#
# missing / extra gate only requests published by GATED_SIMULATIONS. Every other
# simulation (StressSimulation: it exists to find the breaking point) is report-only —
# its counts are printed and written to the CSVs, tagged "report-only", but never fail
# the run. unexpected stays global: a POST that cannot be attributed to any publish is
# a correctness fault whatever the load. The latency gate applies only when
# GATED_SCENARIO published something, so a stress-only run is not failed for lacking it.
#
# Latency is measured across two clocks (Gatling agent pod → WireMock pod); both are
# NTP-synced cluster nodes, so skew is expected to be well under the thresholds.
#
# Env:
#   MANIFEST              publish manifest CSV written by the simulations (required)
#   NAMESPACE             reach WireMock via kubectl exec (pipeline), or
#   WIREMOCK_ADMIN_URL    reach WireMock directly, e.g. http://localhost:8080 (local)
#   MAX_P95_MS            p95 latency gate for the gated scenario (required)
#   MAX_P99_MS            p99 latency gate for the gated scenario (required)
#   GATED_SIMULATIONS     space-separated simulations whose missing/extra outputs are
#                         gated (default CapacitySimulation; others are reported only)
#   GATED_SCENARIO        simulation/scenario whose latency is gated
#                         (default CapacitySimulation/Capacity; others are reported only)
#   REPORT_DIR            where reports are written (default build/nft)
#   DRAIN_TIMEOUT_SECONDS max wait for outputs to arrive (default 600)
#   STALL_SECONDS         stop waiting early if the POST count stops moving (default 120)
set -euo pipefail

: "${MANIFEST:?MANIFEST must be set}"
: "${MAX_P95_MS:?MAX_P95_MS must be set}"
: "${MAX_P99_MS:?MAX_P99_MS must be set}"
GATED_SIMULATIONS="${GATED_SIMULATIONS:-CapacitySimulation}"
GATED_SCENARIO="${GATED_SCENARIO:-CapacitySimulation/Capacity}"
REPORT_DIR="${REPORT_DIR:-build/nft}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-600}"
STALL_SECONDS="${STALL_SECONDS:-120}"
POLL_SECONDS=5

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HEARING_STUB="$SCRIPT_DIR/../resources/wiremock/mappings/hearing-payload.json"
POST_PATH=$(jq -r '.request.urlPath' "$SCRIPT_DIR/../resources/wiremock/mappings/informant-register.json")

mkdir -p "$REPORT_DIR"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

error() {
  if [ -n "${TF_BUILD:-}" ]; then echo "##vso[task.logissue type=error]$*"; else echo "ERROR: $*"; fi
}

# ── WireMock admin access ──
if [ -n "${WIREMOCK_ADMIN_URL:-}" ]; then
  wiremock() { curl -sf "$@"; }
  ADMIN="$WIREMOCK_ADMIN_URL"
else
  : "${NAMESPACE:?NAMESPACE or WIREMOCK_ADMIN_URL must be set}"
  WIREMOCK_POD=$(kubectl get pods -n "$NAMESPACE" \
    -l app.kubernetes.io/name=wildfly-app,app.kubernetes.io/instance=zwiremock \
    -o jsonpath='{.items[0].metadata.name}')
  [ -n "$WIREMOCK_POD" ] || { error "WireMock pod not found in $NAMESPACE"; exit 1; }
  wiremock() { kubectl exec -i "$WIREMOCK_POD" -n "$NAMESPACE" -c wildfly-app -- curl -sf "$@"; }
  ADMIN="http://localhost:8080"
fi
POST_CRITERIA=$(jq -cn --arg p "$POST_PATH" '{method: "POST", urlPath: $p}')

# ── Expectations ──
[ -s "$MANIFEST" ] || { error "Publish manifest $MANIFEST is missing or empty"; exit 1; }
jq -r '.response.jsonBody.hearing.prosecutionCases[].prosecutionCaseIdentifier.prosecutionAuthorityId' \
  "$HEARING_STUB" | sort -u > "$WORK/authorities"
AUTHORITIES=$(wc -l < "$WORK/authorities" | tr -d ' ')
PUBLISHES=$(( $(wc -l < "$MANIFEST") - 1 ))
UNIQUE=$(tail -n +2 "$MANIFEST" | cut -d, -f4 | sort -u | wc -l | tr -d ' ')
EXPECTED=$(( UNIQUE * AUTHORITIES ))
echo "Published $PUBLISHES messages: $UNIQUE unique requests × $AUTHORITIES authorities = $EXPECTED expected POSTs"

# ── Drain: wait for the POST count to reach the expectation and settle ──
count_posts() {
  wiremock -X POST "$ADMIN/__admin/requests/count" -H "Content-Type: application/json" \
    -d "$POST_CRITERIA" | jq -r '.count' 2>/dev/null || echo -1
}
START=$(date +%s); LAST=-1; LAST_CHANGE=$START; SETTLED=0
while :; do
  NOW=$(date +%s); COUNT=$(count_posts)
  if [ "$COUNT" != "$LAST" ]; then
    echo "  drain: $COUNT / $EXPECTED POSTs ($((NOW - START))s)"
    LAST=$COUNT; LAST_CHANGE=$NOW; SETTLED=0
  elif [ "$COUNT" -ge "$EXPECTED" ]; then
    # Reached: hold for 3 polls so late extras are caught too
    SETTLED=$((SETTLED + 1)); [ $SETTLED -ge 3 ] && break
  fi
  if [ $((NOW - LAST_CHANGE)) -ge "$STALL_SECONDS" ] && [ "$COUNT" -lt "$EXPECTED" ]; then
    echo "  drain: stalled at $COUNT for ${STALL_SECONDS}s"; break
  fi
  if [ $((NOW - START)) -ge "$DRAIN_TIMEOUT_SECONDS" ]; then
    echo "  drain: timeout after ${DRAIN_TIMEOUT_SECONDS}s at $COUNT"; break
  fi
  sleep $POLL_SECONDS
done

# ── Pull every Results POST: hearingId, authority, received time ──
wiremock -X POST "$ADMIN/__admin/requests/find" -H "Content-Type: application/json" \
  -d "$POST_CRITERIA" > "$WORK/journal.json"
# --stream: a full run's journal is ~50k POSTs with complete register bodies, so
# emit one request at a time instead of loading the whole document into memory
jq -rn --stream 'fromstream(2 | truncate_stream(inputs | select(.[0][0] == "requests")))
  | ((try (.body | fromjson) catch null) // {}) as $b
  | [($b.hearingId // "UNPARSEABLE"), ($b.prosecutionAuthorityId // "UNPARSEABLE"), (.loggedDate | tostring)]
  | @tsv' "$WORK/journal.json" > "$WORK/posts.tsv"
echo "Journal returned $(wc -l < "$WORK/posts.tsv" | tr -d ' ') POSTs"

# ── Correlate ──
awk -F'\t' -v OFS=',' \
    -v authfile="$WORK/authorities" -v manifest="$MANIFEST" -v gatedsims="$GATED_SIMULATIONS" \
    -v missing="$REPORT_DIR/missing-outputs.csv" -v extra="$REPORT_DIR/extra-outputs.csv" \
    -v unexpected="$REPORT_DIR/unexpected-outputs.csv" -v latency="$REPORT_DIR/e2e-latency.csv" '
BEGIN {
  while ((getline a < authfile) > 0) { auth[a] = 1; nauth++ }
  ng = split(gatedsims, gs, " "); for (i = 1; i <= ng; i++) gated[gs[i]] = 1
  FS = ","
  while ((getline line < manifest) > 0) {
    if (line ~ /^simulation,/) continue
    split(line, f, ",")          # simulation,scenario,requestId,hearingId,publishedAtEpochMs
    h = f[4]
    if (!(h in pub) || f[5] + 0 < pub[h]) {
      pub[h] = f[5] + 0; scen[h] = f[1] "/" f[2]; req[h] = f[3]
      gate[h] = (f[1] in gated) ? "gated" : "report-only"
    }
  }
  FS = "\t"
  print "simulation/scenario,requestId,hearingId,authorityId,posts,gate" > extra
  print "hearingId,authorityId,receivedAtEpochMs,reason" > unexpected
}
{
  h = $1; a = $2; t = $3 + 0
  if (h == "UNPARSEABLE") { print h, a, t, "body not parseable as JSON" > unexpected; nunexp++; next }
  if (!(h in pub))      { print h, a, t, "hearingId never published" > unexpected; nunexp++; next }
  if (!(a in auth))     { print h, a, t, "authority not expected" > unexpected; nunexp++; next }
  k = h SUBSEP a
  n[k]++
  if (!(k in first) || t < first[k]) first[k] = t
}
END {
  print "simulation/scenario,requestId,hearingId,authorityId,gate" > missing
  print "simulation/scenario,requestId,hearingId,publishedAtEpochMs,completedAtEpochMs,latencyMs" > latency
  for (h in pub) {
    complete = 1; done = 0
    for (a in auth) {
      k = h SUBSEP a
      g = gate[h]
      if (!(k in n)) { print scen[h], req[h], h, a, g > missing; nmiss[g]++; complete = 0; continue }
      if (n[k] > 1)  { print scen[h], req[h], h, a, n[k], g > extra; nextra[g] += n[k] - 1 }
      if (first[k] > done) done = first[k]
    }
    if (complete) print scen[h], req[h], h, pub[h], done, done - pub[h] > latency
  }
  printf "%d %d %d %d %d\n", nmiss["gated"] + 0, nextra["gated"] + 0, nunexp + 0, \
    nmiss["report-only"] + 0, nextra["report-only"] + 0
}' "$WORK/posts.tsv" > "$WORK/counts"
read -r MISSING EXTRA UNEXPECTED REPORT_MISSING REPORT_EXTRA < "$WORK/counts"

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
  echo "  published=$PUBLISHES unique=$UNIQUE authorities=$AUTHORITIES expectedPosts=$EXPECTED"
  echo "  gated ($GATED_SIMULATIONS): missing=$MISSING extra=$EXTRA"
  echo "  report-only (other simulations): missing=$REPORT_MISSING extra=$REPORT_EXTRA"
  echo "  unexpected (all simulations, gated)=$UNEXPECTED"
  echo "Publish → Results POST latency (ms, request complete = last authority POSTed)"
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
  error "$MISSING expected outputs missing (request × authority) — see missing-outputs.csv"
  awk -F, 'NR == 1 || $NF == "gated"' "$REPORT_DIR/missing-outputs.csv" | head -6; FAILED=1
fi
if [ "$EXTRA" -gt 0 ]; then
  error "$EXTRA extra POSTs (dedupe failure) — see extra-outputs.csv"
  awk -F, 'NR == 1 || $NF == "gated"' "$REPORT_DIR/extra-outputs.csv" | head -6; FAILED=1
fi
if [ "$UNEXPECTED" -gt 0 ]; then
  error "$UNEXPECTED POSTs not attributable to a published request/expected authority — see unexpected-outputs.csv"
  head -6 "$REPORT_DIR/unexpected-outputs.csv"; FAILED=1
fi
if [ $((REPORT_MISSING + REPORT_EXTRA)) -gt 0 ]; then
  echo "NOTE: report-only simulations had $REPORT_MISSING missing and $REPORT_EXTRA extra outputs (not gated)"
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
  echo "Output verification passed: every gated request ($GATED_SIMULATIONS) produced exactly $AUTHORITIES POSTs; $LATENCY_RESULT"
fi
exit $FAILED
