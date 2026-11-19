-- Read-only, PRODUCTION. Phase A of far-shut-canvas-plan.md (revised after review, 2026-11-19): is a clear
-- 113 km horizon under a canvas, with the 226 km corridor shut by forecast low cloud, common; does ERA5 agree
-- the corridor was shut (at the event hour AND the dark-side hour); is it confounded with an incoming deep
-- deck; and what do readers' outcome ratings say? Run with:
--   docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 \
--     < docs/engineering/cost-queries/far-shut-canvas-sizing.sql
--
-- Unit: ONE SLOT = the latest Sonnet 5.5 SCORED run per (location, date, event) at T+0/1 made before the event,
-- chosen BEFORE any class filter (so an earlier run cannot stand in for a later one that left the shape).
-- Population: cone low < 20, canvas (max of cone mid/high and overhead mid/high) >= 50, not [BUILDING],
-- far low recorded, stars present. Classes: FS far low >= 80, FM 50-79, FO < 50.
-- Canvas type: 'mid' when the larger of cone/overhead mid >= the larger of cone/overhead high (ties 'mid';
-- antisolar ignored). "ERA5 open" uses the ledger's own cut, far low <= 30.
-- Nothing persists: TEMP views are session-local.

CREATE TEMP VIEW fs_runs AS
SELECT fe.id, fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at,
       COALESCE(fe.sky_rating, fe.rating) AS stars, fe.fiery_sky_potential AS fiery,
       CASE WHEN fe.solar_low_cloud < 20
             AND greatest(fe.solar_mid_cloud, fe.solar_high_cloud, fe.mid_cloud, fe.high_cloud) >= 50
             AND COALESCE(fe.solar_trend_building, FALSE) = FALSE
             AND fe.far_solar_low_cloud IS NOT NULL
             AND COALESCE(fe.sky_rating, fe.rating) IS NOT NULL
            THEN CASE WHEN fe.far_solar_low_cloud >= 80 THEN 'FS'
                      WHEN fe.far_solar_low_cloud >= 50 THEN 'FM' ELSE 'FO' END END AS class,
       CASE WHEN greatest(fe.solar_mid_cloud, fe.mid_cloud) >= greatest(fe.solar_high_cloud, fe.high_cloud)
            THEN 'mid' ELSE 'high' END AS canvas,
       (COALESCE(fe.far_solar_mid_cloud, 0) >= 50)                                   AS far_mid_deck,
       (greatest(fe.far_solar_cloud_700hpa, fe.far_solar_cloud_500hpa) >= 50)        AS far_deep_deck,
       cv.far_low_cloud AS era5_far_low, cv.far_low_cloud_dark AS era5_far_low_dark,
       cv.horizon_low_cloud AS era5_cone_low
FROM forecast_evaluation fe
LEFT JOIN cloud_verification cv ON cv.forecast_evaluation_id = fe.id
WHERE fe.target_type IN ('SUNRISE', 'SUNSET') AND fe.days_ahead <= 1
  AND fe.evaluation_model = 'SONNET_55' AND fe.batch_state = 'SCORED'
  AND fe.forecast_run_at < fe.solar_event_time
  AND fe.target_date >= CURRENT_DATE - 60;

CREATE TEMP VIEW fs_slots AS
SELECT * FROM (
    SELECT DISTINCT ON (location_id, target_date, target_type) *
    FROM fs_runs
    ORDER BY location_id, target_date, target_type, forecast_run_at DESC, id DESC
) latest
WHERE class IS NOT NULL;

-- 1. Size, ratings and ERA5 agreement by class, event and canvas type (per slot).
SELECT class, target_type AS event, canvas,
       count(*)                                                       AS slots,
       count(DISTINCT (target_date, target_type))                     AS evenings,
       round(100.0 * avg((stars >= 4)::int), 1)                       AS pct_4plus,
       round(avg(fiery))                                              AS fiery_mean,
       count(era5_far_low)                                            AS era5_checked,
       round(100.0 * avg((era5_far_low >= 80)::int) FILTER (WHERE era5_far_low IS NOT NULL), 1) AS era5_ge80_pct,
       round(100.0 * avg((era5_far_low <= 30)::int) FILTER (WHERE era5_far_low IS NOT NULL), 1) AS era5_open_le30_pct,
       round(avg(era5_far_low))                                       AS era5_mean,
       round(100.0 * avg((era5_far_low_dark >= 80)::int) FILTER (WHERE era5_far_low_dark IS NOT NULL), 1)
                                                                      AS era5_dark_ge80_pct,
       round(100.0 * avg((era5_cone_low < 20)::int) FILTER (WHERE era5_cone_low IS NOT NULL), 1)
                                                                      AS era5_cone_clear_pct
