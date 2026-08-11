#!/usr/bin/env bash
#
# Why was a forecast slot not re-evaluated as its event approached?
#
# Written during the investigation of a 5-star Angel of the North sunset that was evaluated at
# T+1 and never refreshed on the day of the event. The cause was Gate 1 — the CACHED gate in
# BriefingCandidateCollector — resolving its freshness threshold from stability alone, so a
# SETTLED region's 36h window swallowed the whole remaining lead time. See
# docs/engineering/horizon-aware-freshness-plan.md.
#
# Keep this runnable. The disposition trail is the only durable record of why a slot was skipped,
# and reconstructing these queries from scratch under time pressure is what made that
# investigation take a day.
#
# READ ONLY. Every statement is a SELECT.
#
# Usage:  ./scripts/diagnose-stale-forecast.sh
#         LOC="Bamburgh" EVENT_DATE=2026-08-14 EVENT_TYPE=SUNRISE ./scripts/diagnose-stale-forecast.sh
#         ./scripts/diagnose-stale-forecast.sh 2>&1 | tee /tmp/diag-$(date +%F).log
#
# Run it on the Docker host. Connection details match docker-compose.yml.

set -uo pipefail

LOC="${LOC:-Angel of the North}"
EVENT_DATE="${EVENT_DATE:-$(date -u +%F)}"
EVENT_TYPE="${EVENT_TYPE:-SUNSET}"
DB_CONTAINER="${DB_CONTAINER:-goldenhour-db}"
DB_USER="${DB_USER:-goldenhour}"
DB_NAME="${DB_NAME:-goldenhour}"
WINDOW_DAYS="${WINDOW_DAYS:-14}"

# Local socket auth in the postgres image is trust; if that ever changes, export PGPASSWORD
# and add  -e PGPASSWORD  to the docker exec line.
q() {
  local title="$1"; shift
  printf '\n\033[1m=== %s ===\033[0m\n' "$title"
  docker exec -i "$DB_CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -P pager=off -v ON_ERROR_STOP=1 "$@"
}

printf 'Location : %s\nEvent    : %s %s\nWindow   : %s days\nContainer: %s\n' \
  "$LOC" "$EVENT_DATE" "$EVENT_TYPE" "$WINDOW_DAYS" "$DB_CONTAINER"

# ---------------------------------------------------------------------------
# TIER 1 — why was THIS slot not re-evaluated?
# ---------------------------------------------------------------------------

q "Q1  Location -> region (gives the cache key this slot is gated on)" -c "
SELECT l.id, l.name, r.name AS region, l.enabled,
       r.name || '|' || DATE '${EVENT_DATE}' || '|' || '${EVENT_TYPE}' AS cache_key
FROM locations l
LEFT JOIN regions r ON r.id = l.region_id
WHERE l.name = '${LOC}';
"

q "Q2  Disposition trail — THE query. Why the slot was or wasn't evaluated, per cycle" -c "
SELECT created_at AT TIME ZONE 'UTC' AS cycle_utc,
       days_ahead, disposition, detail, job_run_id
FROM forecast_run_disposition
WHERE location_name = '${LOC}'
  AND evaluation_date = DATE '${EVENT_DATE}'
  AND event_type = '${EVENT_TYPE}'
ORDER BY created_at;
"
# Since the horizon-cap change, a SKIPPED_CACHED detail reads
#   'Fresh cached evaluation within 2h (T+0, SETTLED)'
# — the horizon is in the string. A row skipped at T+0 against a 36h threshold is the old bug
# and means the change is not deployed (or t0-hours has been raised).

q "Q3  The cache rows behind it — note which column is the freshness clock" -c "
SELECT cache_key, source,
       evaluated_at AT TIME ZONE 'UTC' AS first_write_utc,
       updated_at   AT TIME ZONE 'UTC' AS last_write_utc,
       round(EXTRACT(epoch FROM (NOW() - updated_at)) / 3600.0, 1) AS true_age_h
FROM cached_evaluation
WHERE evaluation_date = DATE '${EVENT_DATE}'
  AND target_type = '${EVENT_TYPE}'
ORDER BY region_name;
"
# ⚠️ Age is measured from updated_at, NOT evaluated_at. persistToDb sets evaluated_at only on
# INSERT, so it is a row-creation stamp: a slot re-evaluated three days running still carries its
# day-one value. Reading the wrong column mis-dated a slot by 31h during the original
# investigation. Both are selected here so the difference is visible rather than assumed.

q "Q4  Stability band for the grid cell — picks which threshold applied" -c "
SELECT grid_cell_key, stability_level, reason,
       classified_at AT TIME ZONE 'UTC' AS classified_utc
FROM stability_snapshot
WHERE location_names ILIKE '%${LOC}%';
"

q "Q5  forecast_evaluation rows — a missing row for the latest cycle corroborates a region skip" -c "
SELECT fe.forecast_run_at, fe.days_ahead, fe.rating, fe.low_cloud, fe.mid_cloud,
       (fe.summary IS NOT NULL) AS has_summary
FROM forecast_evaluation fe
JOIN locations l ON l.id = fe.location_id
WHERE l.name = '${LOC}'
  AND fe.target_date = DATE '${EVENT_DATE}'
  AND fe.target_type = '${EVENT_TYPE}'
ORDER BY fe.forecast_run_at DESC
LIMIT 10;
"
# location_id, not location_name — V47 replaced the name column with an FK.

# ---------------------------------------------------------------------------
# TIER 2 — one slot, or systemic?
# ---------------------------------------------------------------------------

q "Q6  Scheduler reality — cron + status + last fire (the DB is authoritative, not the seeds)" -c "
SELECT job_key, status, schedule_type, cron_expression, fixed_delay_ms,
       last_fire_time AT TIME ZONE 'UTC' AS last_fire_utc,
       last_completion_time AT TIME ZONE 'UTC' AS last_done_utc
