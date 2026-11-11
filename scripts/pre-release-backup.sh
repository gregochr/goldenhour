#!/bin/bash
# ============================================================================
# Pre-release database backup + rollback manifest
#
# Runs on the production Docker host from deploy.yml, immediately before the
# new images go up. Captures the three things a rollback needs, at one moment:
#
#   1. a database dump          — Flyway is forward-only, so old images alone
#                                 cannot undo a migration
#   2. the running image DIGESTS — not tags. `docker compose pull` overwrites
#                                 :latest, so after a bad release :latest IS
#                                 the bad release. A digest can never be
#                                 re-pointed, which is what makes the
#                                 association durable
#   3. the Flyway schema version — tells you at a glance whether a rollback
#                                 needs the restore at all, or just the images
#
# The dump and the digests only mean anything as a PAIR: restoring one without
# the other gives you a combination that has never been tested.
#
# It also saves the OUTGOING nginx.conf and docker-compose.yml beside the manifest
# (docs/engineering/engineering-gaps-plan.md, Phase 4, R6), read from the host's
# current files. So deploy.yml must run this BEFORE it materialises the new
# release's copies, or it would save the new ones. scripts/auto-rollback.sh
# restores both with the images, so configuration rolls back with the build it
# belongs to. A file that is missing is skipped with a warning (the manifest then
# simply has no key for it, and the automatic rollback says it is images-only).
#
# NEVER OVERWRITES A ROLLBACK POINT (R7). If a usable manifest already exists for
# this version, a re-run of the same tag keeps the first and exits 0: by then the
# first run may have started the new images, so "what is running now" is no
# longer the outgoing build and recording it would destroy the only good
# rollback point. An existing but unusable manifest, or a dump with no manifest,
# is moved aside (not deleted at that moment; it ages out under the retention rule below) and a fresh point taken.
#
# RETENTION. Nothing used to delete from pre-release/, so it grew by one full production dump per release for
# ever. After a NEW point has been written and its manifest validated and moved into place (and only then:
# not on the keep-first early exit, not on any failure path), the script keeps the newest PRE_RELEASE_KEEP
# usable rollback points (default 5, a whole number >= 1; anything else logs a WARNING and deletes nothing).
#   - A point is a version V and its WHOLE set: V.json, goldenhour_pre-V.sql.gz, V.nginx.conf,
#     V.docker-compose.yml, and any of those with an .unusable-<stamp> suffix. Sets go whole or not at all.
#   - USABLE = V.json passes manifest_complete and the dump exists (no gzip -t: too slow over several dumps).
#   - Sets are ranked by the newest mtime among their files. Every set strictly older than the Nth-newest
#     usable point is deleted. With fewer than N usable points nothing is deleted.
#   - Moved-aside (.unusable-*) files and unusable sets do NOT count toward N, so repeated failed re-runs
#     cannot push good rollback points out. They survive while they are newer than the Nth usable point and
#     age out with it.
#   - Never deleted: this release's own set, any file a kept manifest names (dump, nginx_conf, compose_file),
#     rollback.sh's goldenhour_pre-rollback-* safety dumps, *.partial files, directories, and anything that
#     does not match the shapes above (versions must look like v<digit>...).
#   - A retention failure is a WARNING only: the new rollback point exists, so the script still exits 0.
#
# Failure here aborts the release. That is deliberate, and differs from the
# nightly backup's off-box copy (non-fatal there): this is the one moment the
# backup is actually load-bearing.
#
# Exit codes: 0 a rollback point exists for this version (new, or kept from an
# earlier run); 1 it could not be taken, so the release must stop.
#
# Usage: ./pre-release-backup.sh v2.11.11
#   PRE_RELEASE_KEEP  How many usable rollback points to keep (default 5)
#   COMPOSE_DIR   Directory holding the outgoing nginx.conf and docker-compose.yml
#                 (default: $HOME/goldenhour)
# ============================================================================

set -euo pipefail

RELEASE_VERSION="${1:-}"
[ -n "$RELEASE_VERSION" ] || { echo "ERROR: release version required, e.g. $0 v2.11.11" >&2; exit 1; }