FROM fs_slots
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

-- 2. Precision against prevalence: P(ERA5 >= 80 | FS) vs P(ERA5 >= 80) over all classes.
SELECT round(100.0 * avg((era5_far_low >= 80)::int) FILTER (WHERE class = 'FS' AND era5_far_low IS NOT NULL), 1)
           AS precision_fs_pct,
       round(100.0 * avg((era5_far_low >= 80)::int) FILTER (WHERE era5_far_low IS NOT NULL), 1)
           AS prevalence_pct,
       count(*) FILTER (WHERE class = 'FS' AND era5_far_low <= 30) AS fs_era5_open_le30,
       count(*) FILTER (WHERE class = 'FS' AND era5_far_low IS NOT NULL) AS fs_era5_checked
FROM fs_slots;

-- 3. Confounders inside FS: is the far corridor shut by low cloud alone, or by a continuous / deep deck
--    (an incoming system)?
SELECT far_mid_deck, far_deep_deck,
       count(*)                                                   AS fs_slots,
       round(100.0 * avg((stars >= 4)::int), 1)                   AS pct_4plus,
       round(100.0 * avg((era5_far_low >= 80)::int) FILTER (WHERE era5_far_low IS NOT NULL), 1) AS era5_ge80_pct
FROM fs_slots WHERE class = 'FS'
GROUP BY 1, 2 ORDER BY 1, 2;

-- 4. FS's share of 4+ ratings in this population.
SELECT count(*) FILTER (WHERE stars >= 4)                                         AS all_4plus,
       count(*) FILTER (WHERE stars >= 4 AND class = 'FS')                        AS fs_4plus,
       count(*) FILTER (WHERE stars >= 4 AND class = 'FS' AND era5_far_low >= 80) AS fs_4plus_era5_ge80,
       round(100.0 * count(*) FILTER (WHERE stars >= 4 AND class = 'FS')
             / NULLIF(count(*) FILTER (WHERE stars >= 4), 0), 1)                  AS fs_share_of_4plus_pct
FROM fs_slots;

-- 5. Per location, T+0 evenings: how often is the slot FS? (The owner reads off his own locations.)
SELECT l.id AS location_id, l.name,
       count(*)                                  AS t0_slots,
       count(*) FILTER (WHERE r.class = 'FS')    AS fs_slots,
       round(100.0 * avg((r.class = 'FS')::int), 1) AS fs_pct
FROM (SELECT DISTINCT ON (location_id, target_date, target_type) *
      FROM fs_runs
      WHERE forecast_run_at::date = target_date
      ORDER BY location_id, target_date, target_type, forecast_run_at DESC, id DESC) r
JOIN locations l ON l.id = r.location_id
GROUP BY l.id, l.name
HAVING count(*) FILTER (WHERE r.class = 'FS') > 0
ORDER BY fs_pct DESC, l.name;

-- 6. Outcome ratings (descriptive; selected by whoever chose to rate), classed from the run the reader saw.
--    by_admin = any ADMIN account. "Didn't see it" rows excluded. Within FS, split by ERA5 (dose-response).
SELECT r.class,
       CASE WHEN r.class = 'FS' AND r.era5_far_low >= 80 THEN 'ERA5 shut'
            WHEN r.class = 'FS' AND r.era5_far_low <= 30 THEN 'ERA5 open'
            WHEN r.class = 'FS' THEN 'ERA5 mid/unknown' ELSE '-' END     AS fs_era5,
       (u.role = 'ADMIN')                                               AS by_admin,
       count(*)                                                         AS ratings,
       round(avg(o.forecast_rating), 2)                                 AS forecast_mean,
       round(avg(o.rating), 2)                                          AS outcome_mean,
       count(*) FILTER (WHERE o.rating <= o.forecast_rating - 2)        AS outcome_2plus_below
FROM outcome_rating o
JOIN app_user u ON u.id = o.user_id
JOIN fs_runs r ON r.id = o.forecast_evaluation_id
WHERE o.rating IS NOT NULL AND r.class IS NOT NULL
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;
