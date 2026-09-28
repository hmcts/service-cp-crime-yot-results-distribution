#!/usr/bin/env bash
#
# Container smoke: build the image, run it against the committed compose dependencies, require it to
# report readiness inside the 60-second budget (spec SC-101/SC-103, container half), and then call
# the operations API through that same readiness gate - one endpoint, and it writes nothing. Tears
# the stack down on every exit path, success or failure.
#
# This is the local equivalent of the "Container smoke" step in
# .github/workflows/ci-build-publish.yml; both run this same script, so the two cannot drift.
#
#   ./scripts/container-smoke.sh
#
# It proves the packaged artefact starts and answers, which no JUnit suite can: the *IT suites run
# inside the build's JVM and would still pass if the image were unbuildable.

set -euo pipefail

readonly READINESS_BUDGET_SECONDS=60
readonly DEPENDENCY_BUDGET_SECONDS=120
readonly READINESS_URL="http://localhost:8082/actuator/health/readiness"
readonly FLAG_URL="http://localhost:8082/operations/flag"

# A project name of this script's own. Everything it creates — containers, network, volumes — is
# namespaced under it, so the teardown's `down --volumes` can only ever destroy what this script
# made. Without it the script would share the default project with a developer's own
# `docker compose up`, and a smoke run would silently delete their database volume.
readonly PROJECT_NAME="yot-results-distribution-smoke"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

compose() {
  docker compose --project-name "$PROJECT_NAME" "$@"
}

log() {
  printf '[container-smoke] %s\n' "$1"
}

teardown() {
  # Captured first: everything below overwrites $?, and the script's real outcome must survive the
  # cleanup rather than be replaced by it.
  local status=$?

  log "tearing down"
  if ! compose logs --no-color --tail 50 app; then
    log "WARNING: could not read the application container's logs"
  fi

  if ! compose down --volumes --remove-orphans; then
    log "FAIL: teardown left containers, networks or volumes behind"
    # A cleanup failure fails an otherwise green run: leftovers from this project poison the next
    # run, and a green tick over a stack that would not come down is a lie.
    if [ "$status" -eq 0 ]; then
      status=1
    fi
  fi

  exit "$status"
}
trap teardown EXIT

