#!/usr/bin/env bash
# Configures WireMock stubs via kubectl exec into the WireMock pod.
#
# Called by the generic ASB perf-test pipeline BEFORE Gatling starts.
# The ADO agent cannot reach WireMock's admin API directly (the istio sidecar
# routes localhost:8080 to the service pod, not WireMock), so kubectl exec is
# the only path.
#
# The mappings are src/gatling/resources/wiremock/mappings/*.json — shared with
# WireMockStubs.java (local runs). There is no outbound stub: the service records
# each register in its own store, and verify-outputs.sh reads it from there.
#
# The hearing-payload stub echoes the requested hearingId back as hearing.id via
# response templating, so every recorded register carries the hearingId of the
# message that caused it. That is what lets verify-outputs.sh correlate register
# rows to requests; a preflight below fails the run if templating does not render.
#
# Expects: NAMESPACE and KUBECONFIG to be set by the calling pipeline, which then runs Gatling
# with -Dgatling.wiremockViaScript=true so the simulations do not try to install the stubs again
# (they cannot reach WireMock from the agent, and would abort).
set -euo pipefail

: "${NAMESPACE:?NAMESPACE must be set}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAPPINGS_DIR="$SCRIPT_DIR/../resources/wiremock/mappings"

# items[*], not items[0]: an empty match is then an empty string the check below can report,
# rather than a jsonpath error that set -e turns into an unexplained exit
WIREMOCK_POD=$(kubectl get pods -n "$NAMESPACE" \
  -l app.kubernetes.io/name=wildfly-app,app.kubernetes.io/instance=zwiremock \
  -o jsonpath='{.items[*].metadata.name}' | awk '{ print $1 }')

if [ -z "$WIREMOCK_POD" ]; then
  echo "ERROR: WireMock pod not found in namespace $NAMESPACE"
  exit 1
fi

wiremock() {
  kubectl exec "$WIREMOCK_POD" -n "$NAMESPACE" -c wildfly-app -- curl -sf "$@"
}

# Start from an empty journal so the run's fallback/subscription calls can be inspected alone
wiremock -X DELETE "http://localhost:8080/__admin/requests" >/dev/null
echo "WireMock request journal reset"

for mapping in hearing-payload now-subscriptions; do
  wiremock -X POST "http://localhost:8080/__admin/mappings" \
    -H "Content-Type: application/json" \
    -d "$(cat "$MAPPINGS_DIR/$mapping.json")" >/dev/null
  echo "WireMock $mapping stub installed"
done

# ── Preflight: the hearing stub must echo the hearingId ──
# If response templating is unavailable every Redis miss would read a literal
# "{{regexExtract ...}}" hearing id and nothing could be correlated — fail here,
# before load, rather than after a wasted run.
PROBE_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z')
ECHOED=$(wiremock "http://localhost:8080/results-query-api/query/api/rest/results/hearingDetails/internal/$PROBE_ID" \
  | jq -r '.hearing.id')
if [ "$ECHOED" != "$PROBE_ID" ]; then
  echo "ERROR: hearing-payload stub returned hearing.id='$ECHOED', expected '$PROBE_ID'."
  echo "       WireMock response templating (regexExtract) is not rendering; registers cannot be"
  echo "       correlated to requests. Enable the response-template transformer in the WireMock image."
  exit 1
fi
echo "Preflight passed: hearing-payload stub echoes the requested hearingId"

# The probe GET is in the journal; clear it so it cannot be mistaken for load
wiremock -X DELETE "http://localhost:8080/__admin/requests" >/dev/null
