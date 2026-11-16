-- Read-only, PRODUCTION. Phase 0 of input-equivalent-share-plan.md: how many Sonnet T+0/1 sky requests in
-- one pipeline run would share one request if the prompt is compared after a LADDER of masks, each level
-- adding to the one before? Uses forecast_evaluation_prompt (V164, the exact user message, kept 30 days).
-- Run with:
--   docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 \
--     < docs/engineering/cost-queries/input-equivalent-share-replay.sql
--
-- Every level keeps: target date, event, model, the system-prompt hash (inland vs coastal), the pipeline
-- run, and every bracketed rule label in the message ([BUILDING], [THIN STRIP], [CLEARING],
-- [AT/NEAR DEW POINT], ...). Masks replace NUMBERS or place geometry only, never a label.
--   L0  the "Location: <name>. " prefix removed                     (= shared-call-replay strict)
--   L1  + the event time cut to the minute                          (= shared-call-replay minute)
--   L2  + the event time removed (places in one run differ by seconds to a minute)
--   L3  + the upwind sample's geometry "(43km along 244° WSW)" removed (values kept)
--   L4  + the approach-trend history slots T-3h..T-1h masked (event slot and labels kept)
--   L5  + the mist/visibility trend numbers masked (labels kept)
--   L6  reference only, not adoptable without a new review: + overhead detail lines masked
--       (humidity, dew point, weather code, boundary layer, aerosol, wind)
-- saveable = group size minus one, summed. Stars columns: inside a group every place would get the
-- representative's answer, so the stars it actually got show what sharing would change.
-- The TEMP VIEW is session-local: nothing persists in the database.

CREATE TEMP VIEW ies_rows AS
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           p.id AS pipeline_run_id, a.cost_micro_dollars
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%' AND a.custom_id NOT LIKE '%-f'
      AND a.called_at >= now() - interval '30 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
),
r AS (
    SELECT f.id, c.pipeline_run_id, c.cost_micro_dollars AS cost_micro,
           COALESCE(f.sky_rating, f.rating) AS stars,
           f.target_date, f.target_type, f.evaluation_model, f.prompt_hash,
           CASE WHEN starts_with(pr.user_message, 'Location: ' || l.name || '. ')
                THEN substr(pr.user_message, length('Location: ' || l.name || '. ') + 1) END AS b0
    FROM forecast_evaluation f
    JOIN forecast_evaluation_prompt pr ON pr.evaluation_id = f.id
    JOIN locations l ON l.id = f.location_id
    JOIN cyc c ON c.eval_id = f.id
    WHERE f.days_ahead <= 1
      AND f.target_type IN ('SUNRISE', 'SUNSET')
      AND f.evaluation_model = 'SONNET_55'
),
m AS (
    SELECT r.*,
        regexp_replace(b0,
            '^(SUNRISE|SUNSET): ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2})(:[0-9]{2}(\.[0-9]+)?)? UTC\.',
            '\1: \2 UTC.') AS b1,
        regexp_replace(b0, '^(SUNRISE|SUNSET): [^\n]*? UTC\.', '\1.') AS b2
    FROM r
),
m3 AS (SELECT m.*, regexp_replace(b2, 'Upwind sample \([^)]*\)', 'Upwind sample', 'g') AS b3 FROM m),
m4 AS (SELECT m3.*, regexp_replace(b3, 'T-[0-9]h=[^ \n]*', 'T-h=*', 'g') AS b4 FROM m3),
m5 AS (
    SELECT m4.*,
        regexp_replace(b4,
            'vis=[0-9,]+m temp=-?[0-9.]+°C dew=-?[0-9.]+°C \(gap=-?[0-9.]+°C\)', 'vis=*', 'g') AS b5
    FROM m4
),
m6 AS (
    SELECT m5.*,
        regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(b5,
            'Humidity: [^\n]*', 'Humidity: *', 'g'),
            'Dew point: [^\n]*', 'Dew point: *', 'g'),
            'Weather code: [^\n]*', 'Weather code: *', 'g'),
            'Boundary layer: [^\n]*', 'Boundary layer: *', 'g'),
            'PM2\.5: [^\n]*', 'PM2.5: *', 'g'),
            'Wind: [^,\n]*', 'Wind: *', 'g') AS b6
    FROM m5
)
SELECT id, pipeline_run_id, cost_micro, stars, (b0 IS NOT NULL) AS prefix_matched, lvl,
       CASE WHEN b0 IS NULL THEN 'u' || id
            ELSE md5(concat_ws('|', target_date, target_type, evaluation_model, prompt_hash, body)) END AS k
FROM m6
CROSS JOIN LATERAL (VALUES ('L0', b0), ('L1', b1), ('L2', b2), ('L3', b3), ('L4', b4), ('L5', b5),
                           ('L6 (reference)', b6)) AS v(lvl, body);

-- 1. The ladder: what one request per group would save at each level, and what sharing would change.
WITH g AS (
    SELECT lvl, pipeline_run_id, k, count(*) AS n, avg(cost_micro) AS c, max(stars) - min(stars) AS spread
    FROM ies_rows GROUP BY 1, 2, 3
), tot AS (
    SELECT count(*) AS requests, count(*) FILTER (WHERE prefix_matched) AS prefix_matched,
           round(sum(cost_micro) / 1e6, 2) AS usd_30d
    FROM ies_rows WHERE lvl = 'L0'
)
SELECT g.lvl,
       tot.requests, tot.prefix_matched, tot.usd_30d,
       sum(n - 1)                                                       AS saveable,
       round(100.0 * sum(n - 1) / tot.requests, 2)                      AS pct_saveable,
       round(sum((n - 1) * c) / 1e6, 2)                                 AS saveable_usd_30d,
       count(*) FILTER (WHERE n > 1)                                    AS groups_2plus,
       count(*) FILTER (WHERE n > 1 AND spread = 0)                     AS groups_same_stars,
       count(*) FILTER (WHERE n > 1 AND spread = 1)                     AS groups_spread_1,
       count(*) FILTER (WHERE n > 1 AND spread >= 2)                    AS groups_spread_2plus
FROM g CROSS JOIN tot
GROUP BY g.lvl, tot.requests, tot.prefix_matched, tot.usd_30d
ORDER BY g.lvl;
