-- V148: Deduplicate pre-existing duplicate (pipeline_run_id, pick_rank) rows
-- in pipeline_run_pick, then enforce uniqueness going forward.
--
-- Root cause (see PipelineOrchestrator.waitAndBriefPhase): resuming a run
-- whose currentPhase was already BRIEFING at startup unconditionally
-- restarted the BRIEFING phase and re-ran refreshBriefing() +
-- persistPicksForCycle(). PipelineRunPickService.persist() always INSERTed
-- (repository.save() on a fresh entity), so a process restart between a
-- briefing publish and the run reaching COMPLETED could leave a pipeline
-- run holding two rank-1 (or two rank-2) pick rows. The V104 index on
-- (pipeline_run_id, pick_rank) was never unique, so nothing prevented it.
--
-- ⚠️ This migration DELETES ROWS. Production is a separate host that is
-- never inspected from here, so we cannot know whether it already carries
-- duplicates from the pre-fix window — the dedupe below must run
-- unconditionally, before the constraint is added, or the deploy fails on
-- the first pre-existing duplicate. For any (pipeline_run_id, pick_rank)
-- group with more than one row, every row except the one with the highest
-- id (the most recently written — the freshest snapshot of that pick) is
-- deleted. The statement is idempotent: after it runs once, no group has
-- more than one row, so running it again is a no-op.
--
-- The orchestrator and pick-persistence fixes that stop new duplicates from
-- being created land alongside this migration (PipelineOrchestrator no
-- longer restarts an already-current BRIEFING phase; PipelineRunPickService
-- now upserts on (pipeline_run_id, pick_rank) instead of blind-inserting).
DELETE FROM pipeline_run_pick p
WHERE EXISTS (
    SELECT 1 FROM pipeline_run_pick newer
    WHERE newer.pipeline_run_id = p.pipeline_run_id
      AND newer.pick_rank = p.pick_rank
      AND newer.id > p.id
);

-- Superseded by the unique index the constraint below creates.
DROP INDEX idx_pick_pipeline_run;

ALTER TABLE pipeline_run_pick
    ADD CONSTRAINT uq_pipeline_run_pick_rank UNIQUE (pipeline_run_id, pick_rank);
