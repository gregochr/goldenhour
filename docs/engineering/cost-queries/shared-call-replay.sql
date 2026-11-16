-- Read-only, PRODUCTION. P2a's answer from history instead of a week of logs (triage-cost-cut-plan.md, P2):
-- how many Sonnet T+0/1 sky requests in one pipeline run sent the SAME prompt apart from the place name?
-- Uses forecast_evaluation_prompt (V164), which keeps every batch sky request's exact user message for
-- 30 days. Run with:
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/shared-call-replay.sql
--
-- strict key = target date, event, model, system-prompt hash (inland vs coastal), and the user message with
--              its leading "Location: <name>. " removed. A message that does not start with that exact
--              prefix never shares.
-- minute key = the same, with the event time ("SUNRISE: 2026-11-16T07:23:45 UTC.") cut to the minute.
-- saveable   = calls one request per group would have saved (group size minus one, summed).
-- The stars columns check the name's influence: within a strict group every place saw an identical
-- prompt apart from its name, so any star spread there is the name or the model's own wobble.
-- The TEMP VIEW is session-local: nothing persists in the database.

CREATE TEMP VIEW share_rows AS
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           p.id AS pipeline_run_id, p.cycle_type, a.cost_micro_dollars
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%' AND a.custom_id NOT LIKE '%-f'
      AND a.called_at >= now() - interval '30 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
),
r AS (
    SELECT f.id, f.location_id, l.grid_lat, l.grid_lng, c.pipeline_run_id, c.cycle_type,
           c.cost_micro_dollars AS cost_micro,
           COALESCE(f.sky_rating, f.rating) AS stars,
           f.target_date, f.target_type, f.evaluation_model, f.prompt_hash,
           CASE WHEN starts_with(pr.user_message, 'Location: ' || l.name || '. ')
                THEN substr(pr.user_message, length('Location: ' || l.name || '. ') + 1) END AS body
    FROM forecast_evaluation f
    JOIN forecast_evaluation_prompt pr ON pr.evaluation_id = f.id
    JOIN locations l ON l.id = f.location_id
    JOIN cyc c ON c.eval_id = f.id
    WHERE f.days_ahead <= 1
      AND f.target_type IN ('SUNRISE', 'SUNSET')
      AND f.evaluation_model = 'SONNET_55'
)
SELECT *,
       CASE WHEN body IS NULL THEN 'u' || id
            ELSE md5(concat_ws('|', target_date, target_type, evaluation_model, prompt_hash, body)) END
           AS strict_key,
       CASE WHEN body IS NULL THEN 'u' || id
            ELSE md5(concat_ws('|', target_date, target_type, evaluation_model, prompt_hash,
                     regexp_replace(body,
                         '^(SUNRISE|SUNSET): ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2})(:[0-9]{2}(\.[0-9]+)?)? UTC\.',
                         '\1: \2 UTC.'))) END
           AS minute_key
FROM r;

-- 1. By cycle: requests, how many matched the name prefix, and what one-request-per-group would save.
WITH s AS (
    SELECT cycle_type, pipeline_run_id, strict_key, count(*) AS n, avg(cost_micro) AS c
    FROM share_rows GROUP BY 1, 2, 3
), m AS (
    SELECT cycle_type, pipeline_run_id, minute_key, count(*) AS n, avg(cost_micro) AS c
    FROM share_rows GROUP BY 1, 2, 3
), t AS (
    SELECT cycle_type, count(*) AS requests, count(*) FILTER (WHERE body IS NOT NULL) AS prefix_matched,
           round(sum(cost_micro) / 1e6, 2) AS usd_30d
    FROM share_rows GROUP BY 1
)
SELECT t.cycle_type, t.requests, t.prefix_matched, t.usd_30d,
       (SELECT sum(n - 1) FROM s WHERE s.cycle_type = t.cycle_type)                         AS strict_saveable,
       (SELECT round(sum((n - 1) * c) / 1e6, 2) FROM s WHERE s.cycle_type = t.cycle_type)   AS strict_usd_30d,
       (SELECT sum(n - 1) FROM m WHERE m.cycle_type = t.cycle_type)                         AS minute_saveable,
       (SELECT round(sum((n - 1) * c) / 1e6, 2) FROM m WHERE m.cycle_type = t.cycle_type)   AS minute_usd_30d
FROM t
ORDER BY t.cycle_type;

-- 2. Inside the strict groups: same weather cell? same stars? (the name's influence, plus model wobble)
SELECT count(*)                                                        AS strict_groups_2plus,
       sum(n)                                                          AS places_in_them,
       count(*) FILTER (WHERE cells = 1)                               AS groups_one_cell,
       count(*) FILTER (WHERE spread = 0)                              AS groups_same_stars,
       count(*) FILTER (WHERE spread = 1)                              AS groups_spread_1,
       count(*) FILTER (WHERE spread >= 2)                             AS groups_spread_2plus
FROM (
    SELECT pipeline_run_id, strict_key, count(*) AS n,
           count(DISTINCT (grid_lat, grid_lng)) AS cells,
           max(stars) - min(stars) AS spread
    FROM share_rows
    GROUP BY 1, 2
    HAVING count(*) > 1
) g;
