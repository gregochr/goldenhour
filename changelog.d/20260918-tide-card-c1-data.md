### Added — tide data on the Plan tab window card's pool, per-window summary and run (C1)

`utils/windowFirstSpots.js#buildWindowSpots` copies three tide facts flat onto each spot
descriptor — `tideState`, `tideAligned` (null unless `tideState` is served, never a stray `false`
for an inland spot), and `tideQuality` (C0's served `tideAlignmentQuality`) — so the card's
reach-gated pool, spread histogram and best-reachable line all read one population, never a second
index. `utils/windowFirstCards.js#buildWindowCards` derives a new per-window summary,
`card.tideFit` — deliberately never `tide`, which already names the served `BriefingWindowTide` the
tide-window series forwards to the Map tab's strip — `{coastal, matched, live, meanQuality}` over
that same pool: `live` at half the coastal pool in reach, floored at three
(`TIDE_LIVE_MIN_COASTAL`/`TIDE_LIVE_FLOOR`/`TIDE_LIVE_SHARE`), `meanQuality` averaged over the
matched spots that carry a served quality. `utils/windowFirstStrip.js` forwards `tideFit` beside
`tide`. New `utils/windowFirstTideRun.js#tideRun(cards)` ranks the live windows in one forecast —
`matched` first, then the served mean quality (nulls last), then strip order — and names the one
window to take only where more than one is live.

No visible change — nothing renders this data yet (C2). `card.tideFit` and `tideRun` are new,
named members of CLAUDE.md's reach-scoped Backend-heavy licensed class ("and only those"): the pool
is per-user, so no servable answer exists on the shared `GET /api/briefing`, the same reasoning
that already licenses the spread histogram and the best-reachable line.
