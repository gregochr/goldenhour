### Changed — the served briefing is memoised for 30 seconds

Performance plan phase P1b (`docs/engineering/performance-review-2026-10-plan.md`).
`BriefingService.getCachedBriefingForApi()`, which backs `GET /api/briefing`, `GET /api/briefing/digest`
and the Ask snapshot, now reuses its assembled result for up to 30 seconds instead of re-running the
whole serve assembly (hot topics, live rating re-reads, tide rollup, roughly 25 queries) on every
request. The memo is keyed by the identity of the cached briefing, the UK civil date and a new
`ServedBriefingEpoch`, which every writer that changes a serve bumps after commit: hot-topic and
aurora simulation toggles and aurora state writes, `cached_evaluation` writes and the admin wipe,
the end of each batch result, stability-skip dispositions, travel days, and every location and region
write. The epoch is read before assembly, Rewind bypasses the memo, and role shaping and the Lite pick
shaper still run per request on the memoised result. A memo hit issues no database statement.
Behaviour change: anything the writer list misses (live aurora summaries, pastness at a window's
edge) can now trail by up to 30 seconds.
