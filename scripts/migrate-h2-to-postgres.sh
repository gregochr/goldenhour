#!/bin/bash
# ============================================================================
# One-time H2 → PostgreSQL data migration for Golden Hour
#
# Prerequisites:
#   - Docker Desktop running
#   - H2 database file at /Users/gregochr/goldenhour-data/goldenhour.mv.db
#   - PostgreSQL container running (docker compose up goldenhour-db -d)
#   - H2 JAR available (downloaded automatically if missing)
#
# Usage:
#   1. docker compose down                        (stop everything)
#   2. cp -r /Users/gregochr/goldenhour-data /Users/gregochr/goldenhour-data-backup-$(date +%Y%m%d)
#   3. docker compose up goldenhour-db -d         (start PG only)
#   4. ./scripts/migrate-h2-to-postgres.sh        (this script)
#   5. docker compose up -d                       (start everything)
# ============================================================================

set -euo pipefail

# --- Config ---
H2_DB_PATH="/Users/gregochr/goldenhour-data/goldenhour"
H2_JAR_URL="https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar"
H2_JAR="/tmp/h2-2.3.232.jar"
EXPORT_FILE="/tmp/h2_export.sql"
PG_IMPORT_FILE="/tmp/pg_import.sql"
PG_CONTAINER="goldenhour-db"
PG_DB="goldenhour"
PG_USER="goldenhour"

# Tables with IDENTITY columns (need sequence reset after import)
IDENTITY_TABLES=(
    forecast_evaluation
    actual_outcome
    locations
    app_user
    refresh_token
    tide_extreme
    job_run
    api_call_log
    model_selection
    regions
    exchange_rate
    optimisation_strategy
    model_test_run
    model_test_result
    prompt_test_run
    prompt_test_result
)

# --- Helpers ---
info()  { echo "$(tput setaf 2)[INFO]$(tput sgr0) $*"; }
warn()  { echo "$(tput setaf 3)[WARN]$(tput sgr0) $*"; }
error() { echo "$(tput setaf 1)[ERROR]$(tput sgr0) $*" >&2; exit 1; }

# --- Step 1: Download H2 JAR if missing ---
if [ ! -f "$H2_JAR" ]; then
    info "Downloading H2 JAR..."
    curl -sL "$H2_JAR_URL" -o "$H2_JAR"
fi

# --- Step 2: Verify H2 database exists ---
if [ ! -f "${H2_DB_PATH}.mv.db" ]; then
    error "H2 database not found at ${H2_DB_PATH}.mv.db"
fi

# --- Step 3: Export H2 data ---
info "Exporting H2 database to SQL..."
java -cp "$H2_JAR" org.h2.tools.Script \
    -url "jdbc:h2:file:${H2_DB_PATH};ACCESS_MODE_DATA=r" \
    -user sa \
    -password "" \
    -script "$EXPORT_FILE"

info "Export complete: $(wc -l < "$EXPORT_FILE") lines"

# --- Step 4: Transform H2 SQL → PostgreSQL-compatible SQL ---
info "Transforming SQL for PostgreSQL..."

cat > "$PG_IMPORT_FILE" << 'HEADER'
-- Auto-generated H2 → PostgreSQL data import
-- Skip Flyway history, schema DDL, and H2 system commands
SET session_replication_role = 'replica';  -- disable FK checks during import
HEADER

# Extract only INSERT statements, skip Flyway and system tables
grep -E "^INSERT INTO " "$EXPORT_FILE" \
    | grep -v "flyway_schema_history" \
    | grep -v "^INSERT INTO \"INFORMATION_SCHEMA" \
    | sed 's/STRINGDECODE//' \
    | sed "s/X'\\([0-9A-Fa-f]*\\)'/E'\\\\\\\\x\\1'/g" \
    >> "$PG_IMPORT_FILE"

# Re-enable FK checks
cat >> "$PG_IMPORT_FILE" << 'FOOTER'

SET session_replication_role = 'origin';  -- re-enable FK checks
FOOTER

