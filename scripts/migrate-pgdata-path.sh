#!/bin/bash
# ============================================================================
# One-off: move goldenhour-data off the macOS-shaped /Users path
#
#   /Users/gregochr/goldenhour-data  ->  /home/gregochr/goldenhour-data
#
# WHY. Linux creates /Users happily, so this has always worked — the cost is
# that the production database lives somewhere nobody would think to look. That
# confusion is not hypothetical: the same stale macOS assumption is what let the
# nightly backup fail unnoticed for 132 nights.
#
# History, from directory timestamps on the host rather than assumption:
#   28 Mar  /home/gregochr/goldenhour-data/postgres created and in use
#   12 Apr 20:15  last writes to it
#   12 Apr 20:22  /Users/gregochr/goldenhour-data created — live ever since
# So the data started in the Linux-correct place and was moved to the
# macOS-shaped one on 12 April, leaving the /home copy behind. This script moves
# it back. Expect to find that stale 12-April copy still sitting at the
# destination; the gate below refuses rather than merging two data directories
# from different points in time, which is the one outcome worse than not
# migrating at all. Move it aside by hand first.
#
# THE DANGEROUS PART, and how this avoids it.
#   If docker-compose.yml points at a path that does not contain a database,
#   Postgres does not fail — it runs initdb and starts a BRAND NEW EMPTY one.
#   The app then comes up healthy against an empty database and starts writing
#   to it. So any window where the compose file and the data disagree risks
#   looking exactly like total data loss.
#
#   Rather than sequence carefully around that window, this script removes it:
#   after moving, it leaves a SYMLINK at the old path. Both the old and the new
#   compose file therefore resolve to the same real directory, and the repo
#   change can be merged and deployed whenever — before or after this runs.
#
# Run ON THE DOCKER HOST (where `docker ps` lists goldenhour-db):
#   ./scripts/migrate-pgdata-path.sh          # dry run, changes nothing
#   ./scripts/migrate-pgdata-path.sh --apply
#
# Expect ~30-60s of downtime while containers are recreated.
# ============================================================================

set -euo pipefail

OLD_ROOT="${OLD_ROOT:-/Users/gregochr/goldenhour-data}"
NEW_ROOT="${NEW_ROOT:-/home/gregochr/goldenhour-data}"

# Resolve defaults from the INVOKING user, not $HOME: this must run under sudo
# (see the root check below), and under sudo $HOME is /root — which would look
# for the compose file and the backups in the wrong place and fail confusingly.
INVOKER="${SUDO_USER:-$(id -un)}"
INVOKER_HOME="$(getent passwd "$INVOKER" | cut -d: -f6)"
COMPOSE_DIR="${COMPOSE_DIR:-${INVOKER_HOME}/goldenhour}"
BACKUP_DIR="${BACKUP_DIR:-${INVOKER_HOME}/goldenhour-backups}"
PG_CONTAINER="${PG_CONTAINER:-goldenhour-db}"
PG_DB="${PG_DB:-goldenhour}"
PG_USER="${PG_USER:-goldenhour}"

APPLY=false
[ "${1:-}" = "--apply" ] && APPLY=true

say()  { echo "==> $*"; }
die()  { echo "ERROR: $*" >&2; exit 1; }

# --- Preconditions ------------------------------------------------------------
# Must be root. A Postgres data directory is mode 700 owned by uid 70, so an
# unprivileged user cannot even stat PG_VERSION inside it — the first version of
# this script reported "does not look like a Postgres data directory" on a
# perfectly good one, because it tested readability it did not have. Moving the
# directory needs root regardless.
if [ "$(id -u)" -ne 0 ]; then
    die "must run as root (the data directory is mode 700, uid 70). Re-run: sudo $0 ${*:-}"
fi

command -v docker >/dev/null 2>&1 || die "docker not found — run this on the Docker host."
[ -d "$COMPOSE_DIR" ] || die "compose directory not found: $COMPOSE_DIR"

# Source must be a real PGDATA, not an empty directory someone created by accident.
[ -f "${OLD_ROOT}/postgres/PG_VERSION" ] \
    || die "${OLD_ROOT}/postgres has no PG_VERSION even as root — it is genuinely not a Postgres data directory. Check: docker inspect ${PG_CONTAINER} --format '{{json .Mounts}}'"

# Destination must not already hold data.
if [ -e "$NEW_ROOT" ] && [ -n "$(ls -A "$NEW_ROOT" 2>/dev/null || true)" ]; then
    die "${NEW_ROOT} already exists and is not empty. Refusing to merge two data directories."
fi

# A fresh, valid backup is the precondition for touching a database at all.
NEWEST="$(find "${BACKUP_DIR}/daily" -name 'goldenhour_*.sql.gz' -type f -printf '%T@ %p\n' 2>/dev/null \
          | sort -rn | head -1 | cut -d' ' -f2- || true)"
[ -n "$NEWEST" ] || die "No backup found in ${BACKUP_DIR}/daily. Run: sudo systemctl start goldenhour-backup.service"
AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$NEWEST") ) / 3600 ))
[ "$AGE_H" -le 24 ] || die "Newest backup is ${AGE_H}h old. Take a fresh one first: sudo systemctl start goldenhour-backup.service"
gzip -t "$NEWEST" || die "Newest backup fails its integrity check. Do NOT proceed."
say "Backup precondition met: $(basename "$NEWEST") (${AGE_H}h old, $(du -h "$NEWEST" | cut -f1))"

