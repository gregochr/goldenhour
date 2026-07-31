#!/bin/bash
# ============================================================================
# Daily PostgreSQL backup for Golden Hour
#
# Creates a compressed pg_dump, rotates old backups (keeps last 7 daily +
# last 4 weekly), optionally copies off-box, and records a success marker.
#
# ---------------------------------------------------------------------------
# WHY THIS WAS REWRITTEN (2026-07-26)
#
# The previous version hardcoded macOS paths (/Users/gregochr/...) and shipped
# a launchd plist. Docker moved off the Mac onto the Ubuntu host (dockermacmini)
# around 2026-03-16, and from that night on the job ran on the Mac, failed the
# "is goldenhour-db running?" guard, exited 1, and logged to a file nobody read.
# 132 nights. The only backup on disk was dated 16 March.
#
# So this script now:
#   - defaults to the host it actually runs on (no hardcoded /Users)
#   - refuses to look like it worked when it didn't (see FAILURES, below)
#   - writes a machine-readable success marker so an INDEPENDENT checker can
#     verify freshness. The lesson of those 132 nights is that a job's own
#     self-report is worthless when the job is the thing that is broken.
#
# ---------------------------------------------------------------------------
# Install (systemd — the Docker host, i.e. where `docker ps` shows goldenhour-db):
#   sudo cp scripts/goldenhour-backup.service scripts/goldenhour-backup.timer \
#           /etc/systemd/system/
#   sudo systemctl daemon-reload
#   sudo systemctl enable --now goldenhour-backup.timer
#   systemctl list-timers goldenhour-backup    # confirm it is scheduled
#
# Uninstall the dead macOS job (run on the Mac):
#   launchctl unload ~/Library/LaunchAgents/com.goldenhour.backup.plist
#   rm ~/Library/LaunchAgents/com.goldenhour.backup.plist
#
# Manual run:
#   ./scripts/backup-postgres.sh
#
# Restore:
#   gunzip -c ~/goldenhour-backups/daily/goldenhour_2026-07-26.sql.gz \
#     | docker exec -i goldenhour-db psql -U goldenhour -d goldenhour
#
# Config (all overridable by environment):
#   BACKUP_DIR   where backups are written   (default ~/goldenhour-backups)
#   OFFBOX_DEST  rsync destination for a second copy on other hardware.
#                Unset = no off-box copy, and the script says so loudly.
#                e.g. OFFBOX_DEST=gregochr@192.168.0.101:~/goldenhour-backups/daily/
# ============================================================================

set -euo pipefail

# --- Config ---
PG_CONTAINER="${PG_CONTAINER:-goldenhour-db}"
PG_DB="${PG_DB:-goldenhour}"
PG_USER="${PG_USER:-goldenhour}"
BACKUP_DIR="${BACKUP_DIR:-${HOME}/goldenhour-backups}"
OFFBOX_DEST="${OFFBOX_DEST:-}"
DAILY_DIR="${BACKUP_DIR}/daily"
WEEKLY_DIR="${BACKUP_DIR}/weekly"
DAILY_KEEP=7
WEEKLY_KEEP=4
DATE=$(date +%Y-%m-%d)
DAY_OF_WEEK=$(date +%u)  # 1=Monday, 7=Sunday
LOG_FILE="${BACKUP_DIR}/backup.log"
# Written only on a fully successful run — a human-readable "when did this last
# work end to end". Note that backup-verify.yml does NOT key off this: it checks
# the age of the newest dump itself, because verifying the artefact is stronger
# than trusting the job's own claim to have produced one.
MARKER_FILE="${BACKUP_DIR}/last-success"

# Read by backup-verify.yml. Holds "ok <timestamp>" or "failed <timestamp>";
# absent means off-box copying is not configured at all.
OFFBOX_STATUS_FILE="${BACKUP_DIR}/offbox-status"

# --- Setup ---
mkdir -p "$DAILY_DIR" "$WEEKLY_DIR"

# Logs to stderr, not stdout, so that a function which both logs and returns a
# value via echo (rotate(), below) does not have its log lines swallowed into
# the caller's command substitution.
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$LOG_FILE" >&2; }

# FAILURES: every early exit goes through here, so a failure is always recorded
# in the log with a reason and always leaves a non-zero exit status for systemd.
# The marker file is deliberately NOT updated, which is what the independent
# freshness check keys off.
die() { log "ERROR: $*"; exit 1; }

# --- Preconditions ------------------------------------------------------------
command -v docker >/dev/null 2>&1 \
    || die "docker not found on PATH. This must run on the Docker host — the box where 'docker ps' lists ${PG_CONTAINER}."

