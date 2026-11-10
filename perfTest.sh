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
# ──────────────────────────────────────────────────────────────────────────────

ACTUATOR_URL="${2:-http://localhost:8082/informantregister}"
WIREMOCK_URL="${3:-http://localhost:8080}"
REPORT_DIR="build/reports/gatling"

if [ -n "${ASB_NAMESPACE:-}" ]; then
  GATLING_OPTS="-Dgatling.asbNamespace=$ASB_NAMESPACE"
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
  GATLING_OPTS="-Dgatling.connectionString=$CONNECTION_STRING"
  echo "=== NFT Performance Tests (SAS / emulator) ==="
fi

echo "Actuator: $ACTUATOR_URL"
echo "WireMock: $WIREMOCK_URL"
echo ""

# Check service is up
echo "Checking service health..."
if ! curl -sf "$ACTUATOR_URL/actuator/health" > /dev/null 2>&1; then
  echo "ERROR: Service is not running at $ACTUATOR_URL"
  echo "Start it with: docker compose up -d"
  exit 1
fi
echo "Service is healthy."
echo ""

# Build Gatling classes
echo "Building Gatling classes..."
./gradlew gatlingClasses -q
echo ""

# Run Capacity Simulation (pipeline gate — with assertions)
echo "=== Running Capacity Simulation (pipeline gate) ==="
./gradlew gatlingRun \
  --simulation=uk.gov.hmcts.cp.simulation.CapacitySimulation \
  $GATLING_OPTS \
  -Dgatling.actuatorUrl="$ACTUATOR_URL" \
  -Dgatling.wiremockUrl="$WIREMOCK_URL"
echo ""

# Run Stress Simulation (exploratory — no assertions)
echo "=== Running Stress Simulation (exploratory) ==="
./gradlew gatlingRun \
  --simulation=uk.gov.hmcts.cp.simulation.StressSimulation \
  $GATLING_OPTS \
  -Dgatling.actuatorUrl="$ACTUATOR_URL" \
  -Dgatling.wiremockUrl="$WIREMOCK_URL"
echo ""

echo "=== Reports ==="
ls -dt "$REPORT_DIR"/*/ 2>/dev/null | head -2 | while read -r dir; do
  echo "  $dir/index.html"
done
