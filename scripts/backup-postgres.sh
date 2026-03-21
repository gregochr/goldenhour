#!/bin/bash
# ============================================================================
# Daily PostgreSQL backup for Golden Hour
#
# Creates a compressed pg_dump, rotates old backups (keeps last 7 daily +
# last 4 weekly). Designed to run via launchd or cron.
#
# Install (launchd — recommended for macOS):
#   cp scripts/com.goldenhour.backup.plist ~/Library/LaunchAgents/
#   launchctl load ~/Library/LaunchAgents/com.goldenhour.backup.plist
#
# Install (cron):
#   crontab -e
#   0 2 * * * /Users/gregochr/IdeaProjects/goldenhour/scripts/backup-postgres.sh
#
# Manual run:
#   ./scripts/backup-postgres.sh
#
# Restore:
#   gunzip -k /Users/gregochr/goldenhour-backups/daily/goldenhour_2026-03-16.sql.gz
#   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < goldenhour_2026-03-16.sql
# ============================================================================

set -euo pipefail

# --- Config ---
PG_CONTAINER="goldenhour-db"
PG_DB="goldenhour"
PG_USER="goldenhour"
BACKUP_DIR="/Users/gregochr/goldenhour-backups"
DAILY_DIR="${BACKUP_DIR}/daily"
WEEKLY_DIR="${BACKUP_DIR}/weekly"
DAILY_KEEP=7
WEEKLY_KEEP=4
DATE=$(date +%Y-%m-%d)
DAY_OF_WEEK=$(date +%u)  # 1=Monday, 7=Sunday
LOG_FILE="${BACKUP_DIR}/backup.log"

# --- Helpers ---
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$LOG_FILE"; }

# --- Setup ---
mkdir -p "$DAILY_DIR" "$WEEKLY_DIR"

# --- Check container is running ---
if ! docker inspect "$PG_CONTAINER" --format='{{.State.Running}}' 2>/dev/null | grep -q true; then
    log "ERROR: Container $PG_CONTAINER is not running. Skipping backup."
    exit 1
fi

# --- Daily backup ---
DAILY_FILE="${DAILY_DIR}/goldenhour_${DATE}.sql.gz"

if [ -f "$DAILY_FILE" ]; then
    log "Daily backup already exists: $DAILY_FILE — skipping"
else
    log "Starting daily backup..."
    docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" \
        --clean --if-exists --no-owner --no-privileges \
        | gzip > "$DAILY_FILE"

    SIZE=$(du -h "$DAILY_FILE" | cut -f1)
    log "Daily backup complete: $DAILY_FILE ($SIZE)"
fi

# --- Weekly backup (Sunday) ---
if [ "$DAY_OF_WEEK" -eq 7 ]; then
    WEEKLY_FILE="${WEEKLY_DIR}/goldenhour_week_${DATE}.sql.gz"
    if [ ! -f "$WEEKLY_FILE" ]; then
        cp "$DAILY_FILE" "$WEEKLY_FILE"
        log "Weekly backup created: $WEEKLY_FILE"
    fi
fi

# --- Rotate daily (keep last N) ---
DAILY_COUNT=$(find "$DAILY_DIR" -name "goldenhour_*.sql.gz" -type f | wc -l | tr -d ' ')
if [ "$DAILY_COUNT" -gt "$DAILY_KEEP" ]; then
    REMOVE_COUNT=$((DAILY_COUNT - DAILY_KEEP))
    find "$DAILY_DIR" -name "goldenhour_*.sql.gz" -type f | sort | head -n "$REMOVE_COUNT" | while read -r f; do
        rm "$f"
        log "Rotated old daily backup: $f"
    done
fi

# --- Rotate weekly (keep last N) ---
WEEKLY_COUNT=$(find "$WEEKLY_DIR" -name "goldenhour_week_*.sql.gz" -type f | wc -l | tr -d ' ')
if [ "$WEEKLY_COUNT" -gt "$WEEKLY_KEEP" ]; then
    REMOVE_COUNT=$((WEEKLY_COUNT - WEEKLY_KEEP))
    find "$WEEKLY_DIR" -name "goldenhour_week_*.sql.gz" -type f | sort | head -n "$REMOVE_COUNT" | while read -r f; do
        rm "$f"
        log "Rotated old weekly backup: $f"
    done
fi

log "Backup job complete. Daily: $DAILY_COUNT (keep $DAILY_KEEP), Weekly: $WEEKLY_COUNT (keep $WEEKLY_KEEP)"
