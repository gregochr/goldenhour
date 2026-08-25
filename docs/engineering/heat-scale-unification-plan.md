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
 [3.2,[201,146,48]],[3.9,[223,107,42]],[4.3,[214,58,38]],[5,[242,96,52]]]
```

Three stops are load-bearing and must not be "tidied":

- **The uneven spacing itself.** Regional means occupy roughly 1.9–4.6. Even spacing spends the
  blue and the red on values that never survive the blur, rendering every night the same orange.
- **`2.2` is held dark** so a bone marker label clears 4.5:1 against it. Lightening it breaks
  marker contrast.
- **`3` exists as its own stop** because `rating` is an integer and 3★ is likely the commonest
  value; interpolating 2.8→3.2 put it on a dun khaki.

---

## 2. ⚠️ Where the brief has gone stale — read before following it

The brief was written against a tree that has since moved. **Four of its changes are wrong as
written.** A session following it literally will either fail to find its target, edit the wrong
module, or undo work that just landed. The rows are in the order a session meets them.

| Brief says | Reality | Consequence |
|---|---|---|
| Change 1: "replace `STOPS`" in the kernel, `heatField.js` | **Wrong module.** `heatField.js` contains no `STOPS` — it imports `rampRgb` from `scoreRamp.js`, where `RAMP_STOPS` lives (`scoreRamp.js:29`). | Following it literally either stalls the session or creates a **second** ramp definition — the one thing this whole series exists to prevent. Stage 1 targets `scoreRamp.js`. |
| Change 3: retire `RATING_COLOURS` | **Already deleted** in v1 retirement D3. Only a Javadoc mention survives in `scoreRamp.js:17`. | Half of Change 3 is done. `scoreColour()` **is** still live (4 call sites in `markerUtils.js`) — that half stands. |
| Change 4: `buildMarkerSvg` hard-codes `fill="#0f172a"`, make it conditional bone-below-3★ | **Already fixed**, differently and better, by #627 (merged 2026-08-25). Ink is derived per fill through `readableInkOn`, with a computed AA sweep in `MarkerIcon.test.jsx` pinning every stop ≥ 4.5:1. `fill="#0f172a"` no longer appears anywhere. | **Do not implement Change 4.** A hard 3★ threshold would be a regression from a computed rule. The AA sweep already guards the new ramp for free. |
| Change 5: "delete `PopupScoreRow`, use `ScoreBar` in both places" | `ScoreBar.jsx` was **deleted** in v1 retirement D4 (zero-importer sweep). The live Plan-side component is `PlanScoreBar.jsx`, used by `LocationFourDaySheet` and `WindowSpotPeek`. | The merge target is `PlanScoreBar`, not `ScoreBar`. The duplication is real and still worth collapsing — just between different files than the brief names. |

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

**It is live, not theoretical.** `markerUtils.starsFromAverage(avg)` returns `avg / 20` — a
*continuous* 0–5 value — which `scoreColour` feeds straight to `rampHex`. Cluster badges therefore
paint interpolated fills and put a count label on them.

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
- Add `rampPct(v, lo, hi)` — maps a 0–100 metric onto the 1–5 ramp domain.
- **`MODE` defaults to `'verdict'`.** This stage is therefore *provably* zero-visual-change,
  which is what makes it a safe first landing and a clean revert point.

Tests: both modes sample correctly at every whole star; the uneven stops interpolate
monotonically; clamping holds outside 1–5; `rampPct` maps `lo`→1 and `hi`→5.

*Nothing downstream of `ramp()` changes in this stage — that is the brief's own rule.*

### Stage 2 — Tokens and legend

- `--color-heat-1 … --color-heat-5` in `index.css`: `#3A5C70`, `#4C6677`, `#C49440`, `#DD5F29`,
  `#F26034` (the temperature ramp sampled at whole stars, for discrete uses; the field itself
  interpolates).
