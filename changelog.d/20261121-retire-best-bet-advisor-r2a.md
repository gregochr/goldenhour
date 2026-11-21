### Chore — remove the dead best-bet fields from the briefing (Best bet advisor retirement, R2a)

`GET /api/briefing` no longer carries `bestBets`, `bestBetModel`, `bestBetStatus` or `bestBetsWithdrawn`: since
R1 they were always empty or null, and no client reads them. The `BestBet` record, the matching
`DailyBriefingResponse` components, withers and constructor overloads, and the two R1 neutralisation steps (startup
restore and the Lite role shaper) are deleted. A briefing persisted before this change still loads, because the
record ignores unknown properties (pinned by `PersistedBriefingRemovedKeysTest`). Readers see no behaviour change.
`RunType.BRIEFING_BEST_BET`, `BestBetStatus` and `pipeline_run.best_bet_status` stay until the R2b migration, which
is a separate release (`docs/engineering/best-bet-advisor-retirement-r2a-plan.md`). No migration in this change.
