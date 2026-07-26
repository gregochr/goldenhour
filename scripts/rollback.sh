#!/bin/bash
# ============================================================================
# Roll production back to the state captured before a given release.
#
# THIS IS PLAN B. Fixing forward is almost always better: a rollback restores
# the database, which DISCARDS everything written since the release — new
# forecasts, outcomes, registrations. Reach for this only when forward is not
# available quickly enough.
#
# Reads the manifest written by pre-release-backup.sh and does three things
# that only work together:
#   1. dumps the CURRENT state first, so the rollback is itself reversible
#   2. restores the pre-release dump
#   3. starts the exact image digests that were running before
#
# Step 3 uses digests rather than :latest, because after a bad release :latest
# is the bad release.
#
# Usage:
#   ./scripts/rollback.sh              # list what can be rolled back to
#   ./scripts/rollback.sh v2.11.11     # roll back the release v2.11.11 introduced
# ============================================================================

set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-goldenhour-db}"
PG_DB="${PG_DB:-goldenhour}"
PG_USER="${PG_USER:-goldenhour}"
BACKUP_DIR="${BACKUP_DIR:-${HOME}/goldenhour-backups}"
RELEASE_DIR="${BACKUP_DIR}/pre-release"
COMPOSE_DIR="${COMPOSE_DIR:-${HOME}/goldenhour}"
OVERRIDE_FILE="${COMPOSE_DIR}/docker-compose.rollback.yml"

