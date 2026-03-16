#!/bin/bash
# ============================================================================
# One-time H2 → PostgreSQL data migration for Golden Hour
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

IDENTITY_TABLES=(
    forecast_evaluation actual_outcome locations app_user refresh_token
    tide_extreme job_run api_call_log model_selection regions
    exchange_rate optimisation_strategy model_test_run model_test_result
    prompt_test_run prompt_test_result
)

info()  { echo "$(tput setaf 2)[INFO]$(tput sgr0) $*"; }
warn()  { echo "$(tput setaf 3)[WARN]$(tput sgr0) $*"; }
error() { echo "$(tput setaf 1)[ERROR]$(tput sgr0) $*" >&2; exit 1; }

# --- Step 1: Download H2 JAR if missing ---
if [ ! -f "$H2_JAR" ]; then
    info "Downloading H2 JAR..."
    curl -sL "$H2_JAR_URL" -o "$H2_JAR"
fi

# --- Step 2: Verify H2 database exists ---
[ -f "${H2_DB_PATH}.mv.db" ] || error "H2 database not found at ${H2_DB_PATH}.mv.db"

# --- Step 3: Export H2 data ---
info "Exporting H2 database to SQL..."
java -cp "$H2_JAR" org.h2.tools.Script \
    -url "jdbc:h2:file:${H2_DB_PATH};ACCESS_MODE_DATA=r" \
    -user sa -password "" -script "$EXPORT_FILE"
info "Export complete: $(wc -l < "$EXPORT_FILE") lines"

# --- Step 4: Transform H2 SQL → PostgreSQL-compatible SQL ---
info "Transforming SQL for PostgreSQL..."

# Use perl to join multi-line INSERTs, fix table names, skip flyway
perl -e '
    local $/;  # slurp mode
    my $sql = <STDIN>;

    # Split into statements (terminated by semicolons at end of line)
    my @stmts;
    my $current = "";
    for my $line (split /\n/, $sql) {
        $current .= ($current ? "\n" : "") . $line;
        if ($line =~ /;\s*$/) {
            push @stmts, $current;
            $current = "";
        }
    }

    print "SET session_replication_role = '\''replica'\'';\n\n";

    for my $stmt (@stmts) {
        # Only keep INSERT statements
        next unless $stmt =~ /^INSERT INTO/;
        # Skip flyway history
        next if $stmt =~ /flyway_schema_history/i;
        # Transform "PUBLIC"."TABLE_NAME" → lowercase table name
        $stmt =~ s/"PUBLIC"\."([^"]+)"/lc($1)/ge;
        print $stmt . "\n";
    }

    print "\nSET session_replication_role = '\''origin'\'';\n";
' < "$EXPORT_FILE" > "$PG_IMPORT_FILE"

info "Generated import file: $(grep -c 'INSERT INTO' "$PG_IMPORT_FILE" || echo 0) INSERT statements"

# --- Step 5: Verify PostgreSQL is running ---
info "Checking PostgreSQL container..."
docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" > /dev/null 2>&1 \
    || error "PostgreSQL container '$PG_CONTAINER' is not running."

# --- Step 6: Check schema exists ---
TABLE_COUNT=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -c \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';" \
    | tr -d ' ')
[ "$TABLE_COUNT" -ge 10 ] || error "Only $TABLE_COUNT tables found — run Flyway first."
info "PostgreSQL schema ready: $TABLE_COUNT tables"

# --- Step 7: Import data ---
info "Importing data into PostgreSQL..."
docker cp "$PG_IMPORT_FILE" "$PG_CONTAINER":/tmp/pg_import.sql
IMPORT_OUTPUT=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -f /tmp/pg_import.sql 2>&1)
IMPORT_ERRORS=$(echo "$IMPORT_OUTPUT" | grep "ERROR" || true)
if [ -n "$IMPORT_ERRORS" ]; then
    warn "Import errors detected:"
    echo "$IMPORT_ERRORS"
else
    info "Import completed without errors"
fi

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
        warn "  No sequence found for $TABLE"
    fi
done

# --- Step 9: Verify row counts ---
info ""
info "=== Row count verification ==="
printf "%-30s %10s %10s %s\n" "TABLE" "H2" "PG" "STATUS"
printf "%-30s %10s %10s %s\n" "-----" "--" "--" "------"

ALL_OK=true
for TABLE in "${IDENTITY_TABLES[@]}"; do
    TABLE_UPPER=$(echo "$TABLE" | tr '[:lower:]' '[:upper:]')
    H2_COUNT=$(grep -c "^INSERT INTO \"PUBLIC\".\"${TABLE_UPPER}\"" "$EXPORT_FILE" 2>/dev/null || echo "0")
    PG_COUNT=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -c \
        "SELECT COUNT(*) FROM ${TABLE};" 2>/dev/null | tr -d ' ' || echo "?")

    if [ "$H2_COUNT" = "$PG_COUNT" ]; then STATUS="OK"; else STATUS="MISMATCH"; ALL_OK=false; fi
    printf "%-30s %10s %10s %s\n" "$TABLE" "$H2_COUNT" "$PG_COUNT" "$STATUS"
done

echo ""
if [ "$ALL_OK" = true ]; then
    info "All row counts match. Migration successful!"
else
    warn "Some row counts differ — junction tables (location_*_type) are not tracked here."
    warn "Manually verify: docker exec $PG_CONTAINER psql -U $PG_USER -d $PG_DB -c 'SELECT COUNT(*) FROM <table>;'"
fi

info ""
info "Next steps:"
info "  1. docker compose up -d"
info "  2. curl http://localhost:8082/actuator/health"
info "  3. Keep H2 backup for at least 2 weeks"