# Same filesystem means mv is instant and atomic; otherwise we must copy.
SRC_FS="$(stat -c %d "$(dirname "$OLD_ROOT")")"
DST_FS="$(stat -c %d "$(dirname "$NEW_ROOT")")"
# shellcheck disable=SC2209  # "mv"/"copy" are mode names, not commands to run
if [ "$SRC_FS" = "$DST_FS" ]; then MODE=mv; else MODE=copy; fi
say "Transfer mode: ${MODE} ($(du -sh "$OLD_ROOT" 2>/dev/null | cut -f1) to move)"

# Row counts now, to compare against afterwards.
# Exact match on a captured value rather than `| grep -q true`. grep's match is
# unanchored, so it also accepts "untrue" and would accept the struct dump you
# get if this format string is ever shortened to {{.State}} — which contains the
# word `true` even for a stopped container, i.e. it would fail OPEN. Comparing
# the whole value fails closed instead. (No pipe also makes the pipefail/SIGPIPE
# shape structurally impossible here, though measurement says it never fired:
# 6000 runs of the piped form, zero spurious failures — the output is 5 bytes.)
[ "$(docker inspect "$PG_CONTAINER" --format='{{.State.Running}}' 2>/dev/null)" = "true" ] \
    || die "${PG_CONTAINER} is not running — cannot take the before-counts that verify this worked."
BEFORE="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "SELECT (SELECT count(*) FROM forecast_evaluation) || '/' || (SELECT count(*) FROM locations) || '/' || (SELECT count(*) FROM app_user)")"
say "Row counts before (forecast_evaluation/locations/app_user): ${BEFORE}"

if [ "$APPLY" != true ]; then
    cat <<PLAN

  DRY RUN — nothing changed. With --apply this would:
    1. docker compose down            (in ${COMPOSE_DIR})
    2. ${MODE} ${OLD_ROOT} -> ${NEW_ROOT}
    3. symlink ${OLD_ROOT} -> ${NEW_ROOT}   (so BOTH compose versions keep working)
    4. rewrite /Users paths in ${COMPOSE_DIR}/docker-compose.yml
    5. docker compose up -d, then verify row counts still read ${BEFORE}

  Re-run with --apply when ready.

PLAN
    exit 0
fi

# --- Apply --------------------------------------------------------------------
cd "$COMPOSE_DIR"
say "Stopping the stack"
docker compose down

say "Moving data"
mkdir -p "$(dirname "$NEW_ROOT")"
if [ "$MODE" = mv ]; then
    mv "$OLD_ROOT" "$NEW_ROOT"
else
    # -a preserves ownership, which Postgres requires (uid 999 inside the image).
    cp -a "$OLD_ROOT" "$NEW_ROOT"
    mv "$OLD_ROOT" "${OLD_ROOT}.premigration"
fi

# The compatibility symlink is the whole safety story: after this, the old and
# new compose files are equivalent, so neither ordering nor a mid-flight deploy
# can point Postgres at an empty directory.
ln -s "$NEW_ROOT" "$OLD_ROOT"
say "Symlinked ${OLD_ROOT} -> ${NEW_ROOT} (both paths now resolve to the same data)"

say "Rewriting compose paths on this host"
sed -i "s|${OLD_ROOT}|${NEW_ROOT}|g" docker-compose.yml

say "Starting the stack"
docker compose up -d

say "Waiting for the database"
ready=false
for _ in $(seq 1 60); do
    if docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c 'SELECT 1' >/dev/null 2>&1; then
        ready=true; break
    fi
    sleep 2
done
[ "$ready" = true ] || die "Database did not come back. Revert: docker compose down; rm ${OLD_ROOT}; mv ${NEW_ROOT} ${OLD_ROOT}; git checkout docker-compose.yml; docker compose up -d"

AFTER="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "SELECT (SELECT count(*) FROM forecast_evaluation) || '/' || (SELECT count(*) FROM locations) || '/' || (SELECT count(*) FROM app_user)")"
say "Row counts after: ${AFTER}"

if [ "$BEFORE" != "$AFTER" ]; then
    die "ROW COUNTS CHANGED (${BEFORE} -> ${AFTER}). Postgres may have initialised an empty database. STOP and restore from ${NEWEST}."
fi

cat <<DONE

────────────────────────────────────────────────────────────────────────
MIGRATION COMPLETE — row counts match (${AFTER})
────────────────────────────────────────────────────────────────────────
  Data now at:   ${NEW_ROOT}
  Compat symlink: ${OLD_ROOT} -> ${NEW_ROOT}

  The symlink stays until the repo change has deployed and you have seen a
  release land cleanly. Removing it earlier reintroduces the empty-database
  risk this script exists to avoid. When ready:  rm ${OLD_ROOT}
$([ "$MODE" = copy ] && echo "
  Copy mode was used; the original is preserved at ${OLD_ROOT}.premigration
  Delete it only once you are satisfied:  rm -rf ${OLD_ROOT}.premigration")
────────────────────────────────────────────────────────────────────────

DONE
