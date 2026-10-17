-- Tuning the serve-time neighbour guard's rule. The served slot carries no solar-horizon cloud, so
-- a guard at serve time can only use distance (or the grid cell). For each rated row with a
-- sky_rating, flag it when its sky_rating is 3+ and at least 2 above EVERY sky-rated neighbour in
-- the same run, window, model and horizon within r km, for r = 3, 5, 8, 12, and in the same
-- grid cell. A row with no neighbour inside r is never flagged at that r. Witnesses are counted as
-- DISTINCT neighbour places: a retry can leave two rows for one slot in the same run hour. flag_5km_2nb also
-- requires at least two neighbours inside 5 km (the plan's min-neighbours rule). flag_plan is the
-- plan's full default rule: no neighbour within 5 km rated within 1 star of it (any elevation), and at
-- least two neighbours within 5 km that are at a similar height (both elevations unknown, or both known
-- and within 150 m). Run after
-- nothing else (it does not use cell-groups.sql). Rows written before V164 have no sky_rating and are
-- not counted. Its population is the GUARD's: an enabled, regioned place that is untyped or carries an open-sky
-- type (LANDSCAPE, SEASCAPE, WATERFALL), mixed types included (the app treats a place with LANDSCAPE and
-- BLUEBELL as a sky place). cell-groups.sql's narrower population (no woodland, bluebell or wildlife
-- type at all) is the same-cell spread measurement's and is left as published.
CREATE TEMP VIEW guard_loc AS
SELECT l.id, l.lat, l.lon, l.elevation_m, l.grid_lat, l.grid_lng
FROM locations l
WHERE l.enabled AND l.region_id IS NOT NULL
  AND (NOT EXISTS (SELECT 1 FROM location_location_type t WHERE t.location_id = l.id)
       OR EXISTS (SELECT 1 FROM location_location_type t WHERE t.location_id = l.id
                  AND t.location_type IN ('LANDSCAPE', 'SEASCAPE', 'WATERFALL')));

CREATE TEMP VIEW rated AS
SELECT e.location_id, g.lat, g.lon, g.elevation_m, g.grid_lat, g.grid_lng, e.target_date, e.target_type,
       e.evaluation_model, e.days_ahead, e.sky_rating,
       SUBSTR(CAST(e.forecast_run_at AS VARCHAR(40)), 1, 13) AS run_hour
FROM forecast_evaluation e
JOIN guard_loc g ON g.id = e.location_id
WHERE e.rating IS NOT NULL
  AND e.target_type IN ('SUNRISE', 'SUNSET')
  AND e.forecast_run_at >= :'since'
  -- the place still takes this event (LocationEntity.supportsTargetType: none, ALLDAY, or the type)
  AND (NOT EXISTS (SELECT 1 FROM location_solar_event_type z WHERE z.location_id = e.location_id)
       OR EXISTS (SELECT 1 FROM location_solar_event_type z WHERE z.location_id = e.location_id
                  AND z.solar_event_type IN ('ALLDAY', e.target_type)));

CREATE TEMP VIEW near_pair AS
SELECT a.location_id, a.target_date, a.target_type, a.run_hour, a.evaluation_model, a.days_ahead,
       a.sky_rating AS sky, b.location_id AS nb_loc, b.sky_rating AS nb_sky,
       CASE WHEN a.grid_lat = b.grid_lat AND a.grid_lng = b.grid_lng THEN 1 ELSE 0 END AS same_cell,
       CASE WHEN (a.elevation_m IS NULL AND b.elevation_m IS NULL)
              OR (a.elevation_m IS NOT NULL AND b.elevation_m IS NOT NULL
                  AND ABS(a.elevation_m - b.elevation_m) <= 150) THEN 1 ELSE 0 END AS elev_ok,
       111.32 * SQRT((a.lat - b.lat) * (a.lat - b.lat)
         + ((a.lon - b.lon) * COS(RADIANS(a.lat))) * ((a.lon - b.lon) * COS(RADIANS(a.lat)))) AS km
FROM rated a
JOIN rated b
  ON b.location_id <> a.location_id
 AND b.target_date = a.target_date AND b.target_type = a.target_type
 AND b.run_hour = a.run_hour AND b.evaluation_model = a.evaluation_model
 AND b.days_ahead = a.days_ahead
WHERE a.sky_rating IS NOT NULL AND b.sky_rating IS NOT NULL
  AND ABS(a.lat - b.lat) < 0.12 AND ABS(a.lon - b.lon) < 0.2;

CREATE TEMP VIEW row_hi AS
SELECT location_id, target_date, target_type, run_hour, evaluation_model, days_ahead, sky,
       MAX(CASE WHEN same_cell = 1 THEN nb_sky END) AS hi_cell,
       MAX(CASE WHEN km <= 3 THEN nb_sky END) AS hi3,
       MAX(CASE WHEN km <= 5 THEN nb_sky END) AS hi5,
       MAX(CASE WHEN km <= 8 THEN nb_sky END) AS hi8,
       MAX(CASE WHEN km <= 12 THEN nb_sky END) AS hi12,
       COUNT(DISTINCT CASE WHEN km <= 5 THEN nb_loc END) AS n5,
       COUNT(DISTINCT CASE WHEN km <= 5 AND elev_ok = 1 THEN nb_loc END) AS n5e
FROM near_pair
GROUP BY location_id, target_date, target_type, run_hour, evaluation_model, days_ahead, sky;

SELECT evaluation_model AS model, days_ahead AS t, COUNT(DISTINCT run_hour) AS runs,
       SUM(CASE WHEN hi12 IS NOT NULL THEN 1 ELSE 0 END) AS rows_12km,
       SUM(CASE WHEN sky >= 3 AND sky - hi_cell >= 2 THEN 1 ELSE 0 END) AS flag_cell,
       SUM(CASE WHEN sky >= 3 AND sky - hi3 >= 2 THEN 1 ELSE 0 END) AS flag_3km,
       SUM(CASE WHEN sky >= 3 AND sky - hi5 >= 2 THEN 1 ELSE 0 END) AS flag_5km,
       SUM(CASE WHEN sky >= 3 AND sky - hi8 >= 2 THEN 1 ELSE 0 END) AS flag_8km,
       SUM(CASE WHEN sky >= 3 AND sky - hi12 >= 2 THEN 1 ELSE 0 END) AS flag_12km,
       SUM(CASE WHEN sky >= 4 AND sky - hi5 >= 2 THEN 1 ELSE 0 END) AS flag4_5km,
       SUM(CASE WHEN sky >= 3 AND sky - hi5 >= 2 AND n5 >= 2 THEN 1 ELSE 0 END) AS flag_5km_2nb,
       SUM(CASE WHEN sky >= 3 AND sky - hi5 >= 2 AND n5e >= 2 THEN 1 ELSE 0 END) AS flag_plan
FROM row_hi
GROUP BY evaluation_model, days_ahead
ORDER BY evaluation_model, days_ahead;
