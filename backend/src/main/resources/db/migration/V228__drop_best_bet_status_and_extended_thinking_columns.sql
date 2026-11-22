-- Best-bet advisor retirement, R2b-2. The previous release image (the one containing R2b-1, V227) no longer maps
-- pipeline_run.best_bet_status or model_selection.extended_thinking, so both columns can go. Irreversible: the
-- values are not recoverable. Must not be applied until that release is deployed, because an older image still
-- maps both columns under ddl-auto: validate.
ALTER TABLE pipeline_run DROP COLUMN IF EXISTS best_bet_status;

ALTER TABLE model_selection DROP COLUMN IF EXISTS extended_thinking;
