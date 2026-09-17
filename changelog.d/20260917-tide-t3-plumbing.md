### Added — the map tab's tide-fit plumbing (T3 of the tide-window increment)

Every fact T1 and T2 put on `GET /api/briefing` now reaches the frontend structures the chip, the
callout/sheet block and the tide strip will draw from in the phases that follow — still no
browser-visible change this phase.

`utils/locationSheet.js#buildTideAlignmentIndex` now indexes every coastal slot carrying a served
`tideState`, not only one with a derivable on-the-light fact, and each entry carries the full
tide-fit family (`aligned`, `onTheLight`, `phrase`, `level`, `direction`, `height`, `shortfall`,
`fitPhrase`, `gated`) rather than the two on-the-light-only fields it held before. Reading
`tideAligned` here does not revive the two-tide-axes conflation CLAUDE.md warns against — the
index's readers ask the preference question on purpose, and the on-the-light fact survives
unchanged for the offset clause alone.

`MapView.jsx`'s `spotOf` copies each location's own id (load-bearing for `mapTideFit`'s id-first
lookup — without it, two coastal locations sharing a display name would silently conflate), its
tide want (`tideTypes`), whether it is coastal-and-tidal at all (`coastal`, reusing
`mapCallout.js#isCoastalTidalLocation`), this window's served tier (`tideTier`, via the new
`mapTideFit.tierOf`), its shortfall arrow, its fit-block body text (`tideFitPhrase`, which T4's
tooltip needs for both tiers), and whether it is tide-gated onto `labelSpots` — the pool both the
label chips and the Pins layer read.

`utils/mapEvents.js#solarRow` forwards the served `BriefingWindowTide` verbatim as `tide` on a
served solar row, and `null` on a D-13 filler or a night row.

A new pure module, `utils/mapTideFit.js`, holds the increment's licensed client derivations
(CLAUDE.md's Backend-heavy bullet): `tierOf` (a served-boolean read), `nextAlignedRow` (a forward
scan over the served per-window alignment index — a lookup, never a formula over a single anchor's
curve), and `stripModel` (the strip's own per-render shape — the coastal-and-in-view viewport
filter via `bounds.pad(0.12)`, the dimmed/matched split, the dominant unmet want tallied across the
dimmed pool with a stated HIGH > LOW > MID tie-break, and the earliest next-fit window across every
currently-dimmed spot wanting that want).

Frontend only. `mapTideFit.test.js` is new (every branch, both tie-break directions, `pad` proven
against points either side of the exact 12% edge); `locationSheet.test.js` gains coverage for the
index entry the old skip used to drop (a served miss with no derivable on-the-light fact) and the
gated field; `mapEvents.test.js` gains a `tide`-forwarding suite (served, filler, night, and the
chronological interleave unchanged). Three existing test fixtures (`MapViewHeat.test.jsx`,
`mapRegionDrilldown.test.js`) gained a `tideState` field their slots were missing, now required by
the narrower index skip. `npm run lint && npm test && npm audit --audit-level=high && npm run
build` all green (6302 tests).

Adversarial review (4 read-only lenses: runtime behaviour, project conventions including the
Backend-heavy licence and the two-tide-axes rule, test quality, what it makes harder for T4–T6)
found and fixed: `spotOf` never set an `id` on label spots, leaving `nextAlignedRow`'s id-first
lookup unreachable in production (two lenses found this independently); `spotOf` was missing
`tideFitPhrase`, which T4's own tooltip requirement needs; `mapTideFit.js`'s tide-index parameter
was named `index`, colliding in meaning with the unrelated `evIndex` array position beside it in
the same call (renamed to `idx`, matching `lookupForWindow`'s own convention); the pad-boundary
tests bounded the ratio to a wide range rather than pinning the documented `0.12` figure (tightened
to bracket the exact edge); a redundant interleave test duplicated an existing ordering test's exact
fixture and re-asserted values already proven elsewhere (trimmed); `buildTideAlignmentIndex`'s doc
comment sat above a different function's declaration (moved to its own); and `MapCallout.jsx`'s
stale `tideOnLight` prop doc still said "never `tideAligned`", now factually wrong about the shape
flowing into it (corrected). Browser-verified against a locally seeded coastal fixture (three
locations, one with a two-value want to reach the null-shortfall case): `GET /api/briefing` served
every T1/T2/T3 field correctly, including the null-shortfall arrow for the two-value want; the Map
tab, its callout, its location sheet and Pins mode all rendered with zero console errors and zero
failed network requests. Not seen: a `match`-tier chip on screen (the seeded tide cycles happened to
miss every window in the fixture) — the served `aligned: true` case is covered by the automated
suite instead.
