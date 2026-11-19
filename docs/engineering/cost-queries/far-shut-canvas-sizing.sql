-- Read-only, PRODUCTION. Phase A of far-shut-canvas-plan.md: how common is a clear 113 km horizon under a
-- canvas while the 226 km far corridor is shut by low cloud, how often is it rated 4+, does ERA5 agree the
-- corridor was shut, and what do readers' outcome ratings say? Run with:
--   docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 \
--     < docs/engineering/cost-queries/far-shut-canvas-sizing.sql
--
-- Population: Sonnet 5.5, T+0/1, SCORED, last 60 days, a clear solar horizon (cone low < 20), a canvas
-- (max of cone mid/high and overhead mid/high >= 50), not [BUILDING], far low recorded.
-- Classes (forecast side): FS far low >= 80 ("far shut"), FM 50-79, FO < 50 ("far open", the control).
-- Canvas type: 'mid' when mid cloud (cone or overhead) is the larger layer, else 'high'.
-- Unit: the latest scored run per (location, date, event), which classifies the slot. Outcome table:
-- readers' ratings joined by (location, date, event); "forecast" there is outcome_rating.forecast_rating,
-- the star the reader was shown. Descriptive only.
-- The TEMP VIEW is session-local: nothing persists.

CREATE TEMP VIEW fs_slots AS
SELECT DISTINCT ON (fe.location_id, fe.target_date, fe.target_type)
       fe.id, fe.location_id, fe.target_date, fe.target_type,
       COALESCE(fe.sky_rating, fe.rating) AS stars, fe.fiery_sky_potential AS fiery,
       CASE WHEN fe.far_solar_low_cloud >= 80 THEN 'FS'
            WHEN fe.far_solar_low_cloud >= 50 THEN 'FM' ELSE 'FO' END AS class,
       CASE WHEN greatest(fe.solar_mid_cloud, fe.mid_cloud) >= greatest(fe.solar_high_cloud, fe.high_cloud)
            THEN 'mid' ELSE 'high' END AS canvas,
       cv.id AS cv_id, cv.far_low_cloud AS era5_far_low, cv.horizon_low_cloud AS era5_cone_low
FROM forecast_evaluation fe
LEFT JOIN cloud_verification cv ON cv.forecast_evaluation_id = fe.id
WHERE fe.target_type IN ('SUNRISE', 'SUNSET') AND fe.days_ahead <= 1
  AND fe.evaluation_model = 'SONNET_55' AND fe.batch_state = 'SCORED'
  AND fe.target_date >= CURRENT_DATE - 60
  AND fe.solar_low_cloud < 20
  AND greatest(fe.solar_mid_cloud, fe.solar_high_cloud, fe.mid_cloud, fe.high_cloud) >= 50
  AND COALESCE(fe.solar_trend_building, FALSE) = FALSE
  AND fe.far_solar_low_cloud IS NOT NULL
  AND COALESCE(fe.sky_rating, fe.rating) IS NOT NULL
ORDER BY fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at DESC, fe.id DESC;

-- 1. Size, ratings and ERA5 agreement by class, event and canvas type.
SELECT class, target_type AS event, canvas,
       count(*)                                                AS slots,
       count(DISTINCT (target_date, target_type))              AS evenings,
       round(100.0 * avg((stars >= 4)::int), 1)                AS pct_4plus,
       round(avg(fiery))                                       AS fiery_mean,
       count(cv_id)                                            AS era5_checked,
       round(100.0 * avg((era5_far_low >= 80)::int) FILTER (WHERE cv_id IS NOT NULL), 1) AS era5_far_ge80_pct,
       round(100.0 * avg((era5_far_low < 50)::int)  FILTER (WHERE cv_id IS NOT NULL), 1) AS era5_far_lt50_pct,
       round(100.0 * avg((era5_cone_low < 20)::int) FILTER (WHERE cv_id IS NOT NULL), 1) AS era5_cone_clear_pct
FROM fs_slots
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

-- 2. How much of all clear-horizon-with-canvas 4+ ratings sit in FS (and in ERA5-confirmed FS).
SELECT count(*) FILTER (WHERE stars >= 4)                                         AS all_4plus,
       count(*) FILTER (WHERE stars >= 4 AND class = 'FS')                        AS fs_4plus,
       count(*) FILTER (WHERE stars >= 4 AND class = 'FS' AND era5_far_low >= 80) AS fs_4plus_era5_confirmed,
       round(100.0 * count(*) FILTER (WHERE stars >= 4 AND class = 'FS')
             / NULLIF(count(*) FILTER (WHERE stars >= 4), 0), 1)                  AS fs_share_of_4plus_pct
FROM fs_slots;

-- 3. Outcome ratings (descriptive; selected by whoever chose to rate): forecast vs outcome stars by class.
--    by_admin marks the owner's account; no personal column is selected. "Didn't see it" rows excluded.
SELECT s.class, (u.role = 'ADMIN') AS by_admin,
       count(*)                                   AS ratings,
       round(avg(o.forecast_rating), 2)           AS forecast_mean,
       round(avg(o.rating), 2)                    AS outcome_mean,
       count(*) FILTER (WHERE o.rating <= o.forecast_rating - 2) AS outcome_2plus_below
FROM outcome_rating o
JOIN app_user u ON u.id = o.user_id
JOIN fs_slots s ON s.location_id = o.location_id AND s.target_date = o.event_date
                AND s.target_type = o.event_type
WHERE o.rating IS NOT NULL
GROUP BY 1, 2
ORDER BY 1, 2;
