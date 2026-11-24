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
# Every recorded register must carry the hearingId of the message that caused it —
# that is what lets verify-outputs.sh correlate register rows to requests. This
# WireMock (a WAR in WildFly) does not apply response templating, so the shared
# hearing-payload mapping cannot echo the id: Gatling registers an exact-path static
# stub per query-fallback hearing instead (FallbackHearingStubs.java, through the
# pipeline's port-forward passed as -Dgatling.wiremockAdminUrl), and the shared
# mapping stays only as a catch-all. A preflight below proves a stub of that exact
# shape is reached, and answers, through the route the service uses.
#
# Expects: NAMESPACE and KUBECONFIG to be set by the calling pipeline. Gatling then runs with
# -Dgatling.wiremockViaScript=true — build.gradle defaults it on wherever TF_BUILD is set, i.e. on
# every Azure DevOps agent — so the simulations do not try to install the stubs again (they cannot
# reach WireMock from the agent, and would abort). SERVICE_CONTEXT is the service's Helm release
# (default yotresultsdistribution-service, the pipeline's `context`), whose Deployment is found by
# its app.kubernetes.io/instance label as the pipeline finds the pod; setting RESULTS_BASE_URL skips
# reading it from that Deployment.
set -euo pipefail

: "${NAMESPACE:?NAMESPACE must be set}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAPPINGS_DIR="$SCRIPT_DIR/../resources/wiremock/mappings"

# items[*], not items[0]: an empty match is then an empty string the check below can report,
# rather than a jsonpath error that set -e turns into an unexplained exit. Running pods only, the
# same set the count below checks, so a Pending or Failed leftover is never the exec target.
WIREMOCK_POD=$(kubectl get pods -n "$NAMESPACE" \
  -l app.kubernetes.io/name=wildfly-app,app.kubernetes.io/instance=zwiremock \
  --field-selector=status.phase=Running \
  -o jsonpath='{.items[*].metadata.name}' | awk '{ print $1 }')

if [ -z "$WIREMOCK_POD" ]; then
  echo "ERROR: no running WireMock pod found in namespace $NAMESPACE"
  exit 1
fi

# Gatling registers per-hearing stubs on one pod (its port-forward) while the service
# reaches WireMock through the ingress: with more than one pod, a share of fallback
# GETs would land on a pod without the stub. Stubs are in-memory, per pod.
WIREMOCK_PODS=$(kubectl get pods -n "$NAMESPACE" \
  -l app.kubernetes.io/name=wildfly-app,app.kubernetes.io/instance=zwiremock \
  --field-selector=status.phase=Running -o name | wc -l | tr -d ' ')
if [ "$WIREMOCK_PODS" != "1" ]; then
  echo "ERROR: expected exactly 1 running WireMock pod in $NAMESPACE, found $WIREMOCK_PODS."
  echo "       Per-hearing stubs are registered on a single pod; scale zwiremock to 1 replica."
  exit 1
fi

wiremock() {
  kubectl exec "$WIREMOCK_POD" -n "$NAMESPACE" -c wildfly-app -- curl -sSf "$@"
}

# Remove per-hearing stubs an earlier run left on this WireMock. The shared pipeline gets a fresh
# namespace per run, so there it is a no-op; on a reused WireMock it stops them piling up across
# runs, which would grow the fallback latency under test (WireMock matches every request against
# every mapping). Gatling leaves its own in place: the service may still be draining when a
# simulation ends. They are tagged metadata.nft=fallback-hearing; nothing else is touched, so any
# mapping baked into the image stays.
if wiremock -X POST "http://localhost:8080/__admin/mappings/remove-by-metadata" \
  -H "Content-Type: application/json" \
  -d '{"matchesJsonPath":{"expression":"$.nft","equalTo":"fallback-hearing"}}' >/dev/null; then
  echo "WireMock per-hearing stubs from earlier runs removed"
else
  echo "WARN: could not remove earlier per-hearing stubs; fallback latency may include their cost"
fi

# Start from an empty journal so the run's fallback/subscription calls can be inspected alone
wiremock -X DELETE "http://localhost:8080/__admin/requests" >/dev/null
echo "WireMock request journal reset"

for mapping in hearing-payload now-subscriptions; do
  wiremock -X POST "http://localhost:8080/__admin/mappings" \
    -H "Content-Type: application/json" \
    -d "$(cat "$MAPPINGS_DIR/$mapping.json")" >/dev/null
  echo "WireMock $mapping stub installed"
done