PG_CONTAINER="${PG_CONTAINER:-goldenhour-db}"
PG_DB="${PG_DB:-goldenhour}"
PG_USER="${PG_USER:-goldenhour}"
BACKUP_DIR="${BACKUP_DIR:-${HOME}/goldenhour-backups}"
# Deliberately NOT daily/: that directory rotates at 7 days, which would delete
# the dump you want after a regression that took a fortnight to notice.
RELEASE_DIR="${BACKUP_DIR}/pre-release"
PRE_RELEASE_KEEP="${PRE_RELEASE_KEEP:-5}"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-goldenhour-backend}"
# Written by scripts/record-deployed-images.sh after each successful deploy.
LEDGER="${DEPLOYED_IMAGES_LEDGER:-${HOME}/goldenhour-data/deployed-images.json}"
FRONTEND_CONTAINER="${FRONTEND_CONTAINER:-goldenhour-frontend}"

DUMP_FILE="${RELEASE_DIR}/goldenhour_pre-${RELEASE_VERSION}.sql.gz"
MANIFEST_FILE="${RELEASE_DIR}/${RELEASE_VERSION}.json"

COMPOSE_DIR="${COMPOSE_DIR:-${HOME}/goldenhour}"
NGINX_SAVED="${RELEASE_DIR}/${RELEASE_VERSION}.nginx.conf"
COMPOSE_SAVED="${RELEASE_DIR}/${RELEASE_VERSION}.docker-compose.yml"

mkdir -p "$RELEASE_DIR"
log() { echo "[pre-release-backup] $*"; }

# --- Never overwrite an existing rollback point (R7) --------------------------
# Checked before the docker preconditions on purpose: keeping a good point needs
# neither docker nor a running database, and a re-run must not die on them.
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
# A manifest is only worth keeping (or writing) if it PARSES and names everything a rollback needs: the dump,
# both digests and the schema version. A grep for the digests would accept a truncated file whose tail is gone.
manifest_complete() {
    if command -v python3 >/dev/null 2>&1; then
        python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
r = d["rollback_to"]
assert d["dump"] and r["backend"] and r["frontend"] and r["schema_version"]
' "$1" >/dev/null 2>&1
    else
        jq -e '(.dump | length > 0) and (.rollback_to.backend | length > 0) and (.rollback_to.frontend | length > 0)
               and (.rollback_to.schema_version | length > 0)' "$1" >/dev/null 2>&1
    fi
}
usable_existing_manifest() {
    [ -f "$MANIFEST_FILE" ] || return 1
    manifest_complete "$MANIFEST_FILE" || return 1
    [ -f "$DUMP_FILE" ] || return 1
    gzip -t "$DUMP_FILE" 2>/dev/null || return 1
    # STALE: record-deployed-images.sh writes the ledger after every SUCCESSFUL deploy. A ledger newer than this
    # manifest means a release went live after the manifest was taken, so its "outgoing" digests are no longer
    # what the next release replaces. Keeping it would roll back to a build two releases old.
    if [ -f "$LEDGER" ] && [ "$LEDGER" -nt "$MANIFEST_FILE" ]; then
        log "the manifest for ${RELEASE_VERSION} is older than the deploy ledger: a newer release went live since — it is stale."
        return 1
    fi
    return 0
}
if usable_existing_manifest; then
    log "a rollback point for ${RELEASE_VERSION} already exists (${MANIFEST_FILE}) — keeping the first and"
    log "making no new one: this looks like a re-run of the same tag, and what is running now may already be"
    log "the new release, so it must not be recorded as the outgoing build."
    exit 0
fi
# Anything else already there is preserved under a new name, never overwritten.
for stale in "$MANIFEST_FILE" "$DUMP_FILE" "$NGINX_SAVED" "$COMPOSE_SAVED"; do
    if [ -e "$stale" ]; then
        mv "$stale" "${stale}.unusable-${stamp}"
        log "WARNING: moved an unusable earlier file aside: ${stale}.unusable-${stamp}"
    fi
done

# --- Retention ----------------------------------------------------------------
# Reads one top-level string field of a manifest (empty when absent or unreadable).
manifest_field() {
    if command -v python3 >/dev/null 2>&1; then
        python3 -c '
import json, sys
print(json.load(open(sys.argv[1])).get(sys.argv[2]) or "")
' "$1" "$2" 2>/dev/null || true
    else
        jq -r --arg k "$2" '.[$k] // empty' "$1" 2>/dev/null || true
    fi
}