IMPORT_LINES=$(grep -c "^INSERT INTO" "$PG_IMPORT_FILE" || true)
info "Generated import file: ${IMPORT_LINES} INSERT statements"

# --- Step 5: Verify PostgreSQL is running ---
info "Checking PostgreSQL container..."
if ! docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" > /dev/null 2>&1; then
    error "PostgreSQL container '$PG_CONTAINER' is not running. Start it with: docker compose up goldenhour-db -d"
fi

# --- Step 6: Check if Flyway has run (schema exists) ---
TABLE_COUNT=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -c \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';" \
    | tr -d ' ')

if [ "$TABLE_COUNT" -lt 10 ]; then
    warn "Only $TABLE_COUNT tables found in PostgreSQL. Start the backend first to run Flyway migrations:"
    warn "  docker compose up goldenhour-backend -d  (wait for it to start, then stop it)"
    warn "  docker compose stop goldenhour-backend"
    warn "  Then re-run this script."
    error "Schema not ready — Flyway migrations must run first."
fi

info "PostgreSQL schema ready: $TABLE_COUNT tables"

# --- Step 7: Import data ---
info "Importing data into PostgreSQL..."
docker cp "$PG_IMPORT_FILE" "$PG_CONTAINER":/tmp/pg_import.sql
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -f /tmp/pg_import.sql

# --- Step 8: Reset IDENTITY sequences ---
info "Resetting IDENTITY sequences..."
for TABLE in "${IDENTITY_TABLES[@]}"; do
    SEQ_NAME=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -c \
        "SELECT pg_get_serial_sequence('${TABLE}', 'id');" 2>/dev/null | tr -d ' ')

    if [ -n "$SEQ_NAME" ] && [ "$SEQ_NAME" != "" ]; then
        docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c \
            "SELECT setval('${SEQ_NAME}', COALESCE((SELECT MAX(id) FROM ${TABLE}), 0) + 1, false);" \
            > /dev/null
        info "  Reset sequence for $TABLE"
    else
        warn "  No sequence found for $TABLE (may use a different naming convention)"
    fi
done

# --- Step 9: Verify row counts ---
info ""
info "=== Row count verification ==="
printf "%-30s %10s %10s %s\n" "TABLE" "H2" "PG" "STATUS"
printf "%-30s %10s %10s %s\n" "-----" "--" "--" "------"

ALL_OK=true
for TABLE in "${IDENTITY_TABLES[@]}"; do
    H2_COUNT=$(grep -c "^INSERT INTO \"${TABLE^^}\"" "$EXPORT_FILE" 2>/dev/null || \
               grep -c "^INSERT INTO \"${TABLE}\"" "$EXPORT_FILE" 2>/dev/null || echo "0")

    PG_COUNT=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -c \
        "SELECT COUNT(*) FROM ${TABLE};" 2>/dev/null | tr -d ' ' || echo "?")

    if [ "$H2_COUNT" = "$PG_COUNT" ]; then
        STATUS="OK"
    else
        STATUS="MISMATCH"
        ALL_OK=false
    fi

    printf "%-30s %10s %10s %s\n" "$TABLE" "$H2_COUNT" "$PG_COUNT" "$STATUS"
done

echo ""
if [ "$ALL_OK" = true ]; then
    info "All row counts match. Migration successful!"
else
    warn "Some row counts differ. Check the MISMATCH rows above."
    warn "H2 counts are based on INSERT grep — may differ if table names are case-sensitive in export."
    warn "Manually verify: docker exec $PG_CONTAINER psql -U $PG_USER -d $PG_DB -c 'SELECT COUNT(*) FROM <table>;'"
fi

info ""
info "Next steps:"
info "  1. Start the full stack: docker compose up -d"
info "  2. Verify health: curl http://localhost:8082/actuator/health"
info "  3. Verify data: curl http://localhost:8082/api/forecast (with auth)"
info "  4. Keep H2 backup for at least 2 weeks"
