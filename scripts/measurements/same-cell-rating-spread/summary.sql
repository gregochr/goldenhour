-- Share of same-cell groups whose served rating spans 2+ / 3+ stars, by model and horizon.
-- "all" = every group; "inland" = groups with no tidal location (rating carries no tide average);
-- "sky" = groups where every member has sky_rating (V164+), spread measured on sky_rating.
SELECT evaluation_model AS model, days_ahead AS t,
       COUNT(*) AS groups,
       SUM(CASE WHEN hi - lo >= 2 THEN 1 ELSE 0 END) AS ge2,
       ROUND(100.0 * SUM(CASE WHEN hi - lo >= 2 THEN 1 ELSE 0 END) / COUNT(*), 1) AS ge2_pct,
       SUM(CASE WHEN hi - lo >= 3 THEN 1 ELSE 0 END) AS ge3,
       ROUND(100.0 * SUM(CASE WHEN hi - lo >= 3 THEN 1 ELSE 0 END) / COUNT(*), 1) AS ge3_pct,
       SUM(CASE WHEN n_tidal = 0 THEN 1 ELSE 0 END) AS inland_groups,
       SUM(CASE WHEN n_tidal = 0 AND hi - lo >= 2 THEN 1 ELSE 0 END) AS inland_ge2,
       SUM(CASE WHEN n_sky = n THEN 1 ELSE 0 END) AS sky_groups,
       SUM(CASE WHEN n_sky = n AND sky_hi - sky_lo >= 2 THEN 1 ELSE 0 END) AS sky_ge2,
       COUNT(DISTINCT run_hour) AS runs
FROM cell_group
GROUP BY evaluation_model, days_ahead
ORDER BY evaluation_model, days_ahead;
