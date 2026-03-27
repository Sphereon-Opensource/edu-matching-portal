#!/usr/bin/env bash
# =============================================================================
# Portal E2E Test Runner
# =============================================================================
# Builds fat JARs, starts the Docker Compose test stack, waits for health
# checks, runs integration/E2E tests, and tears everything down.
#
# Usage:
#   ./test-e2e.sh                  # full run (build + infra + tests + teardown)
#   ./test-e2e.sh --skip-build     # skip fat JAR build (use existing artifacts)
#   ./test-e2e.sh --skip-teardown  # leave containers running after tests
#   ./test-e2e.sh --infra-only     # start infra and wait, no tests
#
# Prerequisites:
#   - Docker and Docker Compose v2
#   - JDK 21 (for Gradle fat JAR build)
#   - Node.js 20+ and npm (for Playwright tests)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.test.yml"
ENV_FILE="$SCRIPT_DIR/.env.test"

# ── Defaults ────────────────────────────────────────────────────────────────
SKIP_BUILD=false
SKIP_TEARDOWN=false
INFRA_ONLY=false
HEALTH_TIMEOUT=180  # seconds

# ── Parse arguments ────────────────────────────────────────────────────────
for arg in "$@"; do
  case "$arg" in
    --skip-build)    SKIP_BUILD=true ;;
    --skip-teardown) SKIP_TEARDOWN=true ;;
    --infra-only)    INFRA_ONLY=true; SKIP_TEARDOWN=true ;;
    --help|-h)
      head -20 "$0" | tail -15
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg"
      exit 1
      ;;
  esac
done

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }

# ── Cleanup on exit ─────────────────────────────────────────────────────────
cleanup() {
  local exit_code=$?
  if [ "$SKIP_TEARDOWN" = true ]; then
    warn "Skipping teardown (--skip-teardown). Containers are still running."
    info "To stop:  docker compose -f $COMPOSE_FILE --env-file $ENV_FILE down -v"
    return
  fi
  info "Tearing down test stack..."
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" down -v --remove-orphans 2>/dev/null || true
  if [ $exit_code -eq 0 ]; then
    ok "Test run completed successfully."
  else
    fail "Test run failed with exit code $exit_code."
  fi
}
trap cleanup EXIT

# ── Step 1: Build fat JARs ─────────────────────────────────────────────────
if [ "$SKIP_BUILD" = false ]; then
  info "Building fat JARs..."
  cd "$PROJECT_ROOT"

  info "  Building service-sts..."
  ./gradlew :service-sts:fatJar --no-daemon -q 2>&1 | tail -5
  ok "  service-sts fat JAR built."

  info "  Building service-auth-bridge..."
  ./gradlew :service-auth-bridge:fatJar --no-daemon -q 2>&1 | tail -5
  ok "  service-auth-bridge fat JAR built."
else
  warn "Skipping fat JAR build (--skip-build)."
fi

# ── Step 2: Tear down any previous test stack ──────────────────────────────
info "Cleaning up previous test stack (if any)..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" down -v --remove-orphans 2>/dev/null || true

# ── Step 3: Start Docker Compose test stack ────────────────────────────────
info "Starting test stack..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build 2>&1 | tail -20

# ── Step 4: Wait for health checks ────────────────────────────────────────
wait_for_health() {
  local service_name="$1"
  local url="$2"
  local elapsed=0

  info "  Waiting for $service_name at $url ..."
  while [ $elapsed -lt $HEALTH_TIMEOUT ]; do
    if curl -sf "$url" > /dev/null 2>&1; then
      ok "  $service_name is healthy (${elapsed}s)."
      return 0
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  fail "  $service_name did not become healthy within ${HEALTH_TIMEOUT}s."
  info "  Container logs for debugging:"
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs "$service_name" 2>&1 | tail -30
  return 1
}

info "Waiting for services to become healthy..."

# Wait for postgres via Docker health check (pg_isready doesn't respond to HTTP)
info "  Waiting for postgres (via Docker health check)..."
elapsed=0
while [ $elapsed -lt $HEALTH_TIMEOUT ]; do
  status=$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps postgres --format '{{.Health}}' 2>/dev/null || echo "unknown")
  if [ "$status" = "healthy" ]; then
    ok "  postgres is healthy (${elapsed}s)."
    break
  fi
  sleep 3
  elapsed=$((elapsed + 3))
