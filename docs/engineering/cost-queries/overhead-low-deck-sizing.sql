-- Read-only, PRODUCTION. Phase A of overhead-low-deck-plan.md: a low deck overhead (>= 60%) with a clear
-- solar horizon (cone low < 20) and an open far corridor (far low < 50). How common is it, how is it rated,
-- with and without a mid/high canvas, does ERA5 agree there was a deck overhead, and what do readers' outcome
-- ratings say? Run with:
--   docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 \
--     < docs/engineering/cost-queries/overhead-low-deck-sizing.sql
--
-- Unit: ONE SLOT = the latest Sonnet 5.5 SCORED run per (location, date, event) at T+0/1 made before the event,
-- chosen BEFORE any class filter. Classes (all: cone low < 20, far low < 50, not [BUILDING], stars present):
--   OL-canvas  overhead low >= 60, and max(cone/overhead mid/high) >= 50   (16 Nov's shape)
--   OL-bare    overhead low >= 60, and no mid/high canvas (< 50)            (20 Nov's shape)
--   CTL-canvas overhead low < 30 with a canvas (the control for OL-canvas)
--   CTL-bare   overhead low < 30 without a canvas (the control for OL-bare)
-- (30-59% overhead low is left out of both, so classes and controls are well separated.)
-- Nothing persists: TEMP views are session-local.

CREATE TEMP VIEW ol_runs AS
SELECT fe.id, fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at,
       COALESCE(fe.sky_rating, fe.rating) AS stars, fe.fiery_sky_potential AS fiery,
       CASE WHEN fe.solar_low_cloud < 20 AND fe.far_solar_low_cloud < 50
             AND COALESCE(fe.solar_trend_building, FALSE) = FALSE
             AND COALESCE(fe.sky_rating, fe.rating) IS NOT NULL
            THEN CASE
                WHEN fe.low_cloud >= 60 AND greatest(fe.solar_mid_cloud, fe.solar_high_cloud, fe.mid_cloud, fe.high_cloud) >= 50 THEN 'OL-canvas'
                WHEN fe.low_cloud >= 60 THEN 'OL-bare'
                WHEN fe.low_cloud < 30 AND greatest(fe.solar_mid_cloud, fe.solar_high_cloud, fe.mid_cloud, fe.high_cloud) >= 50 THEN 'CTL-canvas'
                WHEN fe.low_cloud < 30 THEN 'CTL-bare'
            END END AS class,
       cv.observer_low_cloud AS era5_obs_low, cv.horizon_low_cloud AS era5_cone_low
FROM forecast_evaluation fe
LEFT JOIN cloud_verification cv ON cv.forecast_evaluation_id = fe.id
WHERE fe.target_type IN ('SUNRISE', 'SUNSET') AND fe.days_ahead <= 1
  AND fe.evaluation_model = 'SONNET_55' AND fe.batch_state = 'SCORED'
  AND fe.forecast_run_at < fe.solar_event_time
  AND fe.target_date >= CURRENT_DATE - 60;

CREATE TEMP VIEW ol_slots AS
SELECT * FROM (
    SELECT DISTINCT ON (location_id, target_date, target_type) *
    FROM ol_runs
    ORDER BY location_id, target_date, target_type, forecast_run_at DESC, id DESC
) latest
WHERE class IS NOT NULL;

-- 1. Size, ratings and ERA5 agreement by class and event.
SELECT class, target_type AS event,
       count(*)                                                       AS slots,
       count(DISTINCT (target_date, target_type))                     AS evenings,
       round(avg(stars), 2)                                           AS stars_mean,
       round(100.0 * avg((stars >= 4)::int), 1)                       AS pct_4plus,
       round(100.0 * avg((stars <= 2)::int), 1)                       AS pct_2minus,
       round(avg(fiery))                                              AS fiery_mean,
       count(era5_obs_low)                                            AS era5_checked,
       round(100.0 * avg((era5_obs_low >= 60)::int) FILTER (WHERE era5_obs_low IS NOT NULL), 1) AS era5_obs_low_ge60_pct,
       round(avg(era5_obs_low))                                       AS era5_obs_low_mean,
       round(100.0 * avg((era5_cone_low < 20)::int) FILTER (WHERE era5_cone_low IS NOT NULL), 1) AS era5_cone_clear_pct
FROM ol_slots
GROUP BY 1, 2
ORDER BY 1, 2;

-- 2. Per location, T+0: how often is the slot OL (either subtype)? (The owner reads off his own places.)
SELECT l.id AS location_id, l.name,
       count(*)                                              AS t0_slots,
       count(*) FILTER (WHERE r.class LIKE 'OL-%')           AS ol_slots,
       round(100.0 * avg((r.class LIKE 'OL-%')::int), 1)     AS ol_pct
FROM (SELECT DISTINCT ON (location_id, target_date, target_type) *
      FROM ol_runs
      WHERE forecast_run_at::date = target_date
      ORDER BY location_id, target_date, target_type, forecast_run_at DESC, id DESC) r
JOIN locations l ON l.id = r.location_id
GROUP BY l.id, l.name
HAVING count(*) FILTER (WHERE r.class LIKE 'OL-%') > 0
ORDER BY ol_pct DESC, l.name;

-- 3. Outcome ratings (descriptive; selected by whoever chose to rate), classed from the run the reader saw.
--    Signed error = forecast stars shown minus the reader's stars. "Didn't see it" rows excluded.
SELECT r.class, (u.role = 'ADMIN') AS by_admin,
       count(*)                                          AS ratings,
       round(avg(o.forecast_rating), 2)                  AS forecast_mean,
       round(avg(o.rating), 2)                           AS outcome_mean,
       round(avg(o.forecast_rating - o.rating), 2)       AS signed_error_mean,
       count(*) FILTER (WHERE abs(o.forecast_rating - o.rating) >= 2) AS off_by_2plus
FROM outcome_rating o
JOIN app_user u ON u.id = o.user_id
JOIN ol_runs r ON r.id = o.forecast_evaluation_id
WHERE o.rating IS NOT NULL AND o.forecast_rating IS NOT NULL AND r.class IS NOT NULL
GROUP BY 1, 2
ORDER BY 1, 2;
