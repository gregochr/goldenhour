-- P1 export (task T1): one (metric, value) table of first-occurrence instants, counts and training-case ids.
-- Plan: docs/engineering/far-cloud-p1-plan.md section 3. READ-ONLY: a single SELECT inside COPY, writes nothing.
-- Every count uses the same filter as export_forecasts.sql: SUNRISE/SUNSET and target_date >= 2026-02-01.
-- Run: docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_summary.sql > p1_summary.csv
COPY (
    SELECT metric, value FROM (
        SELECT 1 AS ord, 'first_scored_forecast_run_at' AS metric,
               to_char(min(forecast_run_at), 'YYYY-MM-DD"T"HH24:MI:SS') AS value
          FROM forecast_evaluation WHERE batch_state = 'SCORED'
           AND target_type IN ('SUNRISE', 'SUNSET') AND target_date >= DATE '2026-02-01'
        UNION ALL
        SELECT 2, 'first_sky_rating_forecast_run_at',
               to_char(min(forecast_run_at), 'YYYY-MM-DD"T"HH24:MI:SS')
          FROM forecast_evaluation WHERE sky_rating IS NOT NULL
           AND target_type IN ('SUNRISE', 'SUNSET') AND target_date >= DATE '2026-02-01'
        UNION ALL
        SELECT 3, 'first_sonnet_55_forecast_run_at',
               to_char(min(forecast_run_at), 'YYYY-MM-DD"T"HH24:MI:SS')
          FROM forecast_evaluation WHERE evaluation_model = 'SONNET_55'
           AND target_type IN ('SUNRISE', 'SUNSET') AND target_date >= DATE '2026-02-01'
        UNION ALL
        SELECT 4,
               'prompted_rows|' || to_char(fe.target_date, 'YYYY-MM') || '|T+' ||
                   COALESCE(CASE WHEN fe.days_ahead >= 3 THEN '3plus' ELSE fe.days_ahead::text END, 'null') || '|' || fe.target_type,
               count(*)::text
          FROM forecast_evaluation fe
         WHERE fe.target_type IN ('SUNRISE', 'SUNSET') AND fe.triage_reason IS NULL
           AND fe.target_date >= DATE '2026-02-01'
         GROUP BY to_char(fe.target_date, 'YYYY-MM'),
                  COALESCE(CASE WHEN fe.days_ahead >= 3 THEN '3plus' ELSE fe.days_ahead::text END, 'null'),
                  fe.target_type
        UNION ALL
        SELECT 5,
               'rated_rows|' || to_char(fe.target_date, 'YYYY-MM') || '|T+' ||
                   COALESCE(CASE WHEN fe.days_ahead >= 3 THEN '3plus' ELSE fe.days_ahead::text END, 'null') || '|' || fe.target_type,
               count(*)::text
          FROM forecast_evaluation fe
         WHERE fe.target_type IN ('SUNRISE', 'SUNSET') AND fe.rating IS NOT NULL
           AND fe.target_date >= DATE '2026-02-01'
         GROUP BY to_char(fe.target_date, 'YYYY-MM'),
                  COALESCE(CASE WHEN fe.days_ahead >= 3 THEN '3plus' ELSE fe.days_ahead::text END, 'null'),
                  fe.target_type
        UNION ALL
        SELECT 6, 'cloud_verification_rows', count(*)::text
          FROM cloud_verification cv JOIN forecast_evaluation fe ON fe.id = cv.forecast_evaluation_id
         WHERE fe.target_type IN ('SUNRISE', 'SUNSET') AND fe.target_date >= DATE '2026-02-01'
        UNION ALL
        SELECT 7, 'cloud_verification_rows_far_mid_cloud_not_null', count(*)::text
          FROM cloud_verification cv JOIN forecast_evaluation fe ON fe.id = cv.forecast_evaluation_id
         WHERE cv.far_mid_cloud IS NOT NULL
           AND fe.target_type IN ('SUNRISE', 'SUNSET') AND fe.target_date >= DATE '2026-02-01'
        UNION ALL
        SELECT 8, 'training_case_location|Angel of the North|' || id::text || '|' || name, id::text
          FROM locations WHERE name ILIKE '%Angel of the North%'
        UNION ALL
        SELECT 8, 'training_case_location|Low Hauxley|' || id::text || '|' || name, id::text
          FROM locations WHERE name ILIKE '%Low Hauxley%'
        UNION ALL
        SELECT 8, 'training_case_location|Copt Hill|' || id::text || '|' || name, id::text
          FROM locations WHERE name ILIKE '%Copt Hill%'
    ) s
    ORDER BY ord, metric
) TO STDOUT WITH CSV HEADER;
