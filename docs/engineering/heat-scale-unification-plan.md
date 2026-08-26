# One colour scale, everywhere — implementation plan

**Status: planned, not started.** Source of truth for the design intent is the Claude Design
handoff, committed alongside this plan at `docs/design/temperature-scale/`
(`temperature-scale.html` §07 is its change list, `heat-field.js` is the reference kernel,
`PROMPTS.md` the implementation prompt). This document is the *port*
plan: it records where the brief and the tree disagree, and slices the work into stages each
sized for a single Claude Code Sonnet session.

**The goal, in the owner's words:** the Plan view, its popups, the Map view and its popups all
use the same colour scheme, and that scheme is driven by config.

---

## 1. What the design asks for

A **temperature scale** — cold blue at 1★ through gold at 3★ to hot orange-red at 5★ — replacing
the current red→green verdict ramp as the default, with the existing ramp retained as a
user-selectable alternative. Eight stops, deliberately uneven:

```
[[1,[58,92,112]],[2.2,[80,104,120]],[2.8,[146,140,128]],[3,[196,148,64]],
 [3.2,[201,146,48]],[3.9,[223,107,42]],[4.3,[222,72,38]],[5,[200,40,32]]]
```

⚠️ **Revised 2026-08-26.** The hot leg's two stops moved — `4.3` from `#D63A26` to `#DE4826`,
`5` from `#F26034` to `#C82820` — after the owner noticed 4.3★ reading hotter than 5★. It was a
real defect, not a swap: luminance ran 0.264 → **0.175** → 0.275, a trough then a recovery. It now
descends 0.264 → 0.203 → 0.139. Two consequences, both measured: the sub-AA band fell from
**13.2% in three runs to 10.2% in two** (a ramp that reverses direction crosses the dead zone
twice), and every whole star now clears comfortably — 7.13, 6.04, 6.51, **5.03**, 5.56:1.

⚠️ **Do not brighten `5` past `4.3` again.** Gold at 3★ is already the ramp's brightest point, so
a bright top end gives a middling night and a great one the same visual weight. The top is the
ramp's *deepest* colour by design.

Four stops are load-bearing and must not be "tidied":

- **The uneven spacing itself.** Regional means occupy roughly 1.9–4.6. Even spacing spends the
  blue and the red on values that never survive the blur, rendering every night the same orange.
- **`2.2` is held dark** so a bone marker label clears 4.5:1 against it. Lightening it breaks
  marker contrast.
- **`3` exists as its own stop** because `rating` is an integer and 3★ is likely the commonest
  value; interpolating 2.8→3.2 put it on a dun khaki.

---

## 2. ⚠️ Where the brief has gone stale — read before following it

The brief was written against a tree that has since moved. **Five of its claims are wrong as
written.** A session following it literally will either fail to find its target, edit the wrong
module, or undo work that just landed. The rows are in the order a session meets them.

| Brief says | Reality | Consequence |
|---|---|---|
| Change 1: "replace `STOPS`" in the kernel, `heatField.js` | **Wrong module.** `heatField.js` contains no `STOPS` — it imports `rampRgb` from `scoreRamp.js`, where `RAMP_STOPS` lives (`scoreRamp.js:29`). | Following it literally either stalls the session or creates a **second** ramp definition — the one thing this whole series exists to prevent. Stage 1 targets `scoreRamp.js`. |
| Change 3: retire `RATING_COLOURS` | **Already deleted** in v1 retirement D3. Only a Javadoc mention survives in `scoreRamp.js:17`. | Half of Change 3 is done. `scoreColour()` **is** still live (4 call sites in `markerUtils.js`) — that half stands. |
| Change 4: `buildMarkerSvg` hard-codes `fill="#0f172a"`, make it conditional bone-below-3★ | **Already fixed**, differently and better, by #627 (merged 2026-08-25). Ink is derived per fill through `readableInkOn`, with a computed AA sweep in `MarkerIcon.test.jsx` pinning every stop ≥ 4.5:1. `fill="#0f172a"` no longer appears anywhere. | **Do not implement Change 4.** A hard 3★ threshold would be a regression from a computed rule. The AA sweep already guards the new ramp for free. |
| Change 5: "delete `PopupScoreRow`, use `ScoreBar` in both places" | `ScoreBar.jsx` was **deleted** in v1 retirement D4 (zero-importer sweep). The live Plan-side component is `PlanScoreBar.jsx`, used by `LocationFourDaySheet` and `WindowSpotPeek`. | The merge target is `PlanScoreBar`, not `ScoreBar`. The duplication is real and still worth collapsing — just between different files than the brief names. |
| Change 5, second premise: "both step into four buckets, so 26 and 49 are the same colour" (`#A06E00` vs `bg-amber-700`) | **The bucket ladder is gone.** `#A06E00` appears nowhere in the frontend; `bg-amber-700` survives only in `LocationAlerts` and `RunProgressRow`, unrelated. Both components already use byte-identical gradient strings, and the popup ramps its *number* through a 3-stop `rampTint`. | The defect the brief describes no longer exists. Stage 5 is a **deduplication plus a ramp migration**, not a threshold bug fix — which changes what its tests should pin. |

Two further notes:

- **`MarkerIcon.test.jsx` no longer asserts `RATING_COLOURS[4] === '#CC8A00'`** (the brief's
  checklist expects to update that). It now runs a computed contrast sweep instead. That sweep is
  an asset: it will fail loudly if any new ramp stop drops a label below AA.
- **`PlanScoreBar` already uses gradients** (`FIERY_FILL`, `GOLDEN_FILL` are `linear-gradient`s
  with hard-coded hexes). The brief argues explicitly for **solid** fills — "a bar has one value,
  and a gradient across a ramp that starts cold is a five-hue rainbow". Moving to solid is
  therefore a deliberate visual change on the Plan side, not just a refactor.

### 2.1 Label-bearing fills can land where neither ink clears AA — DECIDED, snap to whole stars

`readableInkOn` picks whichever of `#0F172A` / `#FFFFFF` contrasts better with the fill. That is
only *good enough* where one of them clears 4.5:1. Every ramp through mid-luminance has points
where neither does; the question is whether any label lands on one.

