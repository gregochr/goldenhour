-- Drop the Location Model Test and Briefing Model Test tables (model-test-retirement-plan.md, Phase 2).
--
-- Why a separate, later migration: Phase 1 deleted the entities, services and screens and left the four
-- tables in place. ROLLBACK RULE (prod runs ddl-auto: validate) -- an older image must still validate
-- against the newer schema, so a migration may drop only what the PREVIOUS release's image no longer maps.
-- The previous release (v2.35.2) carries Phase 1 and maps none of the four tables, so it is the rollback
-- target for this migration.
--
-- Dependencies (checked with grep -rn "model_test" over every migration): the only foreign keys are
-- model_test_result.test_run_id -> model_test_run ON DELETE CASCADE (V34:37), model_test_result.region_id ->
-- regions and model_test_result.location_id -> locations (V34:38-39), model_test_run.parent_run_id ->
-- model_test_run (V39:9-10, a self-reference) and briefing_model_test_result.test_run_id ->
-- briefing_model_test_run (V63:33-34). Nothing else references any of the four, so no CASCADE is needed;
-- indexes and the identity sequences go with their tables. Each result table is dropped before its run
-- table because it holds the FK to it.
--
-- Dropping model_test_result also removes the FK to regions that V137 section 6 had to work around, so
-- regions and locations rows can be deleted without a historic result row blocking them.
DROP TABLE briefing_model_test_result;
DROP TABLE briefing_model_test_run;
DROP TABLE model_test_result;
DROP TABLE model_test_run;
