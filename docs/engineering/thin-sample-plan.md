# Thin-sample field and pick eligibility — build plan

Owner ask, 2026-10-22: on the Plan tab the Sat 24 Oct sunrise card wore BEST BET, read `Worth it 4.0★`
and glowed orange over Tyneside on **6 rated places of 83** in reach. The only caveat was the spread
row's quiet `6/83 rated`. A new reader would miss it.

Two changes, independent of each other:

- **Pick eligibility (backend):** a forced sample may still set a region's verdict, but may no longer
  take BEST BET / ALSO GOOD. Spec: `docs/design/thin-sample/PICK_ELIGIBILITY.md`.
- **Thin-sample field (frontend):** hatch the ground around unrated places, add `· N rated` to a thin
  card's verdict line, and replace the legend's `unshaded — not scored`. Spec:
  `docs/design/thin-sample/README.md` and its screenshots.

Each phase below is one PR, built by a Sonnet subagent from this plan, then reviewed (the UI review
cadence in CLAUDE.md: build → tests → adversarial review → fix → re-verify → commit).

---

## §1 Corrections to the design handoff (owner-approved 2026-10-22)

The handoff's rule "unrated = no score for that window" covers three kinds of place in this codebase.
Only one of them is unrated.

1. **Woods and wildlife hides are never hatched.** `buildHeatSpots` gives a non-sky place
   (`skySubject: false`, `heatSpots.js:140`) a null score on every window, by design. Under the
   handoff's rule every one of them would be hatched "not rated yet" for ever. They take no part in
   the new layer: no colour, no hatch, plain ground — exactly what they draw today.
2. **A weather stand-down counts as looked at, not unrated.** A score row with a null rating and a
   `triageReason` was examined and stood down. It is NOT hatched; it stays plain ground (it draws no
   colour today either, and that is unchanged). Only a sky place with neither a rating nor a triage
   reason for the window is "not rated yet".
3. **The Map tab's count has its own population.** The Plan card's count is the reach pool's (the
   figure already in `6/83 rated`). The Map tab has no reach pool: its verdict follows the SCOPE
   segment (*My area* / *Everywhere*, `utils/mapVerdict.js`). The map pill's count is therefore the
   rated sky places in scope, under the same two thresholds over the scope's sky places. The two
   surfaces can print different numbers for one window; each names its own population.
4. **When `· N rated` is shown.** Only when ALL of:
   - the card's spread gate fails (`hasSpreadSample` false, `windowFirstSpread.js`);
   - the verdict is a rated word (`WORTH_IT`, `MAYBE` and the other rated grades — never `UNRATED`,
     whose own copy already says "Too few rated", never `STAND_DOWN`, `AWAITING` or an away cell);
   - at least one place in the pool is rated (no `· 0 rated` beside a verdict that rests on places
     further away).
5. **"This window has no rating at all" is the existing predicate.** The whole-plate hatch, the footer
   note and the hidden sentence already read `card.bestRating` (`WindowFirstHeatStrip.jsx:620`, a
   production defect fixed by using it). The new layer is skipped by that same predicate, never by an
   empty point set. The Map tab uses its own existing `windowUnscored`.
6. **The count is spoken.** The visible `· 6 rated` is `aria-hidden`-safe only if the card's spoken
   sentence carries it in words ("6 places rated").