**It is live, not theoretical, and it reaches TWO surfaces rather than one.**
`markerUtils.starsFromAverage(avg)` returns `avg / 20` — a *continuous* 0–5 value — which
`scoreColour` feeds straight to `rampHex`. Both of its ramp-bearing callers are labelled:

- the **cluster bubble** (`markerUtils.js:204`), whose fill comes from `mean(ratings) × 20` and
  carries a count;
- an **individual marker** (`markerUtils.js:90`) for a location with both potentials but no rating
  yet, whose fill comes from `Math.round((fierySky + goldenHour) / 2)` and whose label is that raw
  average — so a marker reading "62" paints from 3.1★, an interpolated point.

⚠️ An earlier draft of this section said "markers already comply; cluster badges do not." **That
was wrong** and understated what the snap fixes. The `ratingColour` path *does* comply — `rating`
is an integer 1–5 — but the average path does not. Snapping inside `scoreColour` covers both
without touching either call site, which is the chokepoint argument holding up under a case it was
not written for.

**The existing guard cannot see it.** `MarkerIcon.test.jsx`'s sweep is `it.each([1, 2, 3, 4, 5])` —
the only five values never at risk. It passes by luck, not by construction.

#### The decision

> **Any fill that carries a label samples at whole stars. Only label-free surfaces interpolate.**

Markers already comply; cluster badges do not. **Snapping the cluster fill is the whole change.**
It costs a cluster nothing — a cluster is a zoom artifact you resolve by zooming — and it returns
the fill to the resolution the data actually has, since `rating` is an integer. Add one test
asserting the invariant directly, rather than relying on a sweep that happens to sample safe
values.

The ramp is **not** redesigned. All five whole stars clear AA comfortably (1★ 7.13:1, 2★ 6.04,
3★ 6.51, 4★ 4.87, 5★ 5.52), so once nothing samples between stops, the band's width stops
mattering.

#### The measurement, and a reconciliation worth keeping

Two independent scans disagreed and **both were wrong**. Resolved at 0.01★ across 1.00–5.00,
against the ink pair the app actually ships (`#0F172A` / `#FFFFFF`):

| | result |
|---|---|
| **Correct** | **13.2% of the range** (53 of 401 samples), in **three** runs: 2.48–2.60★, 4.10–4.24★, 4.37–4.61★ |
| This plan's first draft | "2.48–4.61★, 53%" — **wrong** |
| Design's §05 scan | "≈2.28–2.60★, ≈8%, one run" — **incomplete** |

- **The 53% was a span-versus-measure error**: the first draft printed the distance from the
  *first* failing sample to the *last* and called it a contiguous band, sweeping up all the
  passing values in between. Three narrow runs became one wide one. (The failing-sample count is
  53 out of 401 — the same number as the bogus percentage, which is what made it look plausible.)
- **Design's scan used the doc's bone ink `#F2E7D3`, not the app's white.** That reproduces their
  2.27–2.61★ run almost exactly. Their guess was that this plan had used a *pre-#627* ink; it was
  the other way round. Bone is also **worse**, not better: 21.7% across two runs, because it gives
  up contrast on the dark stops that white holds.
- **The hot end was missed by both.** Design reasoned that "the ramp only crosses the dead zone
  between 2.2 and 3.0" — true for the cold half, but the 3.9 → 4.3 → 5 leg crosses it twice more.
  The 4.3 stop `#D63A26` is a dark saturated red where dark ink falls to 3.82:1 and white reaches
  only 4.67:1; at 4.15★ and 4.50★ the best available ink is 4.26:1 and 4.24:1. Those dips sit
  exactly where 4★ and 5★ ratings live.

**Two conventions have to be stated or the artifacts drift apart again** — three separate scans
disagreed in this session for exactly this reason:

- **Quote the failing-sample count (n ÷ 401), not the summed run widths.** Span-summing drops one
  0.01★ step per run and reports 12.5% for the same scan.
- **Measure the rounded hex, not the interpolated float.** `rampRgb` ends in `Math.round`, so the
  browser only ever receives quantised channels; contrast computed from the float measures a
  colour that never renders. It does not move the 13.2%, but it does move the run boundaries by a
  step — the figures above are the rounded-hex ones, and they are what the design doc's live scan
  now publishes.

None of this changes the decision — snapping makes the ramp's interior unreachable by any label,
whatever its width. It is recorded because "we measured it and it was fine" would be the wrong
thing to carry forward: the interior is *not* fine, it is merely no longer sampled.

---

## 3. The stages

Each stage is independently shippable, independently revertible, and sized for one Sonnet
session. Every stage that touches UI takes the project's adversarial-review cadence before it
lands (CLAUDE.md, "UI Work — Review Cadence").

### Stage 1 — Two ramps in one module, no visual change ✅ landed

`frontend/src/utils/scoreRamp.js` gains a second stop list and a mode.

