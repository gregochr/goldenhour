-- Phase A protocol list: today's slots in four classes, from the latest T+0 SCORED batch run per (location, event),
-- at the locations you name. Broken-low plan section 2 A4 (BL, CL); far-shut-canvas-plan.md section 5 A2 (FS, FO).
-- READ-ONLY: a single SELECT inside COPY, writes nothing. Stored forecast columns only.
-- Pass the location ids with psql -v, e.g. -v locs='1,2,3'. The variable is required: psql substitutes it into
-- the IN list below, and an unset one is a syntax error, not "all locations".
-- Run: ssh <host> "docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -v locs='1,2,3' -f -" < bl_today.sql
-- Today is the UK civil date. Timestamps are naive UTC. Every class needs triage clear, a sky rating and no
-- [BUILDING] trend. The class is assigned in the CASE below; a row is in at most one class (first match wins).
--   BL (broken low): cone low 20-39, no canvas (cone mid/high < 10, antisolar mid/high < 10), far 226 km low < 30,
--      observer low < 60.
--   CL (clear control for BL): the same with cone low < 10.
--   FS (far-shut canvas): cone low < 20, a canvas (max of cone mid, cone high, overhead mid, overhead high >= 50),
--      far 226 km low >= 80.
--   FO (far-open canvas, the control for FS): as FS with far low < 50.
-- BL and CL need no canvas and FS and FO need one, so they are disjoint, except that BL/CL do not test the overhead
-- mid/high columns: a CL row with overhead canvas >= 50 and far low < 30 would also meet FO. BL and CL are checked
-- first and keep such a row (they are unchanged from the broken-low protocol).
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
    ),
    classed AS (
        SELECT f.*,
            CASE
                WHEN f.solar_low_cloud BETWEEN 20 AND 39
                     AND f.solar_mid_cloud < 10 AND f.solar_high_cloud < 10
                     AND f.antisolar_mid_cloud < 10 AND f.antisolar_high_cloud < 10
                     AND f.far_solar_low_cloud < 30 AND f.low_cloud < 60 THEN 'BL'
                WHEN f.solar_low_cloud < 10
                     AND f.solar_mid_cloud < 10 AND f.solar_high_cloud < 10
                     AND f.antisolar_mid_cloud < 10 AND f.antisolar_high_cloud < 10
                     AND f.far_solar_low_cloud < 30 AND f.low_cloud < 60 THEN 'CL'
                WHEN f.solar_low_cloud < 20
                     AND greatest(f.solar_mid_cloud, f.solar_high_cloud, f.mid_cloud, f.high_cloud) >= 50
                     AND f.far_solar_low_cloud >= 80 THEN 'FS'
                WHEN f.solar_low_cloud < 20
                     AND greatest(f.solar_mid_cloud, f.solar_high_cloud, f.mid_cloud, f.high_cloud) >= 50
                     AND f.far_solar_low_cloud < 50 THEN 'FO'
            END AS slot_class
          FROM latest f
         WHERE f.triage_reason IS NULL
           AND f.sky_rating IS NOT NULL
           AND COALESCE(f.solar_trend_building, FALSE) = FALSE
    )
    SELECT
        l.name                                                      AS location_name,
        f.target_type                                               AS event,
        to_char(f.solar_event_time, 'YYYY-MM-DD"T"HH24:MI:SS')      AS event_time,
        f.slot_class                                                AS class,
        f.solar_low_cloud                                           AS cone_low,
        f.solar_mid_cloud                                           AS cone_mid,
        f.solar_high_cloud                                          AS cone_high,
        f.antisolar_mid_cloud                                       AS antisolar_mid,
        f.antisolar_high_cloud                                      AS antisolar_high,
        f.far_solar_low_cloud                                       AS far_low,
        f.mid_cloud                                                 AS overhead_mid,
        f.high_cloud                                                AS overhead_high,
        f.low_cloud                                                 AS observer_low,
        f.sky_rating                                                AS sky_rating
    FROM classed f
    JOIN locations l ON l.id = f.location_id
    WHERE f.slot_class IS NOT NULL
    ORDER BY f.solar_event_time, l.name
) TO STDOUT WITH CSV HEADER;
