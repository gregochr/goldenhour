-- Correct aurora evaluation model: HAIKU → SONNET (claude-sonnet-4-6).
-- Aurora per-location scoring requires higher-accuracy reasoning over Kp, OVATION,
-- cloud cover, and Bortle data — Haiku underperforms here.

UPDATE model_selection
SET active_model = 'SONNET',
    updated_at   = CURRENT_TIMESTAMP
WHERE run_type = 'AURORA_EVALUATION';
