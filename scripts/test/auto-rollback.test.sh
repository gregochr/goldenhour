#!/bin/bash
# Tests for scripts/auto-rollback.sh (docs/engineering/engineering-gaps-plan.md, Phase 4, R9-R12, R19).
#
# A stub `docker`, `psql`, `pg_dump` and `pg_restore` sit first on PATH and log every call; the sibling
# prod-health.sh and post-deploy-smoke.sh are stubs whose exit codes the case chooses. The central
# assertion, made in every case that runs anything: no database command is EVER issued.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

DIGEST_B="ghcr.io/gregochr/goldenhour-backend@sha256:$(printf 'a%.0s' {1..64})"
DIGEST_F="ghcr.io/gregochr/goldenhour-frontend@sha256:$(printf 'b%.0s' {1..64})"
CASES=()

cleanup_cases() {
    local d
    for d in "${CASES[@]}"; do rm -rf "$d"; done
}
trap cleanup_cases EXIT

# new_case: a fresh sandbox. Sets CASE and exports everything the script and the stubs read.
new_case() {
    CASE="$(mktemp -d)"
    CASES+=("$CASE")
    mkdir -p "$CASE/s/lib" "$CASE/bin" "$CASE/compose" "$CASE/backups/pre-release"
    cp "$SCRIPTS/auto-rollback.sh" "$CASE/s/"
    cp "$SCRIPTS/lib/release-manifest.sh" "$CASE/s/lib/"

    cat > "$CASE/s/prod-health.sh" <<'EOF'
#!/bin/bash
echo "prod-health $*" >> "$CALL_LOG"
echo "prod-health: stub exits ${STUB_HEALTH_RC:-0}"
exit "${STUB_HEALTH_RC:-0}"
EOF
    cat > "$CASE/s/post-deploy-smoke.sh" <<'EOF'
#!/bin/bash
echo "post-deploy-smoke $*" >> "$CALL_LOG"
echo "post-deploy-smoke: stub exits ${STUB_SMOKE_RC:-0}"
exit "${STUB_SMOKE_RC:-0}"
EOF

    # docker logs every call; on `compose up` it snapshots what Compose would have read at that moment.
    cat > "$CASE/bin/docker" <<'EOF'
#!/bin/bash
echo "docker $*" >> "$CALL_LOG"
case "$1 $2" in
    "compose up")
        n=$(( $(cat "$CASE_DIR/up.count" 2>/dev/null || echo 0) + 1 ))
        echo "$n" > "$CASE_DIR/up.count"
        cp "$COMPOSE_DIR/nginx.conf" "$CASE_DIR/up$n.nginx" 2>/dev/null || true
        cp "$COMPOSE_DIR/docker-compose.yml" "$CASE_DIR/up$n.compose" 2>/dev/null || true
        cp "$COMPOSE_DIR/docker-compose.override.yml" "$CASE_DIR/up$n.override" 2>/dev/null || true
        [ "$n" = "${STUB_UP_FAIL_AT:-0}" ] && exit 1
        ;;