# Cleared first, then built. `bootJar` does not remove what it did not write, and the artefact is
# named for the version it was built under (`ARTEFACT_VERSION`), so a jar from an earlier build with
# a different version sits beside the new one - the Dockerfile copies `build/libs/*.jar` whole and
# the entrypoint takes the lexicographically first of them, which is how this script comes to smoke
# code that is no longer in the tree while printing PASS.
log "clearing any earlier application jar"
rm -f build/libs/*.jar

log "building the application jar"
./gradlew bootJar

# The whole local stack, because the image now runs generation-enabled against it: `wiremock` is
# systemdocgenerator, notificationnotify and Azure App Configuration, `fileservice-postgres` is the
# framework file service's database and `artemis` carries `public.event`.
log "starting dependencies"
compose up --detach postgres servicebus-emulator wiremock fileservice-postgres artemis

# Only these two are waited on, and the readiness policy is why. `postgres` is a readiness input, so
# the pod cannot report UP without it; `wiremock` answers the flag read, so the flag endpoint cannot
# get an answer without it. The broker is never a readiness input (spec FR-011) and the file-service
# component answers UP between runs without asking, both pinned by `e2e/ReadinessPolicyIT`, so
# waiting on either would only make this script slower than the thing it is testing.
for dependency in postgres wiremock; do
  log "waiting for ${dependency} to report healthy (budget ${DEPENDENCY_BUDGET_SECONDS}s)"
  deadline=$((SECONDS + DEPENDENCY_BUDGET_SECONDS))
  until [ "$(docker inspect --format '{{.State.Health.Status}}' \
      "$(compose ps --quiet "$dependency")")" = "healthy" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      log "FAIL: ${dependency} did not become healthy within ${DEPENDENCY_BUDGET_SECONDS}s"
      exit 1
    fi
    sleep 2
  done
done

log "building the application image"
compose build app

log "starting the application container"
compose up --detach app

log "polling ${READINESS_URL} (budget ${READINESS_BUDGET_SECONDS}s)"
deadline=$((SECONDS + READINESS_BUDGET_SECONDS))
until curl --silent --fail --max-time 2 "$READINESS_URL" | grep -q '"status":"UP"'; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: readiness did not report UP within ${READINESS_BUDGET_SECONDS}s"
    exit 1
  fi
  sleep 2
done

log "PASS: readiness reported UP within the ${READINESS_BUDGET_SECONDS}s budget"

# The other half of what the image has to do, and the half that changed in increment 005. A deployed
# pod used to serve the actuator and nothing else, so an operator reached a named action by
# `kubectl exec ... -- ./startup.sh <command>` - a path that lived in the entrypoint and that no
# JUnit suite covered. The actions are the seven endpoints under `/operations/**` now, served by the
# pod itself, and what only the built image can prove has moved with them: that the image serves
# them at all, on the port the compose stack publishes, through the same gate readiness answered
# on - a controller left unscanned, a filter registered in the wrong order or an OpenAPI document
# missing from the jar are all things that pass every slice test and answer nothing here.
#
# `GET /operations/flag` is the one to call: it reads and changes nothing, so a smoke run cannot
# leave a batch or an e-mail behind it.
#
# No identity header, because `docker-compose.yml` switches both estate filters off for the local
# loop - a laptop has no usersgroups to resolve a caller's groups through and no audit broker to
# publish to. What that leaves under assertion is the surface and the reading, which is what this
# script can prove; who may reach it is `OperationsAuthzIT`'s, over the real filter and the real
# rules.
#
# The reading is taken through the REAL reader, with no mode override at all. It used to need
# `YOTRESULTSDISTRIBUTION_GENERATION_FLAG_MODE=STUB`, because the live reader authorises its App Configuration
# read on the pod's workload identity - AZURE_CLIENT_ID, the tenant and the projected federated token
# - and a compose container holds none of the three; a bearer credential is refused a plain-HTTP URL
# by the SDK before a socket is opened, so pointing it at the WireMock stub was not an option either.
# `yotresultsdistribution.feature.credential=local-test`, which docker-compose.yml sets on `app`, swaps that
# identity for a published pair the stub does not check and leaves everything else deployed. So what
# this step asserts is the whole path an operator uses: the endpoint, the deployed reader, the
# deployed SDK client, the key in the path, the label in the query and the fail-closed reading of
# the answer.
log "calling GET ${FLAG_URL}"
# In the `if` deliberately: errexit does not apply to a condition, so a failure is read and reported
# here rather than ending the script with no line saying what was called. `--fail` so that a status
# this surface answers a refusal under is a failure here and not a body to grep.
if flag_output=$(curl --silent --fail --show-error --max-time 5 "$FLAG_URL" 2>&1); then
  flag_status=0
else
  flag_status=$?
fi

if [ "$flag_status" -ne 0 ]; then
  log "FAIL: GET ${FLAG_URL} did not answer 2xx (curl exited ${flag_status})"
  log "      the endpoint answers 200 for all three readings - ON, OFF and unreadable - so a"
  log "      non-2xx here is the surface, not the flag"
  printf '%s\n' "$flag_output" | tail -5
  exit 1
fi

# A 2xx alone is not the whole assertion: the body is what a runbook step reads, and an endpoint
# that answered an empty 200 would satisfy the check above.
if ! printf '%s\n' "$flag_output" | grep -q '"flag":"ON"'; then
  log "FAIL: GET ${FLAG_URL} answered 2xx without the reading a runbook step reads"
  printf '%s\n' "$flag_output" | tail -5
  exit 1
fi

log "PASS: GET /operations/flag answered 200 with flag=ON"
