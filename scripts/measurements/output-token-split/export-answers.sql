-- Pairs every billed sky call of one batch job run with the answer it produced.
-- Run on PRODUCTION (read-only). Change the job_run_id below to measure a different batch.
--
--   psql "$PROD_DB_URL" -At -v job_run_id=2353 -f export-answers.sql > answers.jsonl
--
-- One JSON line per call: custom_id, billed output_tokens, and the answer rebuilt from the
-- stored columns in the schema's property order (PromptBuilder.buildOutputConfig), with
-- absent fields dropped, as the model would have omitted them.
SELECT json_build_object(
           'custom_id', a.custom_id,
           'output_tokens', a.output_tokens,
           'answer', json_strip_nulls(json_build_object(
               'rating', COALESCE(f.sky_rating, f.rating),
               'fiery_sky', f.fiery_sky_potential,
               'golden_hour', f.golden_hour_potential,
               'summary', f.summary,
               'headline', f.headline,
               'basic_fiery_sky', f.basic_fiery_sky_potential,
               'basic_golden_hour', f.basic_golden_hour_potential,
               'basic_summary', f.basic_summary,
               'inversion_score', f.inversion_score,
               'inversion_potential', f.inversion_potential,
               'mist_potential', f.mist_potential)))::text
FROM api_call_log a
JOIN forecast_evaluation f
  ON f.id = substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint
WHERE a.job_run_id = :job_run_id
  AND a.succeeded
  AND a.custom_id LIKE 'fc-%'
  AND a.output_tokens IS NOT NULL;

-- Sanity check (run separately): how many calls the batch had, and how many the join could not
-- pair (bluebell/woodland lanes, failures, ids without an -r suffix).
--   SELECT count(*) AS calls,
--          count(*) FILTER (WHERE custom_id LIKE 'fc-%' AND custom_id ~ '-r[0-9]+(-f)?$') AS pairable,
--          sum(output_tokens) AS output_tokens
--   FROM api_call_log WHERE job_run_id = 2353 AND succeeded;
