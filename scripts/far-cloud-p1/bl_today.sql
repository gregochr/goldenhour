-- Broken-low Phase A protocol list (broken-low-canvas-plan.md section 2, A4): today's broken-low (BL) and clear
-- control (CL) slots, from the latest T+0 batch run per (location, event), at the locations you name.
-- READ-ONLY: a single SELECT inside COPY, writes nothing. Stored forecast columns only.
-- Pass the location ids with psql -v, e.g. -v locs='1,2,3'. The variable is required: psql substitutes it into
-- the IN list below, and an unset one is a syntax error, not "all locations".
-- Run: ssh <host> "docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -v locs='1,2,3' -f -" < bl_today.sql
-- Today is the UK civil date. Timestamps are naive UTC. BL: cone low 20-39, CL: cone low < 10, and for both: cone
-- mid/high < 10, antisolar mid/high < 10, far 226 km low < 30, observer low < 60, no [BUILDING] trend.
COPY (
    WITH latest AS (
        SELECT DISTINCT ON (fe.location_id, fe.target_type) fe.*
          FROM forecast_evaluation fe
         WHERE fe.target_type IN ('SUNRISE', 'SUNSET')
           AND fe.target_date = (now() AT TIME ZONE 'Europe/London')::date
           AND fe.days_ahead = 0
           AND fe.batch_state = 'SCORED'
           AND fe.forecast_run_at < fe.solar_event_time
           AND fe.location_id IN (:locs)
         ORDER BY fe.location_id, fe.target_type, fe.forecast_run_at DESC, fe.id DESC
    )
    SELECT
        l.name                                                      AS location_name,
        f.target_type                                               AS event,
        to_char(f.solar_event_time, 'YYYY-MM-DD"T"HH24:MI:SS')      AS event_time,
        CASE WHEN f.solar_low_cloud BETWEEN 20 AND 39 THEN 'BL' ELSE 'CL' END AS class,
        f.solar_low_cloud                                           AS cone_low,
        f.solar_mid_cloud                                           AS cone_mid,
        f.solar_high_cloud                                          AS cone_high,
        f.antisolar_mid_cloud                                       AS antisolar_mid,
        f.antisolar_high_cloud                                      AS antisolar_high,
        f.far_solar_low_cloud                                       AS far_low,
        f.low_cloud                                                 AS observer_low,
        f.sky_rating                                                AS sky_rating
    FROM latest f
    JOIN locations l ON l.id = f.location_id
    WHERE f.triage_reason IS NULL
      AND f.sky_rating IS NOT NULL
      AND (f.solar_low_cloud BETWEEN 20 AND 39 OR f.solar_low_cloud < 10)
      AND f.solar_mid_cloud < 10 AND f.solar_high_cloud < 10
      AND f.antisolar_mid_cloud < 10 AND f.antisolar_high_cloud < 10
      AND f.far_solar_low_cloud < 30
      AND f.low_cloud < 60
      AND COALESCE(f.solar_trend_building, FALSE) = FALSE
    ORDER BY f.solar_event_time, l.name
) TO STDOUT WITH CSV HEADER;
