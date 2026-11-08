#!/bin/bash
# Tests for scripts/pre-release-backup.sh (docs/engineering/engineering-gaps-plan.md, Phase 4, R6, R7, R20):
# it saves the OUTGOING nginx.conf and docker-compose.yml beside the manifest, and it never overwrites a
# rollback point that already exists for a version.
#
# A stub `docker` answers the handful of questions the script asks (is the database up, which image is
# running, what is its digest, the schema version, a dump) and logs every call.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

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
    mkdir -p "$CASE/bin" "$CASE/compose" "$CASE/backups"
    cat > "$CASE/bin/docker" <<'EOF'
#!/bin/bash
echo "docker $*" >> "$CALL_LOG"
case "$*" in
    *State.Running*) echo true ;;
    *"{{.Image}}"*) echo "sha256:image-id" ;;
    *RepoDigests*) echo "ghcr.io/gregochr/goldenhour-${IMAGE_FLAVOUR:-old}@sha256:$(printf '%s' "${IMAGE_FLAVOUR:-old}" | sha256sum | cut -c1-64)" ;;
    *psql*) echo "195" ;;
    *pg_dump*) echo "-- a fake dump taken at ${DUMP_STAMP:-first}" ;;
esac
exit 0
EOF
    chmod +x "$CASE/bin/docker"
    echo "OUTGOING nginx" > "$CASE/compose/nginx.conf"
    echo "OUTGOING compose" > "$CASE/compose/docker-compose.yml"
    : > "$CASE/calls.log"
    export CALL_LOG="$CASE/calls.log" BACKUP_DIR="$CASE/backups" COMPOSE_DIR="$CASE/compose"
    export DEPLOYED_IMAGES_LEDGER="$CASE/no-ledger.json"
    export PATH="$CASE/bin:$ORIGINAL_PATH"
    export IMAGE_FLAVOUR=old DUMP_STAMP=first
    R="$BACKUP_DIR/pre-release"
}

run_backup() {
    OUT="$(bash "$SCRIPTS/pre-release-backup.sh" "$@" 2>&1)"
    RC=$?
}

echo "-- first run: dump, manifest and the OUTGOING config files"
new_case
run_backup v9
assert_eq "exit 0" 0 "$RC"
assert_file_exists "dump written" "$R/goldenhour_pre-v9.sql.gz"
assert_file_exists "manifest written" "$R/v9.json"
assert_eq "manifest is valid JSON" 0 "$(jq -e . "$R/v9.json" >/dev/null 2>&1; echo $?)"
assert_eq "saved nginx.conf is the outgoing file" "OUTGOING nginx" "$(cat "$R/v9.nginx.conf")"
assert_eq "saved compose file is the outgoing file" "OUTGOING compose" "$(cat "$R/v9.docker-compose.yml")"
assert_eq "manifest names the nginx copy" "v9.nginx.conf" "$(jq -r .nginx_conf "$R/v9.json")"
assert_eq "manifest names the compose copy" "v9.docker-compose.yml" "$(jq -r .compose_file "$R/v9.json")"
assert_contains "manifest keeps the digest key rollback.sh reads" "$(cat "$R/v9.json")" '"backend": "ghcr.io/gregochr/goldenhour-old@sha256:'
assert_eq "manifest schema version" "195" "$(jq -r .rollback_to.schema_version "$R/v9.json")"

echo "-- the manifest is still readable by both manifest readers"
MANIFEST="$R/v9.json"
# shellcheck source=scripts/lib/release-manifest.sh
source "$SCRIPTS/lib/release-manifest.sh"
assert_contains "field() finds the backend" "$(field backend)" "@sha256:"
assert_eq "field() finds the dump" "goldenhour_pre-v9.sql.gz" "$(field dump)"
assert_eq "field() finds schema_version" "195" "$(field schema_version)"

