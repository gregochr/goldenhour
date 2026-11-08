#!/bin/bash
# Simulates the control flow of the deploy job's SSH script (docs/engineering/engineering-gaps-plan.md, Phase 4,
# R1, R3, R4, R6) with stubs: the script is extracted from .github/workflows/deploy.yml and run for real under a
# fake HOME, with stub `git`, `docker` and `mkdir` first on PATH and stub release scripts inside a fake tag.
#
# What it proves:
#   - a failure BEFORE `up` never reaches a rollback (and never reaches the pull);
#   - a release failure AFTER `up` with SMOKE_ENFORCE unset fails the job but does not roll back;
#   - with SMOKE_ENFORCE=true it rolls back, and the rollback's 1 or 2 is the job's exit code;
#   - inconclusive (3) and misconfigured (4) never roll back, enforced or not;
#   - record-deployed-images.sh runs only on success;
#   - the outgoing configuration is backed up BEFORE the new one is written;
#   - the smoke password reaches the release scripts' environment and is never printed.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

SECRET="s3cr3t-smoke-pw-DO-NOT-PRINT"
CASES=()
cleanup_cases() {
    local d
    for d in "${CASES[@]}"; do rm -rf "$d"; done
}
trap cleanup_cases EXIT
ORIGINAL_PATH="$PATH"

new_case() {
    CASE="$(mktemp -d)"
    CASES+=("$CASE")
    mkdir -p "$CASE/home/goldenhour" "$CASE/bin" "$CASE/fixture/scripts/lib" "$CASE/tagfiles"
    bash "$HERE/extract-deploy-script.sh" "$REPO/.github/workflows/deploy.yml" > "$CASE/deploy-script.sh"

    echo "OLD nginx" > "$CASE/home/goldenhour/nginx.conf"
    echo "OLD compose" > "$CASE/home/goldenhour/docker-compose.yml"
    echo "NEW nginx" > "$CASE/tagfiles/nginx.conf"
    printf 'services:\n  goldenhour-db:\n    volumes:\n      - pgdata:/var/lib/postgresql/data\n' > "$CASE/tagfiles/docker-compose.yml"
    cp "$REPO/scripts/lib/release-manifest.sh" "$CASE/fixture/scripts/lib/"

    # The stub release scripts record WHAT they saw into $EVENTS and exit as the case dictates.
    cat > "$CASE/fixture/scripts/pre-release-backup.sh" <<'EOF'
#!/bin/bash
echo "backup $1 nginx=$(cat "$HOME/goldenhour/nginx.conf") compose_dir=$COMPOSE_DIR" >> "$EVENTS"
exit "${STUB_BACKUP_RC:-0}"
EOF
    cat > "$CASE/fixture/scripts/prod-health.sh" <<'EOF'
#!/bin/bash
echo "health $*" >> "$EVENTS"
exit "${STUB_HEALTH_RC:-0}"
EOF
    cat > "$CASE/fixture/scripts/post-deploy-smoke.sh" <<'EOF'
#!/bin/bash
echo "smoke version=${RELEASE_VERSION:-unset} password=$([ -n "${SMOKE_DEPLOY_PASSWORD:-}" ] && echo set || echo unset) login_required=${SMOKE_LOGIN_REQUIRED:-unset}" >> "$EVENTS"
exit "${STUB_SMOKE_RC:-0}"
EOF
    cat > "$CASE/fixture/scripts/auto-rollback.sh" <<'EOF'
#!/bin/bash
echo "rollback $*" >> "$EVENTS"
exit "${STUB_ROLLBACK_RC:-1}"
EOF
    cat > "$CASE/fixture/scripts/record-deployed-images.sh" <<'EOF'
#!/bin/bash
echo "record" >> "$EVENTS"
exit 0
EOF

    cat > "$CASE/bin/git" <<'EOF'
#!/bin/bash
case "$1" in
    fetch) echo "git fetch" >> "$EVENTS" ;;
    archive)
        [ "${STUB_ARCHIVE_FAIL:-0}" = 1 ] && { echo "fatal: not a valid object name" >&2; exit 128; }
        tar -c -C "$CASE_DIR/fixture" scripts ;;
    show) cat "$CASE_DIR/tagfiles/${2#*:}" ;;
    *) echo "unexpected git $*" >&2; exit 99 ;;
esac
EOF
    cat > "$CASE/bin/docker" <<'EOF'
#!/bin/bash
echo "docker $1 $2" >> "$EVENTS"
if [ "$1" = run ]; then exit 1; fi
exit 0
EOF
    # The script makes /home/gregochr/... which a CI runner cannot; everything else goes to the real mkdir.
    cat > "$CASE/bin/mkdir" <<'EOF'
