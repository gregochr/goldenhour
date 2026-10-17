-- Keeps what is needed to study Haiku's two-star rating flips on identical inputs:
--   (a) the exact user message each batch SKY forecast request sent (api_call_log.request_body is
--       empty for batch rows, so no production prompt could be replayed), and
--   (b) Claude's own sky rating before the tide score is averaged in (forecast_evaluation.rating
--       is the combined star).
--
-- The message lives in a side table, not a column: forecast_evaluation is read whole-entity on hot
-- serve paths and a ~1-1.5 KB text column there would be loaded on every map request. The system
-- prompt is not stored (identical per run). Rows are pruned nightly by forecast_prompt_cleanup
-- (photocast.forecast-prompt.retention-days, default 30).
CREATE TABLE forecast_evaluation_prompt (
    evaluation_id BIGINT      NOT NULL PRIMARY KEY
        REFERENCES forecast_evaluation (id) ON DELETE CASCADE,
    user_message  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_forecast_evaluation_prompt_created_at
    ON forecast_evaluation_prompt (created_at);

-- Nullable: null for rows scored before this migration, triage rows, the sky-not-forecast
-- substitution and the synchronous engine.
ALTER TABLE forecast_evaluation ADD COLUMN sky_rating INTEGER;

-- 03:50 UTC is free: 03:30 holds the aurora batch and disposition cleanup, 03:45 the
-- slot_atmosphere cleanup. Same shape as V162's seed.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('forecast_prompt_cleanup',
        'Forecast Prompt Cleanup',
        'Prunes stored batch forecast user messages older than 30 days',
        'CRON',
        '0 50 3 * * *',
        'ACTIVE');
