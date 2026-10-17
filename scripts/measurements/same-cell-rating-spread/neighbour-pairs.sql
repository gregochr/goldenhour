-- Pairs of rated sky locations within :km km of each other, rated in the same run for the same
-- window by the same model, whose solar-horizon cloud inputs agree to within 5 points on all
-- three layers (the Caw Gap / Cawfields shape: near-identical prompts, different stars).
-- Run after cell-groups.sql (it reuses the rated view). Distance is equirectangular, fine at 15 km.
CREATE TEMP VIEW twin AS
SELECT a.evaluation_model, a.days_ahead, a.rating AS ra, b.rating AS rb,
       a.is_coastal_tidal AS ta, b.is_coastal_tidal AS tb
FROM rated a
JOIN rated b
  ON b.location_id > a.location_id
 AND b.target_date = a.target_date AND b.target_type = a.target_type
 AND b.run_hour = a.run_hour AND b.evaluation_model = a.evaluation_model
 AND b.days_ahead = a.days_ahead
WHERE a.solar_low_cloud IS NOT NULL AND b.solar_low_cloud IS NOT NULL
  AND ABS(a.solar_low_cloud - b.solar_low_cloud) <= 5
  AND ABS(a.solar_mid_cloud - b.solar_mid_cloud) <= 5
  AND ABS(a.solar_high_cloud - b.solar_high_cloud) <= 5
  AND 111.32 * SQRT((a.lat - b.lat) * (a.lat - b.lat)
        + ((a.lon - b.lon) * COS(RADIANS(a.lat))) * ((a.lon - b.lon) * COS(RADIANS(a.lat))))
      <= CAST(:'km' AS DOUBLE PRECISION);

SELECT evaluation_model AS model, days_ahead AS t, COUNT(*) AS pairs,
       SUM(CASE WHEN ABS(ra - rb) >= 2 THEN 1 ELSE 0 END) AS ge2,
       ROUND(100.0 * SUM(CASE WHEN ABS(ra - rb) >= 2 THEN 1 ELSE 0 END) / COUNT(*), 1) AS ge2_pct,
       SUM(CASE WHEN ABS(ra - rb) >= 3 THEN 1 ELSE 0 END) AS ge3,
       SUM(CASE WHEN NOT ta AND NOT tb THEN 1 ELSE 0 END) AS inland_pairs,
       SUM(CASE WHEN NOT ta AND NOT tb AND ABS(ra - rb) >= 2 THEN 1 ELSE 0 END) AS inland_ge2
FROM twin
GROUP BY evaluation_model, days_ahead
ORDER BY evaluation_model, days_ahead;
