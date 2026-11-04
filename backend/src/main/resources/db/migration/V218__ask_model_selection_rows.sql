-- V218: Ask PhotoCast's Claude model becomes two model_selection rows, ASK (typed questions) and
-- ASK_READY (the ready-answer precompute), so it can be switched on Operations -> Run Config without a
-- deploy. It was the YAML key photocast.ask.model (retired in the same change), whose default and
-- production value is HAIKU, so the rows are seeded HAIKU and nothing changes on deploy.
--
-- Each insert is guarded on run_type so a row an operator has already created is left alone.
-- extended_thinking stays false (the Ask engine does not honour the flag).

INSERT INTO model_selection (active_model, run_type, extended_thinking, updated_at)
SELECT 'HAIKU', 'ASK', false, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM model_selection WHERE run_type = 'ASK');

INSERT INTO model_selection (active_model, run_type, extended_thinking, updated_at)
SELECT 'HAIKU', 'ASK_READY', false, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM model_selection WHERE run_type = 'ASK_READY');