# Fills SET_FILES with every existing file of version $1's set (see RETENTION in the header).
set_files_of() {
    local v="$1" name f
    SET_FILES=()
    for name in "${v}.json" "goldenhour_pre-${v}.sql.gz" "${v}.nginx.conf" "${v}.docker-compose.yml"; do
        for f in "${RELEASE_DIR}/${name}" "${RELEASE_DIR}/${name}".unusable-*; do
            case "$f" in *.partial) continue ;; esac
            if [ -f "$f" ] && [ ! -L "$f" ]; then SET_FILES+=("$f"); fi
        done
    done
}

# Deletes whole rollback-point sets older than the Nth-newest usable point. Bash 3.2 safe (no associative
# arrays, no GNU stat/sort); mtimes are compared with -nt. Returns non-zero only to be reported as a warning.
prune_old_rollback_points() {
    local keep="$PRE_RELEASE_KEEP" f name base v i j n seen newest failed=0 remaining=0 del protect="" mf pf
    case "$keep" in
        ''|*[!0-9]*) log "WARNING: PRE_RELEASE_KEEP='${keep}' is not a whole number >= 1 — pruning nothing."; return 0 ;;
    esac
    [ "${#keep}" -le 6 ] || keep=999999
    if [ "$keep" -lt 1 ]; then
        log "WARNING: PRE_RELEASE_KEEP='${keep}' is not a whole number >= 1 — pruning nothing."
        return 0
    fi

    # Discover versions from every filename shape in the set.
    local versions=()
    for f in "${RELEASE_DIR}"/*; do
        if [ -f "$f" ] && [ ! -L "$f" ]; then :; else continue; fi
        name="${f##*/}"
        case "$name" in *.partial) continue ;; esac
        base="${name%.unusable-*}"
        case "$base" in
            goldenhour_pre-rollback-*) continue ;;
            goldenhour_pre-*.sql.gz) v="${base#goldenhour_pre-}"; v="${v%.sql.gz}" ;;
            *.nginx.conf) v="${base%.nginx.conf}" ;;
            *.docker-compose.yml) v="${base%.docker-compose.yml}" ;;
            *.json) v="${base%.json}" ;;
            *) continue ;;
        esac
        case "$v" in v[0-9]*) ;; *) continue ;; esac
        seen=0
        if [ "${#versions[@]}" -gt 0 ]; then
            for i in "${versions[@]}"; do
                if [ "$i" = "$v" ]; then seen=1; fi
            done
        fi
        if [ "$seen" -eq 0 ]; then versions+=("$v"); fi
    done
    n="${#versions[@]}"
    [ "$n" -gt 0 ] || return 0

    # Per version: newest file (by mtime) and whether the point is usable.
    local newest_of=() usable_of=()
    for ((i = 0; i < n; i++)); do
        v="${versions[$i]}"
        set_files_of "$v"
        newest=""
        if [ "${#SET_FILES[@]}" -gt 0 ]; then
            for f in "${SET_FILES[@]}"; do
                if [ -z "$newest" ] || [ "$f" -nt "$newest" ]; then newest="$f"; fi
            done
        fi
        newest_of[i]="$newest"
        usable_of[i]=0
        if [ -f "${RELEASE_DIR}/${v}.json" ] && manifest_complete "${RELEASE_DIR}/${v}.json" \
            && [ -f "${RELEASE_DIR}/goldenhour_pre-${v}.sql.gz" ]; then
            usable_of[i]=1
        fi
    done

    # A usable point is KEPT when fewer than $keep usable points are strictly newer than it.
    local kept=() usable_count=0 newer
    for ((i = 0; i < n; i++)); do
        [ "${usable_of[$i]}" -eq 1 ] || continue
        usable_count=$((usable_count + 1))
        newer=0
        for ((j = 0; j < n; j++)); do
            [ "${usable_of[$j]}" -eq 1 ] || continue
            if [ "${newest_of[$j]}" -nt "${newest_of[$i]}" ]; then newer=$((newer + 1)); fi
        done
        if [ "$newer" -lt "$keep" ]; then
            kept+=("$i")
            # Files a kept manifest names must survive even if they sit in an otherwise old set.
            mf="${RELEASE_DIR}/${versions[$i]}.json"
            for pf in dump nginx_conf compose_file; do
                name="$(manifest_field "$mf" "$pf")"
                if [ -n "$name" ]; then protect="${protect}