esac
exit 0
EOF
    for db in psql pg_dump pg_restore pg_dumpall; do
        # shellcheck disable=SC2016  # $* and $CALL_LOG are meant to expand when the STUB runs
        printf '#!/bin/bash\necho "FORBIDDEN-DB-COMMAND %s $*" >> "$CALL_LOG"\nexit 0\n' "$db" > "$CASE/bin/$db"
    done
    chmod +x "$CASE"/s/*.sh "$CASE"/bin/*

    echo "NEW nginx" > "$CASE/compose/nginx.conf"
    echo "NEW compose" > "$CASE/compose/docker-compose.yml"
    echo "OLD nginx" > "$CASE/backups/pre-release/v9.nginx.conf"
    echo "OLD compose" > "$CASE/backups/pre-release/v9.docker-compose.yml"
    : > "$CASE/calls.log"

    export CASE_DIR="$CASE" CALL_LOG="$CASE/calls.log" COMPOSE_DIR="$CASE/compose"
    export BACKUP_DIR="$CASE/backups" GIT_REPO_DIR="$CASE/no-such-repo"
    export PATH="$CASE/bin:$ORIGINAL_PATH"
    unset STUB_HEALTH_RC STUB_SMOKE_RC STUB_UP_FAIL_AT ROLLBACK_DEADLINE_SECONDS GITHUB_ACTIONS
}
ORIGINAL_PATH="$PATH"

# write_manifest SCHEMA [config|noconfig]
write_manifest() {
    local cfg=""
    if [ "${2:-config}" = config ]; then
        cfg='  "nginx_conf": "v9.nginx.conf",
  "compose_file": "v9.docker-compose.yml",
'
    fi
    cat > "$BACKUP_DIR/pre-release/v9.json" <<JSON
{
  "releasing": "v9",
  "taken_at": "2026-11-08T10:00:00Z",
  "rollback_to": {
    "backend": "${DIGEST_B}",
    "frontend": "${DIGEST_F}",
    "schema_version": "${1}"
  },
${cfg}  "dump": "goldenhour_pre-v9.sql.gz"
}
JSON
}

# make_repo MIGRATION_COUNT: a throwaway repository whose tag v9 carries V1..Vn migrations.
make_repo() {
    local repo="$CASE/repo" dir i
    dir="$repo/backend/src/main/resources/db/migration"
    mkdir -p "$dir"
    for ((i = 1; i <= $1; i++)); do : > "$dir/V${i}__m${i}.sql"; done
    git init -q "$repo"
    git -C "$repo" add -A
    git -C "$repo" -c user.email=t@example.com -c user.name=t commit -qm migrations
    git -C "$repo" tag v9
    export GIT_REPO_DIR="$repo"
}

run_rollback() {
    OUT="$(bash "$CASE/s/auto-rollback.sh" "$@" 2>&1)"
    RC=$?
}

no_db_commands() {
    assert_file_lacks "$1: no pg_restore, psql, pg_dump or pg_dumpall was ever run" "$CALL_LOG" "FORBIDDEN-DB-COMMAND"
    assert_file_lacks "$1: no docker exec either" "$CALL_LOG" "docker exec"
}

echo "-- missing manifest: nothing happens, exit 2"
new_case
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "says there is no rollback point" "$OUT" "no rollback point for v9"
assert_contains "banner is loud" "$OUT" "MANUAL ACTION NEEDED"
assert_eq "nothing was called" "" "$(cat "$CALL_LOG")"
assert_file_missing "no pin written" "$COMPOSE_DIR/docker-compose.override.yml"

echo "-- manifest with no digests: nothing happens, exit 2"
new_case
printf '{ "releasing": "v9", "rollback_to": { "schema_version": "5" } }\n' > "$BACKUP_DIR/pre-release/v9.json"
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "names the missing digests" "$OUT" "no usable image digests"
assert_eq "nothing was called" "" "$(cat "$CALL_LOG")"
assert_file_missing "no pin written" "$COMPOSE_DIR/docker-compose.override.yml"

echo "-- a digest that is not a digest is refused (it would be written into YAML)"
new_case
write_manifest 5
sed -i 's|"backend": ".*"|"backend": "evil image@sha256:x"|' "$BACKUP_DIR/pre-release/v9.json"
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_file_missing "no pin written" "$COMPOSE_DIR/docker-compose.override.yml"

echo "-- --dry-run prints the plan and runs nothing"
new_case
write_manifest 5
run_rollback v9 --dry-run
assert_eq "exit 0" 0 "$RC"
assert_contains "prints the backend digest" "$OUT" "$DIGEST_B"
assert_contains "prints the frontend digest" "$OUT" "$DIGEST_F"
assert_contains "says nothing was run" "$OUT" "nothing was run"
assert_eq "no command was called" "" "$(cat "$CALL_LOG")"
assert_file_missing "no pin written" "$COMPOSE_DIR/docker-compose.override.yml"
assert_eq "nginx.conf untouched" "NEW nginx" "$(cat "$COMPOSE_DIR/nginx.conf")"

echo "-- an existing pin is respected: exit 2, nothing changed"
new_case
write_manifest 5
printf '# Written by scripts/rollback.sh by hand\nservices: {}\n' > "$COMPOSE_DIR/docker-compose.override.yml"
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "banner names the existing pin" "$OUT" "already exists"
assert_eq "nothing was called" "" "$(cat "$CALL_LOG")"

echo "-- happy path: pin written with the exact digests, config restored, up on exactly two services"
new_case
write_manifest 5
run_rollback v9
assert_eq "exit 1 (rolled back is still a failed release)" 1 "$RC"
assert_contains "ROLLED BACK banner" "$OUT" "ROLLED BACK"
assert_contains "banner: database NOT restored" "$OUT" "DATABASE NOT RESTORED"
assert_contains "banner: how to roll forward" "$OUT" "To roll forward"
assert_contains "banner: removing the pin brings :latest back" "$OUT" "bring back :latest, which is the bad release"
assert_file_has "pin pins the backend digest" "$COMPOSE_DIR/docker-compose.override.yml" "image: ${DIGEST_B}"
assert_file_has "pin pins the frontend digest" "$COMPOSE_DIR/docker-compose.override.yml" "image: ${DIGEST_F}"
assert_file_has "pin names the script that wrote it" "$COMPOSE_DIR/docker-compose.override.yml" "scripts/auto-rollback.sh"
assert_file_has "pin names the version" "$COMPOSE_DIR/docker-compose.override.yml" "release v9"
assert_eq "docker compose up called exactly once" 1 "$(grep -c '^docker compose up' "$CALL_LOG")"
assert_file_has "up is --no-deps on exactly the two services" "$CALL_LOG" "docker compose up -d --no-deps --force-recreate goldenhour-backend goldenhour-frontend"
assert_file_has "the pin existed when Compose started" "$CASE/up1.override" "image: ${DIGEST_B}"
assert_eq "the OLD nginx.conf was in place at up" "OLD nginx" "$(cat "$CASE/up1.nginx")"
assert_eq "the OLD compose file was in place at up" "OLD compose" "$(cat "$CASE/up1.compose")"
assert_file_has "prod-health ran with --deploy --classify" "$CALL_LOG" "prod-health --deploy --classify"
assert_file_has "the smoke ran in rollback mode" "$CALL_LOG" "post-deploy-smoke --rollback-mode"
assert_not_contains "config restored, not images only" "$OUT" "IMAGES ONLY"
no_db_commands "happy path"

echo "-- manifest written before config was saved: images only, and it says so"
new_case
write_manifest 5 noconfig
run_rollback v9
assert_eq "exit 1" 1 "$RC"
assert_contains "banner says images only" "$OUT" "IMAGES ONLY"
assert_eq "NEW nginx.conf left alone" "NEW nginx" "$(cat "$COMPOSE_DIR/nginx.conf")"
assert_file_has "still pinned" "$COMPOSE_DIR/docker-compose.override.yml" "image: ${DIGEST_B}"
no_db_commands "images only"

echo "-- schema changed since the manifest: the banner says so, loudly"
new_case
write_manifest 1
make_repo 3
run_rollback v9
assert_eq "exit 1" 1 "$RC"
assert_contains "SCHEMA CHANGED banner" "$OUT" "SCHEMA CHANGED"
assert_contains "names the newest migration" "$OUT" "V3"
assert_contains "old code may fail on writes" "$OUT" "fail on writes"
assert_contains "database was not restored" "$OUT" "database was NOT restored"
no_db_commands "schema changed"

echo "-- schema unchanged: the banner says no restore was needed"
new_case
write_manifest 3
make_repo 3
run_rollback v9
assert_eq "exit 1" 1 "$RC"
assert_contains "reports no newer migration" "$OUT" "no newer migration"
assert_not_contains "no SCHEMA CHANGED alarm" "$OUT" "SCHEMA CHANGED"

echo "-- previous build unhealthy (release class): exit 2, pin stays, smoke not run"
new_case
write_manifest 5
export STUB_HEALTH_RC=1
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "MANUAL ACTION NEEDED" "$OUT" "MANUAL ACTION NEEDED"
assert_contains "banner says the pin is left in place" "$OUT" "LEFT IN PLACE"
assert_file_exists "pin stays" "$COMPOSE_DIR/docker-compose.override.yml"
assert_file_lacks "smoke was not run against a build that is not up" "$CALL_LOG" "post-deploy-smoke"
assert_contains "unknown schema comparison does not hide the rollback.sh advice" "$OUT" "rollback.sh v9"
no_db_commands "unhealthy previous build"

echo "-- previous build unhealthy AFTER a schema change: rollback.sh is recommended"
new_case
write_manifest 1
make_repo 3
export STUB_HEALTH_RC=1
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "recommends the full restore" "$OUT" "cannot read the newer schema"
assert_file_exists "pin stays" "$COMPOSE_DIR/docker-compose.override.yml"

echo "-- previous build unhealthy with the schema unchanged: the restore is NOT recommended"
new_case
write_manifest 3
make_repo 3
export STUB_HEALTH_RC=1
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "says a restore is not indicated" "$OUT" "NOT indicated"

echo "-- health check inconclusive (infrastructure): not verified, pin stays, exit 2"
new_case
write_manifest 5
export STUB_HEALTH_RC=3
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_file_exists "pin stays" "$COMPOSE_DIR/docker-compose.override.yml"
assert_contains "says the rollback is not verified" "$OUT" "NOT verified"

echo "-- only the smoke failed on the old build (health passed): the release is put back as it was, exit 2"
new_case
write_manifest 5
export STUB_SMOKE_RC=1
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "ROLLBACK FAILED banner" "$OUT" "ROLLBACK FAILED"
assert_contains "the release is back up as it was" "$OUT" "back up as it was"
assert_file_missing "pin removed" "$COMPOSE_DIR/docker-compose.override.yml"
assert_eq "NEW nginx.conf restored" "NEW nginx" "$(cat "$COMPOSE_DIR/nginx.conf")"
assert_eq "NEW compose restored" "NEW compose" "$(cat "$COMPOSE_DIR/docker-compose.yml")"
assert_eq "docker compose up called twice (back, then forward)" 2 "$(grep -c '^docker compose up' "$CALL_LOG")"
assert_eq "the second up ran without the pin" "" "$(cat "$CASE/up2.override" 2>/dev/null)"
assert_eq "the second up ran with the NEW nginx.conf" "NEW nginx" "$(cat "$CASE/up2.nginx")"
no_db_commands "restore forward"

echo "-- the restore-forward fails too: exit 2 and a different banner"
new_case
write_manifest 5
export STUB_SMOKE_RC=1 STUB_UP_FAIL_AT=2
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "banner says the rollback failed AND bringing the release back failed" "$OUT" "bringing v9 back up failed"

echo "-- smoke inconclusive on the old build (3 or 4): the rollback stands, exit 1, flagged"
new_case
write_manifest 5
export STUB_SMOKE_RC=3
run_rollback v9
assert_eq "exit 1" 1 "$RC"
assert_contains "ROLLED BACK" "$OUT" "ROLLED BACK"
assert_contains "flags the smoke as INCONCLUSIVE" "$OUT" "INCONCLUSIVE"
assert_file_exists "pin stays" "$COMPOSE_DIR/docker-compose.override.yml"

echo "-- docker compose up fails on the old images: exit 2, pin stays, nothing is verified"
new_case
write_manifest 5
export STUB_UP_FAIL_AT=1
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_file_exists "pin stays" "$COMPOSE_DIR/docker-compose.override.yml"
assert_file_lacks "prod-health not run" "$CALL_LOG" "prod-health"
no_db_commands "up failed"

echo "-- the deadline: the banner is printed even when it is reached"
new_case
write_manifest 5
export ROLLBACK_DEADLINE_SECONDS=0
run_rollback v9
assert_eq "exit 2" 2 "$RC"
assert_contains "banner printed" "$OUT" "MANUAL ACTION NEEDED"
assert_contains "banner mentions the deadline" "$OUT" "deadline"
assert_file_exists "pin stays" "$COMPOSE_DIR/docker-compose.override.yml"
no_db_commands "deadline"

echo "-- a signal mid-way: the banner is printed and the exit is 2"
new_case
write_manifest 5
cat > "$CASE/s/prod-health.sh" <<'EOF'
#!/bin/bash
sleep 30
EOF
bash "$CASE/s/auto-rollback.sh" v9 > "$CASE/term.out" 2>&1 &
pid=$!
sleep 2
kill -TERM "$pid"
wait "$pid"
RC=$?
assert_eq "exit 2 on SIGTERM" 2 "$RC"
assert_file_has "banner printed on SIGTERM" "$CASE/term.out" "MANUAL ACTION NEEDED"

echo "-- GITHUB_ACTIONS: the banner is also an ::error:: annotation"
new_case
write_manifest 5
export GITHUB_ACTIONS=true
run_rollback v9
assert_contains "annotation" "$OUT" "::error::auto-rollback v9: ROLLED BACK"

echo "-- usage errors are exit 2"
new_case
run_rollback
assert_eq "no version" 2 "$RC"
run_rollback "v9;rm -rf /"
assert_eq "a version that is not a tag" 2 "$RC"

finish_tests
