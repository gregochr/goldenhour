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
assert_eq "no clutter left beside it" 4 "$(find "$R" -type f | wc -l | tr -d ' ')"

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
assert_eq "the corrupt dump was kept under another name" 1 "$(find "$R" -name 'goldenhour_pre-v9.sql.gz.unusable-*' | wc -l | tr -d ' ')"
assert_eq "the old manifest was kept under another name" 1 "$(find "$R" -name 'v9.json.unusable-*' | wc -l | tr -d ' ')"
assert_contains "warns about it" "$OUT" "moved an unusable earlier file aside"

echo "-- a dump with no manifest (a crash between the two) is moved aside too"
new_case
mkdir -p "$R"
echo "orphan" > "$R/goldenhour_pre-v9.sql.gz"
run_backup v9
assert_eq "exit 0" 0 "$RC"
assert_eq "orphan dump kept" 1 "$(find "$R" -name 'goldenhour_pre-v9.sql.gz.unusable-*' | wc -l | tr -d ' ')"
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

echo "-- a STALE manifest (the deploy ledger is newer: a later release went live) is moved aside, not kept"
new_case
run_backup v9
touch -t 202601010000 "$R/v9.json" "$R/goldenhour_pre-v9.sql.gz"
echo '{}' > "$DEPLOYED_IMAGES_LEDGER"
touch -t 202601010100 "$DEPLOYED_IMAGES_LEDGER"
export IMAGE_FLAVOUR=newer
run_backup v9
assert_eq "exit 0" 0 "$RC"
assert_contains "says it is stale" "$OUT" "stale"
assert_eq "the stale manifest was kept under another name" 1 "$(find "$R" -name 'v9.json.unusable-*' | wc -l | tr -d ' ')"
assert_contains "a fresh point records the build running NOW" "$(cat "$R/v9.json")" "goldenhour-newer@sha256"

echo "-- a ledger OLDER than the manifest does not make it stale"
new_case
run_backup v9
echo '{}' > "$DEPLOYED_IMAGES_LEDGER"
touch -t 202001010000 "$DEPLOYED_IMAGES_LEDGER"
export IMAGE_FLAVOUR=newer
run_backup v9
assert_contains "kept" "$OUT" "keeping the first"
assert_not_contains "no fresh point" "$(cat "$R/v9.json")" "goldenhour-newer"

echo "-- a truncated manifest is not kept (it must PARSE)"
new_case
run_backup v9
head -c 60 "$R/v9.json" > "$CASE/trunc.json"
cp "$CASE/trunc.json" "$R/v9.json"
export IMAGE_FLAVOUR=newer
run_backup v9
assert_eq "exit 0" 0 "$RC"
assert_eq "the truncated manifest was moved aside" 1 "$(find "$R" -name 'v9.json.unusable-*' | wc -l | tr -d ' ')"
assert_eq "a complete manifest was taken" 0 "$(jq -e '.dump and .rollback_to.backend' "$R/v9.json" >/dev/null 2>&1; echo $?)"

echo "-- a manifest that parses but has no dump is not kept"
new_case
run_backup v9
jq 'del(.dump)' "$R/v9.json" > "$CASE/nodump.json"
cp "$CASE/nodump.json" "$R/v9.json"
run_backup v9
assert_eq "exit 0" 0 "$RC"
assert_eq "moved aside" 1 "$(find "$R" -name 'v9.json.unusable-*' | wc -l | tr -d ' ')"
assert_eq "the new manifest names its dump" "goldenhour_pre-v9.sql.gz" "$(jq -r .dump "$R/v9.json")"

echo "-- a manifest that fails validation is never left in place (written as .partial, moved only when valid)"
new_case
printf '#!/bin/bash\nexit 1\n' > "$CASE/bin/python3"
chmod +x "$CASE/bin/python3"
run_backup v9
assert_eq "exit 1: the release stops" 1 "$RC"
assert_file_missing "no manifest" "$R/v9.json"
assert_file_missing "no .partial left either" "$R/v9.json.partial"
assert_contains "says why" "$OUT" "invalid or incomplete manifest"

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

# ---------------------------------------------------------------------------------------------------------------
# Retention: keep the newest PRE_RELEASE_KEEP usable points, delete whole older sets.
# ---------------------------------------------------------------------------------------------------------------
# seed_point VERSION YYYYMMDDhhmm: a complete, usable point (all four files), aged with a portable touch -t.
seed_point() {
    local v="$1" t="$2"
    mkdir -p "$R"
    printf '{"releasing":"%s","taken_at":"x","rollback_to":{"backend":"x","frontend":"y","schema_version":"1"},"dump":"goldenhour_pre-%s.sql.gz","nginx_conf":"%s.nginx.conf","compose_file":"%s.docker-compose.yml"}\n' \
        "$v" "$v" "$v" "$v" > "$R/$v.json"
    echo "dump $v" | gzip > "$R/goldenhour_pre-$v.sql.gz"
    echo "nginx $v" > "$R/$v.nginx.conf"
    echo "compose $v" > "$R/$v.docker-compose.yml"
    touch -t "$t" "$R/$v.json" "$R/goldenhour_pre-$v.sql.gz" "$R/$v.nginx.conf" "$R/$v.docker-compose.yml"
}
# set_count VERSION: how many files of that version's set survive (4 = whole, 0 = gone).
set_count() {
    find "$R" -type f \( -name "$1.json*" -o -name "goldenhour_pre-$1.sql.gz*" -o -name "$1.nginx.conf*" \
        -o -name "$1.docker-compose.yml*" \) | wc -l | tr -d ' '
}
seed_seven() {
    seed_point v1 202601010000; seed_point v2 202601020000; seed_point v3 202601030000
    seed_point v4 202601040000; seed_point v5 202601050000; seed_point v6 202601060000
    seed_point v7 202601070000
}