# --- No argument: show what is available --------------------------------------
if [ $# -eq 0 ]; then
    echo "Releases with a rollback point:"
    echo ""
    if ! ls "${RELEASE_DIR}"/*.json >/dev/null 2>&1; then
        echo "  (none — pre-release backups start from the next deploy)"
        exit 0
    fi
    for m in "${RELEASE_DIR}"/*.json; do
        v="$(basename "$m" .json)"
        taken="$(grep -o '"taken_at": *"[^"]*"' "$m" | cut -d'"' -f4)"
        schema="$(grep -o '"schema_version": *"[^"]*"' "$m" | cut -d'"' -f4)"
        echo "  ${v}  (captured ${taken}, schema ${schema})"
    done
    echo ""
    echo "Usage: $0 <version>    — rolls back the state that <version> replaced"
    exit 0
fi

VERSION="$1"
MANIFEST="${RELEASE_DIR}/${VERSION}.json"
[ -f "$MANIFEST" ] || { echo "ERROR: no manifest for ${VERSION} at ${MANIFEST}" >&2; echo "Run '$0' with no arguments to list what is available." >&2; exit 1; }

field() { grep -o "\"$1\": *\"[^\"]*\"" "$MANIFEST" | head -1 | cut -d'"' -f4; }
BACKEND_IMAGE="$(field backend)"
FRONTEND_IMAGE="$(field frontend)"
SCHEMA_VERSION="$(field schema_version)"
TAKEN_AT="$(field taken_at)"
DUMP_FILE="${RELEASE_DIR}/$(field dump)"

[ -n "$BACKEND_IMAGE" ] && [ -n "$FRONTEND_IMAGE" ] || { echo "ERROR: manifest is missing image digests — cannot roll back safely." >&2; exit 1; }
[ -f "$DUMP_FILE" ] || { echo "ERROR: dump referenced by the manifest is missing: ${DUMP_FILE}" >&2; exit 1; }
gzip -t "$DUMP_FILE" || { echo "ERROR: dump fails its integrity check — do NOT roll back to it." >&2; exit 1; }

CURRENT_SCHEMA="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "SELECT COALESCE(MAX(version), 'none') FROM flyway_schema_history WHERE success" 2>/dev/null | tr -d '[:space:]' || echo unknown)"

# --- Show the plan before doing any of it -------------------------------------
cat <<PLAN

────────────────────────────────────────────────────────────────────────
ROLLBACK PLAN — undoing release ${VERSION}
────────────────────────────────────────────────────────────────────────
  Captured:          ${TAKEN_AT}
  Restore dump:      ${DUMP_FILE}
                     ($(du -h "$DUMP_FILE" | cut -f1))
  Backend  ->        ${BACKEND_IMAGE}
  Frontend ->        ${FRONTEND_IMAGE}
  Schema:            ${CURRENT_SCHEMA} (now)  ->  ${SCHEMA_VERSION} (restored)

  DATA LOSS: everything written since ${TAKEN_AT} will be discarded —
  forecasts, recorded outcomes, user registrations, aurora history.

  A dump of the CURRENT state is taken first, so this is reversible.
────────────────────────────────────────────────────────────────────────

PLAN

read -r -p "Type the version (${VERSION}) to proceed, anything else to abort: " CONFIRM
[ "$CONFIRM" = "$VERSION" ] || { echo "Aborted. Nothing changed."; exit 0; }

# --- 1. Safety dump of where we are now ---------------------------------------
SAFETY="${RELEASE_DIR}/goldenhour_pre-rollback-${VERSION}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
echo "==> Dumping current state first: ${SAFETY}"
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" \
    --clean --if-exists --no-owner --no-privileges | gzip > "$SAFETY"
gzip -t "$SAFETY" || { echo "ERROR: safety dump failed its check — refusing to continue." >&2; exit 1; }
echo "    ok ($(du -h "$SAFETY" | cut -f1))"

# --- 2. Stop the app, leaving the database up ---------------------------------
# The restore uses --clean, whose DROPs fail while the backend holds connections.
echo "==> Stopping application containers (database stays up)"
cd "$COMPOSE_DIR"
docker compose stop goldenhour-backend goldenhour-frontend

# --- 3. Restore -----------------------------------------------------------------
echo "==> Restoring ${DUMP_FILE}"
gunzip -c "$DUMP_FILE" | docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" >/dev/null
RESTORED_SCHEMA="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "SELECT COALESCE(MAX(version), 'none') FROM flyway_schema_history WHERE success" | tr -d '[:space:]')"
echo "    schema now at ${RESTORED_SCHEMA} (expected ${SCHEMA_VERSION})"

# --- 4. Pin the old digests and start ------------------------------------------
# Written as a compose override so the real docker-compose.yml is untouched.
# It stays on disk deliberately: while it exists, production is pinned to the
# rolled-back build and a stray `docker compose up` cannot silently restore the
# broken release. Delete it when you fix forward.
cat > "$OVERRIDE_FILE" <<OVERRIDE
# Written by scripts/rollback.sh — production is pinned to the build that
# preceded ${VERSION}. DELETE THIS FILE when rolling forward again.
services:
  goldenhour-backend:
    image: ${BACKEND_IMAGE}
  goldenhour-frontend:
    image: ${FRONTEND_IMAGE}
OVERRIDE

echo "==> Starting the previous images"
docker compose -f docker-compose.yml -f "$OVERRIDE_FILE" up -d --no-deps goldenhour-backend goldenhour-frontend

# --- 5. Confirm it came back ----------------------------------------------------
echo "==> Waiting for backend health"
healthy=false
for _ in $(seq 1 30); do
    if docker exec goldenhour-backend curl -sf http://localhost:8082/actuator/health >/dev/null 2>&1; then
        healthy=true; break
    fi
    sleep 5
done

cat <<DONE

────────────────────────────────────────────────────────────────────────
$([ "$healthy" = true ] && echo "ROLLBACK COMPLETE — backend healthy" || echo "ROLLBACK APPLIED — BACKEND NOT HEALTHY, check: docker compose logs goldenhour-backend")
────────────────────────────────────────────────────────────────────────
  Running:     ${BACKEND_IMAGE}
  Schema:      ${RESTORED_SCHEMA}
  Undo this:   gunzip -c ${SAFETY} | docker exec -i ${PG_CONTAINER} psql -U ${PG_USER} -d ${PG_DB}

  Production is PINNED by ${OVERRIDE_FILE}.
  When you fix forward, delete it or the pin will outlive the problem:
      rm ${OVERRIDE_FILE}
────────────────────────────────────────────────────────────────────────

DONE
[ "$healthy" = true ]
