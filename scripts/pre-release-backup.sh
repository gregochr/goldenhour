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
# Failure here aborts the release. That is deliberate, and differs from the
# nightly backup's off-box copy (non-fatal there): this is the one moment the
# backup is actually load-bearing.
#
# Usage: ./pre-release-backup.sh v2.11.11
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
BACKEND_CONTAINER="${BACKEND_CONTAINER:-goldenhour-backend}"
# Written by scripts/record-deployed-images.sh after each successful deploy.
LEDGER="${DEPLOYED_IMAGES_LEDGER:-${HOME}/goldenhour-data/deployed-images.json}"
FRONTEND_CONTAINER="${FRONTEND_CONTAINER:-goldenhour-frontend}"

DUMP_FILE="${RELEASE_DIR}/goldenhour_pre-${RELEASE_VERSION}.sql.gz"
MANIFEST_FILE="${RELEASE_DIR}/${RELEASE_VERSION}.json"

mkdir -p "$RELEASE_DIR"
log() { echo "[pre-release-backup] $*"; }

# --- Preconditions ------------------------------------------------------------
docker inspect "$PG_CONTAINER" --format='{{.State.Running}}' 2>/dev/null | grep -q true \
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

SCHEMA_VERSION="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "$SCHEMA_VERSION_SQL" 2>/dev/null \
    | tr -d '[:space:]' || echo 'unknown')"

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

# --- Manifest -----------------------------------------------------------------
cat > "$MANIFEST_FILE" <<JSON
{
  "releasing": "${RELEASE_VERSION}",
  "taken_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "rollback_to": {
    "backend": "${PREV_BACKEND}",
    "frontend": "${PREV_FRONTEND}",
    "schema_version": "${SCHEMA_VERSION}"
  },
  "dump": "$(basename "$DUMP_FILE")"
}
JSON

# Validate now, while it is cheap. A manifest that only turns out to be
# malformed on the night you need it is worth no more than no manifest at all.
if command -v python3 >/dev/null 2>&1; then
    python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$MANIFEST_FILE" \
        || { echo "ERROR: wrote an invalid manifest — aborting the release." >&2; exit 1; }
fi

log "manifest written: ${MANIFEST_FILE}"
log "rollback with: ./scripts/rollback.sh ${RELEASE_VERSION}"