echo "-- retention: 7 older usable points + a new one, default keep 5: the 3 oldest sets go whole"
new_case
seed_seven
run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_eq "v1 set gone" 0 "$(set_count v1)"
assert_eq "v2 set gone" 0 "$(set_count v2)"
assert_eq "v3 set gone" 0 "$(set_count v3)"
assert_eq "v4 whole" 4 "$(set_count v4)"
assert_eq "v7 whole" 4 "$(set_count v7)"
assert_eq "the new point whole" 4 "$(set_count v8)"
assert_contains "logs each deletion" "$OUT" "pruned old rollback point: $R/v1.json"
assert_contains "logs the summary" "$OUT" "5 usable rollback point(s) remain"

echo "-- retention: PRE_RELEASE_KEEP=2 override"
new_case
seed_seven
PRE_RELEASE_KEEP=2 run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_eq "v6 gone" 0 "$(set_count v6)"
assert_eq "v7 kept" 4 "$(set_count v7)"
assert_eq "new kept" 4 "$(set_count v8)"
assert_eq "oldest gone" 0 "$(set_count v1)"

echo "-- retention: fewer usable points than N, or exactly N, deletes nothing"
new_case
seed_point v1 202601010000; seed_point v2 202601020000
run_backup v3
assert_eq "exit 0" 0 "$RC"
assert_eq "v1 kept" 4 "$(set_count v1)"
assert_eq "v2 kept" 4 "$(set_count v2)"
new_case
seed_point v1 202601010000; seed_point v2 202601020000
PRE_RELEASE_KEEP=3 run_backup v3
assert_eq "exactly N: exit 0" 0 "$RC"
assert_eq "exactly N: v1 kept" 4 "$(set_count v1)"
assert_not_contains "exactly N: nothing pruned" "$OUT" "pruned old"

echo "-- retention: a failed backup deletes nothing (pg_dump fails, then no digests)"
new_case
seed_seven
cat > "$CASE/bin/docker" <<'EOT'
#!/bin/bash
case "$*" in
    *State.Running*) echo true ;;
    *"{{.Image}}"*) echo "sha256:x" ;;
    *RepoDigests*) echo "ghcr.io/x@sha256:abc" ;;
    *pg_dump*) exit 1 ;;
esac
exit 0
EOT
run_backup v8
assert_eq "pg_dump failure: exit 1" 1 "$RC"
assert_eq "pg_dump failure: v1 kept" 4 "$(set_count v1)"
assert_not_contains "pg_dump failure: nothing pruned" "$OUT" "pruned old"
cat > "$CASE/bin/docker" <<'EOT'
#!/bin/bash
case "$*" in
    *State.Running*) echo true ;;
esac
exit 0
EOT
run_backup v8
assert_eq "no digests: exit 1" 1 "$RC"
assert_eq "no digests: v1 kept" 4 "$(set_count v1)"

echo "-- retention: the keep-first re-run deletes nothing"
new_case
seed_seven
run_backup v8
assert_eq "first run pruned v1" 0 "$(set_count v1)"
seed_point v0 202512010000
run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_contains "kept the first" "$OUT" "keeping the first"
assert_not_contains "no pruning on the early exit" "$OUT" "pruned old"
assert_eq "the older point seeded after is untouched" 4 "$(set_count v0)"

echo "-- retention: unusable and moved-aside sets do not count toward N; an old one ages out"
new_case
seed_seven
# Newer unusable sets (a manifest with no dump; a version with only .unusable-* files) must not push v4 out.
echo '{}' > "$R/v20.json"
echo old > "$R/v21.json.unusable-20260116T000000Z"
touch -t 202601150000 "$R/v20.json"
touch -t 202601160000 "$R/v21.json.unusable-20260116T000000Z"
# An unusable set older than the 5th-newest usable point (v4): deleted with the age-out.
echo '{}' > "$R/v0.json"
echo d > "$R/goldenhour_pre-v0.sql.gz.unusable-20251201T000000Z"
touch -t 202512010000 "$R/v0.json" "$R/goldenhour_pre-v0.sql.gz.unusable-20251201T000000Z"
run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_eq "v4 (5th newest usable) kept despite the newer unusable sets" 4 "$(set_count v4)"
assert_eq "v3 (6th usable) gone" 0 "$(set_count v3)"
assert_eq "newer unusable manifest survives" 1 "$(set_count v20)"
assert_eq "newer moved-aside-only set survives" 1 "$(set_count v21)"
assert_eq "older unusable set deleted" 0 "$(set_count v0)"
new_case
seed_seven
echo x > "$R/v1.json.unusable-20260101T000000Z"
touch -t 202601010000 "$R/v1.json.unusable-20260101T000000Z"
PRE_RELEASE_KEEP=2 run_backup v8
assert_eq "a moved-aside file goes with its old version" 0 "$(set_count v1)"