**Status:** implemented and adversarially reviewed (four read-only lenses: correctness,
import-site consistency, test quality, docs/conventions — all clean, no findings). One thing this
stage's own "Trap 3" note got wrong, caught by the consistency review: `RAMP_STOPS` was not
imported by only the three named test files — it was also imported by two **production** files,
`components/MapView.jsx` and `components/WindowFirstHeatStrip.jsx` (both build a legend gradient
directly from the stop list), plus two more test files the note missed
(`MapViewHeat.test.jsx`, `windowFirstSpots.test.js`). All were mechanical renames to
`STOPS_VERDICT`; no assertion values or rendered pixels changed. `frontend/src/index.css`'s
cross-file pointer comment (added in #627) also named the old export and was corrected in
passing. `npm run lint && npm test && npm audit --audit-level=high && npm run build` all pass.

- Rename the existing `RAMP_STOPS` to `STOPS_VERDICT` (its five hexes already match the
  reference kernel's `STOPS_VERDICT` exactly — verified, no colour drift).
- Add `STOPS_TEMP` verbatim from the reference kernel, uneven spacing intact.
- Add module-level `MODE`, `setMode()`, `getMode()`, and route `rampHex`/`rampRgb` through it.
- Add `scoreFromPercent(value, lo, hi)` — maps a 0–100 metric onto the ramp's **1–5 score
  domain**, returning a *number*, not a colour. ⚠️ The reference kernel calls this `rampPct` and
  returns a *colour* from it; this app splits domain-mapping from colour-lookup (`rampHex` /
  `rampRgb` already take a score), so it is renamed to make the divergence impossible to miss.
  Callers compose: `rampHex(scoreFromPercent(v, lo, hi))`. ⚠️ **Superseded 2026-08-26** — the
  potentials turned out bimodal, so this linear map is replaced by `starFromScore` + frozen
  `ANCHORS` (Stage 4). It shipped with no caller and **Stage 5a deleted it** — it is no longer in
  the tree. Left described here because the stage list is also a history, and a reader tracing
  Stage 1's diff will find it there.
- **`MODE` defaults to `'verdict'`.** This stage is therefore *provably* zero-visual-change,
  which is what makes it a safe first landing and a clean revert point.

Tests: both modes sample correctly at every whole star; the uneven stops interpolate
monotonically; clamping holds outside 1–5; `scoreFromPercent` maps `lo`→1 and `hi`→5 **as a
number** — assert `toBe(1)`, not a colour string.

*Nothing downstream of `ramp()` changes in this stage — that is the brief's own rule.*

### Stage 2 — Tokens and legend ✅ landed

**Status:** implemented and adversarially reviewed (four read-only lenses: runtime correctness —
re-checked against a post-`setMode('temp')` runtime rather than import-time state, specifically for
the `RAMP_GRADIENT` trap below — CSS/token correctness, test quality, docs/conventions. Three came
back clean; the fourth flagged one asymmetric test assertion, fixed in the same commit). One thing
this stage's own bullet list below got wrong: `--color-heat-1..5` landed in the `@theme static`
block, not the plain block beside `--color-verdict-*`. Nothing in this stage consumes them yet, and
Tailwind v4 prunes an unreferenced plain-block token to the empty string — the exact
`--color-plex-panel` failure this file's own header comment already records; `--color-heat-sea`
already lives in the static block for the same reason. `--color-verdict-*` itself was not touched.
`scoreRamp.js` gained one exported accessor, `activeStops()`, promoting the private function Stage 1
already wrote, so both gradient sites read one source instead of each branching on `getMode()`
independently. The map legend was already inline in the render body, so swapping its
`STOPS_VERDICT` import for `activeStops()` was the whole fix there. `WindowFirstHeatStrip.jsx`'s
`RAMP_GRADIENT` is now `rampGradient()`, a plain function called from the render body, so it reads
`activeStops()` fresh on every render; its stale "depends on nothing that changes" comment was
rewritten rather than left standing next to code it no longer describes. Tests: both gradient
surfaces gained a paired test each — one pinning today's verdict-stop rendering (and the *absence*
of any temperature-stop colour), one calling `setMode('temp')` **after** the test file's own
imports had already resolved and confirming the temperature stops appear (and the verdict ones
don't) — the ordering a frozen-at-import constant could not have passed. A new `heatTokens.test.js`
pins each `--color-heat-N` token against `rampHex(N)` in temp mode rather than against `STOPS_TEMP`'s
literals, since the 2★/4★ tokens are interpolated points, not stops in that list. `setMode('verdict')`
was added to each affected `describe`'s `afterEach`. `npm run lint && npm test && npm audit
--audit-level=high && npm run build` all pass.

- ✅ **The two hot stops Stage 1 shipped were corrected here.** `STOPS_TEMP`'s pre-revision
  `4.3: '#D63A26'` and `5: '#F26034'` became **`#DE4826`** and **`#C82820`**. It belonged in this
  stage rather than its own because the tokens are sampled from the corrected ramp — landing them
  against the old stops would have put two artifacts out of step. `heatTokens.test.js` asserts
  each token equals `rampHex(score)` rather than a literal, so they cannot drift apart again.
- `--color-heat-1 … --color-heat-5` in `index.css`: `#3A5C70`, `#4C6677`, `#C49440`, **`#DF6229`**,
  **`#C82820`** (the *corrected* ramp sampled at whole stars, for discrete uses; the field itself
  interpolates). Note 2★ and 4★ are **interpolated points, not stops** — do not expect to find
  them in `STOPS_TEMP`.
- **Do not touch `--color-verdict-*`.** Those are saturated web colours for verdict *words*
  (`#16a34a` / `#d97706` / `#b91c1c`) and are unrelated to the muted ramp, despite older handoff
  notes claiming otherwise.
- ⚠️ **There are TWO ramp-gradient sites, not one**, and this plan previously named only the map's:
  - `MapView.jsx:2137` — the map legend, built inline during render from `STOPS_VERDICT`.
  - `WindowFirstHeatStrip.jsx:78` — `RAMP_GRADIENT`, the Plan footer's ramp bar.
- ⚠️ **`RAMP_GRADIENT` is a module-level `const`, evaluated once at import.** Its own comment says
  "Computed once at module load — it depends on nothing that changes", which was true before Stage 1
  and is **false now**: it depends on `MODE`. Left as a module constant it will silently keep
  painting the verdict ramp forever after a later stage flips the default, with nothing failing.
  It has to become a function or move inside the render. Update that comment too — a stale comment
  asserting the old invariant is how this returns.
- **The words `poor → worth it` do not change on either scale** — the bar carries the metaphor,
  the words carry the meaning.

### Stage 3 — Markers and clusters onto the ramp ✅ landed

**Status:** implemented and adversarially reviewed (four read-only lenses: correctness/invariant —
does the whole-star snap actually hold everywhere, test quality, docs/conventions, and forward
impact on Stages 4–6. Correctness and docs/conventions came back clean; test quality and
forward-impact each surfaced one real finding, both fixed in the same commit). Two things this
stage's own bullet list below got wrong:

- **"its four call sites" is three.** `scoreColour` is called at `markerUtils.js:99` (the avg
  branch), `:104` (`scoreColour(null)`, short-circuits before touching the ramp) and `:213`
  (`createClusterIcon`'s cluster average). `ratingColour()` is a separate function on the same
  continuum, not a fourth caller of `scoreColour` — it calls `rampHex(rating)` directly, which
  needs no rounding because every producer of `rating` (backend DTOs, aurora scores) is already an
  integer, verified by tracing each one rather than assumed.
- **The arc migration bullet did not ship in this stage, on purpose** — the session's actual
  kickoff prompt explicitly superseded it: moving `#f97316`/`#E5A00D` onto the ramp now, ahead of
  Stage 5 putting the popup's score bars on the same ramp, would have made the pin agree with
  itself while still disagreeing with the popup showing the same two numbers — the exact
  cross-surface disagreement this whole series exists to remove. Left for a later stage once the
  popup is also on the ramp. The arc strokes carry no text label on the coloured line itself, so
  they were never in scope for the §2.1 whole-star invariant either way.

This stage was built on `origin/claude/heat-scale-stage-2-86f10e` (PR **#637**, open, not yet
merged) rather than `main`, since it needs Stage 2's exported `activeStops` and corrected hot-leg
stops (`#DE4826`/`#C82820`) — `main` alone only has Stage 1. It will need a rebase once #637 merges.
The review's two real findings were both in the new test file, not the source change: the
`afterEach(() => setMode('verdict'))` guard was originally written at file top level, covering
every test in `MarkerIcon.test.jsx`, where the house convention (`scoreRamp.test.js`,
`heatTokens.test.js`, `MapViewHeat.test.jsx`, `WindowFirstHeatStrip.test.jsx`) scopes it to the
smallest `describe` that actually calls `setMode` — moved to match; and a new `mockClusterFor`
helper duplicated the existing `mockCluster` helper further down the same file behaviourally, for
no reason beyond `mockCluster` being lexically out of scope at that point — fixed by hoisting
`mockCluster` to module scope and deleting the duplicate. `npm run lint && npm test` and
`npm run build` all pass; `npm audit` was not re-run this stage (no dependency changed).

- **Keep `scoreColour()` — reimplement it, do not delete it.** The brief says to retire it in
  favour of `HeatField.ramp()`, and that is superseded by §2.1: the snap needs a chokepoint, and
  this is it. Its *job* changes — from a stepped 0–100 twin of the ramp to the single place a
  0–100 average becomes a whole-star ramp colour — but the function survives and its call sites
  keep calling it. Deleting it and routing the callers straight at the ramp is exactly the
  failure §2.1 exists to prevent: continuous fills under labels.
- ⚠️ **The Fiery Sky / Golden Hour arcs move in Stage 5b, not here.** They hard-code `#f97316` and
  `#E5A00D` at five sites in `markerUtils.js`, and the brief is right that they must move with the
  score bars — they are the same two metrics, and the pin and the popup must not disagree. But the
  bars only reach the ramp in Stage 5b, via `starFromScore` + `ANCHORS`. Moving the arcs here would
  open a window where the pin reads the ramp and the popup still reads a gradient, which is the
  precise disagreement the brief is trying to prevent. Sequencing corrected 2026-08-26.
- **Ink needs no work at whole stars** — `readableInkOn` already derives it per fill, and all five
  temperature stops clear AA (1★ 7.13:1, 2★ 6.04, 3★ 6.51, 4★ 4.87, 5★ 5.52, computed).
- **Snap the cluster fill to whole stars** (§2.1's decision). Be precise about where:

  `markerUtils.scoreColour(avg)` is the single chokepoint — it is `avg == null ? NO_DATA_COLOUR :
  rampHex(starsFromAverage(avg))`, and `starsFromAverage` is `avg / 20`, continuous. **Round inside
  `scoreColour`**, at that call: `rampHex(Math.round(starsFromAverage(avg)))`. Not at its three call
  sites — one more would eventually be added without it, and the failure is invisible.

  **Round, do not floor.** The nearest whole star is the honest reading of an average; flooring
  systematically under-reports and would make an 89-average cluster render as 4★ when it is nearer
  5. `rampHex` already clamps to 1–5, so `avg = 0` needs no special case.

  `markerUtils.js:64` (`rampHex(rating)`) is already integer and needs no change.
- Add a test asserting the invariant directly — *label-bearing fills sample at whole stars* —
  rather than extending the existing value sweep, which passes on the only five safe values.

### Stage 4 — Calibrate the two 0–100 metrics — RESOLVED, and it ships NO code

⚠️ **There is no Stage 4 pull request, and there should not be one.** This stage's deliverable was
always *numbers*, not software: measure the two metrics, decide how they map onto the ramp. All
three of its outputs are delivered — the query is committed at
`docs/engineering/heat-scale-stage4-calibration.sql`, the measurement is below, and the decision is
the `ANCHORS` tables.

Its would-be code changed shape when the distribution turned out bimodal. The original plan had it
handing Stage 5 a `lo`/`hi` pair; the replacement is a pair of anchor **tables**, and a table with
no function to read it is not a shippable unit. So the tables land in **Stage 5a** alongside
`starFromScore`, which is the thing that gives them meaning. Nothing is outstanding here.

The ramp is indexed 1–5 because ratings are. `fierySkyPotential` and `goldenHourPotential` are
0–100, and assuming they span the full ramp is wrong.

**The first answer — p05/p95 with a linear map — was measured and then discarded.** Kept here
because the reasoning matters more than the numbers.

Measured on production 2026-08-25 over `cached_evaluation` (the store the UI reads), fiery came
out 8 / 72 and golden 15 / 76 over 19,488 evaluations. That already overturned the design doc's
illustrative 25–85, which would have put a typical fiery 40 at 2.0★ — the cold end — where the
measured pair puts it at 3.0★, gold. The brief's premise that these values cluster at 50–78 was a
fixture artifact: the real p05s are 8 and 15.

**Then the shape check killed the linear map entirely.** Measured 2026-08-26, 10-point buckets
plus a zero probe: **both metrics are bimodal** — fiery peaks at 10–19 and again at 70–79, golden
at 20–29 and 70–79, both troughing at 50–59. Most evenings are unremarkable and a distinct minority
are good; that is the sky, not an artifact. The **zero-inflation hypothesis is refuted** — 12
exact-zero fiery readings and 5 golden, so the low mass is genuine dim-sky forecasts rather than
stood-down slots.

No two-point linear map can spread a bimodal population. Six alternative pairs per metric were
tested and **none beat p05/p95**: the constants were never the problem, the map was. Under it,
51.4% of fiery readings landed in the 1★ band, its dominant bucket (44% of readings) spanned 0.56
of a star, and golden clamped 18% of readings to an identical maximum — a good evening and a great
one rendering the same.

**The replacement is `ANCHORS` + `starFromScore(v, metric)`** — a piecewise-linear table per
metric, verbatim from the reference kernel. ⚠️ `scoreFromPercent`, shipped in Stage 1, is
**superseded**; it has no caller and Stage 5a deletes it.

```
fiery  [[0,1],[20,1.9],[35,2.4],[50,2.8],[60,3.2],[72,4],[85,4.7],[100,5]]
golden [[0,1],[25,1.9],[40,2.4],[55,3],[70,3.8],[85,4.6],[100,5]]
```

Two properties are load-bearing, both Design's, recorded because they are what an implementer
would "improve":

1. **The anchors are FROZEN CONSTANTS, not a running calibration.** Derived from one measurement
   then fixed — the standing `STOPS_TEMP`'s uneven spacing already has, itself derived from
   measured regional means and never re-derived per season. Re-measure only to check the physics
   has not moved; **do not re-anchor per season**, because that makes colour relative to the
   population, and a 3.0 must look like a 3.0 in every week. ⚠️ This is why piecewise did **not**
   cost what an earlier draft of this plan claimed: piecewise and *relative* are independent axes
   and only the first was ever in question. That draft conflated them.
2. **The spacing is deliberately NOT even-occupancy.** 70% of fiery readings sit below 30 and all
   mean the same thing — *don't bother* — so they share 1.3 stars, while the top third of the
   range holds ~15% of readings and every decision worth making, and gets 1.8. ⚠️ An earlier draft
   scored candidate mappings against an even-occupancy target. **That target was wrong** — a
   self-invented proxy, not the goal. Colour belongs where the decision is, not where the readings
   pile up, and heavy concentration in the low bands is the intended outcome rather than a defect.

**Measured effect**, over the same 19,832 evaluations:

| | fiery | golden |
|---|---|---|
| resolution in the decision zone (60–100) | 0.019 → **0.045** stars/point (**2.4×**) | 0.026 → **0.043** (**1.7×**) |
| 72 / 85 / 100 under the linear map | 5.00 / 5.00 / 5.00 ★ | 4.74 / 5.00 / 5.00 ★ |
| 72 / 85 / 100 under the anchors | **4.00 / 4.70 / 5.00 ★** | **3.91 / 4.60 / 5.00 ★** |

Those last two rows are the whole argument: the linear map rendered a good evening and a great one
identically; the anchors do not.

The measurement queries are kept at `docs/engineering/heat-scale-stage4-calibration.sql`.

### Stage 5 — One score bar, continuous solid fill — SPLIT into 5a and 5b

⚠️ **Split 2026-08-26, on sizing.** As written this was four jobs in one session: build Stage 4's
mapping (never coded — Stage 4 was a *decision*, and `ANCHORS`/`starFromScore` exist only in the
reference kernel), delete the superseded linear one, merge two score-bar components across eight
call sites in three files, and move five hard-coded arc colours.

- **5a — the mapping.** `ANCHORS` + `starFromScore` into `scoreRamp.js`; `scoreFromPercent`
  deleted. **No visual change** — nothing calls it yet, the same shape as Stage 1.
- **5b — the surfaces.** The score-bar merge and the arcs, as specified below, against 5a's landed
  API.

⚠️ Two defects in the reference implementation to fix in 5a rather than transliterate. It returns
**5** — the top of the ramp — for a non-finite input, because `clamp` propagates `NaN` and the loop
falls through to `return 5`; that contradicts the invariant `rampRgb` states and guards two
functions away (*an unknown reading must never render as the best one*). And it silently falls back
to the fiery table for an unknown metric (`ANCHORS[metric] || ANCHORS.fiery`), which returns a
plausible wrong answer — at v=80, fiery gives 4.43 and golden 4.33.

#### Stage 5b — one score bar, continuous solid fill

**Split into 5a and 5b.** The ramp math (`ANCHORS` + `starFromScore`, replacing the deleted
`scoreFromPercent`) is a self-contained, callerless module change; the `ScoreBar` merge and the
marker-arc migration are a separate UI change with eight call sites and a browser-verifiable visual
diff. Sizing them as one session risked the ramp math getting a shallower review than a UI stage
earns, or the UI merge getting rushed to fit alongside it. They ship as two sessions instead.

#### Stage 5a — `ANCHORS` + `starFromScore` ✅ landed

**Status:** implemented and adversarially reviewed (three read-only lenses: correctness — including
the specific question "can any input produce a star value outside 1–5, or the top of the ramp
without meaning to" — test quality, and docs/forward-impact for Stage 5b). Correctness surfaced one
real finding, fixed in the same commit: the unrecognised-metric guard was `if (!ANCHORS[metric])`,
which is bracket-access truthiness rather than an own-key test — a `metric` string that happens to
name an `Object.prototype` member (`'toString'`, `'constructor'`, `'hasOwnProperty'`, `'__proto__'`)
resolved to a truthy non-array value, so the guard silently passed, the interpolation loop's body
never ran against that non-array, and control fell through to the trailing `return 5` — an unthrown
top-of-ramp result for a metric that was never recognised, in direct contradiction of the function's
own doc comment. Fixed with `Object.hasOwn(ANCHORS, metric)`. Test quality surfaced two gaps, both
closed in the same commit: the "`scoreFromPercent` is fully deleted" check only swept for surviving
imports elsewhere in the tree and never asserted `scoreRamp.js` itself no longer exports the name
(a function left defined-but-unreferenced would have passed silently); and the sweep's regex missed
a default+named import and a barrel re-export (`export { scoreFromPercent } from '...'`) — broadened,
and self-pinned with two fixture-string assertions of its own so the regex's reach cannot silently
narrow again. `npm run lint && npm test && npm audit --audit-level=high && npm run build` all pass.

- `scoreRamp.js` gains `ANCHORS` (the two frozen per-metric tables, verbatim from the reference
  kernel) and `starFromScore(value, metric)` (0–100 → 1–5, piecewise-linear between anchors).
- `scoreFromPercent` and its JSDoc are deleted outright, along with every mention outside the two
  files' own historical prose.
- Nothing calls `starFromScore` yet — verified by grep, not just by the plan saying so.
  `PlanScoreBar`, `PopupScoreRow` and `markerUtils` are untouched.
- Tests: every anchor point exact for both metrics, mid-segment interpolation, clamping outside
  0–100, non-finite → 1 (not 5 — the most load-bearing assertion in the file), the two metrics
  disagreeing at one shared input, a monotonicity property test over the full anchor tables and a
  full 0–100 integer sweep per metric, the prototype-collision throw case above, and the
  `scoreFromPercent`-deletion pair (repo-wide import sweep + direct non-export assertion).

#### Stage 5b — wire it up ✅ landed

**Status:** implemented, unit-tested (19 new `ScoreBar.test.jsx` tests + updated `MarkerIcon.test.jsx`
assertions; 4,232 frontend tests green, lint clean), browser-verified against a seeded local DB
(H2 direct SQL insert, since triggering a real Claude evaluation needs `ANTHROPIC_API_KEY`), and
adversarially reviewed (8 finder angles via the Agent tool, each verified) before landing, per
CLAUDE.md's UI cadence.

**What the plan got wrong, corrected during implementation:**

- **The call-site table omitted `dense` for `LocationFourDaySheet`.** Both current Plan surfaces
  (`WindowSpotPeek`'s peek and `LocationFourDaySheet`'s location sheet) rendered at the same 10px
  scale before this merge, via `PlanScoreBar`'s hard-coded `fontSize: '10px'`. The table's call-site
  mapping listed "add `dense`" only for `WindowSpotPeek`; taken literally, `LocationFourDaySheet`
  would have silently jumped to the popup's 11px scale and lost `labelClassName` support (needed for
  the row-dimming CSS rule) — nothing in the plan's rationale asked for either change. Both Plan call
  sites pass `dense` in the shipped code.
- **"Tinted to match" needed a floor, not the raw ramp hue.** The literal instruction — tint the
  number to the same colour as the bar — fails WCAG AA at the ramp's dark end: 1★ measures 2.84:1 and
  2★ measures 3.54:1 against `--color-plex-surface` as plain text, both under 4.5:1, and worse once
  `LocationFourDaySheet`'s row-dimming rule (0.8 opacity) applies. Caught in the browser, not on
  paper, exactly as this section's own instructions asked. Fixed with `NUMBER_TINT_FLOOR = 2.8` (the
  first star value where the ramp clears AA in every state, on every real background this component
  renders against, with margin) — the bar's own fill stays unclamped, since it carries no text and
  therefore no contrast requirement of its own.
- **The rating ring vs. disc "halo" question, resolved in the browser as asked.** At every rating
  below 5★, the ring's partial arc (a visible gap against the dark background track) unambiguously
  reads as a progress gauge regardless of the colour match with the disc beneath it. At a full 5★
  ring, colour and disc match exactly and the arc has no gap — but it reads as an intentional glow for
  a top rating, not a broken halo. No second colour language was invented; `rampHex(rating)` ships as
  specified.
- **Adversarial review caught two real defects fixed before landing** (not anticipated by this plan
  text): `starFromScore`'s metric-typo guard was skipped whenever `score` was `null`, so a bad metric
  on a not-yet-scored slot passed silently instead of throwing (now validated unconditionally); and
  the AA-floor's own doc comment claimed general safety without scoping it to `scoreRamp.js`'s default
  `verdict` mode — the module's dormant `temp` mode has a genuinely darker hot end that would fail AA
  once a later stage wires it to a live control, which a one-sided floor does nothing to prevent (now
  documented as an explicit, unresolved gap for whoever ships Stage 6/7's preference).
- **One further real defect found, correctly left unfixed here**: the map popup's Scores section is
  gated on `fierySky != null` alone, so a location scored on Golden Hour only shows no scores at all
  in the popup (Plan surfaces gate each bar independently and don't have this bug). Pre-existing,
  unchanged by this diff, out of this stage's scope — flagged as a follow-up rather than folded in.

Below this line is the original brief, kept for the record of what was asked; the corrections above
are what actually shipped.

⚠️ **Read §2's Change 5 row first.** Both premises in the brief are stale: `ScoreBar.jsx` was
deleted in D4, and the "four buckets, so 26 and 49 are the same colour" defect **no longer exists**
— `#A06E00` appears nowhere in the frontend, and the two live components already share
byte-identical gradient strings. What remains is real but different: **two components, one
duplicated pair of gradients, drifting apart.**

#### What is actually duplicated

| | `PlanScoreBar.jsx` (Plan) | `PopupScoreRow` (in `MarkerPopupContent.jsx`) |
|---|---|---|
| call sites | `LocationFourDaySheet` ×2, `WindowSpotPeek` ×2 | ×4, all in `MarkerPopupContent` |
| bar fill | `fill` **prop**, `FIERY_FILL` / `GOLDEN_FILL` | derived from `label` string, same two constants |
| null score | not handled — callers guard with `!= null` | renders `—` |
| number tint | **none**, deliberate (SC 1.4.1 note in its module doc) | `rampTint` over a 3-stop scale |
| tooltip | none | `InfoTip` via `SCORE_TOOLTIPS`, dotted underline on the label |
| type scale | 10px | 11px |
| markup | Tailwind classes + `.wf-peek-bar` | all inline styles |
| `testId` | required prop | none |

The gradients are quoted, not imported — `PlanScoreBar`'s module doc explains why (importing a
~1,300-line component into the Plan chunk to fetch two strings). Once the fill is **derived from
the ramp**, that reason evaporates and the duplication goes with it.

#### The merged component

Rename `PlanScoreBar.jsx` → **`components/ScoreBar.jsx`**. It will serve Plan *and* Map, so
"Plan" in the name becomes wrong; the `ScoreBar.jsx` deleted in D4 is gone and the name is free.

```
ScoreBar({ label, score, metric, testId, tooltip, dense, labelClassName })

  label          string, required        'Fiery Sky' | 'Golden Hour' — printed as-is
  score          number | null, required 0-100; null renders an em dash, not a zero-length bar
  metric         'fiery' | 'golden'      REPLACES the `fill` prop — selects the ANCHORS table
  testId         string, required        popup call sites gain one; they have none today
  tooltip        node, optional          popup passes <InfoTip .../>; Plan passes nothing
  dense          bool, default false     true = the Plan peek's 10px scale
  labelClassName string, optional        unchanged, LocationFourDaySheet's dimming hook
```

**`fill` disappears as a prop.** It becomes `rampHex(starFromScore(score, metric))` — a
**continuous solid** colour, not a gradient. The composition matters: `starFromScore` returns a
1–5 *number* and `rampHex` turns it into a colour; assigning the number straight to a CSS
`background` yields no fill at all. `scoreFromPercent` is already gone — deleted in Stage 5a, which
also landed `ANCHORS` + `starFromScore`. `PlanScoreBar`'s exported `FIERY_FILL` / `GOLDEN_FILL`
and `MarkerPopupContent`'s private copies are all deleted, along with `rampTint`, `FIERY_TINT` and
`GOLDEN_TINT`. Solid, not gradient: a bar has one value, and a gradient across a ramp that starts
cold is a five-hue rainbow.

**The number is tinted to match, on both surfaces.** This closes `PlanScoreBar`'s documented
deviation deliberately rather than by accident. It does not reintroduce an SC 1.4.1 problem: the
numeral states the value in text, so nothing is encoded by colour alone — which was the actual
requirement the note was protecting.

#### Call-site mapping — all eight

| file | change |
|---|---|
| `WindowSpotPeek` ×2 | drop `fill`, add `metric="fiery"` / `"golden"`, add `dense` |
| `LocationFourDaySheet` ×2 | drop `fill`, add `metric`, keep `labelClassName` |
| `MarkerPopupContent` ×4 | `PopupScoreRow` → `ScoreBar`, add `metric` and `testId`, pass `tooltip={SCORE_TOOLTIPS[label] && <InfoTip .../>}` |

The Plan call sites keep their `score != null &&` guards — they render *nothing* for a missing
score, where the popup renders an em dash. That difference is deliberate and survives the merge:
a tooltip with a stray dash in it is noise; a popup row that vanishes is a layout jump.

#### Also in this stage: the marker arcs

`markerUtils.js` hard-codes `#f97316` / `#E5A00D` at five sites, but ⚠️ **they are not all the
same quantity and must not take the same formula**:

- **Four potential arcs** — `buildMarkerSvg`'s fiery/golden pair and `createClusterIcon`'s pair.
  These carry the 0–100 metrics the score bars render, so they move onto
  `rampHex(starFromScore(v, metric))` here, in the same commit — not in Stage 3, which would leave
  the pin on the ramp while the popup was still on a gradient.
- **One rating ring** — `buildMarkerSvg`'s single full ring, the Haiku path
  (`markerUtils.js:167-171`), whose fill is `FULL_CIRC * (rating / 5)`. It receives a **1–5
  rating**, not a 0–100 potential, and there is no metric table for it. It takes
  **`rampHex(rating)`**. ⚠️ Routing it through `starFromScore` would read a 5★ rating as the raw
  value 5 and map it to ≈**1.2★** — a top-rated location painted as the ramp's cold end.

Neither the arcs nor the ring carry a label, so §2.1's whole-star rule does not constrain them:
they may sample the ramp continuously.

⚠️ **Open question for the implementer to check in the browser, not to decide in the abstract:**
the rating ring sits immediately outside a disc already filled with `ratingColour(rating)` — the
same value through the same function. Colouring the ring from the ramp makes both the same hue, and
the ring may stop reading as a gauge and start reading as a halo. Look at it before committing; if
it reads badly, say so rather than inventing a second colour language for one ring.

#### Scope boundary

`PopupScoreRow`'s inline styles move across as-is. CLAUDE.md's "Tailwind only, no inline styles"
rule is violated by **both** components today; converting them is pre-existing debt and **not**
this stage's job. Say so in the PR rather than silently expanding.

#### Tests

`WindowSpotPeek`'s tests are the purity check — this project's own standard, set by the
`solarDayGeometry` extraction and reused when `PeekScoreBar` became `PlanScoreBar`. They cannot
pass entirely unedited here (the fill genuinely changes), so pin the parts that must not move:
label text, the `data-score` attribute, the `100 - pct` rest-width, and the null branch. Add one
test that the fill is the ramp's colour for that score rather than any gradient string.

### Stage 6 — The preference, full-stack (still defaulting to verdict)

The brief says persist through `settingsApi`, not `localStorage`. That makes this a **backend**
stage, which the brief does not say out loud:

- Migration (next free V-number — **read it off `main`, never from a written-down number**) adding
  `map_colour_scale` and `markers_follow_scale` as columns on **`app_user`**. ⚠️ **There is no
  `user_settings` table** — every user setting is a column on `app_user` (`V67` for the home
  location, `V136` for `local_radius_miles`), and `user_drive_time` is the only side table. A
  migration written against `user_settings` fails at deploy.
- Entity, `UserSettingsResponse` (currently a 9-field record), `UserSettingsService`,
  `UserSettingsController`.
- ⚠️ `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` pins that everything under
  `/api/user/settings*` is never ETag-filtered. New fields ride the existing path, so this stays
  green — but do not add a new path here without adding it to that test.
- Frontend: `settingsApi`, then a new **Map Colours** section in `UserSettingsModal.jsx`.
  The modal has **no toggle or checkbox pattern today** — only text inputs and `btn-primary`
  buttons — so the control itself is new work. Follow the existing section shape (uppercase
  `text-xs font-medium text-plex-text-muted tracking-wide` heading, matching Profile / Home
  Location / Drive Times), and leave it **outside the `isPro` gate**: reading the map is not a
  Pro feature.
- Wire the loaded setting into `setMode()` at one place, so Plan and Map can never disagree.

Default stays `'verdict'` through this stage. That gives a dogfooding window where the scale can
be switched on deliberately before it becomes everyone's default.

### Stage 7 — Flip the default, and tell people

⚠️ **BLOCKED on a contrast decision. Measure before flipping — do not treat this as a one-line
default change.**

Stage 5b tints the score bar's **number** from the ramp, floored at `NUMBER_TINT_FLOOR = 2.8★` so
it clears 4.5:1 as *text* on the panel background. That floor was measured against
**`STOPS_VERDICT`**, and 5b's own review flagged, correctly, that it was not scoped to the active
mode. Measured against `STOPS_TEMP`:

| | worst point, 2.8★–5★, both backgrounds, rest and dimmed |
|---|---|
| verdict (today) | **4.75:1** — holds |
| **temperature (after the flip)** | **2.38:1** — fails |

**And it fails at the HOT end, where a floor cannot help**, because a floor clamps the bottom:

| star | rest | dimmed (0.8) |
|---|---|---|
| 4.0★ | 4.82:1 | 3.55:1 |
| 4.3★ | **4.13:1** | **3.08:1** |
| 5.0★ | **3.08:1** | **2.38:1** |

So flipping the default as-is ships failing contrast on the score number at **every rating from
roughly 4.1★ upward** — the good evenings, the ones a reader most wants to read.

⚠️ **This is a direct consequence of a fix we were right to make.** Making the hot leg monotonic
(so 4.3★ stopped reading hotter than 5★) deepened the top end to `#C82820`. That is *better* for
fills, where `readableInkOn` picks an ink to sit on top of it, and *worse* for text, where the ramp
colour **is** the ink on a dark surface. The two uses pull opposite ways; the ramp is right and the
text use of it is what has to give.

**Options, for the owner or Design — not a mechanical choice:**

1. **Drop the number tint.** The bar carries the colour, the numeral carries the value, and
   SC 1.4.1 is satisfied either way. This is what `PlanScoreBar` did before 5b, and its own doc
   comment called the no-tint deliberate — the temp-mode data now supports that position for a
   sounder reason than the one recorded there. Smallest change; removes the whole class of problem.
2. **Derive a readable variant of the ramp hue** — keep the hue, lift the luminance until it clears
   4.5:1. Preserves "tinted to match" as a real idea, but it is new colour machinery and a second
   colour language to maintain.
3. **Tint from a clamped band** (floor *and* ceiling). Cheap, but it makes 5★ and 4★ numbers the
   same colour — deleting the signal exactly where it matters.

Recommended: **(1)**, unless Design wants to own (2). Whatever is chosen, re-measure rather than
reasoning: the sweep is at 0.02★ across both backgrounds in rest and dimmed states.

Once that is settled:

- `mapColourScale` defaults to `'temp'`; `markersFollowScale` defaults on. Existing installs get
  the new scale with no migration prompt.
- A **one-time, dismissible notice** on the map: "Colours now run cold to hot."

The notice is the only part of this work that reaches the person who was misreading the old map.
The preference does not — they will never open Settings to discover they were wrong. It is not a
setting; it is a sentence.

---

### Stage 8 — the Golden Hour score a null Fiery Sky hides

Found during Stage 5b's browser verification, **pre-existing** and correctly left out of that
stage's scope. Scheduled last, on the owner's instruction, because it is a behaviour fix rather
than part of the colour work.

`MarkerPopupContent` gates its whole **Scores** section on `fierySkyPotential` alone, in **two
places**:

| site | gate |
|---|---|
| the forecast popup (~line 894) | `role !== 'LITE_USER' && popupFiery != null` |
| the briefing drill-down (~line 1111) | `role !== 'LITE_USER' && briefingScore.fierySkyPotential != null` |

`popupGolden` / `goldenHourPotential` are resolved independently. So a location with **no Fiery Sky
reading but a perfectly good Golden Hour one** shows no Scores section at all — the heading, the
tooltip and a real measurement all suppressed by the absence of the *other* measurement.

**Stage 5b makes the fix simpler than it was.** The old `PopupScoreRow` could not render a missing
score; `ScoreBar` renders an em dash for one, deliberately. So the gate no longer has to protect
anything — it only has to stop an empty section appearing when **both** are absent:

```
role !== 'LITE_USER' && (fiery != null || golden != null)
```

⚠️ **Fix both sites or neither.** They are the same defect twice, and fixing one leaves the other
as a puzzle for whoever meets it next.

⚠️ **Check what a LITE user sees before changing the boolean's shape.** The role test and the null
test are currently one expression; the freemium split is a product rule and this stage is not the
place to renegotiate it.

**Tests:** a location with fiery null and golden present shows the section with a dash and a real
bar; both null shows nothing; both present is unchanged. Assert it at **both** sites — a single
test passing at one of them is how this survived in the first place.


## 4. Cross-cutting rules

1. **One `MODE`, read from one module.** Plan thumbnails and the Map tab must never disagree
   about what a colour means. This is the same rule the planning area already follows.
2. **Nothing downstream of `ramp()` changes.** If a stage finds itself editing the blur, the
   kernel geometry or the canvas, it has strayed.
3. **The AA sweep is the contrast gate.** #627's computed test in `MarkerIcon.test.jsx` fails if
   any stop drops a label below 4.5:1. Treat a failure as evidence about the ramp.
4. **`--color-verdict-*` is out of scope** in every stage.
5. **Frontend CI is four commands, not one** — `npm run lint && npm test && npm audit
   --audit-level=high && npm run build`. The audit step is the one nothing local runs and has
   already cost one CI round.
6. Every UI-touching stage gets the adversarial review before it lands, per CLAUDE.md.

---

## 5. Open questions for the owner

1. **Is the potential distribution bimodal?** Query 3 of the calibration SQL answers it. If it is,
   `lo` should be the foot of the upper mode rather than p05 — see Stage 4. Cheap, and it only
   needs answering before Stage 5 ships.
2. **The semantic inversion.** On the current ramp red means *bad* (1★); on the temperature ramp
   red-orange means *good* (4.3★+). Anyone who has learned the old map will read the new one
   exactly backwards until they see the notice. Stage 7 is designed for this, but it is worth a
   conscious decision rather than a side effect.
3. **Does `markersFollowScale` earn its place?** It is a second preference guarding a subset of
   the first. If markers should simply follow the scale, dropping it removes a control, a column
   and a branch. Kept in the plan because the brief specifies it.
