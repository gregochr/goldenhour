-- Read-only, PRODUCTION. For each re-rating, how much did the weather inputs change since the previous
-- rating of the same slot, and how much did the stars move? Answers "if inputs barely changed, can we skip?"
-- max_cloud_delta = largest change (percentage points) across overhead, solar-side, antisolar-side and
-- far-solar low cloud, plus precipitation probability. A field present on one side only counts as 999.
WITH s AS (
    SELECT target_type, days_ahead,
           COALESCE(sky_rating, rating) AS stars,
           LAG(COALESCE(sky_rating, rating)) OVER w AS prev_stars,
           forecast_run_at - LAG(forecast_run_at) OVER w AS gap,
           GREATEST(
             abs(coalesce(low_cloud, -999)            - coalesce(LAG(low_cloud) OVER w, -999)),
             abs(coalesce(mid_cloud, -999)            - coalesce(LAG(mid_cloud) OVER w, -999)),
             abs(coalesce(high_cloud, -999)           - coalesce(LAG(high_cloud) OVER w, -999)),
             abs(coalesce(solar_low_cloud, -999)      - coalesce(LAG(solar_low_cloud) OVER w, -999)),
             abs(coalesce(solar_mid_cloud, -999)      - coalesce(LAG(solar_mid_cloud) OVER w, -999)),
             abs(coalesce(solar_high_cloud, -999)     - coalesce(LAG(solar_high_cloud) OVER w, -999)),
             abs(coalesce(antisolar_low_cloud, -999)  - coalesce(LAG(antisolar_low_cloud) OVER w, -999)),
             abs(coalesce(antisolar_mid_cloud, -999)  - coalesce(LAG(antisolar_mid_cloud) OVER w, -999)),
             abs(coalesce(antisolar_high_cloud, -999) - coalesce(LAG(antisolar_high_cloud) OVER w, -999)),
             abs(coalesce(far_solar_low_cloud, -999)  - coalesce(LAG(far_solar_low_cloud) OVER w, -999)),
             abs(coalesce(precipitation_probability_percent, -999)
                 - coalesce(LAG(precipitation_probability_percent) OVER w, -999))
           ) AS max_cloud_delta,
           (visibility IS NOT DISTINCT FROM LAG(visibility) OVER w
            AND precipitation IS NOT DISTINCT FROM LAG(precipitation) OVER w
            AND humidity IS NOT DISTINCT FROM LAG(humidity) OVER w
            AND weather_code IS NOT DISTINCT FROM LAG(weather_code) OVER w) AS other_same
    FROM forecast_evaluation
    WHERE forecast_run_at >= now() - interval '30 days'
      AND COALESCE(sky_rating, rating) IS NOT NULL
    WINDOW w AS (PARTITION BY location_id, target_date, target_type ORDER BY forecast_run_at)
),
b AS (
    SELECT *,
           CASE WHEN max_cloud_delta = 0 AND other_same THEN '0 identical'
                WHEN max_cloud_delta = 0               THEN '0 cloud, other moved'
                WHEN max_cloud_delta <= 5              THEN '1-5'
                WHEN max_cloud_delta <= 10             THEN '6-10'
                WHEN max_cloud_delta <= 20             THEN '11-20'
                WHEN max_cloud_delta <= 40             THEN '21-40'
                ELSE '>40' END AS delta_bucket
    FROM s WHERE prev_stars IS NOT NULL
),
scoped AS (
    SELECT 'all re-ratings' AS scope, * FROM b
    UNION ALL
    -- Cut A's population: today's sunrise re-rated within 8 h of the previous rating (evening -> nightly).
    SELECT 'T0 sunrise, gap <= 8h', * FROM b
    WHERE target_type = 'SUNRISE' AND days_ahead = 0 AND gap <= interval '8 hours'
)
SELECT scope, delta_bucket,
       count(*)                                                              AS pairs,
       round(100.0 * count(*) FILTER (WHERE stars = prev_stars) / count(*), 1)        AS pct_same,
       round(100.0 * count(*) FILTER (WHERE abs(stars - prev_stars) = 1) / count(*), 1) AS pct_moved_1,
       round(100.0 * count(*) FILTER (WHERE abs(stars - prev_stars) >= 2) / count(*), 1) AS pct_moved_2plus
FROM scoped
GROUP BY scope, delta_bucket
ORDER BY scope, min(CASE delta_bucket WHEN '0 identical' THEN 0 WHEN '0 cloud, other moved' THEN 1
                         WHEN '1-5' THEN 2 WHEN '6-10' THEN 3 WHEN '11-20' THEN 4 WHEN '21-40' THEN 5
                         ELSE 6 END);