7. **Scope of the layer.** Solar windows only (sunrise and sunset, past windows included). Never on
   an astro or aurora night (`AuroraFieldThumb` and the Map tab's night modes are untouched). When a
   region is focused, the hatch fades with the field's `focus` the same way the colour does.

---

## §2 What must not change

- The colour ramp, bloom, confidence haze, field alpha, and every rated place's colour.
- Stars, `meanRating`, `bestRating`, verdict words, confidence (both changes).
- `sampleSufficient`, `forcedSample`, `VerdictSampleGate` thresholds, how force-evaluation runs.
- The whole-plate hatch on a window with no rating.
- The spread row itself (gate, text, bars).

---

## §3 Phases

```
F1 ──► F2 ──► F3
  └──► F4
B1 (backend; P0 is the owner's confirmation, not a gate)
F5 last (docs sweep), after every other phase has merged
```

B1 touches backend only and could run in parallel with every F phase. F2 and F4 touch different
components and can run in parallel once F1 is merged. F3 touches the same component as F2, so it
follows F2.

### P0 — confirm the cause (owner; does not block B1)

Signed in on the app, in the browser console:

```js
const r = await fetch('/api/briefing', {headers: {Authorization: 'Bearer ' + localStorage.getItem('goldenhour_token')}}).then(r => r.json());
r.days.filter(d => d.date === '2026-10-24').flatMap(d => d.eventSummaries).filter(e => e.targetType === 'SUNRISE').flatMap(e => e.regions).map(g => ({region: g.regionName, verdict: g.displayVerdict, mean: g.meanRating, sufficient: g.sampleSufficient, forced: g.forcedSample}))
```

Expected for Northumberland & Tyneside: `sufficient` absent and `forced: true`. B1 is built either
way, because Q3 (§5) is an owner decision that stands on its own; P0 only says whether B1 is what
removes the Sat 24 BEST BET. If the region turns out to be `sufficient: true`, the Sat pick was
earned under the current sample gate, and the remaining question (should the gate be stricter?) goes
back to the owner.

### B1 — `pickEligible()` (backend)

Spec: `PICK_ELIGIBILITY.md`. In short:

- `BriefingRegion.pickEligible()` = `Boolean.TRUE.equals(sampleSufficient)`. `verdictEligible()` is
  unchanged.
- Classify EVERY `verdictEligible()` caller as a verdict question or a pick question and list the
  result in the PR. Known callers (grep again before starting):
  - `PlanWindowProjector` `:98` (`BY_RANK` ordering) — **verdict**: ordering among regions stays as is.
  - `PlanWindowProjector.candidate` `:583` (BEST BET / ALSO GOOD candidate) — **pick**.
  - `BriefingRollupBuilder` `:564` → `CandidateCoverage.verdictEligible` → `BestBetRanker.dropIneligiblePicks`
    — **pick** (the advisor's `bestBets`). Rename the coverage field to say what it now means.
  - `BestBetFallbackService` `:197` (stale pick resurrection) — **pick**.
  - `PipelineRunPickService` / `pipeline_run_pick` — follows from the above; verify it cannot persist a
    forced-only region.
  - Ask: `AskSnapshot.Region` carries ONE `verdictEligible` boolean (set by `AskSnapshotBuilder`
    `:420`) that two different questions read. `Spread.of` (`:469`, a thin region is counted as a
    region and kept out of the bands) is a **verdict** question and must keep reading verdict
    eligibility, or a forced-but-insufficient region drops out of Ask's bands while its verdict stands.
    `meetsPickRules` (`:913`, a place offered as a pick, the same claim as BEST BET) is a **pick**
    question. So add a second field, `pickEligible`, to `AskSnapshot.Region`, set from
    `BriefingRegion.pickEligible()`, and switch `meetsPickRules` alone to it. Do NOT repoint the
    builder's existing value. Check `AskReadyFreshness`/`ReadyRelevance` and the thread re-join, which
    reach pick eligibility through `isPickEligible`, so they follow with no change of their own.
  - `BriefingRegionEvaluationRollup` `:225` — read it; expected **verdict**.
- A window whose top region is forced-only takes no pick; the strip's two picks are a cap, not a quota.
- Tests per the spec, including the Thu 22 Oct fixture (Today 22 sunrise takes BEST BET; Sat 24 sunrise
  takes none and keeps `Worth it 4.0★`), and an Ask test that a forced-only region's slot is not
  pick-eligible.
- Docs: `forcedSample` javadoc, `verdictEligible()`/`pickEligible()` javadoc, and every CLAUDE.md
  sentence that says the forced exemption covers "verdict, pick eligibility and ranking" — it now
  covers the verdict (and ranking order) only, as of 2026-10-22.
- No prompt change, no migration. `BestBetAuroraPromptRegressionTest` fixtures must not move.

### F1 — the data and the layer (frontend utils, no visible change)

- `utils/heatSpots.js`: carry a per-window **state** beside each score:
  `rated` | `stood` (null rating + `triageReason`) | `unrated` (sky place, neither) | `na` (not a sky
  subject). Read `triageReason` from the score rows (`LocationEvaluationView` already serves it) and
  from `forecastScoreRows` for past windows (check the forecast row's triage field name). Add
  `unratedPointsFor(spots, windowIndex)` beside `heatPointsFor`, returning only `unrated` spots.
- `utils/heatField.js`: add the unknown-ground mask and hatch per the spec's formula (grid 4px, the
  field's `R`, cut-off `d² ≤ 6R²`, coverage clamp 1.15, threshold 0.45), **reusing the kernel's bucket
  walk** (extract it rather than copy it). Upscale with the field's own blur
  (`blur + unc * 2.6`). Rule the existing hatch (`HATCH_GAP`/`HATCH_LINE`/`HATCH_INK`) over the surface,
  mask with one `destination-in`, honour `focus` (§1 #7). Expose it through a new `drawGeo` /
  `drawTiles` option (e.g. `unrated: pts`) that defaults off, painted under the field and above the
  plate. Hard land clip in `drawGeo`; the soft land clip (`clipSoft`) in `drawTiles`.
- Tests: the three mask cases in the spec (lone unrated → 1 at centre; rated on top of unrated → 0;
  empty ground → 0); bucketed mask equals a brute-force mask (mirror the existing kernel test); state
  derivation for all four states, including a wood with a score row and a stood-down place.
- No host passes the option yet, so nothing on screen changes.

### F2 — Plan tab picture and legend

- `WindowFirstHeatStrip.jsx` thumbnails and `WindowRowFieldMap.jsx`: pass the window's unrated
  points to `drawGeo`. Skip them when the existing unscored predicate holds (§1 #5).
- Legend (`wf-heat-foot`): replace `unshaded — not scored` with the two swatches (`not rated yet`,
  `no places`) and the new right-hand copy, per the spec's sizes and colours.
- Check: screenshots 01–04, 06 at desktop and phone widths, in the static harness of the built
  stylesheet if a signed-in browser is not available; say which.

### F3 — the verdict-line count (Plan tab)

- `· N rated` on the card's verdict line under §1 #4, styled per the spec (separator `--ink-3` 400,
  count `--ink` 500, solid colours, no opacity). `N` is the spread's `rated`.
- The spoken sentence gains it (§1 #6).
- Screenshot 05: one line on a ~300px card. Pin the narrowest card in a test of the CSS cascade if
  the line can wrap.
- Tests: shown iff gate fails ∧ rated word ∧ rated ≥ 1; absent on `UNRATED`, `STAND_DOWN`,
  `AWAITING`, away, unscored.

### F4 — Map tab

- `MapHeatLayer.jsx`: pass unrated points to `drawTiles` on solar windows (past included), never on
  a night. Respect `windowUnscored`. Radius unchanged (`radiusFor(map, 7200, 30, 400)`).
- `MapLegendPanel.jsx`: `[ramp] rated · [hatch] not rated yet · [plain] no places`, 10.5px, solar
  windows only (the aurora variant keeps its own key).
- The window pill (`WindowControl`) gains `· N rated` under §1 #3–#4, scope pool. This is a new
  client-derived figure over the scope segment: add it by name to CLAUDE.md's Backend-heavy bullet,
  sixth class (map-landing), as member (f), with its population and exit stated.
- Check the production markers still separate rated from unrated on hatched ground (screenshots
  07–08); report, do not restyle markers in this phase.

### F5 — docs sweep (last)

- CLAUDE.md: the Plan matrix bullet (hatch states, the verdict count), the Map tab bullet, and the
  Plan-screen confidence bullet's forced-exemption sentences if B1 did not already.
- A changelog entry per phase (each phase writes its own `changelog.d/` file; F5 checks they agree).
- The owner's browser checklist: Sat-sunrise-like thin window beside a well-rated one, a wet morning
  with many stand-downs, a region with woods, the phone card, the Map tab zoomed in, an aurora night
  (no hatch).

---

## §4 Subagent brief template

Each phase is delegated with: this plan's path and the phase section; the spec files in
`docs/design/thin-sample/`; "read `docs/engineering/frontend-test-standards.md` (or the backend
standards) first"; the local gates (frontend: `npm run lint && npm test && npm audit
--audit-level=high && npm run build`; backend: the `-Dtest='!**/integration/**'` verify, gated on the
exit code); "never `git checkout --` or stash; commit on the phase branch; do not push"; and a request
to report anything in the spec that the code contradicts rather than guessing.

## §5 Decisions

| # | Decision | Taken |
|---|---|---|
| Q1 | Stand-downs: plain ground, not hatched and not a third look | Owner, 2026-10-22 |
| Q2 | Woods and hides take no part in the layer | Owner, 2026-10-22 |
| Q3 | Forced samples set the verdict but take no pick | Owner, 2026-10-22 |
| Q4 | Map count = scope pool, Plan count = reach pool | Owner, 2026-10-22 |