${name##*/}"; fi
            done
        fi
    done
    if [ "$usable_count" -le "$keep" ]; then
        log "retention: ${usable_count} usable rollback point(s), keeping up to ${keep} — nothing to prune."
        return 0
    fi

    remaining="$usable_count"
    for ((i = 0; i < n; i++)); do
        v="${versions[$i]}"
        [ "$v" != "$RELEASE_VERSION" ] || continue
        # Strictly older than EVERY kept point, i.e. older than the Nth-newest usable one.
        del=1
        for j in "${kept[@]}"; do
            if ! [ "${newest_of[$j]}" -nt "${newest_of[$i]}" ]; then del=0; break; fi
        done
        [ "$del" -eq 1 ] || continue
        set_files_of "$v"
        [ "${#SET_FILES[@]}" -gt 0 ] || continue
        for f in "${SET_FILES[@]}"; do
            case "
${protect}
" in
                *"
${f##*/}
"*) log "retention: keeping ${f##*/} (named by a kept manifest)"; continue ;;
            esac
            if rm -f -- "$f"; then
                log "pruned old rollback point: ${f}"
            else
                log "WARNING: could not delete ${f}"
                failed=1
            fi
        done
        if [ "${usable_of[$i]}" -eq 1 ]; then remaining=$((remaining - 1)); fi
    done
    log "retention: ${remaining} usable rollback point(s) remain (keep ${keep})."
    return "$failed"
}

# --- Preconditions ------------------------------------------------------------
# Checked separately from the container probe below, which the other two scripts
# already do (backup-postgres.sh, migrate-pgdata-path.sh). Without it a missing
# docker binary still fails closed, but reports "goldenhour-db is not running
# here" — which sends you looking at the container on a host where the problem is
# the host.
command -v docker >/dev/null 2>&1 \
    || { echo "ERROR: docker not found on PATH. This must run on the Docker host." >&2; exit 1; }

# Exact match on a captured value rather than `| grep -q true`. grep's match is
# unanchored, so it also accepts "untrue" and would accept the struct dump you
# get if this format string is ever shortened to {{.State}} — which contains the
# word `true` even for a stopped container, i.e. it would fail OPEN. Comparing
# the whole value fails closed instead. (No pipe also makes the pipefail/SIGPIPE
# shape structurally impossible here, though measurement says it never fired:
# 6000 runs of the piped form, zero spurious failures — the output is 5 bytes.)
[ "$(docker inspect "$PG_CONTAINER" --format='{{.State.Running}}' 2>/dev/null)" = "true" ] \
    || { echo "ERROR: ${PG_CONTAINER} is not running here — cannot back up before release." >&2; exit 1; }

# --- Record what is running RIGHT NOW, before anything is pulled --------------
# Must happen before `docker compose pull`, which overwrites the :latest tag and
# destroys the only other handle on the outgoing build.
# Two hops, deliberately. `docker inspect <container>` returns a container object,
# which has NO RepoDigests field — that lives on the image. Asking a container for
# it makes the Go template error, and with 2>/dev/null the error is invisible and
# the output empty, so the guard below fired on every release and aborted the
# deploy before `docker compose pull`. The container's .Image is the image ID,
# which does carry RepoDigests.
digest_of() {
    local container="$1" image_id digest
    image_id="$(docker inspect --format='{{.Image}}' "$container" 2>/dev/null)" || return 0
    [ -n "$image_id" ] || return 0
    digest="$(docker inspect --format='{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' \
        "$image_id" 2>/dev/null || true)"
    if [ -n "$digest" ]; then
        printf '%s' "$digest"
        return 0
    fi
    # The image is running but reports no RepoDigest. Observed once, on
    # 2026-07-29, after a `docker compose pull` between releases moved :latest —
    # it aborted that deploy with `backend=''`. The precise condition is NOT
    # asserted here: it is image-store-dependent (this host uses the containerd
    # snapshotter) and could not be reproduced cleanly afterwards.
    #
    # Either way the container is fine and only the registry reference is
    # missing, so fall back to the ledger written by the deploy that started it.
    ledger_digest "$container" "$image_id"
}

# Returns the recorded RepoDigest for a container, but ONLY when the ledger's
# image ID still matches the one actually running. An image ID is a content
# hash, so that match is what makes the recorded digest a checkable claim about
# THIS image rather than a guess about a previous one. On any mismatch it
# returns nothing and the caller aborts, exactly as it would have without a
# ledger — a manifest pointing at the wrong build is worse than no manifest.
ledger_digest() {
    local container="$1" running_id="$2"
    [ -f "$LEDGER" ] || return 0
    command -v python3 >/dev/null 2>&1 || return 0
    # stdout is the digest (empty when unusable); any explanation goes to stderr.
    CONTAINER="$container" RUNNING_ID="$running_id" LEDGER="$LEDGER" python3 - <<'PYEOF'
import json, os, sys

container = os.environ["CONTAINER"]
try:
    with open(os.environ["LEDGER"]) as fh:
        entry = json.load(fh).get("containers", {}).get(container)
except Exception as exc:
    print(f"[pre-release-backup] ledger unreadable ({exc})", file=sys.stderr)
    sys.exit(0)

if not entry:
    print(f"[pre-release-backup] ledger has no record for {container}", file=sys.stderr)
    sys.exit(0)

if entry.get("imageId") != os.environ["RUNNING_ID"]:
    print(
        f"[pre-release-backup] ledger for {container} records a DIFFERENT image "
        "than the one running — ignoring it rather than pinning the wrong build.",
        file=sys.stderr,
    )
    sys.exit(0)

print(f"[pre-release-backup] {container}: image carries no RepoDigest (displaced by a "
      "newer :latest); recovered from the deploy ledger.", file=sys.stderr)
sys.stdout.write(entry.get("repoDigest", ""))
PYEOF
}

PREV_BACKEND="$(digest_of "$BACKEND_CONTAINER")"
PREV_FRONTEND="$(digest_of "$FRONTEND_CONTAINER")"

if [ -z "$PREV_BACKEND" ] || [ -z "$PREV_FRONTEND" ]; then
    # A locally-built image has no RepoDigest. Better to stop than to write a
    # manifest that looks complete and cannot actually be rolled back to.
    echo "ERROR: could not read image digests (backend='${PREV_BACKEND}' frontend='${PREV_FRONTEND}')." >&2
    echo "       A manifest without digests cannot be rolled back to, so the release is stopping here." >&2
    exit 1
fi

# Flyway's `version` column is VARCHAR, so a plain MAX(version) sorts as TEXT and
# returns '99' once V100+ exists — understating how far the schema has moved, in
# the reassuring direction. Order on the leading integer instead.
#
# This constant is duplicated in scripts/rollback.sh rather than sourced from a
# shared file, and must stay that way: rollback.sh is the manual Plan B and must
# not depend on a sibling file in the host's working copy, and this script is
# kept standalone-extractable too (deploy.yml now extracts the tag's whole
# scripts/ directory, but this constant predates that). The duplication is the
# cost of being standalone.
#
# It has already drifted once: #364 deleted this block while leaving the use
# below, and because that use ends in `|| echo 'unknown'`, the unbound variable
# was swallowed and every manifest since recorded schema_version "unknown"
# without failing a release. CI now runs shellcheck with
# -o check-unassigned-uppercase, which catches exactly that.
SCHEMA_VERSION_SQL="SELECT COALESCE((
    SELECT version FROM flyway_schema_history
    WHERE success AND version IS NOT NULL
    ORDER BY CAST(SUBSTRING(version, '^[0-9]+') AS INTEGER) DESC, installed_rank DESC
    LIMIT 1), 'none')"

