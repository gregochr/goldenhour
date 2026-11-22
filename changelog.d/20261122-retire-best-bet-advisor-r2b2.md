### Chore — best-bet advisor retirement R2b-2: drop the two unmapped columns

Migration V228 drops `pipeline_run.best_bet_status` and `model_selection.extended_thinking`, which no entity has
mapped since R2b-1. It is irreversible and ships only after the release containing R2b-1 is deployed. No ratings,
verdicts or prompts change.
