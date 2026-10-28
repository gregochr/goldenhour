-- Retire the three term run types (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM), the optimisation_strategy table
-- and the Prompt Test harness's rows (sync-engine-retirement-plan.md, Phase 3).
--
-- Why one migration: job_run.run_type and model_selection.run_type map through @Enumerated(STRING), and the
-- three constants are deleted from RunType in the same change. A single row still holding one makes Hibernate
-- throw on read, which would break the Job Runs list and GET /api/models. So the rows go with the constants.
-- No other column stores these values; the only other literals are in immutable historic migrations.
--
-- job_run: api_call_log.job_run_id is the only foreign key to job_run and is ON DELETE CASCADE (V20), so the
-- cost-log rows of those past hand-started runs go with them (admin experiments, accepted in the plan).
-- Three columns point at job_run with no foreign key: forecast_batch.job_run_id (V85),
-- forecast_run_disposition.job_run_id (V101) and pipeline_run.disposition_job_run_id (V163). They carry no
-- foreign key, so a stale reference to a deleted term-type run would be a harmless dangling Long. The
-- batch pipeline is expected to write them against SCHEDULED_BATCH runs only, but production data was not
-- inspected here, and the migration test guards a freshly migrated database only.
--
-- Prompt Test: the harness is deleted. prompt_test_result is cleared before prompt_test_run (it holds the FK
-- to the run). The two TABLES stay: ROLLBACK RULE (prod runs ddl-auto: validate) -- an older image must still
-- validate against the newer schema, so a migration may drop only what the PREVIOUS phase's image no longer
-- maps. Phase 2's image still maps prompt_test_run (including run_type), so dropping them waits for Phase 4,
-- one release later. optimisation_strategy is different: Phase 2's image has no entity for it, so it can go now.
DELETE FROM model_selection
WHERE run_type IN ('VERY_SHORT_TERM', 'SHORT_TERM', 'LONG_TERM');

DELETE FROM job_run
WHERE run_type IN ('VERY_SHORT_TERM', 'SHORT_TERM', 'LONG_TERM');

DELETE FROM prompt_test_result;
DELETE FROM prompt_test_run;

DROP TABLE optimisation_strategy;
