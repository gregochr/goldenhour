### Removed — Prompt Test harness

The admin Prompt Test sub-tab under Operations, its `/api/prompt-test/*` endpoints, `PromptTestService`, the
run and result entities and repositories, and the front-end view and API module are deleted. Its sweep ranges
were the retired term run types, and the Location Model Test and Sky Eval screens remain for comparing models
and prompts. V196 empties `prompt_test_run` and `prompt_test_result`; the tables themselves are dropped one
release later (Phase 4) so the previous image still validates against the schema.
