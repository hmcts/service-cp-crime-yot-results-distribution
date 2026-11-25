#!/usr/bin/env bash
set -euo pipefail

# ── Auth mode ─────────────────────────────────────────────────────────────────
# Entra ID (DEV/STE):  export ASB_NAMESPACE=sbdevccm01.servicebus.windows.net
#                      az login   # DefaultAzureCredential picks this up
#                      ./perfTest.sh
#
# SAS / emulator:      export ASB_EMULATOR_KEY=<emulator SAS key>
#                      ./perfTest.sh [connectionString] [actuatorUrl] [wiremockUrl]
#                      (an explicit connectionString overrides ASB_EMULATOR_KEY)
#
# Optional:            export REDIS_URI=redis://localhost:6379   seed 80% of hearings into Redis
#                      export DATABASE_URL=postgresql://...      verify outcomes afterwards
#                      (src/gatling/scripts/verify-outputs.sh; needs psql)
#
# Without DATABASE_URL only the broker publish is gated: the run can be green while the service
# recorded nothing. Let the queue drain between runs; a previous run's backlog recorded during this
# one's window is reported as unexpected. Keep the 18:00 generation leg off while verifying: never run
# across 18:00 Europe/London on a weekday where the cutover flag is ON and generation is enabled.
#
# The service under test must run its intake leg LIVE against the WireMock stubs, not with the
# STUB adapters docker-compose.yml's `app` uses: YOTRESULTSDISTRIBUTION_PAYLOAD_MODE and
# YOTRESULTSDISTRIBUTION_REFERENCEDATA_MODE left at LIVE, RESULTS_BASE_URL and
# REFERENCEDATA_BASE_URL at the WireMock host, a Redis reachable at
# yotresultsdistribution.payload.redis, and YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID set.
#
# gatlingRun forks with the JVM running Gradle, so JAVA_HOME must be a JDK 25.
# ──────────────────────────────────────────────────────────────────────────────

ACTUATOR_URL="${2:-http://localhost:8082}"
WIREMOCK_URL="${3:-http://localhost:8089}"
REPORT_DIR="build/reports/gatling"
MANIFEST="build/nft/published-manifest.csv"

if [ -n "${ASB_NAMESPACE:-}" ]; then
  GATLING_OPTS=("-Dgatling.asbNamespace=$ASB_NAMESPACE")
  echo "=== NFT Performance Tests (DefaultAzureCredential → $ASB_NAMESPACE) ==="
else
  if [ -n "${1:-}" ]; then
    CONNECTION_STRING="$1"
  elif [ -n "${ASB_EMULATOR_KEY:-}" ]; then
    CONNECTION_STRING="Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=${ASB_EMULATOR_KEY};UseDevelopmentEmulator=true;"
  else
    echo "ERROR: pass a connection string as \$1, or export ASB_EMULATOR_KEY for the local emulator"
    exit 1
  fi
  GATLING_OPTS=("-Dgatling.connectionString=$CONNECTION_STRING")
  echo "=== NFT Performance Tests (SAS / emulator) ==="
fi
if [ -n "${REDIS_URI:-}" ]; then
  GATLING_OPTS+=("-Dgatling.redisUri=$REDIS_URI")
fi

echo "Actuator: $ACTUATOR_URL"
echo "WireMock: $WIREMOCK_URL"
echo ""

# Check service is up
echo "Checking service health..."
if ! curl -sf "$ACTUATOR_URL/actuator/health/readiness" > /dev/null 2>&1; then
  echo "ERROR: Service is not running at $ACTUATOR_URL"
  echo "Start it with LIVE payload and reference-data adapters (see the header of this script)"
  exit 1
fi
echo "Service is healthy."
echo ""

# Build Gatling classes
echo "Building Gatling classes..."
./gradlew gatlingClasses -q
echo ""

# A fresh manifest per run: both simulations append to it
rm -f "$MANIFEST"

# Run Capacity Simulation (pipeline gate — with assertions)
echo "=== Running Capacity Simulation (pipeline gate) ==="
./gradlew gatlingRun \
  --simulation=uk.gov.hmcts.cp.simulation.CapacitySimulation \
  "${GATLING_OPTS[@]}" \
  -Dgatling.actuatorUrl="$ACTUATOR_URL" \
  -Dgatling.wiremockUrl="$WIREMOCK_URL"
echo ""

# Run Stress Simulation (exploratory — no assertions)
echo "=== Running Stress Simulation (exploratory) ==="
./gradlew gatlingRun \
  --simulation=uk.gov.hmcts.cp.simulation.StressSimulation \
  "${GATLING_OPTS[@]}" \
  -Dgatling.actuatorUrl="$ACTUATOR_URL" \
  -Dgatling.wiremockUrl="$WIREMOCK_URL"
echo ""

if [ -n "${DATABASE_URL:-}" ]; then
  echo "=== Verifying outcomes in the processed log ==="
  MANIFEST="$MANIFEST" MAX_P95_MS="${MAX_P95_MS:-5000}" MAX_P99_MS="${MAX_P99_MS:-10000}" \
    src/gatling/scripts/verify-outputs.sh
  echo ""
else
  echo "**********************************************************************************"
  echo "NOTE: outcomes NOT verified (DATABASE_URL unset). Only the broker publish was gated;"
  echo "      nothing above says the service recorded a single register."
  echo "**********************************************************************************"
  echo ""
fi

echo "=== Reports ==="
# Tolerate an empty report dir and head closing the pipe early: both would fail a good run under
# pipefail
{ ls -dt "$REPORT_DIR"/*/ 2>/dev/null || true; } | head -2 | while read -r dir; do
  echo "  ${dir%/}/index.html"
done || true