echo "-- retention: safety dumps, partials, unrelated files and directories survive"
new_case
seed_seven
echo s > "$R/goldenhour_pre-rollback-v1-20260101T000000Z.sql.gz"
echo p > "$R/goldenhour_pre-v1.sql.gz.partial"
echo u > "$R/README.txt"
echo n > "$R/notes.json"
mkdir "$R/v2.json.d"
touch -t 202501010000 "$R/goldenhour_pre-rollback-v1-20260101T000000Z.sql.gz" "$R/goldenhour_pre-v1.sql.gz.partial" \
    "$R/README.txt" "$R/notes.json" "$R/v2.json.d"
PRE_RELEASE_KEEP=1 run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_eq "v7 pruned as expected" 0 "$(set_count v7)"
assert_file_exists "pre-rollback safety dump" "$R/goldenhour_pre-rollback-v1-20260101T000000Z.sql.gz"
assert_file_exists ".partial" "$R/goldenhour_pre-v1.sql.gz.partial"
assert_file_exists "unrelated file" "$R/README.txt"
assert_file_exists "a non-version json" "$R/notes.json"
assert_file_exists "directory" "$R/v2.json.d"

echo "-- retention: an invalid PRE_RELEASE_KEEP warns and deletes nothing"
for bad in 0 abc -1 1.5; do
    new_case
    seed_seven
    PRE_RELEASE_KEEP="$bad" run_backup v8
    assert_eq "keep=$bad: exit 0" 0 "$RC"
    assert_contains "keep=$bad: warns" "$OUT" "WARNING: PRE_RELEASE_KEEP='$bad'"
    assert_eq "keep=$bad: v1 kept" 4 "$(set_count v1)"
    assert_file_exists "keep=$bad: new point exists" "$R/v8.json"
done

echo "-- retention: a file named by a KEPT manifest is never deleted"
new_case
seed_seven
# v7 (kept) names v1's dump: v1's set is pruned, but that one file stays.
printf '{"releasing":"v7","taken_at":"x","rollback_to":{"backend":"x","frontend":"y","schema_version":"1"},"dump":"goldenhour_pre-v1.sql.gz"}\n' > "$R/v7.json"
touch -t 202601070000 "$R/v7.json"
PRE_RELEASE_KEEP=2 run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_file_exists "dump named by a kept manifest survives" "$R/goldenhour_pre-v1.sql.gz"
assert_file_missing "the rest of v1's set is gone" "$R/v1.json"
assert_eq "current version's set whole" 4 "$(set_count v8)"

echo "-- retention: the current version's set is never deleted"
new_case
seed_seven
# An old moved-aside copy of the current version's own file must survive its own release's pruning.
echo x > "$R/v8.json.unusable-20250101T000000Z"
touch -t 202501010000 "$R/v8.json.unusable-20250101T000000Z"
PRE_RELEASE_KEEP=1 run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_file_exists "moved-aside file of the current version survives" "$R/v8.json.unusable-20250101T000000Z"
assert_eq "new point whole (plus the moved-aside file)" 5 "$(set_count v8)"

echo "-- retention: an orphan old dump with no manifest is deleted with the age-out"
new_case
seed_seven
echo orphan > "$R/goldenhour_pre-v0.sql.gz"
touch -t 202512010000 "$R/goldenhour_pre-v0.sql.gz"
run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_file_missing "orphan dump pruned" "$R/goldenhour_pre-v0.sql.gz"

echo "-- retention: an rm failure is only a warning, the script still exits 0 and the new point exists"
new_case
seed_seven
cat > "$CASE/bin/rm" <<'EOT'
#!/bin/bash
case "$*" in *goldenhour_pre-v1.sql.gz*) exit 1 ;; esac
exec /bin/rm "$@"
EOT
chmod +x "$CASE/bin/rm"
run_backup v8
assert_eq "exit 0" 0 "$RC"
assert_contains "warns about the file" "$OUT" "WARNING: could not delete"
assert_contains "warns the retention hit a problem" "$OUT" "retention hit a problem"
assert_file_exists "the new manifest is in place" "$R/v8.json"
assert_file_exists "the file that could not be removed is still there" "$R/goldenhour_pre-v1.sql.gz"
assert_file_missing "other files of the set were still pruned" "$R/v1.json"

finish_tests

