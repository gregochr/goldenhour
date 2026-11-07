#!/usr/bin/env bash
# Starts the smoke suite's backend: the local-profile jar on 8083, against a FRESH H2 file in a
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
exec java -jar "$JAR" --spring.profiles.active=local --server.port=8083 \
  --cors.extra-origins=http://127.0.0.1:4173 >"$LOG_DIR/backend.log" 2>&1
