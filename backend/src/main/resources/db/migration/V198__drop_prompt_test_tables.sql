-- Drop the Prompt Test tables (sync-engine-retirement-plan.md, Phase 4).
--
-- Why a separate, later migration: the Prompt Test harness code was deleted in Phase 3 and V196 emptied both
-- tables, but left them in place. ROLLBACK RULE (prod runs ddl-auto: validate) -- an older image must still
-- validate against the newer schema, so a migration may drop only what the PREVIOUS release's image no longer
-- maps. The Phase 2 image still maps prompt_test_run and prompt_test_result; the Phase 3 image does not. This
-- migration therefore ships one release after V196, so a rolled-back Phase 3 image is the rollback target.
--
-- Dependencies (checked against V44, V45, V125 and every later migration): the only foreign keys are
-- prompt_test_run.parent_run_id -> prompt_test_run (fk_ptr_parent, a self-reference) and
-- prompt_test_result.test_run_id -> prompt_test_run (fk_ptres_test_run). No other table references either,
-- so no CASCADE is needed; indexes and the identity sequences go with their tables. The result table is
-- dropped first because it holds the FK to the run table.
DROP TABLE prompt_test_result;
DROP TABLE prompt_test_run;
