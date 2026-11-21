### Removed — the Claude Best bet advisor call and its admin surfaces

The Best bet advisor no longer runs: a briefing build makes one fewer Claude call (about $2.8 per 30 days by the
2026-11-01 estimate, not yet measured). Nothing a reader sees changes: the Best bet and Also good picks come from the
ratings and the region gloss, not from the advisor, and `/api/briefing` no longer carries the `bestBets`,
`bestBetModel`, `bestBetStatus` or `bestBetsWithdrawn` fields. A briefing stored before the change loads without its
old picks, so no stale pick is served. Gone with it, for admins: the "vs this morning's nightly"
pick comparison in the Runs cycle panel (and `comparison` in `GET /api/admin/pipeline-runs/{id}`), the
`POST /api/admin/advisor-replay` endpoint, the Run Config "Briefing" tab with its Extended Thinking switch, the
Briefing line in the cost profiles and `PUT /api/models/active` for `BRIEFING_BEST_BET` (now a 400), the
`photocast.best-bet.*` configuration keys, and the `pipeline_run_pick` writes. The tables, the enum value and the
response fields stay until a follow-up migration. The advisor's own prompt-regression test
(`BestBetAuroraPromptRegressionTest`) is deleted with it, and the Sky prompt-regression job no longer runs it.
