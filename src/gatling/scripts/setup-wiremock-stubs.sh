#!/usr/bin/env bash
# Configures WireMock stubs via kubectl exec into the WireMock pod.
#
# Called by the generic ASB perf-test pipeline BEFORE Gatling starts.
# The ADO agent cannot reach WireMock's admin API directly (the istio sidecar
# routes localhost:8080 to the service pod, not WireMock), so kubectl exec is
# the only path.
#
# The mappings are src/gatling/resources/wiremock/mappings/*.json — shared with
# WireMockStubs.java (local runs) and verify-outputs.sh (expected authorities).
#
# The hearing-payload stub echoes the requested hearingId back as hearing.id via
# response templating, so every Results POST carries the hearingId of the message
# that caused it. That is what lets verify-outputs.sh correlate outputs to
# requests; a preflight below fails the run if templating does not render.
#
# Expects: NAMESPACE and KUBECONFIG to be set by the calling pipeline.
set -euo pipefail

: "${NAMESPACE:?NAMESPACE must be set}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAPPINGS_DIR="$SCRIPT_DIR/../resources/wiremock/mappings"

WIREMOCK_POD=$(kubectl get pods -n "$NAMESPACE" \
  -l app.kubernetes.io/name=wildfly-app,app.kubernetes.io/instance=zwiremock \
  -o jsonpath='{.items[0].metadata.name}')

if [ -z "$WIREMOCK_POD" ]; then
  echo "ERROR: WireMock pod not found in namespace $NAMESPACE"
  exit 1
fi

wiremock() {
  kubectl exec "$WIREMOCK_POD" -n "$NAMESPACE" -c wildfly-app -- curl -sSf "$@"
}

# Start from an empty journal so the verifier sees only this run's requests
wiremock -X DELETE "http://localhost:8080/__admin/requests" >/dev/null
echo "WireMock request journal reset"

for mapping in hearing-payload now-subscriptions informant-register; do
  wiremock -X POST "http://localhost:8080/__admin/mappings" \
    -H "Content-Type: application/json" \
    -d "$(cat "$MAPPINGS_DIR/$mapping.json")" >/dev/null
  echo "WireMock $mapping stub installed"
done

# ── Preflight: the hearing stub must echo the hearingId ──
# If response templating is unavailable the service would POST a literal
# "{{regexExtract ...}}" hearingId for every request and nothing could be
# correlated — fail here, before load, rather than after a wasted run.
#
# No -f here: a 404 (stub not matched) or 500 (templating error) must surface its
# status and body, not die as a bare "exit code 22". The status is appended on its
# own line via -w rather than --fail-with-body, which the image's curl may predate.
PROBE_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z')
PROBE_PATH="/results-query-api/query/api/rest/results/hearingDetails/internal/$PROBE_ID"
PROBE_RESPONSE=$(kubectl exec "$WIREMOCK_POD" -n "$NAMESPACE" -c wildfly-app -- \
  curl -sS -w '\n%{http_code}' "http://localhost:8080$PROBE_PATH")
PROBE_STATUS=$(printf '%s\n' "$PROBE_RESPONSE" | tail -n 1)
PROBE_BODY=$(printf '%s\n' "$PROBE_RESPONSE" | sed '$d')

if [ "$PROBE_STATUS" != "200" ]; then
  echo "ERROR: hearing-payload preflight GET $PROBE_PATH returned HTTP $PROBE_STATUS."
  echo "       404 = stub not matched (check for a conflicting mapping baked into the image);"
  echo "       500 = WireMock failed rendering the stub (response-template transformer/helper)."
  echo "------- response body -------"
  printf '%s\n' "$PROBE_BODY" | head -c 4000
  echo
  echo "------- installed mappings matching results-query-api -------"
  wiremock "http://localhost:8080/__admin/mappings" \
    | jq -c '.mappings[] | select((.request | tostring) | test("results-query-api")) | {id, priority, request, transformers: .response.transformers}' \
    || true
  exit 1
fi

ECHOED=$(printf '%s\n' "$PROBE_BODY" | jq -r '.hearing.id' 2>/dev/null || echo "<non-JSON body>")
if [ "$ECHOED" != "$PROBE_ID" ]; then
  echo "ERROR: hearing-payload stub returned hearing.id='$ECHOED', expected '$PROBE_ID'."
  echo "       WireMock response templating (regexExtract) is not rendering; outputs cannot be"
  echo "       correlated to requests. Enable the response-template transformer in the WireMock image."
  exit 1
fi
echo "Preflight passed: hearing-payload stub echoes the requested hearingId"

# The probe GET is in the journal; clear it so it cannot be mistaken for load
wiremock -X DELETE "http://localhost:8080/__admin/requests" >/dev/null
