### Fixed — cross-vendor review catches on the map-v2 plan and the changelog fold

Codex's review of #723/#724 landed four findings, three confirmed against the code and folded
into `docs/engineering/map-tab-v2-plan.md`: P5 now serves the astro night window from the
entity's **stored** `nauticalDuskUtc`/`nauticalDawnUtc` (persisted since V64 — an earlier
revision wrongly claimed they existed only on the write path, and would have had the serve path
recompute a window that could diverge from the one the score was computed over); P5's aurora
window now names `computeWindowForDate(date)` and forbids the clock-based
`calculateTonightWindow()`, which would pin tonight's window on other dates' rows; and P6's LITE
treatment is corrected to "no aurora rows" — the promised greyed-row ProPill is unimplementable
while the role-gated aurora API folds 403s to empty, so a LITE client cannot learn an aurora
night exists (now recorded under the O-9 owner decision). The fourth finding (BSD `find` in
`scripts/promote-changelog.sh`) overstated the mechanism — macOS `find` supports `-maxdepth`,
proven by the fixture runs — but the suppressed error path was real: a failing `find` would have
silently promoted a version heading with nothing folded beneath it, a half-promoted state the
duplicate-heading guard then blocks from retry. The collection is now a pure shell glob with no
error path at all. A follow-up Codex catch on this PR closed the same wedge from another angle:
a tracked file the fold's glob cannot see (dot-prefixed, undated, or a subdirectory) was visible
to `release.sh`'s accounting, so promotion would have left it behind and wedged at the leftover
check. The fold set is now exactly the documented `YYYYMMDD-<slug>.md` convention, and both the
helper and `release.sh` (before any branch is created) loudly refuse anything else in the
directory.
