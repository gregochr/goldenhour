### Changed — briefing gloss reused when a region's input is unchanged

P5b of the performance plan (`docs/engineering/performance-review-2026-10-plan.md`). `BriefingGlossService`
keeps the glosses of the last build, keyed by a SHA-256 of the system prompt, a new `GLOSS_VERSION`, the
model id and the region's user message, and reuses one without a Haiku call when the key is unchanged.
Failed or half-dropped glosses are never stored, so the next build retries them; the map is replaced each
build and does not grow. It starts empty after a restart (the persisted briefing carries no prompt or model
fingerprint to seed from safely). STANDDOWN regions are still glossed. Also corrects the log line and
comment that said only GO/MARGINAL regions are glossed.
