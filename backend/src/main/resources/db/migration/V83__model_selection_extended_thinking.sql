-- Add extended_thinking flag to model_selection.
-- When true, the best-bet advisor uses Claude's extended thinking chain
-- (ThinkingConfigEnabled with budgetTokens=10000) for deeper analysis.
-- Only applies to BRIEFING_BEST_BET run type (surfaced in Run Config UI).
-- Silently ignored if the active model is HAIKU (does not support extended thinking).

ALTER TABLE model_selection ADD COLUMN extended_thinking BOOLEAN NOT NULL DEFAULT false;