- **Do not touch `--color-verdict-*`.** Those are saturated web colours for verdict *words*
  (`#16a34a` / `#d97706` / `#b91c1c`) and are unrelated to the muted ramp, despite older handoff
  notes claiming otherwise.
- The map legend (`MapView.jsx`, `wf-map-heat-legend-ramp`) redraws its bar from the active ramp.
  **The words `poor → worth it` do not change on either scale** — the bar carries the metaphor,
  the words carry the meaning.

### Stage 3 — Markers and clusters onto the ramp

- Delete `scoreColour()` (the stepped 0–100 twin) and route its 4 call sites through the ramp.
- Move the Fiery Sky / Golden Hour arcs off hard-coded `#f97316` / `#E5A00D` onto the same
  source as the score bars, so the pin and the popup cannot disagree about the same two numbers.
- **Ink needs no work at whole stars** — `readableInkOn` already derives it per fill, and all five
  temperature stops clear AA (1★ 7.13:1, 2★ 6.04, 3★ 6.51, 4★ 4.87, 5★ 5.52, computed).
- **Snap the cluster fill to whole stars** (§2.1's decision): `scoreColour` currently hands
  `rampHex` a continuous `avg / 20`, which paints a labelled badge from the ramp's interior.
  Round at the boundary so only whole stars are ever sampled.
- Add a test asserting the invariant directly — *label-bearing fills sample at whole stars* —
  rather than extending the existing value sweep, which passes on the only five safe values.

### Stage 4 — Calibrate the two 0–100 metrics ⚠️ needs real data

The ramp is indexed 1–5 because ratings are. `fierySkyPotential` and `goldenHourPotential` are
0–100, and assuming they span the full ramp is wrong — the brief notes every test fixture sits
between 50 and 78, which a naive mapping squeezes into 3.0–4.1: gold to orange, never cold,
never hot.

Produce **one `lo`/`hi` pair per metric**, roughly the 5th and 95th percentile over stored values.
The doc's 25–85 is an illustration and must not be shipped.

**This stage is blocked on data access** and is the one place the plan needs the owner:
production is a separate Linux host, and the local H2 database has no representative distribution.
Either the owner runs the percentile query against production, or we accept a documented interim
pair and re-measure later. *Do not let a Sonnet session invent these numbers.*

### Stage 5 — One score bar, continuous solid fill

- Collapse `PopupScoreRow` (`MarkerPopupContent.jsx`, 4 call sites) into `PlanScoreBar`.
- Replace both bucket ladders with a **continuous solid fill** sampled via `rampPct` using
  Stage 4's constants, number tinted to match. Solid, not gradient.
- Update `PlanScoreBar`'s own tests plus `MarkerPopupContent.test.jsx`.

Today the two implementations use different thresholds — `#A06E00` against Tailwind's
`bg-amber-700` at the third bucket — so 26 and 49 render identically while the same score renders
differently on different surfaces. That is the defect this stage closes.

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

- `mapColourScale` defaults to `'temp'`; `markersFollowScale` defaults on. Existing installs get
  the new scale with no migration prompt.
- A **one-time, dismissible notice** on the map: "Colours now run cold to hot."

The notice is the only part of this work that reaches the person who was misreading the old map.
The preference does not — they will never open Settings to discover they were wrong. It is not a
setting; it is a sentence.

---

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

1. **Stage 4's percentiles.** Production query, or ship a documented interim pair and re-measure?
   This blocks Stages 4–5 but nothing else; every other stage can proceed either way.
2. **The semantic inversion.** On the current ramp red means *bad* (1★); on the temperature ramp
   red-orange means *good* (4.3★+). Anyone who has learned the old map will read the new one
   exactly backwards until they see the notice. Stage 7 is designed for this, but it is worth a
   conscious decision rather than a side effect.
3. **Does `markersFollowScale` earn its place?** It is a second preference guarding a subset of
   the first. If markers should simply follow the scale, dropping it removes a control, a column
   and a branch. Kept in the plan because the brief specifies it.