SCHEMA_VERSION="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "$SCHEMA_VERSION_SQL" 2>/dev/null \
    | tr -d '[:space:]' || echo 'unknown')"

# Validate the RESULT, not just the definition. An unbound query variable is only
# one way to land here — psql failing (database mid-restart, wrong credentials)
# produces the same 'unknown' through the same `||`, with the error hidden by
# 2>/dev/null. Deliberately a warning and not an abort: the header reserves
# aborting for the dump and the digests, which are what a rollback actually
# needs, and refusing to release over an advisory field would be a worse trade.
case "$SCHEMA_VERSION" in
    ''|unknown|none)
        SCHEMA_VERSION="unknown"
        log "WARNING: could not read the Flyway schema version from ${PG_CONTAINER}."
        log "         The manifest will say 'unknown'. Rollback still works — the digests"
        log "         and the dump are what it needs — but you lose the at-a-glance check"
        log "         of whether a restore is required. Worth investigating after the release."
        ;;
esac

log "outgoing backend:  ${PREV_BACKEND}"
log "outgoing frontend: ${PREV_FRONTEND}"
log "schema version:    ${SCHEMA_VERSION}"

# --- Dump ---------------------------------------------------------------------
# --clean --if-exists so the dump can be restored straight over a live database
# during a rollback, rather than needing a manual drop first.
log "dumping ${PG_DB}..."
if ! docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" \
        --clean --if-exists --no-owner --no-privileges \
        | gzip > "${DUMP_FILE}.partial"; then
    rm -f "${DUMP_FILE}.partial"
    echo "ERROR: pg_dump failed — aborting the release rather than deploying without a backup." >&2
    exit 1
