-- Tuning the serve-time neighbour guard's rule. The served slot carries no solar-horizon cloud, so
-- a guard at serve time can only use distance (or the grid cell). For each rated row with a
-- sky_rating, flag it when its sky_rating is 3+ and at least 2 above EVERY sky-rated neighbour in
-- the same run, window, model and horizon within r km, for r = 3, 5, 8, 12, and in the same
-- grid cell. A row with no neighbour inside r is never flagged at that r. flag_5km_2nb also
-- requires at least two neighbours inside 5 km (the plan's min-neighbours rule). flag_plan is the
-- plan's full default rule: no neighbour within 5 km rated within 1 star of it (any elevation), and at
-- least two neighbours within 5 km that are at a similar height (both elevations unknown, or both known
-- and within 150 m). Run after
-- cell-groups.sql. Rows written before V164 have no sky_rating and are not counted.
CREATE TEMP VIEW near_pair AS
SELECT a.location_id, a.target_date, a.target_type, a.run_hour, a.evaluation_model, a.days_ahead,
       a.sky_rating AS sky, b.sky_rating AS nb_sky,
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
       SUM(CASE WHEN km <= 5 THEN 1 ELSE 0 END) AS n5,
       SUM(CASE WHEN km <= 5 AND elev_ok = 1 THEN 1 ELSE 0 END) AS n5e
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
