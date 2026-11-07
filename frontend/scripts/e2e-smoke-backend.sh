#!/usr/bin/env bash
# Starts the smoke suite's backend: the local-profile jar on 8083 (E2E_API_PORT), against a FRESH H2 file in a
# throwaway working directory (the local profile's datasource is ./data/goldenhour, relative to the
# working directory), with a FAKE Anthropic key so nothing here can ever spend money.
#
# Build the jar first:  cd backend && ./mvnw -Plocal-dev -Dmaven.test.skip=true package
# Playwright starts this from playwright.smoke.config.js (webServer); see
# docs/engineering/reference/dev-and-testing.md, "The E2E smoke gate".
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_GLOB="${E2E_BACKEND_JAR:-$HERE/../../backend/target/golden-hour-*.jar}"
LOG_DIR="${E2E_LOG_DIR:-$HERE/../test-results/e2e-logs}"
# 8083 and 4173 in CI; a local run beside another checkout's may move them (playwright.smoke.config.js passes both).
API_PORT="${E2E_API_PORT:-8083}"
WEB_PORT="${E2E_WEB_PORT:-4173}"

# shellcheck disable=SC2206  # the glob is meant to expand
JARS=($JAR_GLOB)
JAR="${JARS[0]}"
if [ ! -f "$JAR" ]; then
  echo "No backend jar at $JAR_GLOB. Build it: cd backend && ./mvnw -Plocal-dev -Dmaven.test.skip=true package" >&2
  exit 1
fi

mkdir -p "$LOG_DIR"
WORK="$(mktemp -d)"
cd "$WORK"

# A fake key, always: this suite must never be able to reach a paid API. Ask runs on its stub.
export ANTHROPIC_API_KEY="sk-ant-e2e-fake-key-never-billed"
unset MAIL_PASSWORD WORLDTIDES_API_KEY TURNSTILE_SECRET_KEY || true

# The browser's Origin is the PREVIEW origin (http://127.0.0.1:4173) and the dev proxy passes it on, so the
# backend's CORS check sees it. Its built-in patterns allow http://localhost:* but not 127.0.0.1, so every
# browser POST (the login form's included) would be a 403 without this.
#
# The seeded fixture (docs/engineering/engineering-gaps-plan.md, Phase 3a.1):
#   --photocast.e2e.seed-fixture=true  turns on E2eLocalFixtureSeeder, which writes 3 regions x 6 places and
#       their ratings through the repositories and the briefing builder beans. It never runs a briefing build,
#       so it needs neither Open-Meteo nor Claude. Only this script sets it.
#   --photocast.sub-regions.enforce=true  matches production (`application-prod.yml`): a Lite reader's ratings
#       outside their sub-region picks are stripped and marked `gated` by LitePickShaper. The seeder gives `lite`
#       picks for two of the three regions (Phase 3a.2); admin and pro are unrestricted whatever this says.
#   ...group.container.include=db,ping,e2eFixture  makes /actuator/health/container wait for the seed:
#       Tomcat accepts requests before ApplicationReadyEvent listeners finish. Only the smoke group changes.
#   ...health.cache.time-to-live=0s  so that probe never serves a stale DOWN after the seed lands.
#
# NOTHING MAY LEAVE THIS MACHINE. The JVM gets a DEAD proxy (nothing listens on DEAD_PROXY_PORT), so every
# outbound HTTP(S) call, Open-Meteo, WorldTides and Anthropic alike (the SDK's OkHttp client uses the default
# ProxySelector, which reads these properties), fails at once with a refused connection. Only loopback is
# exempt. The external health probes therefore read DOWN and the status stream reports DEGRADED; no spec
# asserts on that banner. CI also greps backend.log for the build-path log lines (see the e2e job).
DEAD_PROXY_HOST="127.0.0.1"
DEAD_PROXY_PORT="${E2E_DEAD_PROXY_PORT:-9}"
exec java \
  -Dhttps.proxyHost="$DEAD_PROXY_HOST" -Dhttps.proxyPort="$DEAD_PROXY_PORT" \
  -Dhttp.proxyHost="$DEAD_PROXY_HOST" -Dhttp.proxyPort="$DEAD_PROXY_PORT" \
  -Dhttp.nonProxyHosts='localhost|127.0.0.1' \
  -jar "$JAR" --spring.profiles.active=local --server.port="$API_PORT" \
  --cors.extra-origins="http://127.0.0.1:$WEB_PORT" \
  --photocast.e2e.seed-fixture=true \
  --photocast.sub-regions.enforce=true \
  --management.endpoint.health.group.container.include=db,ping,e2eFixture \
  --management.endpoint.health.cache.time-to-live=0s \
  >"$LOG_DIR/backend.log" 2>&1