FROM scheduler_job_config
WHERE job_key IN ('near_term_batch_evaluation','intraday_forecast_refresh',
                  'daily_briefing','batch_result_polling')
ORDER BY job_key;
"

q "Q7  Pipeline cycles, last 4 days — did the cycle run at all, and did it complete?" -c "
SELECT id, cycle_type, status, current_phase, waiting_on,
       trigger_time AT TIME ZONE 'UTC' AS trigger_utc,
       completed_at AT TIME ZONE 'UTC' AS completed_utc, failure_reason
FROM pipeline_run
WHERE trigger_time >= NOW() - INTERVAL '4 days'
ORDER BY trigger_time;
"

q "Q8  Disposition mix by horizon — the headline number" -c "
SELECT days_ahead,
       count(*) FILTER (WHERE disposition IN ('EVALUATED','FORCE_EVALUATED')) AS evaluated,
       count(*) FILTER (WHERE disposition = 'SKIPPED_CACHED')             AS skipped_cached,
       count(*) FILTER (WHERE disposition = 'SKIPPED_NO_REFRESH_NEEDED')  AS skipped_settled,
       count(*) FILTER (WHERE disposition = 'SKIPPED_TRIAGED')            AS skipped_triaged,
       count(*) FILTER (WHERE disposition = 'SKIPPED_STABILITY')          AS skipped_stability,
       count(*)                                                           AS total
FROM forecast_run_disposition
WHERE created_at >= NOW() - INTERVAL '${WINDOW_DAYS} days'
  AND days_ahead BETWEEN 0 AND 3
GROUP BY days_ahead
ORDER BY days_ahead;
"
# Post-change, skipped_cached at days_ahead=0 should fall toward zero. Read skipped_settled
# beside it: an intraday SETTLED slot that now clears the cache gate is skipped by
# IntradayEligibilityPolicy instead, which is a relabelling and NOT a new evaluation.

q "Q9  Which threshold does the skipping (the detail string carries it)" -c "
SELECT days_ahead, detail, count(*)
FROM forecast_run_disposition
WHERE disposition = 'SKIPPED_CACHED'
  AND created_at >= NOW() - INTERVAL '${WINDOW_DAYS} days'
GROUP BY days_ahead, detail
ORDER BY days_ahead, count(*) DESC;
"

q "Q10 Intraday cycle yield (14:00-15:00 UTC) — does the afternoon cycle achieve anything?" -c "
SELECT date(created_at AT TIME ZONE 'UTC') AS day_utc, disposition, count(*)
FROM forecast_run_disposition
WHERE created_at >= NOW() - INTERVAL '${WINDOW_DAYS} days'
  AND EXTRACT(hour FROM created_at AT TIME ZONE 'UTC') IN (14, 15)
GROUP BY 1, 2
ORDER BY 1, 3 DESC;
"

# ---------------------------------------------------------------------------
# TIER 3 — does re-evaluating actually change the answer?
# ---------------------------------------------------------------------------

q "Q11 Rating movement by stability and cache age" -c "
SELECT stability_level,
       (floor(age_hours / 6) * 6)::int AS age_bucket_h,
       count(*)                                  AS refreshes,
       round(avg(rating_delta), 2)               AS mean_delta,
       max(rating_delta)                         AS max_delta,
       count(*) FILTER (WHERE rating_delta >= 1) AS moved_1_star_plus,
       count(*) FILTER (WHERE rating_delta >= 2) AS moved_2_star_plus
FROM evaluation_delta_log
WHERE logged_at >= NOW() - INTERVAL '30 days'
  AND rating_delta IS NOT NULL
  AND age_basis = 'LOCATION'
GROUP BY stability_level, 2
ORDER BY stability_level, 2;
"
# age_basis = 'LOCATION' is load-bearing, not tidiness. age_hours used to be measured per CACHE
# KEY while ratings are per LOCATION, and a region's slots span several batches
# (inland/coastal/bluebell/woodland), so the second batch of an ordinary cycle logged a genuine
# 24h rating movement at age_hours ~ 0. V140 measures age per location and stamps the basis;
# CACHE_KEY and NULL rows still carry the old, under-reported quantity and must be excluded rather
# than averaged in. Expect few LOCATION rows until every cached entry has been rewritten once.

q "Q11b How much of the table is safe to aggregate yet" -c "
SELECT coalesce(age_basis, 'NULL (pre-V140)') AS basis, count(*),
       min(logged_at) AT TIME ZONE 'UTC' AS first_seen_utc,
       max(logged_at) AT TIME ZONE 'UTC' AS last_seen_utc
FROM evaluation_delta_log
GROUP BY 1 ORDER BY 2 DESC;
"
# Run this before trusting Q11. If LOCATION is a thin slice, Q11 is a small sample rather than a
# 30-day picture — the fallback stops occurring only once every cached entry has been rewritten.

q "Q12 Stale refreshes that moved a star or more — the trips saved and missed" -c "
SELECT location_name, evaluation_date, target_type, stability_level,
       age_hours, age_basis, old_rating, new_rating, rating_delta,
       logged_at AT TIME ZONE 'UTC' AS logged_utc
FROM evaluation_delta_log
WHERE logged_at >= NOW() - INTERVAL '30 days'
  AND age_hours >= 20
  AND rating_delta >= 1
ORDER BY rating_delta DESC, age_hours DESC
LIMIT 40;
"
# age_basis is selected rather than filtered here on purpose: a >= 20h age is already too large
# for the cache-key artefact to manufacture, so these rows are worth reading whatever their basis.

printf '\n\033[1mDone.\033[0m Q2 explains one slot; Q8 and Q10 say whether it is systemic.\n'
