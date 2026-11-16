-- Read-only, PRODUCTION. Before building the 60% triage line for T+0/1 (triage-cost-cut-plan.md, P1):
-- of the T+0/1 ratings with sun-side low cloud 61-80%, how many are a THIN STRIP (PromptBuilder.isThinStrip:
-- near >= 50 and near - far >= 30 points), which P1 exempts, and how many 4-star ratings sit on each side?
-- Run with:
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/triage-60-thin-strip.sql
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           a.cost_micro_dollars
    FROM api_call_log a
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '45 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
)
SELECT CASE WHEN f.solar_low_cloud IS NULL THEN 'no directional data'
            WHEN f.far_solar_low_cloud IS NOT NULL
                 AND f.solar_low_cloud - f.far_solar_low_cloud >= 30 THEN 'thin strip (exempt, stays at 80)'
            ELSE 'blanket (stood down at 60)' END                     AS shape,
       count(*)                                                       AS requests,
       round(sum(COALESCE(c.cost_micro_dollars, 0)) / 1e6, 2)         AS usd_45d,
       count(*) FILTER (WHERE COALESCE(f.sky_rating, f.rating) >= 4)  AS n_ge_4,
       count(*) FILTER (WHERE COALESCE(f.sky_rating, f.rating) = 3)   AS n_3
FROM forecast_evaluation f
LEFT JOIN cyc c ON c.eval_id = f.id
WHERE f.forecast_run_at >= now() - interval '45 days'
  AND f.target_type IN ('SUNRISE', 'SUNSET') AND f.days_ahead <= 1
  AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
  AND COALESCE(f.solar_low_cloud, f.low_cloud) > 60
GROUP BY 1
ORDER BY 1;