#!/bin/bash
for a in "$@"; do case "$a" in /home/gregochr/*) exit 0 ;; esac; done
exec /bin/mkdir "$@"
EOF
    chmod +x "$CASE"/bin/* "$CASE"/fixture/scripts/*.sh

    : > "$CASE/events.log"
    export CASE_DIR="$CASE" EVENTS="$CASE/events.log" HOME="$CASE/home" RELEASE_VERSION=v9
    export PATH="$CASE/bin:$ORIGINAL_PATH"
    export SMOKE_DEPLOY_PASSWORD="$SECRET"
    unset STUB_BACKUP_RC STUB_HEALTH_RC STUB_SMOKE_RC STUB_ROLLBACK_RC STUB_ARCHIVE_FAIL SMOKE_ENFORCE SMOKE_LOGIN_REQUIRED
}

run_deploy() {
    OUT="$(bash "$CASE/deploy-script.sh" 2>&1)"
    RC=$?
    EV="$(cat "$EVENTS")"
}

echo "-- everything passes"
new_case
run_deploy
assert_eq "exit 0" 0 "$RC"
assert_contains "the backup saw the OUTGOING nginx.conf (it runs before the new one is written)" "$EV" "backup v9 nginx=OLD nginx"
assert_eq "the new nginx.conf was then written" "NEW nginx" "$(cat "$HOME/goldenhour/nginx.conf")"
assert_contains "the backup is told where the compose directory is" "$EV" "compose_dir=$HOME/goldenhour"
assert_contains "pull then up" "$EV" "docker compose"
assert_contains "health runs with --deploy --classify" "$EV" "health --deploy --classify"
assert_contains "the smoke sees the version and the password" "$EV" "smoke version=v9 password=set"
assert_contains "images are recorded on success" "$EV" "record"
assert_not_contains "no rollback" "$EV" "rollback"
assert_not_contains "the password is never printed" "$OUT" "$SECRET"
assert_eq "the scripts directory was cleaned up" 0 "$(find /tmp -maxdepth 1 -name 'goldenhour-release-scripts.*' -newer "$CASE/deploy-script.sh" 2>/dev/null | wc -l)"

echo "-- no smoke password configured: the smoke is still run, and sees none"
new_case
export SMOKE_DEPLOY_PASSWORD=""
run_deploy
assert_eq "exit 0" 0 "$RC"
assert_contains "smoke saw no password" "$EV" "password=unset"

echo "-- SMOKE_LOGIN_REQUIRED reaches the smoke"
new_case
export SMOKE_LOGIN_REQUIRED=true
run_deploy
assert_contains "login_required forwarded" "$EV" "login_required=true"

echo "-- a failure BEFORE up: the backup fails"
new_case
export STUB_BACKUP_RC=1 SMOKE_ENFORCE=true
run_deploy
assert_eq "the job fails" 1 "$RC"
assert_not_contains "no pull or up" "$EV" "docker compose"
assert_not_contains "no health check" "$EV" "health"
assert_not_contains "no rollback" "$EV" "rollback"
assert_not_contains "nothing recorded" "$EV" "record"
assert_eq "nginx.conf untouched" "OLD nginx" "$(cat "$HOME/goldenhour/nginx.conf")"

echo "-- a failure BEFORE up: the scripts cannot be extracted from the tag"
new_case
export STUB_ARCHIVE_FAIL=1 SMOKE_ENFORCE=true
run_deploy
assert_eq "the job fails" 1 "$RC"
assert_contains "says why" "$OUT" "could not extract scripts/"
assert_not_contains "no backup" "$EV" "backup"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- a failure BEFORE up: a rollback pin is in place"
new_case
printf '# Written by scripts/auto-rollback.sh (the AUTOMATIC image-only rollback) for release v8\nservices: {}\n' > "$HOME/goldenhour/docker-compose.override.yml"
export SMOKE_ENFORCE=true
run_deploy
assert_eq "the job fails" 1 "$RC"
assert_contains "names the script that wrote the pin" "$OUT" "scripts/auto-rollback.sh"
assert_not_contains "no backup, no pull" "$EV" "backup"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- a failure BEFORE up: the PGDATA guard refuses a bind mount with no database (and reads the NEW compose file)"
new_case
printf 'services:\n  goldenhour-db:\n    volumes:\n      - /nonexistent/pgdata:/var/lib/postgresql/data\n' > "$CASE/tagfiles/docker-compose.yml"
export SMOKE_ENFORCE=true
run_deploy
assert_eq "the job fails" 1 "$RC"
assert_contains "the guard's message" "$OUT" "holds no Postgres data directory"
assert_contains "the backup still ran first" "$EV" "backup v9 nginx=OLD nginx"
assert_not_contains "no pull" "$EV" "docker compose"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- AFTER up, release broken (smoke exit 1), SMOKE_ENFORCE unset: fails, no rollback"
new_case
export STUB_SMOKE_RC=1
run_deploy
assert_eq "exit 1" 1 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"
assert_not_contains "not recorded as running" "$EV" "record"
assert_contains "says it is report-only" "$OUT" "NOT being rolled back (report-only)"

echo "-- AFTER up, release broken, SMOKE_ENFORCE=false or empty: still no rollback"
new_case
export STUB_SMOKE_RC=1 SMOKE_ENFORCE=false
run_deploy
assert_eq "exit 1" 1 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"
export SMOKE_ENFORCE=""
run_deploy
assert_not_contains "no rollback when empty" "$EV" "rollback"

echo "-- AFTER up, release broken (smoke exit 1), SMOKE_ENFORCE=true: rolls back"
new_case
export STUB_SMOKE_RC=1 SMOKE_ENFORCE=true STUB_ROLLBACK_RC=1
run_deploy
assert_eq "exit 1 (rolled back)" 1 "$RC"
assert_contains "auto-rollback ran for this version" "$EV" "rollback v9"
assert_not_contains "a rolled-back release is not recorded as running" "$EV" "record"
export STUB_ROLLBACK_RC=2
run_deploy
assert_eq "the rollback's exit 2 (manual action) is the job's exit" 2 "$RC"
export STUB_ROLLBACK_RC=0
run_deploy
assert_eq "a stray exit 0 from the rollback never makes the job green" 2 "$RC"

echo "-- AFTER up, containers unhealthy (health exit 1), enforced: rolls back and skips the smoke"
new_case
export STUB_HEALTH_RC=1 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 1" 1 "$RC"
assert_contains "auto-rollback ran" "$EV" "rollback v9"
assert_not_contains "smoke skipped" "$EV" "smoke"

echo "-- AFTER up, containers unhealthy, SMOKE_ENFORCE unset: no rollback"
new_case
export STUB_HEALTH_RC=1
run_deploy
assert_eq "exit 1" 1 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- AFTER up, infrastructure (health exit 3), smoke clean: the smoke STILL runs, the job fails with 3, no rollback"
new_case
export STUB_HEALTH_RC=3 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 3" 3 "$RC"
assert_contains "the smoke ran despite the health result" "$EV" "smoke version=v9"
assert_not_contains "no rollback" "$EV" "rollback"
assert_not_contains "no record" "$EV" "record"

echo "-- health 3 (e.g. nginx answering 502 for /api) but the smoke finds the release broken (1): rolls back when enforced"
new_case
export STUB_HEALTH_RC=3 STUB_SMOKE_RC=1 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 1 (rolled back)" 1 "$RC"
assert_contains "the smoke ran" "$EV" "smoke version=v9"
assert_contains "auto-rollback ran" "$EV" "rollback v9"

echo "-- the same without SMOKE_ENFORCE: reports only"
new_case
export STUB_HEALTH_RC=3 STUB_SMOKE_RC=1
run_deploy
assert_eq "exit 1" 1 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- health 3 and smoke 4: precedence 1 > 4 > 3 gives 4, never a rollback"
new_case
export STUB_HEALTH_RC=3 STUB_SMOKE_RC=4 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 4" 4 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- health 2 (a usage error): treated as infrastructure; the smoke still runs; never a rollback"
new_case
export STUB_HEALTH_RC=2 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 3" 3 "$RC"
assert_contains "the smoke ran" "$EV" "smoke version=v9"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- AFTER up, smoke inconclusive (3), enforced: never rolls back"
new_case
export STUB_SMOKE_RC=3 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 3" 3 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"
assert_not_contains "no record" "$EV" "record"

echo "-- AFTER up, smoke misconfigured (4), enforced: never rolls back"
new_case
export STUB_SMOKE_RC=4 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 4" 4 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"
assert_not_contains "no record" "$EV" "record"

echo "-- AFTER up, smoke exits something unexpected (7), enforced: inconclusive, never a rollback"
new_case
export STUB_SMOKE_RC=7 SMOKE_ENFORCE=true
run_deploy
assert_eq "exit 3" 3 "$RC"
assert_not_contains "no rollback" "$EV" "rollback"

echo "-- the secret never appears in any output"
assert_not_contains "last run's output has no password" "$OUT" "$SECRET"

finish_tests