# ── Preflight: a per-hearing stub answers through the service's route ──
# Registers a probe stub shaped exactly as Gatling's per-hearing stubs (same file,
# same derivation as HearingPayloadFixture.stubFor), fetches it the way the service
# would, and checks hearing.id comes back. Failing here, before load, beats a run
# whose fallback registers cannot be correlated.
#
# The probe goes the way the service's own calls go: to RESULTS_BASE_URL (the stack's
# internal ingress), whose Istio VirtualService rewrites /results-query-api onto
# WireMock's context root. Calling the pod directly at /results-query-api 404s in
# WildFly — that context path exists only on the VirtualService — and would prove
# nothing about whether the stubs match what the service actually sends. The URL is
# read from the service's own Deployment (selected by SERVICE_CONTEXT) so it cannot drift from what
# the service resolves; a kubectl failure stops the script rather than reading as "not set".
#
# No -f here: a 404 (stub not matched) or 500 (WireMock error) must surface its
# status and body, not die as a bare "exit code 22". The status is appended on its
# own line via -w rather than --fail-with-body, which the image's curl may predate. A transport
# failure (DNS, refused connection) still prints status 000 through -w; `|| true` keeps set -e from
# ending the script there, so it reaches the diagnostics below like any other failure.
SERVICE_CONTEXT="${SERVICE_CONTEXT:-yotresultsdistribution-service}"
if [ -z "${RESULTS_BASE_URL:-}" ]; then
  SERVICE_ENV=$(kubectl get deployments -n "$NAMESPACE" -l "app.kubernetes.io/instance=$SERVICE_CONTEXT" \
    -o jsonpath='{range .items[*].spec.template.spec.containers[*].env[?(@.name=="RESULTS_BASE_URL")]}{.value}{"\n"}{end}')
  RESULTS_BASE_URL=$(printf '%s\n' "$SERVICE_ENV" | grep -m 1 . || true)
fi
if [ -z "$RESULTS_BASE_URL" ]; then
  echo "ERROR: no $SERVICE_CONTEXT Deployment in $NAMESPACE sets RESULTS_BASE_URL;"
  echo "       cannot probe the route the service uses (set SERVICE_CONTEXT or RESULTS_BASE_URL)."
  exit 1
fi

PROBE_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z')
PROBE_STUB=$(jq -c --arg id "$PROBE_ID" '
  (.request.urlPathPattern | if endswith(".*") then .[:-2] else error("urlPathPattern must end in .*") end) as $prefix
  | {priority: 1,
     request: {method: .request.method, urlPath: ($prefix + $id)},
     response: (.response | del(.transformers) | .jsonBody.hearing.id = $id),
     metadata: {nft: "fallback-hearing"}}' \
  "$MAPPINGS_DIR/hearing-payload.json")
PROBE_PATH=$(printf '%s' "$PROBE_STUB" | jq -r '.request.urlPath')
PROBE_STUB_ID=$(wiremock -X POST "http://localhost:8080/__admin/mappings" \
  -H "Content-Type: application/json" -d "$PROBE_STUB" | jq -r '.id')
PROBE_RESPONSE=$(kubectl exec "$WIREMOCK_POD" -n "$NAMESPACE" -c wildfly-app -- \
  curl -sS -w '\n%{http_code}' "${RESULTS_BASE_URL%/}$PROBE_PATH") || true
PROBE_STATUS=$(printf '%s\n' "$PROBE_RESPONSE" | tail -n 1)
PROBE_BODY=$(printf '%s\n' "$PROBE_RESPONSE" | sed '$d')

if [ "$PROBE_STATUS" != "200" ]; then
  echo "ERROR: hearing-payload preflight GET ${RESULTS_BASE_URL%/}$PROBE_PATH returned HTTP $PROBE_STATUS."
  echo "       404 = the request never matched the probe stub: either the ingress did not route it"
  echo "             to this namespace's WireMock, or WireMock saw a rewritten path the stub is not"
  echo "             keyed on (see the unmatched requests below);"
  echo "       5xx = WireMock or the ingress failed answering it (see the body);"
  echo "       000 = no HTTP answer at all: RESULTS_BASE_URL did not resolve or refused the connection."
  echo "------- response body -------"
  printf '%s\n' "$PROBE_BODY" | head -c 4000
  echo
  echo "------- installed mappings matching results-query-api -------"
  wiremock "http://localhost:8080/__admin/mappings" \
    | jq -c '.mappings[] | select((.request | tostring) | test("results-query-api")) | {id, priority, request}' \
    || true
  echo "------- requests WireMock received but did not match (the path as WireMock saw it) -------"
  wiremock "http://localhost:8080/__admin/requests/unmatched" \
    | jq -c '.requests[] | {method, url}' \
    || true
  echo "------- WildFly deployments in the WireMock pod -------"
  kubectl exec "$WIREMOCK_POD" -n "$NAMESPACE" -c wildfly-app -- \
    ls /opt/jboss/wildfly/standalone/deployments || true
  exit 1
fi

ECHOED=$(printf '%s\n' "$PROBE_BODY" | jq -r '.hearing.id' 2>/dev/null || echo "<non-JSON body>")
if [ "$ECHOED" != "$PROBE_ID" ]; then
  echo "ERROR: probe stub for $PROBE_PATH answered hearing.id='$ECHOED', expected '$PROBE_ID'."
  echo "       A mapping other than the per-hearing stub answered (priority 1 should win);"
  echo "       per-hearing stubs would not make fallback registers correlatable."
  exit 1
fi
echo "Preflight passed: a per-hearing stub answers with its hearingId via $RESULTS_BASE_URL"

# Remove the probe stub (harmless if it lingers: its hearingId is never published), and
# clear the journal so the probe GET cannot be mistaken for load
if [ -n "$PROBE_STUB_ID" ] && [ "$PROBE_STUB_ID" != "null" ]; then
  wiremock -X DELETE "http://localhost:8080/__admin/mappings/$PROBE_STUB_ID" >/dev/null \
    || echo "WARN: could not remove probe stub $PROBE_STUB_ID"
fi
wiremock -X DELETE "http://localhost:8080/__admin/requests" >/dev/null
