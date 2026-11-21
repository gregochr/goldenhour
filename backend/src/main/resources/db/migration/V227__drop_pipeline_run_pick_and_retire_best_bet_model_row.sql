-- Best-bet advisor retirement, R2b-1. The previous release image (2.41.0) maps no entity to pipeline_run_pick,
-- so the table can go now. The model_selection row for the retired run type is configuration, not cost data.
-- job_run / api_call_log rows of BRIEFING_BEST_BET are kept on purpose (cost history), and the
-- pipeline_run.best_bet_status and model_selection.extended_thinking columns stay until R2b-2, the release after
-- this one, because the 2.41.0 image still maps them under ddl-auto: validate.
DROP TABLE IF EXISTS pipeline_run_pick;

DELETE FROM model_selection WHERE run_type = 'BRIEFING_BEST_BET';
