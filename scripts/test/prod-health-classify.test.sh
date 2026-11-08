#!/bin/bash
# Tests for `scripts/prod-health.sh --deploy --classify` (docs/engineering/engineering-gaps-plan.md, Phase 4, R2).
#
# The automatic rollback may only act on a RELEASE failure, never on a host problem, so the exit code
# has to tell them apart: 0 pass, 1 release (backend/frontend container), 3 infrastructure (database,
# cloudflared, Tailscale, the unreachable app). And without --classify nothing changes: any failure is
# exit 1 and the output is the same lines it always was (the daily morning run depends on that).

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

BIN="$(mktemp -d)"
trap 'rm -rf "$BIN"' EXIT

cat > "$BIN/docker" <<'EOF'
#!/bin/bash
printf '{"Service":"goldenhour-db","State":"running","Health":"%s"}\n' "$DB_HEALTH"
printf '{"Service":"goldenhour-backend","State":"running","Health":"%s"}\n' "$BE_HEALTH"
printf '{"Service":"goldenhour-frontend","State":"%s","Health":""}\n' "$FE_STATE"
EOF
cat > "$BIN/systemctl" <<'EOF'
#!/bin/bash
case "$1" in
    is-active) echo "$CF_STATE" ;;
    *) echo "" ;;
esac
EOF
cat > "$BIN/tailscale" <<'EOF'
#!/bin/bash
printf '{"BackendState":"%s"}\n' "$TS_STATE"
EOF
cat > "$BIN/curl" <<'EOF'
#!/bin/bash
printf '%s' "$APP_CODE"
EOF
printf '#!/bin/bash\nexit 0\n' > "$BIN/sleep"
chmod +x "$BIN"/*
export PATH="$BIN:$PATH" HEALTH_TIMEOUT=0 COMPOSE_DIR="$BIN"

healthy() {
    export DB_HEALTH=healthy BE_HEALTH=healthy FE_STATE=running CF_STATE=active TS_STATE=Running APP_CODE=401
}

# run_health ARGS...: sets RC and OUT
run_health() {
    OUT="$(bash "$SCRIPTS/prod-health.sh" "$@" 2>&1)"
    RC=$?
}

echo "-- everything healthy"
healthy
run_health --deploy --classify
assert_eq "classified: exit 0" 0 "$RC"
assert_contains "prints the class totals" "$OUT" "prod-health: release=0 infra=0"
run_health --deploy
assert_eq "plain --deploy: exit 0" 0 "$RC"
assert_not_contains "plain --deploy prints no class line" "$OUT" "release="

echo "-- the backend is unhealthy while the database is fine: a RELEASE failure"
healthy; export BE_HEALTH=unhealthy
run_health --deploy --classify
assert_eq "classified: exit 1" 1 "$RC"
assert_contains "counted as release" "$OUT" "release=1 infra=0"
run_health --deploy
assert_eq "plain --deploy: exit 1 as ever" 1 "$RC"

echo "-- the frontend is not running: a RELEASE failure"
healthy; export FE_STATE=exited
run_health --deploy --classify
assert_eq "classified: exit 1" 1 "$RC"

echo "-- the database is unhealthy: INFRASTRUCTURE, even though the backend is down with it"
healthy; export DB_HEALTH=unhealthy BE_HEALTH=unhealthy
run_health --deploy --classify
assert_eq "classified: exit 3" 3 "$RC"
assert_contains "counted as infra" "$OUT" "release=0 infra=1"
run_health --deploy
assert_eq "plain --deploy: still exit 1" 1 "$RC"

echo "-- cloudflared is down: INFRASTRUCTURE"
healthy; export CF_STATE=failed
run_health --deploy --classify
assert_eq "classified: exit 3" 3 "$RC"

echo "-- Tailscale needs login: INFRASTRUCTURE"
healthy; export TS_STATE=NeedsLogin
run_health --deploy --classify
assert_eq "classified: exit 3" 3 "$RC"

echo "-- the app answers 502 from outside: INFRASTRUCTURE (the container checks decide what counts as the release)"
healthy; export APP_CODE=502
run_health --deploy --classify
assert_eq "classified: exit 3" 3 "$RC"

echo "-- a release failure outranks an infrastructure one"
healthy; export BE_HEALTH=unhealthy CF_STATE=failed
run_health --deploy --classify
assert_eq "classified: exit 1" 1 "$RC"
assert_contains "both counted" "$OUT" "release=1 infra=1"

echo "-- usage: --classify only makes sense with --deploy; --daily is never classified"
run_health --daily --classify
assert_eq "--daily --classify is a usage error" 2 "$RC"
run_health --classify
assert_eq "--classify alone is a usage error" 2 "$RC"
run_health --deploy --bogus
assert_eq "an unknown second argument is a usage error" 2 "$RC"

finish_tests