# Exact match on a captured value rather than `| grep -q true`. grep's match is
# unanchored, so it also accepts "untrue" and would accept the struct dump you
# get if this format string is ever shortened to {{.State}} — which contains the
# word `true` even for a stopped container, i.e. it would fail OPEN. Comparing
# the whole value fails closed instead. (No pipe also makes the pipefail/SIGPIPE
# shape structurally impossible here, though measurement says it never fired:
# 6000 runs of the piped form, zero spurious failures — the output is 5 bytes.)
[ "$(docker inspect "$PG_CONTAINER" --format='{{.State.Running}}' 2>/dev/null)" = "true" ] \
    || die "Container ${PG_CONTAINER} is not running here. This is exactly how the 2026-03-16 gap happened: the job kept running on a host that could not see the database. Install the timer on the Docker host."

# --- Daily backup -------------------------------------------------------------
DAILY_FILE="${DAILY_DIR}/goldenhour_${DATE}.sql.gz"

if [ -f "$DAILY_FILE" ]; then
    log "Daily backup already exists: $DAILY_FILE — skipping dump"
else
    log "Starting daily backup..."
    # pipefail is set, so a pg_dump failure fails the pipeline rather than
    # leaving a small, valid-looking gzip of a truncated dump. Write to .partial
    # first so an interrupted run can never be mistaken for a finished backup.
    if ! docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" \
            --clean --if-exists --no-owner --no-privileges \
            | gzip > "${DAILY_FILE}.partial"; then
        rm -f "${DAILY_FILE}.partial"
        die "pg_dump failed — partial file removed so it cannot be mistaken for a backup."
    fi

    # Integrity gate: a dump that will not gunzip is not a backup.
    if ! gzip -t "${DAILY_FILE}.partial"; then
        rm -f "${DAILY_FILE}.partial"
        die "Backup failed gzip integrity check — partial file removed."
    fi

    mv "${DAILY_FILE}.partial" "$DAILY_FILE"
    SIZE=$(du -h "$DAILY_FILE" | cut -f1)
    log "Daily backup complete: $DAILY_FILE ($SIZE)"
fi

# --- Weekly backup (Sunday) ---------------------------------------------------
if [ "$DAY_OF_WEEK" -eq 7 ]; then
    WEEKLY_FILE="${WEEKLY_DIR}/goldenhour_week_${DATE}.sql.gz"
    if [ ! -f "$WEEKLY_FILE" ]; then
        cp "$DAILY_FILE" "$WEEKLY_FILE"
        log "Weekly backup created: $WEEKLY_FILE"
    fi
fi

# --- Off-box copy -------------------------------------------------------------
# Backups on the same disk as the database are one disk failure from useless.
if [ -n "$OFFBOX_DEST" ]; then
    # BatchMode so an unattended run fails fast instead of blocking forever on a
    # password or host-key prompt that nobody is there to answer.
    if rsync -a -e 'ssh -o BatchMode=yes -o ConnectTimeout=15' "$DAILY_FILE" "$OFFBOX_DEST"; then
        log "Copied off-box: $OFFBOX_DEST"
        echo "ok $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OFFBOX_STATUS_FILE"
    else
        # Deliberately NOT fatal. The local backup is complete and verified at
        # this point; losing redundancy is a lesser failure than losing the
        # backup. The off-box target here is a Mac, which sleeps — failing the
        # whole job every night it happens to be asleep would produce a red
        # backup you learn to ignore, and learning to ignore it is exactly how
        # the previous gap survived 132 nights.
        #
        # Not silent either: the status file below is read by backup-verify.yml,
        # which reports broken replication as its own distinct failure.
        log "WARNING: off-box copy to ${OFFBOX_DEST} failed — local backup is intact but not redundant."
        echo "failed $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OFFBOX_STATUS_FILE"
    fi
else
    log "WARNING: OFFBOX_DEST is not set — backups exist only on this host, alongside the database."
    # Absence of the file means "not configured", so do not leave a stale one
    # behind if off-box copying is turned off again.
    rm -f "$OFFBOX_STATUS_FILE"
fi

# --- Rotate -------------------------------------------------------------------
rotate() {
    local dir="$1" pattern="$2" keep="$3"
    local count
    count=$(find "$dir" -name "$pattern" -type f | wc -l | tr -d ' ')
    if [ "$count" -gt "$keep" ]; then
        find "$dir" -name "$pattern" -type f | sort | head -n "$((count - keep))" | while read -r f; do
            rm "$f"
            log "Rotated old backup: $f"
        done
    fi
    # Recount after rotating so the summary reports what is actually on disk,
    # not the pre-rotation figure.
    find "$dir" -name "$pattern" -type f | wc -l | tr -d ' '
}

DAILY_COUNT=$(rotate "$DAILY_DIR" "goldenhour_*.sql.gz" "$DAILY_KEEP")
WEEKLY_COUNT=$(rotate "$WEEKLY_DIR" "goldenhour_week_*.sql.gz" "$WEEKLY_KEEP")

# --- Success marker -----------------------------------------------------------
# Reached only when everything above succeeded.
date -u +%Y-%m-%dT%H:%M:%SZ > "$MARKER_FILE"

log "Backup job complete. Daily: $DAILY_COUNT (keep $DAILY_KEEP), Weekly: $WEEKLY_COUNT (keep $WEEKLY_KEEP)"
