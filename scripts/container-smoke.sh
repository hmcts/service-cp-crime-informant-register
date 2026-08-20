#!/usr/bin/env bash
#
# Container smoke: build the image, run it against the committed compose dependencies, and require
# it to report readiness inside the 60-second budget (spec SC-004, first half). Tears the stack down
# on every exit path, success or failure.
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

cd "$(dirname "${BASH_SOURCE[0]}")/.."

log() {
  printf '[container-smoke] %s\n' "$1"
}

teardown() {
  log "tearing down"
  docker compose logs --no-color --tail 50 app || true
  docker compose down --volumes --remove-orphans || true
}
trap teardown EXIT

if ! compgen -G "build/libs/*.jar" > /dev/null; then
  log "no jar in build/libs, building one"
  ./gradlew bootJar
fi

log "starting dependencies"
docker compose up --detach postgres servicebus-emulator

log "waiting for postgres to accept connections (budget ${DEPENDENCY_BUDGET_SECONDS}s)"
deadline=$((SECONDS + DEPENDENCY_BUDGET_SECONDS))
until [ "$(docker inspect --format '{{.State.Health.Status}}' \
    "$(docker compose ps --quiet postgres)")" = "healthy" ]; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: postgres did not become healthy within ${DEPENDENCY_BUDGET_SECONDS}s"
    exit 1
  fi
  sleep 2
done

log "building the application image"
docker compose build app

log "starting the application container"
docker compose up --detach app

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
