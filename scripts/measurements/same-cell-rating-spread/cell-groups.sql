-- Same-cell rating spread: one row per (grid cell, window, run, model, horizon) group of 2+ rated
-- sky locations. Read-only. :since is a psql variable (ISO date); see README.md.
-- Portable: no DISTINCT ON, no date_trunc, no intervals (validated on sqlite3 3.43 too).
CREATE TEMP VIEW sky_loc AS
SELECT l.id, l.lat, l.lon, l.grid_lat, l.grid_lng, l.is_coastal_tidal
FROM locations l
WHERE l.grid_lat IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM location_location_type t
                  WHERE t.location_id = l.id AND t.location_type IN ('WOODLAND', 'BLUEBELL', 'WILDLIFE'));

CREATE TEMP VIEW rated AS
SELECT e.location_id, s.lat, s.lon, s.grid_lat, s.grid_lng, s.is_coastal_tidal, e.target_date, e.target_type,
       e.evaluation_model, e.days_ahead, e.rating, e.sky_rating,
       e.solar_low_cloud, e.solar_mid_cloud, e.solar_high_cloud, e.far_solar_low_cloud,
       SUBSTR(CAST(e.forecast_run_at AS VARCHAR(40)), 1, 13) AS run_hour
FROM forecast_evaluation e
JOIN sky_loc s ON s.id = e.location_id
WHERE e.rating IS NOT NULL
  AND e.target_type IN ('SUNRISE', 'SUNSET')
  AND e.forecast_run_at >= :'since';

CREATE TEMP VIEW cell_group AS
SELECT grid_lat, grid_lng, target_date, target_type, run_hour, evaluation_model, days_ahead,
       COUNT(*) AS n, MIN(rating) AS lo, MAX(rating) AS hi,
       COUNT(sky_rating) AS n_sky, MIN(sky_rating) AS sky_lo, MAX(sky_rating) AS sky_hi,
       SUM(CASE WHEN is_coastal_tidal THEN 1 ELSE 0 END) AS n_tidal
FROM rated
GROUP BY grid_lat, grid_lng, target_date, target_type, run_hour, evaluation_model, days_ahead
HAVING COUNT(*) >= 2;
