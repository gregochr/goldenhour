### Added — P1 analysis tools for wasted trips, the canvas rule and far cloud decks

`scripts/far-cloud-p1/` holds the owner-run tools for `docs/engineering/far-cloud-p1-plan.md`: read-only
production exports, a premise check of recent go ratings against ERA5 (Part B), a stored-prompt replay set
and comparison across models and a canvas-rule prompt variant (Part D), a reconstruction of the Angel of the
North 13 Oct sun line (Part A) and the H1–H5 far-deck sizing (Part C), with an Open-Meteo probe, cache and
budget. `PromptReplayHarnessTest` gains a test-only `REPLAY_SYSTEM_PROMPT_FILE` override; with it unset the
replay sends the production system prompt unchanged. No production code changes.