done
if [ $elapsed -ge $HEALTH_TIMEOUT ]; then
  fail "  postgres did not become healthy within ${HEALTH_TIMEOUT}s."
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs postgres 2>&1 | tail -20
fi

wait_for_health "keycloak"           "http://localhost:8083/realms/portal/.well-known/openid-configuration"
wait_for_health "keycloak-surf"      "http://localhost:8084/realms/surf/.well-known/openid-configuration"
wait_for_health "service-sts"        "http://localhost:8080/health"
wait_for_health "service-auth-bridge" "http://localhost:8090/health"

ok "All services are healthy."

# ── Step 5: Run tests ─────────────────────────────────────────────────────
if [ "$INFRA_ONLY" = true ]; then
  ok "Infrastructure is ready. Skipping tests (--infra-only)."
  echo ""
  info "Service endpoints:"
  info "  Keycloak (portal realm):  http://localhost:8083  (admin/admin)"
  info "  Keycloak (surf realm):    http://localhost:8084  (admin/admin)"
  info "  STS:                      http://localhost:8080"
  info "  Auth Bridge:              http://localhost:8090"
  info "  PostgreSQL:               localhost:5432  (portal/test-password)"
  echo ""
  info "Press Ctrl+C to stop."
  # Keep the script alive so trap doesn't fire immediately
  tail -f /dev/null
  exit 0
fi

TESTS_PASSED=true

# 5a. Integration smoke tests
info "Running integration smoke tests..."
echo ""

run_test() {
  local name="$1"
  local cmd="$2"
  if eval "$cmd" > /dev/null 2>&1; then
    ok "  PASS: $name"
  else
    fail "  FAIL: $name"
    TESTS_PASSED=false
  fi
}

# Keycloak OIDC discovery
run_test "Keycloak portal OIDC discovery" \
  "curl -sf http://localhost:8083/realms/portal/.well-known/openid-configuration | grep -q authorization_endpoint"

run_test "Keycloak SURF OIDC discovery" \
  "curl -sf http://localhost:8084/realms/surf/.well-known/openid-configuration | grep -q authorization_endpoint"

# STS health and OIDC
run_test "STS health endpoint" \
  "curl -sf http://localhost:8080/health"

# Auth Bridge health
run_test "Auth Bridge health endpoint" \
  "curl -sf http://localhost:8090/health"

# Keycloak token endpoint (resource owner password grant for test user)
run_test "Keycloak portal token (test user)" \
  "curl -sf -X POST http://localhost:8083/realms/portal/protocol/openid-connect/token \
    -d 'grant_type=password&client_id=portal&client_secret=dev-client-secret&username=test@kw1c.nl&password=password&scope=openid' \
    | grep -q access_token"

run_test "Keycloak SURF token (student user)" \
  "curl -sf -X POST http://localhost:8084/realms/surf/protocol/openid-connect/token \
    -d 'grant_type=password&client_id=portal-federation-client&client_secret=dev-federation-secret&username=student1@kw1c.nl&password=password&scope=openid' \
    | grep -q access_token"

# Verify SURF token contains expected claims
run_test "SURF token contains eduid claim" \
  "TOKEN=\$(curl -sf -X POST http://localhost:8084/realms/surf/protocol/openid-connect/token \
    -d 'grant_type=password&client_id=portal-federation-client&client_secret=dev-federation-secret&username=student1@kw1c.nl&password=password&scope=openid' \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)[\"access_token\"])' 2>/dev/null) && \
   echo \$TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | grep -q eduid"

echo ""

# 5b. Playwright E2E tests (if installed)
if [ -f "$PROJECT_ROOT/apps/portal/playwright.config.ts" ] && command -v npx &>/dev/null; then
  info "Running Playwright E2E tests..."
  cd "$PROJECT_ROOT/apps/portal"
  if npx playwright test --reporter=list 2>&1; then
    ok "Playwright tests passed."
  else
    fail "Playwright tests failed."
    TESTS_PASSED=false
  fi
else
  warn "Playwright not configured or npx not available. Skipping browser E2E tests."
fi

# ── Final result ──────────────────────────────────────────────────────────
echo ""
if [ "$TESTS_PASSED" = true ]; then
  ok "All tests passed."
  exit 0
else
  fail "Some tests failed. Review output above."
  exit 1
fi
