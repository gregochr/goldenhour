-- How many ratings a same-neighbourhood guard would flag: a served rating of 3+ that sits at least
-- 2 stars above EVERY near-twin (same definition as neighbour-pairs.sql: same run, window, model
-- and horizon, within :km km, solar-horizon low/mid/high within 5 points). Only a rating of 3+ can
-- become an Ask pick or move a region mean upward, so that is the population a guard protects.
-- Run after cell-groups.sql.
CREATE TEMP VIEW twin_max AS
SELECT a.location_id, a.target_date, a.target_type, a.run_hour, a.evaluation_model, a.days_ahead,
       a.rating, a.is_coastal_tidal, MAX(b.rating) AS twin_hi, COUNT(*) AS twins
FROM rated a
JOIN rated b
  ON b.location_id <> a.location_id
 AND b.target_date = a.target_date AND b.target_type = a.target_type
 AND b.run_hour = a.run_hour AND b.evaluation_model = a.evaluation_model
 AND b.days_ahead = a.days_ahead
WHERE a.solar_low_cloud IS NOT NULL AND b.solar_low_cloud IS NOT NULL
  AND ABS(a.solar_low_cloud - b.solar_low_cloud) <= 5
  AND ABS(a.solar_mid_cloud - b.solar_mid_cloud) <= 5
  AND ABS(a.solar_high_cloud - b.solar_high_cloud) <= 5
  AND 111.32 * SQRT((a.lat - b.lat) * (a.lat - b.lat)
        + ((a.lon - b.lon) * COS(RADIANS(a.lat))) * ((a.lon - b.lon) * COS(RADIANS(a.lat))))
      <= CAST(:'km' AS DOUBLE PRECISION)
GROUP BY a.location_id, a.target_date, a.target_type, a.run_hour, a.evaluation_model, a.days_ahead,
         a.rating, a.is_coastal_tidal;

SELECT evaluation_model AS model, days_ahead AS t,
       COUNT(*) AS rated_with_twin,
       SUM(CASE WHEN rating >= 3 AND rating - twin_hi >= 2 THEN 1 ELSE 0 END) AS flagged,
       ROUND(100.0 * SUM(CASE WHEN rating >= 3 AND rating - twin_hi >= 2 THEN 1 ELSE 0 END) / COUNT(*), 2) AS flagged_pct,
       SUM(CASE WHEN rating >= 4 AND rating - twin_hi >= 2 THEN 1 ELSE 0 END) AS flagged_4plus,
       SUM(CASE WHEN NOT is_coastal_tidal AND rating >= 3 AND rating - twin_hi >= 2 THEN 1 ELSE 0 END) AS flagged_inland,
       COUNT(DISTINCT run_hour) AS runs
FROM twin_max
GROUP BY evaluation_model, days_ahead
ORDER BY evaluation_model, days_ahead;
