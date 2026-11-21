### Chore — best-bet advisor retirement R2b-1: drop the pick table and the model row

Migration V227 drops the unused `pipeline_run_pick` table and deletes the retired `BRIEFING_BEST_BET` row from
`model_selection` (configuration, not cost data). The advisor's `job_run` and `api_call_log` rows stay as cost
history, so `RunType.BRIEFING_BEST_BET` stays too (history only). The `PipelineRunEntity.bestBetStatus` and
`ModelSelectionEntity.extendedThinking` mappings and the `BestBetStatus` enum are deleted; their columns remain until
R2b-2, the release after this one. A local H2 file drops its orphaned `extended_thinking` column at startup so new
model rows still insert. No ratings, verdicts or prompts change.