fi

if ! gzip -t "${DUMP_FILE}.partial"; then
    rm -f "${DUMP_FILE}.partial"
    echo "ERROR: dump failed its integrity check — aborting the release." >&2
    exit 1
fi

mv "${DUMP_FILE}.partial" "$DUMP_FILE"
log "dump written: ${DUMP_FILE} ($(du -h "$DUMP_FILE" | cut -f1))"

# --- Outgoing configuration (R6) ------------------------------------------------
# Copied from the host's CURRENT files, which are still the outgoing release's:
# deploy.yml runs this before it overwrites them. Written before the manifest,
# which is the commit marker, so a manifest never names a file that is not there.
save_config() {
    local src="$1" dest="$2"
    if [ -f "$src" ] && cp "$src" "${dest}.partial" && mv "${dest}.partial" "$dest"; then
        log "saved outgoing $(basename "$src"): ${dest}"
        return 0
    fi
    rm -f "${dest}.partial"
    log "WARNING: could not save ${src} — an automatic rollback will restore the images only."
    return 1
}
CONFIG_JSON=""
if save_config "${COMPOSE_DIR}/nginx.conf" "$NGINX_SAVED"; then
    CONFIG_JSON="${CONFIG_JSON}  \"nginx_conf\": \"$(basename "$NGINX_SAVED")\",
"
fi
if save_config "${COMPOSE_DIR}/docker-compose.yml" "$COMPOSE_SAVED"; then
    CONFIG_JSON="${CONFIG_JSON}  \"compose_file\": \"$(basename "$COMPOSE_SAVED")\",
"
fi

# --- Manifest -----------------------------------------------------------------
# Written beside the real name and moved into place only once it has been validated, so a crash or a failed
# validation can never leave a truncated manifest where a rollback (or a re-run's keep-first check) would find it.
cat > "${MANIFEST_FILE}.partial" <<JSON
{
  "releasing": "${RELEASE_VERSION}",
  "taken_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "rollback_to": {
    "backend": "${PREV_BACKEND}",
    "frontend": "${PREV_FRONTEND}",
    "schema_version": "${SCHEMA_VERSION}"
  },
${CONFIG_JSON}  "dump": "$(basename "$DUMP_FILE")"
}
JSON

# Validate now, while it is cheap. A manifest that only turns out to be
# malformed on the night you need it is worth no more than no manifest at all.
if ! manifest_complete "${MANIFEST_FILE}.partial"; then
    rm -f "${MANIFEST_FILE}.partial"
    echo "ERROR: wrote an invalid or incomplete manifest — aborting the release." >&2
    exit 1
fi
mv "${MANIFEST_FILE}.partial" "$MANIFEST_FILE"

log "manifest written: ${MANIFEST_FILE}"
log "rollback with: ./scripts/rollback.sh ${RELEASE_VERSION}"

# Retention runs last, and only now that the new point is complete. A failure here must never fail the release:
# the rollback point exists. (`||` also keeps set -e from firing inside the function.)
prune_old_rollback_points || log "WARNING: pre-release retention hit a problem; some old rollback points were not removed."
exit 0