echo "-- re-run of the same tag: the first rollback point is kept, nothing is overwritten"
cp "$R/v9.json" "$CASE/manifest.first"
cp "$R/goldenhour_pre-v9.sql.gz" "$CASE/dump.first"
# By now the "new" release is running, its config is on the host, and the dump would differ.
echo "NEW nginx" > "$COMPOSE_DIR/nginx.conf"
echo "NEW compose" > "$COMPOSE_DIR/docker-compose.yml"
export IMAGE_FLAVOUR=new DUMP_STAMP=second
: > "$CALL_LOG"
run_backup v9
assert_eq "exit 0 (a legitimate re-run is not an abort)" 0 "$RC"
assert_contains "says it kept the first" "$OUT" "keeping the first"
assert_eq "manifest byte-for-byte unchanged" "$(cat "$CASE/manifest.first")" "$(cat "$R/v9.json")"
assert_eq "dump byte-for-byte unchanged" "$(md5sum < "$CASE/dump.first")" "$(md5sum < "$R/goldenhour_pre-v9.sql.gz")"
assert_eq "saved nginx.conf still the OUTGOING one" "OUTGOING nginx" "$(cat "$R/v9.nginx.conf")"
assert_eq "saved compose file still the OUTGOING one" "OUTGOING compose" "$(cat "$R/v9.docker-compose.yml")"
assert_eq "docker was not even asked" "" "$(cat "$CALL_LOG")"
assert_eq "no clutter left beside it" 4 "$(find "$R" -type f | wc -l)"

echo "-- a different version gets its own rollback point"
run_backup v10
assert_eq "exit 0" 0 "$RC"
assert_file_exists "v10 manifest" "$R/v10.json"
assert_eq "v10 saved the config as it is now" "NEW nginx" "$(cat "$R/v10.nginx.conf")"
assert_eq "v9 untouched" "$(cat "$CASE/manifest.first")" "$(cat "$R/v9.json")"

echo "-- an unusable earlier manifest (corrupt dump) is moved aside, never deleted"
new_case
run_backup v9
echo "not gzip" > "$R/goldenhour_pre-v9.sql.gz"
run_backup v9
assert_eq "exit 0" 0 "$RC"
assert_eq "a fresh dump passes gzip -t" 0 "$(gzip -t "$R/goldenhour_pre-v9.sql.gz" 2>/dev/null; echo $?)"
assert_eq "the corrupt dump was kept under another name" 1 "$(find "$R" -name 'goldenhour_pre-v9.sql.gz.unusable-*' | wc -l)"
assert_eq "the old manifest was kept under another name" 1 "$(find "$R" -name 'v9.json.unusable-*' | wc -l)"
assert_contains "warns about it" "$OUT" "moved an unusable earlier file aside"

echo "-- a dump with no manifest (a crash between the two) is moved aside too"
new_case
mkdir -p "$R"
echo "orphan" > "$R/goldenhour_pre-v9.sql.gz"
run_backup v9
assert_eq "exit 0" 0 "$RC"
assert_eq "orphan dump kept" 1 "$(find "$R" -name 'goldenhour_pre-v9.sql.gz.unusable-*' | wc -l)"
assert_file_exists "manifest written" "$R/v9.json"

echo "-- a config file that is missing: images only, the manifest says nothing about it"
new_case
rm "$COMPOSE_DIR/docker-compose.yml"
run_backup v9
assert_eq "exit 0 (the release is not aborted over config)" 0 "$RC"
assert_contains "warns" "$OUT" "WARNING: could not save"
assert_eq "manifest is valid JSON" 0 "$(jq -e . "$R/v9.json" >/dev/null 2>&1; echo $?)"
assert_eq "no compose_file key" "null" "$(jq -r .compose_file "$R/v9.json")"
assert_eq "nginx_conf still recorded" "v9.nginx.conf" "$(jq -r .nginx_conf "$R/v9.json")"
assert_file_missing "no half-written copy" "$R/v9.docker-compose.yml.partial"

echo "-- no digests: still aborts the release (unchanged behaviour)"
new_case
cat > "$CASE/bin/docker" <<'EOF'
#!/bin/bash
case "$*" in
    *State.Running*) echo true ;;
    *"{{.Image}}"*) echo "sha256:x" ;;
esac
exit 0
EOF
run_backup v9
assert_eq "exit 1" 1 "$RC"
assert_file_missing "no manifest" "$R/v9.json"

finish_tests
